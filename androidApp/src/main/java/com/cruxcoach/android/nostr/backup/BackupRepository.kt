package com.cruxcoach.android.nostr.backup

import android.util.Log
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.data.repository.ClimbRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlanRepository
import com.cruxcoach.data.repository.UserRepository
import com.cruxcoach.data.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
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
    private val transactionRunner: TransactionRunner,
) {

    /**
     * Complete a backup cycle. Throws [BackupException] if the pipeline
     * cannot guarantee the blob-before-pointer invariant; the worker
     * translates this into a retry.
     */
    suspend fun performFullBackup(trigger: String = "manual") {
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
            exportedAt = Instant.now().toString(),
            nostrPubkey = pubkey,
        )
        val plaintext = json.toByteArray(Charsets.UTF_8)
        val compressed = BackupCompression.compress(plaintext)
        val ciphertext = BackupCrypto.encrypt(compressed, dataKey)
        val sha256 = ciphertext.sha256Hex()

        // 4 — discover Blossom servers (user's Kind 10063 + defaults)
        val servers = discoverBlossomServers(pubkey)

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
            // worker. Without this, the user sees "Blob upload failed on
            // all 2 servers" and has no way to tell it's an Amber
            // permission issue, not a Blossom outage.
            val authErrors = uploadResults.mapNotNull { r ->
                r.error?.takeIf { it.startsWith("auth:") }
            }
            val detail = when {
                authErrors.size == total && authErrors.any { it.contains("No activity to launch") } ->
                    // Backup always runs via WorkManager (no foreground
                    // Activity), so Amber's Intent-based approval dialog
                    // has nothing to launch onto. The only self-service
                    // fix is the user granting auto-approve in Amber.
                    "Amber needs to be set to \"always approve\" for CruxCoach's signing operations. Open Amber → CruxCoach and enable automatic approval, otherwise background backup can't sign."
                authErrors.size == total -> authErrors.first()
                else -> "Blob upload failed on all $total servers"
            }
            throw BackupException(detail)
        }
        if (ok < total) {
            Log.w(TAG, "event=backup_upload_partial serversOk=$ok serversTotal=$total sizeKb=${ciphertext.size / 1024}")
        } else {
            Log.d(
                TAG,
                "event=backup_upload_ok serversOk=$ok serversTotal=$total sizeKb=${ciphertext.size / 1024} durationMs=${System.currentTimeMillis() - started}",
            )
        }

        // 6 — HEAD-verify on at least one server
        val verified = uploader.verifyExists(sha256, servers)
        if (!verified) {
            Log.w(TAG, "event=backup_verify_failed serversTotal=$total")
            throw BackupException("Blob not visible on any server after upload")
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
        previousSha?.let { stale -> uploader.delete(stale, servers) }

        // 9 — record success
        val now = System.currentTimeMillis() / 1000
        preferences.setLastBackupSync(now)
        Log.d(
            TAG,
            "event=backup_done totalDurationMs=${System.currentTimeMillis() - started}",
        )
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
    suspend fun checkForBackup(timeoutMs: Long = 10_000L): CheckOutcome {
        Log.d(TAG, "event=restore_check_start signerMode=${nostrSigner.getStoredSignerMode().name}")
        val pubkey = nostrSigner.getPublicKeyHex()
        val mode = nostrSigner.getStoredSignerMode()

        val fetched = try {
            when (mode) {
                SignerMode.LOCAL -> fetchByDTagHmac(pubkey, timeoutMs)
                SignerMode.AMBER -> fetchByQueryAllAmber(pubkey, timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=restore_check_miss reason=fetch-error", e)
            return CheckOutcome.Fetch(e.message ?: "fetch failed")
        }

        val (pointerEvent, keyEvent) = fetched ?: run {
            Log.d(TAG, "event=restore_check_miss reason=no-pointer")
            return CheckOutcome.NotFound
        }

        val pointer = try {
            decryptPointer(pointerEvent.content, pubkey)
        } catch (e: Exception) {
            Log.w(TAG, "event=restore_check_miss reason=decrypt-failed", e)
            return CheckOutcome.DecryptFailed
        }

        Log.d(
            TAG,
            "event=restore_check_hit sizeKb=${pointer.size / 1024} ageHours=${(System.currentTimeMillis() / 1000 - pointer.updatedAt) / 3600}",
        )
        return CheckOutcome.Found(
            BackupInfo(pointer = pointer, pointerEvent = pointerEvent, keyEvent = keyEvent),
        )
    }

    /**
     * Download + verify + decrypt + import the backup referenced by [info].
     * On success the caller should flip `backupEnabled = true` and schedule
     * the worker.
     */
    suspend fun restore(info: BackupInfo) {
        val started = System.currentTimeMillis()
        val pointer = info.pointer

        // 1 — unwrap dataKey (needs NIP-44-decrypt access)
        val wrappedHex = info.keyEvent.content
        val dataKeyHex = try {
            nip44DecryptToSelf(wrappedHex)
        } catch (e: Exception) {
            throw BackupException("dataKey unwrap failed", e)
        }
        val dataKey = dataKeyHex.hexToByteArray()
        require(dataKey.size == 32) { "Unwrapped dataKey is not 32 bytes" }

        // 2 — download + verify + decrypt
        val servers = pointer.servers
        val ciphertext = uploader.download(pointer.sha256, servers)
        val actualSha = ciphertext.sha256Hex()
        if (actualSha != pointer.sha256) {
            Log.w(TAG, "event=restore_download_failed reason=sha_mismatch")
            throw BackupException("Blob SHA-256 mismatch (expected ${pointer.sha256.take(8)}..., got ${actualSha.take(8)}...)")
        }
        val compressed = BackupCrypto.decrypt(ciphertext, dataKey)
        val json = BackupCompression.decompress(compressed).toString(Charsets.UTF_8)

        // 3 — import into local DB
        val importResult = CruxCoachBackup.import(
            jsonString = json,
            selectedCategories = CruxCoachBackup.Category.entries.toSet(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            transactionRunner = transactionRunner,
        )

        // 4 — cache dataKey for future backups (self-encrypt via NIP-44)
        val wrappedFresh = nip44EncryptToSelf(dataKeyHex)
        preferences.setWrappedDataKey(wrappedFresh)

        val rowsImported = with(importResult) {
            assessments + bodyStats + workoutLogs + climbLogs + trainingPlans +
                boardAscents + boardBids + boardSessions + climbLists +
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
     * local identity-scoped state (§20.2). Best-effort — relays / servers
     * that ignore the deletion keep a copy.
     */
    suspend fun deleteRemoteBackups() {
        val pubkey = nostrSigner.getPublicKeyHex()
        val backupDTag = runCatching { dTagDeriver.derive(BackupPreferences.IDENTIFIER_BACKUP) }.getOrNull()
        val keyDTag = runCatching { dTagDeriver.derive(BackupPreferences.IDENTIFIER_KEY) }.getOrNull()

        if (backupDTag != null && keyDTag != null) {
            runCatching { publishDeletionForDTags(pubkey, listOf(backupDTag, keyDTag)) }
        }

        preferences.getPreviousBlobSha256()?.let { sha ->
            val servers = runCatching { discoverBlossomServers(pubkey) }.getOrDefault(BlossomUploader.DEFAULT_SERVERS)
            uploader.delete(sha, servers)
        }

        preferences.clearAllIdentityState()
        preferences.setBackupEnabled(false)
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
        val ok = pool.sendEvent(event)
        if (ok) {
            Log.d(TAG, "event=backup_pointer_published writeRelayCount=${pool.writeRelays().size}")
        } else {
            Log.w(TAG, "event=backup_pointer_publish_failed")
        }
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

        Log.w(TAG, "event=key_cache_regenerate reason=unrecoverable")
        preferences.setPreviousBlobSha256(null)
        val fresh = BackupCrypto.generateKey()
        val freshHex = fresh.toHexString()
        val wrapped = nip44EncryptToSelf(freshHex)
        preferences.setWrappedDataKey(wrapped)
        publishKeyEvent(wrapped)
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
    private suspend fun fetchWrappedKeyFromRelays(timeoutMs: Long = 10_000L): String? {
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
        pool.sendEvent(event)
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
        val pointer = events.firstOrNull { ev -> ev.tagValue("d") == backupDTag } ?: return null
        val keyEv = events.firstOrNull { ev -> ev.tagValue("d") == keyDTag } ?: return null
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
        // `version` field. Check each candidate for either shape.
        var pointer: MinimalEvent? = null
        var keyEv: MinimalEvent? = null
        for (ev in events) {
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
        val flow: Flow<String> = pool.subscribe(filter, closeOnEose = true)
        // The pool filters EOSE internally when closeOnEose=true; what
        // reaches us here is already only EVENT payloads. The parse-success
        // guard below deduplicates any malformed strings.
        val collected = withTimeoutOrNull(timeoutMs) {
            flow.toList()
        } ?: emptyList()
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
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

data class BackupInfo(
    val pointer: BackupPointer,
    val pointerEvent: MinimalEvent,
    val keyEvent: MinimalEvent,
)

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
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    fun extractServerTags(): List<String> =
        tags.filter { it.size >= 2 && it[0] == "server" }.map { it[1].trimEnd('/') }

    companion object {
        fun fromJson(json: String): MinimalEvent? = try {
            val obj = Json.parseToJsonElement(json).jsonObject
            MinimalEvent(
                id = (obj["id"] as JsonPrimitive).content,
                pubkey = (obj["pubkey"] as JsonPrimitive).content,
                kind = (obj["kind"] as JsonPrimitive).long.toInt(),
                createdAt = (obj["created_at"] as JsonPrimitive).long,
                tags = (obj["tags"] as JsonArray).map { tagElem ->
                    (tagElem as JsonArray).map { (it as JsonPrimitive).content }
                },
                content = (obj["content"] as JsonPrimitive).content,
                sig = (obj["sig"] as JsonPrimitive).content,
            )
        } catch (_: Exception) {
            null
        }
    }
}
