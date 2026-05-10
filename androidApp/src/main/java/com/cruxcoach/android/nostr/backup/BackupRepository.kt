package com.cruxcoach.android.nostr.backup

import android.util.Log
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepository
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the backup pipeline from FEAT-002 §7.3 and the restore flow
 * from §8. All Nostr + Blossom work is delegated to the lower-level
 * collaborators; this class owns the invariants (blob-before-pointer,
 * `previous_sha256` update after publish, etc).
 */
@Singleton
class BackupRepository @Inject constructor(
    private val pool: NostrRelayPool,
    private val nostrSigner: NostrSigner,
    private val uploader: BlossomUploader,
    private val dTagDeriver: DTagDeriver,
    private val preferences: BackupPreferences,
    // Repositories needed by CruxCoachBackup.export/import — match AppModule wiring.
    private val userRepository: UserRepository,
    private val bodyStatRepository: BodyStatRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val planRepository: PlanRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    /** Board (unencrypted) repository — needed for the v3 own-climb
     *  payload (FEAT-008 §4). Cross-DB by design; see [CruxCoachBackup.export]
     *  for the rationale. */
    private val boardRepository: BoardRepository,
    private val transactionRunner: TransactionRunner,
) {

    /**
     * Serializes the public mutating entry points so a periodic worker
     * tick and a manual "Jetzt sichern" can never run concurrently.
     * Without this, `performFullBackup`'s read-modify-write of
     * `previousBlobSha256` (read at line ~141, write at ~152) racing
     * against itself orphaned blobs on Blossom or — worse — pointed
     * cleanup at the live blob. The same lock guards `restore`,
     * `deleteRemoteBackups`, and `getOrCreateDataKey` so pipeline state
     * can't be observed mid-mutation by any caller. `checkForBackup`
     * is read-only and stays outside the lock.
     */
    private val pipelineMutex = Mutex()

    /**
     * Complete a backup cycle. Throws [BackupException] if the pipeline
     * cannot guarantee the blob-before-pointer invariant; the worker
     * translates this into a retry.
     */
    suspend fun performFullBackup(trigger: String = "manual") = pipelineMutex.withLock {
        val pubkey = nostrSigner.getPublicKeyHex()
        val deviceId = preferences.getOrCreateDeviceId()
        Log.d(
            TAG,
            "event=backup_start trigger=$trigger signerMode=${nostrSigner.getStoredSignerMode().name}",
        )

        // 1-3 — serialize + compress + encrypt
        val dataKey = getOrCreateDataKey()
        val json = CruxCoachBackup.export(
            categories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            boardRepository = boardRepository,
            exportedAt = Instant.now().toString(),
            nostrPubkey = pubkey,
        )
        val plaintext = json.toByteArray(Charsets.UTF_8)
        val compressed = BackupCompression.compress(plaintext)
        val ciphertext = BackupCrypto.encrypt(compressed, dataKey)
        val sha256 = ciphertext.sha256Hex()

        // 4 — discover Blossom servers (user's Kind 10063 + defaults).
        // A relay-side fetch failure must NOT abort the backup: the
        // delete path already falls back to DEFAULT_SERVERS via the
        // same pattern (~line 414) and the upload path was the only
        // remaining caller that could explode the whole pipeline on
        // a transient discovery error. Keep the asymmetric behavior
        // intentional only for "not yet authenticated" paths, never
        // for the durable-write hot path.
        val servers = runCatching { discoverBlossomServers(pubkey) }
            .getOrDefault(BlossomUploader.DEFAULT_SERVERS)

        // 5 — upload
        val started = System.currentTimeMillis()
        val uploadResults = uploader.upload(ciphertext, servers)
        val ok = uploadResults.count { it.accepted }
        val total = servers.size
        if (ok == 0) {
            Log.w(TAG, "event=backup_upload_failed serversTotal=$total")
            // Surface a specific failure reason when every server died for
            // the same structural reason — most commonly an Amber signer
            // that can't launch its approval activity from a background
            // worker. Without this, the user sees a generic upload-failed
            // and has no way to tell it's an Amber permission issue.
            //
            // Backup always runs via WorkManager (no foreground Activity),
            // so Amber's Intent-based approval dialog has nothing to
            // launch onto. The only self-service fix is the user granting
            // auto-approve in Amber.
            val authErrors = uploadResults.mapNotNull { r ->
                r.error?.takeIf { it.startsWith("auth:") }
            }
            val reason = when {
                authErrors.size == total && authErrors.any { it.contains("No activity to launch") } ->
                    BackupErrorReason.AmberNeedsAutoApprove
                authErrors.size == total ->
                    BackupErrorReason.BlobUploadFailed(total = total, authDetail = authErrors.first())
                else ->
                    BackupErrorReason.BlobUploadFailed(total = total, authDetail = null)
            }
            throw BackupException(reason)
        }
        if (ok < total) {
            Log.w(TAG, "event=backup_upload_partial serversOk=$ok serversTotal=$total bytes=${ciphertext.size}")
        } else {
            Log.d(
                TAG,
                "event=backup_upload_ok serversOk=$ok serversTotal=$total bytes=${ciphertext.size} durationMs=${System.currentTimeMillis() - started}",
            )
        }

        // 6 — HEAD-verify on at least one server
        val verified = uploader.verifyExists(sha256, servers)
        if (!verified) {
            Log.w(TAG, "event=backup_verify_failed serversTotal=$total")
            throw BackupException(BackupErrorReason.BlobNotVisibleAfterUpload(total = total))
        }

        // 7 — ONLY NOW publish pointer event
        val previousSha = preferences.getPreviousBlobSha256()
        val pointer = BackupPointer(
            sha256 = sha256,
            size = ciphertext.size.toLong(),
            servers = servers,
            previousSha256 = previousSha,
            updatedAt = System.currentTimeMillis() / 1000,
            deviceId = deviceId,
            categories = CruxCoachBackup.Category.entries.map { it.name },
        )
        publishPointerEvent(pointer, pubkey)
        preferences.setPreviousBlobSha256(sha256)

        // 8 — cleanup previous blob (best-effort)
        previousSha?.let { stale ->
            uploader.delete(stale, servers)
            Log.d(TAG, "event=backup_cleanup previousShaPresent=true serversCleaned=${servers.size}")
        } ?: Log.d(TAG, "event=backup_cleanup previousShaPresent=false serversCleaned=0")

        // 8b — keep the Kind-30078 key event from aging off the relays.
        // The pointer is republished on every backup (so it stays fresh
        // by construction) but the key event is only written once on
        // first-time setup or on local-cache regeneration. Replaceable-
        // parameterized events can still be evicted by relays over time,
        // which would make restore unrecoverable even with the nsec if
        // the local cache is also gone. Republish on a slow cadence so
        // the cost stays negligible (one extra Amber popup every ~30 d
        // without always-approve; nothing for local signers).
        refreshKeyEventIfStale()

        // 9 — record success
        val now = System.currentTimeMillis() / 1000
        preferences.setLastBackupSync(now)
        Log.d(
            TAG,
            "event=backup_done totalDurationMs=${System.currentTimeMillis() - started}",
        )
    }

    private suspend fun refreshKeyEventIfStale() {
        val lastPublish = preferences.getLastKeyEventPublish() ?: 0L
        val nowEpoch = System.currentTimeMillis() / 1000
        if (nowEpoch - lastPublish < KEY_EVENT_REFRESH_INTERVAL_SEC) return
        val wrapped = preferences.getWrappedDataKey() ?: return
        runCatching { publishKeyEvent(wrapped) }
            .onSuccess {
                preferences.setLastKeyEventPublish(nowEpoch)
                Log.d(TAG, "event=key_event_refreshed")
            }
            .onFailure { e ->
                // Not fatal — blob + pointer are already durable; we'll
                // try again on the next backup.
                Log.w(TAG, "event=key_event_refresh_failed reason=${e.message}", e)
            }
    }

    /**
     * Restore-dialog detection. The sealed [CheckOutcome] type distinguishes
     * "no events returned" (NotFound — could be either relays unreachable or
     * no backup for this account; indistinguishable from the client side
     * without a relay-level probe) from "events returned but un-decryptable"
     * (DecryptFailed — wrong key imported) and from "network/crypto threw
     * during fetch" (Fetch). Callers can surface a specific error message
     * to the user instead of a generic "nothing happened".
     */
    suspend fun checkForBackup(timeoutMs: Long = 30_000L): CheckOutcome {
        Log.d(TAG, "event=restore_check_start signerMode=${nostrSigner.getStoredSignerMode().name}")
        val pubkey = nostrSigner.getPublicKeyHex()
        val mode = nostrSigner.getStoredSignerMode()

        val fetched = try {
            when (mode) {
                SignerMode.LOCAL -> fetchByDTagHmac(pubkey, timeoutMs)
                SignerMode.AMBER -> fetchByQueryAllAmber(pubkey, timeoutMs)
            }
        } catch (e: Exception) {
            // Categorise the failure before surfacing any detail to the
            // UI. The raw `e.message` can leak third-party library
            // internals (OkHttp stack frames, Quartz crypto details,
            // Amber IPC payload fragments) that aren't actionable for
            // the user and widen the phishing surface if a hostile
            // relay manages to nudge a specific error string. Keep the
            // full exception + message in logcat for dev debugging.
            Log.w(TAG, "event=restore_check_miss reason=fetch-error originalMessage=${e.message}", e)
            val detail = when (e) {
                is java.net.UnknownHostException -> "network unavailable"
                is kotlinx.serialization.SerializationException -> "relay payload malformed"
                is java.io.IOException -> "network error"
                else -> "fetch failed"
            }
            return CheckOutcome.Fetch(detail)
        }

        val (pointerEvent, keyEvent) = fetched ?: run {
            Log.d(TAG, "event=restore_check_miss reason=no-pointer")
            return CheckOutcome.NotFound
        }

        val pointer = try {
            decryptPointer(pointerEvent.content, pubkey).also { it.validateOrThrow() }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "event=restore_check_miss reason=pointer-invalid field=${e.message}", e)
            return CheckOutcome.Fetch("backup pointer failed validation")
        } catch (e: Exception) {
            Log.w(TAG, "event=restore_check_miss reason=decrypt-failed", e)
            return CheckOutcome.DecryptFailed
        }

        // Timestamp-monotonicity sanity check (M1): if this device has a
        // recorded last-successful-backup time, every pointer we accept
        // must be at least that new. The max-by-createdAt pick above
        // already defends against multi-event relay ambiguity, but this
        // check catches the harder case where every configured relay
        // conspires to withhold newer events — we detect the rollback
        // from our own local state instead of relying on what relays
        // show us. Skipped when lastBackupSync is absent (fresh install,
        // post-identity-switch, or genuine first restore).
        //
        // Tolerance window: LAST_BACKUP_SYNC is written AFTER the pointer
        // publish + blob verify + cleanup, so it's typically a few
        // seconds ahead of pointerEvent.createdAt for the very pointer
        // we're checking — the "delete logbook then restore my fresh
        // backup" flow would otherwise trip on its own freshly-published
        // event. A 5-minute grace absorbs that drift + any reasonable
        // clock skew while still catching rollback attacks (which
        // serve events hours-to-weeks in the past).
        val lastLocalBackup = preferences.lastBackupSync.first()
        if (lastLocalBackup != null &&
            pointerEvent.createdAt < lastLocalBackup - STALE_POINTER_TOLERANCE_SEC
        ) {
            val ageBehind = lastLocalBackup - pointerEvent.createdAt
            Log.w(
                TAG,
                "event=restore_check_miss reason=stale-pointer" +
                    " eventCreatedAt=${pointerEvent.createdAt}" +
                    " lastLocalBackup=$lastLocalBackup" +
                    " secondsBehind=$ageBehind",
            )
            return CheckOutcome.Fetch(
                "Backup on relays is older than this device's last backup " +
                    "(${ageBehind / 3600}h behind) — possible relay rollback or outage",
            )
        }

        // Pre-flight HEAD probe: the pointer survives on relays even
        // after a delete-remote opt-out (relays may ignore the Kind-5
        // deletion event), but the actual encrypted ciphertext could
        // already be gone from every Blossom server. Without the probe
        // we'd say "Found", show the confirm dialog with size +
        // timestamp, and only fail at restore-time with a generic
        // "Restore failed" — confusing because the metadata clearly
        // existed. Pre-filter the pointer's server list through the
        // shared URL gate (same as restore() does at L344) so we don't
        // dial pre-compromise URLs.
        val probeServers = pointer.servers.filter {
            com.cruxcoach.android.nostr.UrlValidation.isValidBlossom(it)
        }
        val info = BackupInfo(pointer = pointer, pointerEvent = pointerEvent, keyEvent = keyEvent)
        val blobReachable = probeServers.isNotEmpty() &&
            uploader.verifyExists(pointer.sha256, probeServers)
        if (!blobReachable) {
            Log.w(
                TAG,
                "event=restore_check_blob_unreachable" +
                    " sha256Prefix=${pointer.sha256.take(8)}" +
                    " serversProbed=${probeServers.size}",
            )
            return CheckOutcome.BlobUnreachable(info)
        }

        Log.d(
            TAG,
            "event=restore_check_hit bytes=${pointer.size} ageHours=${(System.currentTimeMillis() / 1000 - pointer.updatedAt) / 3600}",
        )
        return CheckOutcome.Found(info)
    }

    /**
     * Download + verify + decrypt + import the backup referenced by [info].
     * On success the caller should flip `backupEnabled = true` and schedule
     * the worker.
     */
    suspend fun restore(info: BackupInfo) = pipelineMutex.withLock {
        val started = System.currentTimeMillis()
        val pointer = info.pointer

        // 1 — unwrap dataKey (needs NIP-44-decrypt access)
        val wrappedHex = info.keyEvent.content
        val dataKeyHex = try {
            nip44DecryptToSelf(wrappedHex)
        } catch (e: Exception) {
            throw BackupException(BackupErrorReason.DataKeyUnwrapFailed, cause = e)
        }
        val dataKey = dataKeyHex.hexToByteArray()
        require(dataKey.size == 32) { "Unwrapped dataKey is not 32 bytes" }

        // 2 — download + verify + decrypt, every stage size-capped so a
        // hostile Blossom server cannot OOM the app before the integrity
        // check runs. The uploader streams the body while hashing it and
        // refuses anything over pointer.size + slack; the decompressor
        // refuses anything over MAX_PLAINTEXT_BYTES on its output.
        // Pre-filter pointer.servers through the shared URL gate — if a
        // historical backup was published while the user's nsec was
        // briefly compromised, the pointer could still list attacker
        // URLs; we never dial them regardless of their Schnorr pedigree.
        val servers = pointer.servers.filter { com.cruxcoach.android.nostr.UrlValidation.isValidBlossom(it) }
        if (servers.isEmpty()) {
            throw BackupException(BackupErrorReason.PointerListsNoUsableServers)
        }
        val ciphertext = uploader.download(
            sha256Hex = pointer.sha256,
            servers = servers,
            maxBytes = pointer.size + DOWNLOAD_SLACK_BYTES,
        )
        Log.d(
            TAG,
            "event=restore_download_ok sha256Prefix=${pointer.sha256.take(8)} bytes=${ciphertext.size} durationMs=${System.currentTimeMillis() - started}",
        )
        val compressed = BackupCrypto.decrypt(ciphertext, dataKey)
        val json = BackupCompression
            .decompress(compressed, maxBytes = MAX_PLAINTEXT_BYTES)
            .toString(Charsets.UTF_8)

        // 3 — import into local DB. Pin the decrypted payload to the
        // active signer: NIP-44 already guarantees the caller held the
        // right private key to decrypt, but an additional envelope-
        // pubkey check catches bookkeeping bugs (re-imported own old
        // nsec, mid-flow identity flip before A2 clears ran, etc.)
        // before any row is written.
        val importResult = CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            boardRepository = boardRepository,
            transactionRunner = transactionRunner,
            expectedNostrPubkey = nostrSigner.getPublicKeyHex(),
        )

        // 4 — cache dataKey for future backups (self-encrypt via NIP-44)
        val wrappedFresh = nip44EncryptToSelf(dataKeyHex)
        preferences.setWrappedDataKey(wrappedFresh)

        val rowsImported = with(importResult) {
            assessments + bodyStats + workoutLogs + climbLogs + trainingPlans +
                boardAscents + boardBids + boardSessions + climbLists +
                ownClimbs + ownClimbStats +
                (if (profileImported) 1 else 0)
        }
        Log.d(
            TAG,
            "event=restore_done rowsImported=$rowsImported skippedDuplicates=${importResult.skippedDuplicates} durationMs=${System.currentTimeMillis() - started}",
        )
    }

    /**
     * Active opt-out: publishes Kind 5 deletion events for the pointer +
     * key, deletes the current blob from every Blossom server, and clears
     * local identity-scoped state (§20.2). Still best-effort on the
     * relay / Blossom side — servers that ignore the delete keep their
     * copy — but the caller now gets a structured [DeleteRemoteOutcome]
     * instead of an unconditional success signal, so the UI can say
     * "deletion published, but no Blossom server acknowledged" rather
     * than "remote backups deleted" when nothing was actually removed.
     */
    suspend fun deleteRemoteBackups(): DeleteRemoteOutcome = pipelineMutex.withLock {
        val notes = mutableListOf<DeleteRemoteNote>()
        val pubkey = nostrSigner.getPublicKeyHex()
        val backupDTag = runCatching { dTagDeriver.derive(BackupPreferences.IDENTIFIER_BACKUP) }.getOrNull()
        val keyDTag = runCatching { dTagDeriver.derive(BackupPreferences.IDENTIFIER_KEY) }.getOrNull()
        if (backupDTag == null || keyDTag == null) {
            notes += DeleteRemoteNote.DTagDerivationFailed
        }

        var relaysAttempted = 0
        var relaysAccepted = 0
        if (backupDTag != null && keyDTag != null) {
            val r = runCatching {
                publishDeletionForDTagsWithStats(pubkey, listOf(backupDTag, keyDTag))
            }
            if (r.isSuccess) {
                val (att, acc) = r.getOrNull() ?: (0 to 0)
                relaysAttempted = att
                relaysAccepted = acc
                when {
                    att == 0 -> notes += DeleteRemoteNote.NoWriteRelays
                    acc == 0 -> notes += DeleteRemoteNote.NoRelayAcceptedDeletion
                    acc < att -> notes += DeleteRemoteNote.PartialRelayAccept(accepted = acc, attempted = att)
                }
            } else {
                notes += DeleteRemoteNote.RelayPublishThrew
            }
        }

        var blossomAttempted = 0
        var blossomAccepted = 0
        preferences.getPreviousBlobSha256()?.let { sha ->
            val servers = runCatching { discoverBlossomServers(pubkey) }
                .getOrDefault(BlossomUploader.DEFAULT_SERVERS)
            val outcome = uploader.delete(sha, servers)
            blossomAttempted = outcome.attempted
            blossomAccepted = outcome.succeeded
            when {
                outcome.authFailed -> notes += DeleteRemoteNote.BlossomAuthFailed
                outcome.fullyFailed() -> notes += DeleteRemoteNote.BlossomFullyRejected
                outcome.partiallySucceeded() ->
                    notes += DeleteRemoteNote.BlossomPartial(
                        accepted = outcome.succeeded,
                        attempted = outcome.attempted,
                    )
            }
        }

        // Clear local state regardless: the user explicitly asked for
        // opt-out, so we forget everything we can locally even if the
        // remote delete was only partial. The UI still surfaces the
        // structured counts so the user knows to re-try later if it
        // fell short.
        preferences.clearAllIdentityState()
        preferences.setBackupEnabled(false)

        DeleteRemoteOutcome(
            relaysAttempted = relaysAttempted,
            relaysAccepted = relaysAccepted,
            blossomAttempted = blossomAttempted,
            blossomAccepted = blossomAccepted,
            notes = notes.toList(),
        )
    }

    // ------------------------------------------------------------ pointer ops

    private suspend fun publishPointerEvent(pointer: BackupPointer, pubkey: String) {
        val plaintext = JSON.encodeToString(BackupPointer.serializer(), pointer)
        val ciphertext = nip44EncryptToSelf(plaintext)
        val backupDTag = dTagDeriver.derive(BackupPreferences.IDENTIFIER_BACKUP)
        val tags = arrayOf(arrayOf("d", backupDTag))
        val event = nostrSigner.signer.sign<com.vitorpamplona.quartz.nip01Core.core.Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = tags,
            content = ciphertext,
        )
        // Use the per-relay-stats variant so we can distinguish "every
        // relay rejected" (durable backup chain broken — must throw) from
        // partial accept (still durable on at least one relay). Previously
        // we logged on `false` and continued, which let `performFullBackup`
        // advance `previousBlobSha256` and delete the prior blob even when
        // no relay knew about the new pointer — the user's restore path
        // could then find no pointer at all.
        val (attempted, accepted) = pool.sendEventWithStats(event)
        if (accepted == 0) {
            Log.w(TAG, "event=backup_pointer_publish_failed attempted=$attempted accepted=0")
            throw BackupException(BackupErrorReason.PointerEventNotDurable(attempted = attempted))
        }
        Log.d(
            TAG,
            "event=backup_pointer_published attempted=$attempted accepted=$accepted",
        )
    }

    /** [Pair] of `(relaysAttempted, relaysAccepted)` — surfaces both counts
     *  to the caller instead of the generic "any accepted" boolean that
     *  [pool.sendEvent] returns.
     */
    private suspend fun publishDeletionForDTagsWithStats(
        pubkey: String,
        dTags: List<String>,
    ): Pair<Int, Int> {
        val tags = mutableListOf<Array<String>>().apply {
            dTags.forEach { dTag -> add(arrayOf("a", "$KIND_REPLACEABLE_PARAMETERIZED:$pubkey:$dTag")) }
            add(arrayOf("k", KIND_REPLACEABLE_PARAMETERIZED.toString()))
        }
        val event = nostrSigner.signer.sign<com.vitorpamplona.quartz.nip01Core.core.Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND_DELETION,
            tags = tags.toTypedArray(),
            content = "backup opt-out",
        )
        return pool.sendEventWithStats(event)
    }

    private suspend fun publishDeletionForDTags(pubkey: String, dTags: List<String>) {
        val tags = mutableListOf<Array<String>>().apply {
            dTags.forEach { dTag -> add(arrayOf("a", "$KIND_REPLACEABLE_PARAMETERIZED:$pubkey:$dTag")) }
            add(arrayOf("k", KIND_REPLACEABLE_PARAMETERIZED.toString()))
        }
        val event = nostrSigner.signer.sign<com.vitorpamplona.quartz.nip01Core.core.Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND_DELETION,
            tags = tags.toTypedArray(),
            content = "backup opt-out",
        )
        pool.sendEvent(event)
    }

    // ------------------------------------------------------------ dataKey ops

    /**
     * Resolve the symmetric data-encryption key used for the Blossom blob.
     * Three-tiered recovery so a partially-persisted or otherwise corrupt
     * local cache doesn't silently brick every future backup attempt:
     *
     *  1. Unwrap from local preferences (happy path).
     *  2. If that fails, clear the bad blob and re-fetch the authoritative
     *     Kind 30078 key event from relays; the local cache was a copy of
     *     that event's content to begin with, so in practice this recovers
     *     every scenario where only the local state is bad (App-Crash
     *     between generate + persist, DataStore migration glitch, …).
     *  3. If the relay copy is also missing / undecryptable, generate a
     *     fresh dataKey, publish the new key event, and clear the pointer
     *     stash — any prior Blossom blob becomes orphaned but a new backup
     *     can succeed from this point on.
     */
    private suspend fun getOrCreateDataKey(): ByteArray {
        preferences.getWrappedDataKey()?.let { wrapped ->
            runCatching { unwrapDataKey(wrapped) }.getOrNull()?.let { return it }
            Log.w(TAG, "event=key_cache_miss reason=corrupt")
            preferences.setWrappedDataKey(null)
        }

        runCatching { fetchWrappedKeyFromRelays() }.getOrNull()?.let { wrapped ->
            runCatching { unwrapDataKey(wrapped) }.getOrNull()?.let { dk ->
                Log.i(TAG, "event=key_cache_recover source=relays")
                preferences.setWrappedDataKey(wrapped)
                return dk
            }
        }

        // Refuse to regenerate when there is any prior history on this
        // device. A null result from `fetchWrappedKeyFromRelays` could mean
        // (a) the relays genuinely don't hold a key event for this identity
        // — fresh setup or post-clearAllIdentityState — or (b) the relay
        // subscription timed out / dropped events under load. Pre-fix we
        // treated both cases the same and regenerated, which (combined
        // with the new throw-on-publish) replaced the relay's key event
        // with a fresh one — orphaning the existing Blossom blob from
        // its decryption key. The old backup became permanently
        // undecryptable on every device once the new key event won the
        // replaceable-event race.
        //
        // Signal of "prior history on this device": any one of
        // `lastKeyEventPublish`, `previousBlobSha256`, `lastBackupSync`
        // is non-null. After clearAllIdentityState all three are null,
        // so a clean opt-in / fresh-install path still hits the
        // generate branch below. Pure cache eviction (Android wipes
        // the encrypted preferences but DataStore survives) keeps the
        // history fields and routes us to the throw branch.
        val hasPriorHistory = preferences.getLastKeyEventPublish() != null ||
            preferences.getPreviousBlobSha256() != null ||
            preferences.lastBackupSync.first() != null
        if (hasPriorHistory) {
            Log.w(
                TAG,
                "event=key_cache_regenerate_blocked reason=prior-history-present " +
                    "lastKeyEventPublish=${preferences.getLastKeyEventPublish() != null} " +
                    "previousBlobSha=${preferences.getPreviousBlobSha256() != null} " +
                    "lastBackupSync=${preferences.lastBackupSync.first() != null}",
            )
            throw BackupException(BackupErrorReason.KeyFetchAmbiguous)
        }

        Log.w(TAG, "event=key_cache_regenerate reason=unrecoverable")
        val fresh = BackupCrypto.generateKey()
        val freshHex = fresh.toHexString()
        val wrapped = nip44EncryptToSelf(freshHex)
        // Publish FIRST. If `publishKeyEvent` throws (no relay accepted), we
        // leave local state untouched so the next attempt re-tries from a
        // clean slate. Persisting the wrapped key locally before publish
        // would silently mask the failure: subsequent backups would happily
        // unwrap the locally-cached key and upload blobs that no other
        // device could ever decrypt because no relay holds the key event.
        publishKeyEvent(wrapped)
        preferences.setPreviousBlobSha256(null)
        preferences.setWrappedDataKey(wrapped)
        preferences.setLastKeyEventPublish(System.currentTimeMillis() / 1000)
        return fresh
    }

    private suspend fun unwrapDataKey(wrapped: String): ByteArray {
        val hex = nip44DecryptToSelf(wrapped)
        val bytes = hex.hexToByteArray()
        require(bytes.size == 32) { "Unwrapped dataKey is not 32 bytes" }
        return bytes
    }

    /**
     * Re-fetch the current user's Kind 30078 key event. LOCAL-signer users
     * can derive the d-tag HMAC and query surgically; Amber users don't
     * hold the private key, so we fall back to "query all parameterized
     * events for this pubkey and shape-match the key event".
     */
    private suspend fun fetchWrappedKeyFromRelays(timeoutMs: Long = 30_000L): String? {
        val pubkey = nostrSigner.getPublicKeyHex()
        val event = when (nostrSigner.getStoredSignerMode()) {
            SignerMode.LOCAL -> {
                val keyDTag = dTagDeriver.derive(BackupPreferences.IDENTIFIER_KEY)
                val filter = buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(KIND_REPLACEABLE_PARAMETERIZED)) })
                    put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                    put("#d", buildJsonArray { add(JsonPrimitive(keyDTag)) })
                }
                queryAllValid(filter.toString(), timeoutMs)
                    .firstOrNull { it.tagValue("d") == keyDTag }
            }
            SignerMode.AMBER -> {
                val filter = buildJsonObject {
                    put("kinds", buildJsonArray { add(JsonPrimitive(KIND_REPLACEABLE_PARAMETERIZED)) })
                    put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                }
                queryAllValid(filter.toString(), timeoutMs).firstOrNull { ev ->
                    val decrypted = runCatching { nip44DecryptToSelf(ev.content) }.getOrNull()
                    decrypted != null && decrypted.looksLikeHexBytes(expectedBytes = 32)
                }
            }
        }
        return event?.content
    }

    private suspend fun publishKeyEvent(wrappedDataKey: String) {
        val keyDTag = dTagDeriver.derive(BackupPreferences.IDENTIFIER_KEY)
        val tags = arrayOf(arrayOf("d", keyDTag))
        val event = nostrSigner.signer.sign<com.vitorpamplona.quartz.nip01Core.core.Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            tags = tags,
            content = wrappedDataKey,
        )
        // Same fix as publishPointerEvent: surface "0 relays accepted" as
        // an exception so callers don't stamp success when the key event
        // never landed. Without the throw, restore on a fresh device
        // could be permanently bricked because no relay holds the key.
        val (attempted, accepted) = pool.sendEventWithStats(event)
        if (accepted == 0) {
            Log.w(TAG, "event=key_event_publish_failed attempted=$attempted accepted=0")
            throw BackupException(BackupErrorReason.KeyEventNotDurable(attempted = attempted))
        }
        Log.d(TAG, "event=key_event_published attempted=$attempted accepted=$accepted")
    }

    // --------------------------------------------------------------- fetchers

    private suspend fun discoverBlossomServers(pubkey: String): List<String> {
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_BLOSSOM_SERVER_LIST)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("limit", JsonPrimitive(1))
        }
        val event = queryFirstValid(filter.toString(), timeoutMs = 5_000L)
        val userServers = event?.extractServerTags().orEmpty()
        // Additive union: user servers first (upload priority), then defaults not listed.
        val merged = LinkedHashSet<String>()
        merged.addAll(userServers)
        merged.addAll(BlossomUploader.DEFAULT_SERVERS)
        return merged.toList()
    }

    private suspend fun fetchByDTagHmac(
        pubkey: String,
        timeoutMs: Long,
    ): Pair<MinimalEvent, MinimalEvent>? {
        val backupDTag = dTagDeriver.derive(BackupPreferences.IDENTIFIER_BACKUP)
        val keyDTag = dTagDeriver.derive(BackupPreferences.IDENTIFIER_KEY)

        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_REPLACEABLE_PARAMETERIZED)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("#d", buildJsonArray {
                add(JsonPrimitive(backupDTag))
                add(JsonPrimitive(keyDTag))
            })
        }
        val events = queryAllValid(filter.toString(), timeoutMs)
        // Pick the newest event per d-tag, not the first one any relay
        // happened to deliver. A hostile relay (or an out-of-date one
        // in a multi-relay pool) could otherwise force restore onto a
        // prior backup by serving an older-but-still-signed event
        // before the current one — a classic replaceable-event rollback.
        val pointer = events.filter { it.tagValue("d") == backupDTag }
            .maxByOrNull { it.createdAt } ?: return null
        val keyEv = events.filter { it.tagValue("d") == keyDTag }
            .maxByOrNull { it.createdAt } ?: return null
        return pointer to keyEv
    }

    private suspend fun fetchByQueryAllAmber(
        pubkey: String,
        timeoutMs: Long,
    ): Pair<MinimalEvent, MinimalEvent>? {
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_REPLACEABLE_PARAMETERIZED)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
        }
        val events = queryAllValid(filter.toString(), timeoutMs)
        // The key event's content is valid hex (NIP-44 v2 base64 payload).
        // The pointer's decrypted content is a JSON BackupPointer with a
        // `version` field. Sort by createdAt DESC first so the earliest
        // decryptable match is automatically the newest — happy-case
        // stays at two Amber approval prompts, but rollback attempts
        // no longer win just by arriving first on the wire.
        //
        // Cap the number of decrypt attempts. Under well-behaved relays
        // the happy case spends 2 Amber prompts. A hostile relay can
        // fan out a large set of decoy events (passing the Schnorr gate
        // because they're just replayed real user events, or freshly
        // forged fillers) and drive Amber into a prompt-storm before
        // the user reaches the real pointer/key. Eight attempts is
        // enough headroom for legitimate duplicates while refusing the
        // flood pattern.
        val sorted = events.sortedByDescending { it.createdAt }.take(MAX_AMBER_DECRYPT_ATTEMPTS)
        var pointer: MinimalEvent? = null
        var keyEv: MinimalEvent? = null
        for (ev in sorted) {
            if (pointer == null) {
                val decrypted = runCatching { decryptPointer(ev.content, pubkey) }.getOrNull()
                if (decrypted != null) {
                    pointer = ev
                    continue
                }
            }
            if (keyEv == null) {
                val decrypted = runCatching { nip44DecryptToSelf(ev.content) }.getOrNull()
                if (decrypted != null && decrypted.looksLikeHexBytes(expectedBytes = 32)) {
                    keyEv = ev
                    continue
                }
            }
            if (pointer != null && keyEv != null) break
        }
        if (pointer == null || keyEv == null) return null
        return pointer to keyEv
    }

    /**
     * Short-lived subscription; collects events until EOSE or [timeoutMs],
     * returns the one with the highest `created_at`. Returns null on
     * timeout / no result.
     */
    private suspend fun queryFirstValid(filter: String, timeoutMs: Long): MinimalEvent? {
        val all = queryAllValid(filter, timeoutMs)
        return all.maxByOrNull { it.createdAt }
    }

    private suspend fun queryAllValid(filter: String, timeoutMs: Long): List<MinimalEvent> {
        // skipDedup=true: NostrRelayPool maintains a process-wide
        // `seenEventIds` cache shared by every subscriber, used to
        // collapse duplicate EVENT messages from multiple relays in
        // live streams. Backup checkForBackup / fetchWrappedKeyFromRelays
        // are one-shot historical queries — by the time we run, a
        // long-lived foreground subscription (NostrPushCoordinator,
        // AnnouncementsViewModel, etc.) may have already seen our own
        // backup pointer / key event and added their IDs to
        // `seenEventIds`. The pool would then drop them on our
        // dedicated subscription too, leaving the flow empty after
        // 10s → CheckOutcome.NotFound on a backup that is plainly
        // present on relays. NotificationPollWorker uses the same
        // skipDedup escape hatch for the same reason.
        val flow: Flow<String> = pool.subscribe(filter, skipDedup = true, closeOnEose = true)
        // Collect into a mutable list outside the timeout's cancellation
        // scope so partial results survive a cancel. Pre-fix we used
        // `withTimeoutOrNull { flow.toList() }`, which on timeout cancels
        // the collector — and any EVENT lines arriving in the same
        // millisecond window get logged as "flow missing or full" by the
        // pool (subscriber gone) instead of reaching us. With one slow
        // relay (e.g. damus under load returning EVENTs at +12s) and the
        // others having already EOSE'd, we'd time out with zero events
        // even though the data was on the wire. Now: each event hits
        // `collected` as it arrives, and on timeout we keep whatever we
        // have. The pool filters EOSE internally when closeOnEose=true;
        // what reaches us here is already only EVENT payloads. The
        // parse-success guard below filters any malformed strings.
        val collected = mutableListOf<String>()
        try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                flow.collect { collected.add(it) }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.d(
                TAG,
                "event=query_all_valid_timeout collectedSoFar=${collected.size}",
            )
        }
        return collected.mapNotNull { MinimalEvent.fromJson(it) }
    }

    // ------------------------------------------------------------- crypto ops

    private suspend fun nip44EncryptToSelf(plaintext: String): String {
        val selfPubkey = nostrSigner.getPublicKeyHex()
        return nostrSigner.signer.nip44Encrypt(plaintext, selfPubkey)
    }

    private suspend fun nip44DecryptToSelf(ciphertext: String): String {
        val selfPubkey = nostrSigner.getPublicKeyHex()
        return nostrSigner.signer.nip44Decrypt(ciphertext, selfPubkey)
    }

    private suspend fun decryptPointer(content: String, pubkey: String): BackupPointer {
        val plaintext = nip44DecryptToSelf(content)
        return JSON.decodeFromString(BackupPointer.serializer(), plaintext)
    }

    // ----------------------------------------------------------------- utils

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun String.looksLikeHexBytes(expectedBytes: Int): Boolean {
        if (length != expectedBytes * 2) return false
        return all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    companion object {
        private const val TAG = "BackupSync"
        private const val KIND_REPLACEABLE_PARAMETERIZED = 30078
        private const val KIND_BLOSSOM_SERVER_LIST = 10063
        private const val KIND_DELETION = 5
        // Refresh the Kind-30078 key event roughly every 30 days so
        // relays that evict older replaceable-parameterized events don't
        // leave the only restore anchor stranded.
        private const val KEY_EVENT_REFRESH_INTERVAL_SEC = 30L * 24L * 60L * 60L
        // Amber prompts once per decrypt call; a hostile relay can fan out
        // a large set of lookalike events and drive the user into a prompt
        // storm. Cap the Amber-path decrypt attempts at a value that
        // comfortably absorbs legitimate duplicates (two replaceable d-tag
        // events per relay × a few relays) while refusing the flood.
        private const val MAX_AMBER_DECRYPT_ATTEMPTS = 8
        // M1 tolerance window: accept pointer events up to this far
        // behind LAST_BACKUP_SYNC. LAST_BACKUP_SYNC is recorded after
        // the pointer publish + blob verify + cleanup, so the pointer
        // we just published is routinely a few seconds older on paper.
        // 5 minutes safely absorbs that drift + any clock skew; a real
        // rollback attack serves events hours or days stale.
        private const val STALE_POINTER_TOLERANCE_SEC = 5L * 60L
        // Tiny slack over the pointer-declared blob size — covers
        // framing rounding between ciphertext bytes and HTTP content-
        // length without letting a hostile server pad gigabytes past
        // the declared size.
        private const val DOWNLOAD_SLACK_BYTES = 1024L
        // Hard ceiling on the decompressed backup plaintext. Realistic
        // backups land at ~500 KB after gzip even for 2-year power
        // users; 64 MB plaintext (≈13 MB gzip at the ~5:1 ratio)
        // covers ~20 years of daily Kilter logs while refusing any
        // gzip-bomb payload that would otherwise OOM the restore.
        private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

data class BackupInfo(
    val pointer: BackupPointer,
    val pointerEvent: MinimalEvent,
    val keyEvent: MinimalEvent,
)

/**
 * Structured per-leg outcome of [BackupRepository.deleteRemoteBackups].
 * The UI renders the four counts plus the human-readable [notes] so the
 * user sees exactly how thorough the removal was — "3/3 relays, 2/2
 * Blossom servers acknowledged" vs "2/3 relays, 0/2 Blossom". Keeps
 * the honest Nostr-deletion caveat (third-party mirrors / CDN caches
 * may still hold copies) up to the caller's copy.
 */
data class DeleteRemoteOutcome(
    val relaysAttempted: Int,
    val relaysAccepted: Int,
    val blossomAttempted: Int,
    val blossomAccepted: Int,
    /**
     * Per-leg notes describing partial / failure modes. Each note is
     * mapped to a localized `stringResource(...)` by the UI; the type
     * is intentionally a structured enum (not List<String>) so a
     * German-locale user no longer sees English diagnostic bullets.
     */
    val notes: List<DeleteRemoteNote>,
) {
    /** True only when every attempted leg ack'd and nothing was noted. */
    fun isFullSuccess(): Boolean = notes.isEmpty() &&
        relaysAttempted > 0 && relaysAccepted == relaysAttempted &&
        (blossomAttempted == 0 || blossomAccepted == blossomAttempted)
}

/**
 * Outcome of [BackupRepository.checkForBackup]. Gives callers enough
 * signal to render a specific error message instead of a generic
 * "nothing happened" snackbar.
 */
sealed class CheckOutcome {
    data class Found(val info: BackupInfo) : CheckOutcome()
    /** No matching events returned. Either no backup for this account, or all relays silent. */
    data object NotFound : CheckOutcome()
    /** Pointer event was found on a relay but could not be decrypted — typically a key mismatch. */
    data object DecryptFailed : CheckOutcome()
    /** Subscribe / fetch threw before we could evaluate results. */
    data class Fetch(val message: String) : CheckOutcome()
    /**
     * Pointer + key events present on relays, but a HEAD probe of every
     * Blossom server in the pointer's server list responded as
     * unreachable / blob-absent. Distinguishes the "Backup gefunden,
     * Restore failt sofort" sequence from genuinely-Found: the
     * encrypted ciphertext is gone (e.g. a prior delete-remote opt-out
     * cleaned Blossom but the Kind-5 deletion never reached the
     * particular relay we just queried; or no server ever accepted the
     * upload). HEAD failures can be transient network glitches, so the
     * UI surfaces this as a specific message and lets the user retry
     * later — it does not permanently block restore.
     */
    data class BlobUnreachable(val info: BackupInfo) : CheckOutcome()
}

/**
 * Carries a structured [BackupErrorReason] that the UI maps to a
 * localized `stringResource(...)`. The `message` (used by logcat /
 * `runCatching.exceptionOrNull()?.message` fallbacks) is the dev-facing
 * English form derived via [toLogMessage] — never shown to end users.
 *
 * The legacy `(String, Throwable?)` constructor is kept for rare call
 * sites that still pass a raw string (e.g. unit tests, future generic
 * paths); it lifts the message into [BackupErrorReason.Other].
 */
class BackupException(
    val reason: BackupErrorReason,
    cause: Throwable? = null,
) : Exception(reason.toLogMessage(), cause) {
    constructor(message: String, cause: Throwable? = null)
        : this(BackupErrorReason.Other(message), cause)
}

/**
 * Tiny JSON → event projection so [BackupRepository] doesn't need to drag
 * the full quartz Event type around for passive reads.
 */
data class MinimalEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /**
     * Extract `["server", url]` tag values from a Kind 10063 Blossom
     * server-list event, dropping any entry that doesn't pass the
     * shared scheme + length + whitespace gate. The filter is the
     * same one that gates Kind 10002 relay URLs, so every URL the
     * app dials — regardless of provenance — shares a single allow
     * rule. Cap at 16 distinct entries: legitimate Blossom server
     * lists are 1–3, so anything larger is either a typo or a
     * hostile attempt to drive parallel uploads.
     */
    fun extractServerTags(): List<String> =
        tags.asSequence()
            .filter { it.size >= 2 && it[0] == "server" }
            .map { it[1].trimEnd('/') }
            .filter { com.cruxcoach.android.nostr.UrlValidation.isValidBlossom(it) }
            .distinct()
            .take(16)
            .toList()

    companion object {
        /**
         * Parse + Schnorr-verify a raw Nostr event JSON. Returns `null`
         * for any parse, kind, or signature failure so [queryAllValid]
         * transparently drops forged/garbled events in its
         * `mapNotNull`. Running verification here (the single ingress
         * for every relay-sourced event in FEAT-002) means no caller
         * can accidentally skip the check.
         */
        fun fromJson(json: String): MinimalEvent? = try {
            val event = com.vitorpamplona.quartz.nip01Core.core.Event.fromJson(json)
            if (!event.verifySignature()) {
                null
            } else {
                MinimalEvent(
                    id = event.id,
                    pubkey = event.pubKey,
                    kind = event.kind,
                    createdAt = event.createdAt,
                    tags = event.tags.map { it.toList() },
                    content = event.content,
                    sig = event.sig,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
