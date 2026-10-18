package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.Nip44
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.UrlValidation
import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.Gzip
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.Nip44Cipher
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pointer + the two events it was reconstructed from. */
class BackupInfo(val pointer: BackupPointer, val pointerEvent: NostrEvent, val keyEvent: NostrEvent)

sealed class CheckOutcome {
    class Found(val info: BackupInfo) : CheckOutcome()

    /** No matching events: either no backup for this account, or silent relays. */
    data object NotFound : CheckOutcome()

    /** Events exist but do not decrypt — typically the wrong key was imported. */
    data object DecryptFailed : CheckOutcome()

    /** Events exist, but no Blossom server still holds the ciphertext. */
    class BlobUnreachable(val info: BackupInfo) : CheckOutcome()
    class Failed(val failure: BackupFailure) : CheckOutcome()
}

sealed class RestoreOutcome {
    class Restored(val summary: BackupImportSummary) : RestoreOutcome()
    class Failed(val failure: BackupFailure) : RestoreOutcome()
}

/**
 * Android's `DeleteRemoteNote`: why a remote deletion fell short. [code] is the note
 * (dtagDerivationFailed | noWriteRelays | noRelayAccepted | partialRelayAccept | relayPublishThrew |
 * blossomAuthFailed | blossomFullyRejected | blossomPartial | tombstonePublishFailed | unexpectedError);
 * the counts and [type] fill its text where it has any.
 */
class DeleteRemoteNote(
    val code: String,
    val accepted: Int = 0,
    val attempted: Int = 0,
    /** Tombstone notes: relays that took the pointer's and the key's marker. */
    val backupAccepted: Int = 0,
    val keyAccepted: Int = 0,
    val type: String = "",
) {
    companion object {
        const val DTAG_DERIVATION_FAILED = "dtagDerivationFailed"
        const val NO_WRITE_RELAYS = "noWriteRelays"
        const val NO_RELAY_ACCEPTED = "noRelayAccepted"
        const val PARTIAL_RELAY_ACCEPT = "partialRelayAccept"
        const val RELAY_PUBLISH_THREW = "relayPublishThrew"
        const val BLOSSOM_AUTH_FAILED = "blossomAuthFailed"
        const val BLOSSOM_FULLY_REJECTED = "blossomFullyRejected"
        const val BLOSSOM_PARTIAL = "blossomPartial"
        const val TOMBSTONE_PUBLISH_FAILED = "tombstonePublishFailed"
        const val UNEXPECTED_ERROR = "unexpectedError"
    }
}

/**
 * Android's `DeleteRemoteOutcome`: what each server confirmed. Only confirmations count; a relay
 * or server that did not answer "accepted" is never reported as having deleted anything.
 */
class DeleteRemoteOutcome(
    val relaysAttempted: Int,
    val relaysAccepted: Int,
    val blossomAttempted: Int,
    val blossomAccepted: Int,
    val notes: List<DeleteRemoteNote>,
) {
    /** Android's "Remote deletion requests completed." versus "partially deleted". */
    val complete: Boolean
        get() = notes.isEmpty() && relaysAttempted > 0 && relaysAccepted == relaysAttempted &&
            (blossomAttempted == 0 || blossomAccepted == blossomAttempted)
}

sealed class BackupOutcome {
    class Completed(val sha256: String, val bytes: Int) : BackupOutcome()
    class Failed(val failure: BackupFailure) : BackupOutcome()
}

/**
 * The encrypted-backup pipeline, ported from Android's `BackupRepository`
 * (FEAT-002 §7.3 create, §8 restore) for the LOCAL signer.
 *
 * Wire compatibility is the point: same kind-30078 events, same HMAC d-tags,
 * same NIP-44 envelopes, same AES-256-GCM blob layout and the same gzip
 * payload, so a backup written by the Android app restores here and back.
 *
 * Every public entry point returns a result; nothing throws towards Swift.
 */
class BackupRepository(
    private val hashing: Hashing,
    /** NIP-44 v2; a library on iOS, the Kotlin reference in tests. */
    private val nip44: Nip44Cipher,
    private val aead: AeadCipher,
    private val gzip: Gzip,
    private val clock: WallClock,
    private val events: BackupEventSource,
    private val blossom: BlossomClient,
    private val payloads: BackupPayloadStore,
    private val state: BackupState,
    private val signer: EventSigner,
    /** Fresh copy of the private key from the Keychain; the repository zeroes it. */
    private val secretKeyProvider: () -> ByteArray?,
) {
    /** Serialises the mutating entry points: a manual backup and a scheduled one must not interleave. */
    private val pipelineMutex = Mutex()

    // ---------------------------------------------------------------- restore

    /** Looks for a restorable backup for the active identity. Read-only. */
    suspend fun checkForBackup(timeoutMs: Long = DEFAULT_TIMEOUT_MS): CheckOutcome {
        val secret = secretKeyProvider() ?: return CheckOutcome.Failed(BackupFailure.NO_IDENTITY)
        try {
            val pubkey = NostrKeys.publicKeyHex(secret) ?: return CheckOutcome.Failed(BackupFailure.NO_IDENTITY)
            val backupDTag = dTag(secret, DTagDeriver.IDENTIFIER_BACKUP)
                ?: return CheckOutcome.Failed(BackupFailure.NO_IDENTITY)
            val keyDTag = dTag(secret, DTagDeriver.IDENTIFIER_KEY)
                ?: return CheckOutcome.Failed(BackupFailure.NO_IDENTITY)

            val filter = buildJsonObject {
                put("kinds", buildJsonArray { add(JsonPrimitive(KIND_PARAMETERIZED_REPLACEABLE)) })
                put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
                put(
                    "#d",
                    buildJsonArray {
                        add(JsonPrimitive(backupDTag))
                        add(JsonPrimitive(keyDTag))
                    },
                )
            }
            val candidates = verified(events.query(filter.toString(), timeoutMs), pubkey)
            // Newest per d-tag, never "whatever arrived first": an out-of-date
            // or hostile relay could otherwise replay an older signed pointer.
            val pointerEvent = newestByDTag(candidates, backupDTag) ?: return CheckOutcome.NotFound
            val keyEvent = newestByDTag(candidates, keyDTag) ?: return CheckOutcome.NotFound

            // A previous opt-out shadows both events with a plaintext sentinel.
            if (pointerEvent.content == TOMBSTONE_CONTENT || keyEvent.content == TOMBSTONE_CONTENT) {
                return CheckOutcome.NotFound
            }

            val pointerJson = nip44.decrypt(secret, pubkey, pointerEvent.content)
                ?: return CheckOutcome.DecryptFailed
            val pointer = BackupPointer.decode(pointerJson)
                ?: return CheckOutcome.Failed(BackupFailure.POINTER_INVALID)
            if (!pointer.validate(clock.epochSeconds())) {
                return CheckOutcome.Failed(BackupFailure.POINTER_INVALID)
            }

            // Anti-rollback: relays that conspire to withhold the newest event
            // are caught with local state rather than with what they serve.
            val lastLocal = state.lastBackupSync
            if (lastLocal != null && pointerEvent.createdAt < lastLocal - STALE_POINTER_TOLERANCE_SEC) {
                return CheckOutcome.Failed(BackupFailure.STALE_POINTER)
            }

            val info = BackupInfo(pointer, pointerEvent, keyEvent)
            val servers = usableServers(pointer)
            if (servers.isEmpty()) return CheckOutcome.Failed(BackupFailure.NO_USABLE_SERVERS)
            // The pointer survives an opt-out even when the ciphertext is long
            // gone; probe before promising the user a restore.
            if (!blossom.verifyExists(pointer.sha256, servers)) return CheckOutcome.BlobUnreachable(info)
            return CheckOutcome.Found(info)
        } finally {
            secret.fill(0)
        }
    }

    /** Downloads, verifies, decrypts and imports the backup described by [info]. */
    suspend fun restore(info: BackupInfo): RestoreOutcome = pipelineMutex.withLock {
        val secret = secretKeyProvider() ?: return RestoreOutcome.Failed(BackupFailure.NO_IDENTITY)
        try {
            val pubkey = NostrKeys.publicKeyHex(secret) ?: return RestoreOutcome.Failed(BackupFailure.NO_IDENTITY)
            val pointer = info.pointer
            if (!pointer.validate(clock.epochSeconds())) {
                return RestoreOutcome.Failed(BackupFailure.POINTER_INVALID)
            }
            val dataKeyHex = nip44.decrypt(secret, NostrKeys.publicKeyHex(secret) ?: "", info.keyEvent.content)
                ?: return RestoreOutcome.Failed(BackupFailure.DATA_KEY_UNWRAP_FAILED)
            val dataKey = dataKeyHex.hexToBytesOrNull()?.takeIf { it.size == 32 }
                ?: return RestoreOutcome.Failed(BackupFailure.DATA_KEY_UNWRAP_FAILED)
            try {
                val servers = usableServers(pointer)
                if (servers.isEmpty()) return RestoreOutcome.Failed(BackupFailure.NO_USABLE_SERVERS)
                // Cap = the signed size plus framing slack, so a hostile server
                // cannot stream gigabytes before the hash check runs.
                val ciphertext = blossom.download(pointer.sha256, servers, pointer.size + DOWNLOAD_SLACK_BYTES)
                    ?: return RestoreOutcome.Failed(BackupFailure.DOWNLOAD_FAILED)
                val compressed = BackupCrypto.decrypt(aead, ciphertext, dataKey)
                    ?: return RestoreOutcome.Failed(BackupFailure.BLOB_DECRYPT_FAILED)
                val plaintext = gzip.decompress(compressed, MAX_PLAINTEXT_BYTES)
                    ?: return RestoreOutcome.Failed(BackupFailure.DECOMPRESS_FAILED)
                val json = plaintext.decodeToString()

                // Identity binding: NIP-44 already proves we held the right key,
                // but the envelope check catches a re-imported old nsec or an
                // identity flip mid-flow before a single row is written.
                val preview = try {
                    com.cruxcoach.data.CruxCoachBackup.preview(json)
                } catch (e: Exception) {
                    return RestoreOutcome.Failed(BackupFailure.PAYLOAD_INVALID)
                }
                if (preview.nostrPubkey != null && preview.nostrPubkey != pubkey) {
                    return RestoreOutcome.Failed(BackupFailure.IDENTITY_MISMATCH)
                }
                val summary = payloads.importJson(json, pubkey)
                    ?: return RestoreOutcome.Failed(BackupFailure.IMPORT_FAILED)

                // Keep the data key for future backups from this device.
                nip44.encrypt(secret, NostrKeys.publicKeyHex(secret) ?: "", dataKeyHex)?.let { state.wrappedDataKey = it }
                return RestoreOutcome.Restored(summary)
            } finally {
                dataKey.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    // ----------------------------------------------------------------- create

    /**
     * One full backup cycle, in Android's order: upload, HEAD-verify,
     * republish the key event, and only then the pointer — followed by the
     * cleanup of the previous blob. Any earlier pointer publish would advertise
     * a blob that may not be durable.
     */
    suspend fun performFullBackup(exportedAt: String): BackupOutcome = pipelineMutex.withLock {
        val secret = secretKeyProvider() ?: return BackupOutcome.Failed(BackupFailure.NO_IDENTITY)
        try {
            val pubkey = NostrKeys.publicKeyHex(secret) ?: return BackupOutcome.Failed(BackupFailure.NO_IDENTITY)
            val deviceId = state.orCreateDeviceId()

            val dataKey = when (val resolved = resolveDataKey(secret, pubkey)) {
                is DataKey.Resolved -> resolved.key
                is DataKey.Failed -> return BackupOutcome.Failed(resolved.failure)
            }
            try {
                val json = payloads.exportJson(exportedAt, pubkey)
                    ?: return BackupOutcome.Failed(BackupFailure.EXPORT_FAILED)
                val compressed = gzip.compress(json.encodeToByteArray())
                    ?: return BackupOutcome.Failed(BackupFailure.COMPRESS_FAILED)
                val ciphertext = BackupCrypto.encrypt(aead, hashing, compressed, dataKey)
                    ?: return BackupOutcome.Failed(BackupFailure.ENCRYPT_FAILED)
                val sha256 = hashing.sha256(ciphertext).toHex()

                val servers = discoverServers(pubkey)
                val results = blossom.upload(ciphertext, servers)
                if (results.none { it.accepted }) return BackupOutcome.Failed(BackupFailure.UPLOAD_FAILED)
                if (!blossom.verifyExists(sha256, servers)) {
                    return BackupOutcome.Failed(BackupFailure.BLOB_NOT_VISIBLE)
                }

                // The wrapped key must be durable BEFORE a new pointer advertises
                // a blob that only that key can open.
                val wrapped = state.wrappedDataKey ?: return BackupOutcome.Failed(BackupFailure.KEY_FETCH_AMBIGUOUS)
                if (!publishKeyEvent(secret, wrapped)) {
                    return BackupOutcome.Failed(BackupFailure.KEY_EVENT_NOT_DURABLE)
                }
                state.lastKeyEventPublish = clock.epochSeconds()

                val previousSha = state.previousBlobSha256
                val pointer = BackupPointer(
                    sha256 = sha256,
                    size = ciphertext.size.toLong(),
                    servers = servers,
                    previousSha256 = previousSha,
                    updatedAt = clock.epochSeconds(),
                    deviceId = deviceId,
                    categories = com.cruxcoach.data.CruxCoachBackup.Category.entries.map { it.name },
                )
                if (!publishPointerEvent(secret, pointer)) {
                    return BackupOutcome.Failed(BackupFailure.POINTER_EVENT_NOT_DURABLE)
                }
                state.previousBlobSha256 = sha256

                // Best effort: an orphaned old blob costs storage, not integrity.
                if (previousSha != null) blossom.delete(previousSha, servers)
                state.lastBackupSync = clock.epochSeconds()
                return BackupOutcome.Completed(sha256, ciphertext.size)
            } finally {
                dataKey.fill(0)
            }
        } finally {
            secret.fill(0)
        }
    }

    private sealed class DataKey {
        class Resolved(val key: ByteArray) : DataKey()
        class Failed(val failure: BackupFailure) : DataKey()
    }

    /**
     * Local cache, then the relays' key event, and only for a genuinely fresh
     * identity a new key. Regenerating while prior history exists would replace
     * the relays' key event and make the existing blob undecryptable forever.
     */
    private suspend fun resolveDataKey(secret: ByteArray, pubkey: String): DataKey {
        state.wrappedDataKey?.let { wrapped ->
            unwrapDataKey(secret, wrapped)?.let { return DataKey.Resolved(it) }
            state.wrappedDataKey = null
        }
        fetchWrappedKeyFromRelays(secret, pubkey)?.let { wrapped ->
            unwrapDataKey(secret, wrapped)?.let {
                state.wrappedDataKey = wrapped
                return DataKey.Resolved(it)
            }
        }
        val hasPriorHistory = state.lastKeyEventPublish != null ||
            state.previousBlobSha256 != null ||
            state.lastBackupSync != null
        if (hasPriorHistory) return DataKey.Failed(BackupFailure.KEY_FETCH_AMBIGUOUS)

        val fresh = BackupCrypto.generateKey(hashing) ?: return DataKey.Failed(BackupFailure.ENCRYPT_FAILED)
        val wrapped = nip44.encrypt(secret, NostrKeys.publicKeyHex(secret) ?: "", fresh.toHex())
            ?: return DataKey.Failed(BackupFailure.ENCRYPT_FAILED)
        // Publish first: persisting locally before the relays accept would mask
        // the failure and upload blobs no other device could ever decrypt.
        if (!publishKeyEvent(secret, wrapped)) return DataKey.Failed(BackupFailure.KEY_EVENT_NOT_DURABLE)
        state.previousBlobSha256 = null
        state.wrappedDataKey = wrapped
        state.lastKeyEventPublish = clock.epochSeconds()
        return DataKey.Resolved(fresh)
    }

    private fun unwrapDataKey(secret: ByteArray, wrapped: String): ByteArray? =
        nip44.decrypt(secret, NostrKeys.publicKeyHex(secret) ?: "", wrapped)?.hexToBytesOrNull()?.takeIf { it.size == 32 }

    private suspend fun fetchWrappedKeyFromRelays(secret: ByteArray, pubkey: String): String? {
        val keyDTag = dTag(secret, DTagDeriver.IDENTIFIER_KEY) ?: return null
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(KIND_PARAMETERIZED_REPLACEABLE)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("#d", buildJsonArray { add(JsonPrimitive(keyDTag)) })
        }
        val event = newestByDTag(verified(events.query(filter.toString(), DEFAULT_TIMEOUT_MS), pubkey), keyDTag)
        return event?.content?.takeIf { it != TOMBSTONE_CONTENT }
    }

    // ----------------------------------------------------------------- delete

    /**
     * Port of Android's `deleteRemoteBackups` (FEAT-002 §20.2, the active opt-out): a NIP-09 deletion
     * for the pointer and key events, DELETE of the current blob on every Blossom server, a
     * tombstone that replaces both replaceable events, then the local identity state goes and
     * backups are switched off — also when the remote side fell short, as the user asked to opt
     * out. Every count is a confirmation the server gave; [DeleteRemoteOutcome.notes] say what fell
     * short. Never throws.
     */
    suspend fun deleteRemoteBackups(): DeleteRemoteOutcome {
        // Android cancels the periodic worker first; here the scheduled step checks this flag, and a
        // run already under way finishes under the pipeline lock before the deletion starts.
        state.backupEnabled = false
        return pipelineMutex.withLock {
            val secret = secretKeyProvider()
            try {
                deleteRemote(secret)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DeleteRemoteOutcome(0, 0, 0, 0, listOf(DeleteRemoteNote(DeleteRemoteNote.UNEXPECTED_ERROR, type = e::class.simpleName ?: "Exception")))
            } finally {
                secret?.fill(0)
                // Clear local state regardless: the user explicitly asked for the opt-out.
                state.clearIdentityState()
            }
        }
    }

    private suspend fun deleteRemote(secret: ByteArray?): DeleteRemoteOutcome {
        val notes = mutableListOf<DeleteRemoteNote>()
        val pubkey = secret?.let { NostrKeys.publicKeyHex(it) }
        val backupDTag = if (secret != null && pubkey != null) dTag(secret, DTagDeriver.IDENTIFIER_BACKUP) else null
        val keyDTag = if (secret != null && pubkey != null) dTag(secret, DTagDeriver.IDENTIFIER_KEY) else null
        if (backupDTag == null || keyDTag == null) notes += DeleteRemoteNote(DeleteRemoteNote.DTAG_DERIVATION_FAILED)

        var relaysAttempted = 0
        var relaysAccepted = 0
        if (pubkey != null && backupDTag != null && keyDTag != null) {
            val tags = listOf(backupDTag, keyDTag).map { listOf("a", "$KIND_PARAMETERIZED_REPLACEABLE:$pubkey:$it") } +
                listOf(listOf("k", KIND_PARAMETERIZED_REPLACEABLE.toString()))
            val stats = publishOrNull(KIND_DELETION, tags, DELETION_CONTENT)
            if (stats == null) {
                notes += DeleteRemoteNote(DeleteRemoteNote.RELAY_PUBLISH_THREW)
            } else {
                relaysAttempted = stats.attempted
                relaysAccepted = stats.accepted
                when {
                    stats.attempted == 0 -> notes += DeleteRemoteNote(DeleteRemoteNote.NO_WRITE_RELAYS)
                    stats.accepted == 0 -> notes += DeleteRemoteNote(DeleteRemoteNote.NO_RELAY_ACCEPTED)
                    stats.accepted < stats.attempted ->
                        notes += DeleteRemoteNote(DeleteRemoteNote.PARTIAL_RELAY_ACCEPT, accepted = stats.accepted, attempted = stats.attempted)
                }
            }
        }

        var blossomAttempted = 0
        var blossomAccepted = 0
        state.previousBlobSha256?.let { sha ->
            val servers = if (pubkey != null) discoverServers(pubkey) else BlossomClient.DEFAULT_SERVERS
            val outcome = blossom.delete(sha, servers)
            blossomAttempted = outcome.attempted
            blossomAccepted = outcome.succeeded
            when {
                outcome.authFailed -> notes += DeleteRemoteNote(DeleteRemoteNote.BLOSSOM_AUTH_FAILED)
                outcome.fullyFailed() -> notes += DeleteRemoteNote(DeleteRemoteNote.BLOSSOM_FULLY_REJECTED)
                outcome.partiallySucceeded() ->
                    notes += DeleteRemoteNote(DeleteRemoteNote.BLOSSOM_PARTIAL, accepted = outcome.succeeded, attempted = outcome.attempted)
            }
        }

        // NIP-09 is best effort and dropped by major relays for replaceable kinds; replacing both
        // events with the sentinel is what every relay must honour, so a restore finds nothing.
        if (backupDTag != null && keyDTag != null) {
            val backup = publishOrNull(KIND_PARAMETERIZED_REPLACEABLE, listOf(listOf("d", backupDTag)), TOMBSTONE_CONTENT)
            val key = publishOrNull(KIND_PARAMETERIZED_REPLACEABLE, listOf(listOf("d", keyDTag)), TOMBSTONE_CONTENT)
            val backupAccepted = backup?.accepted ?: 0
            val keyAccepted = key?.accepted ?: 0
            if (backupAccepted == 0 || keyAccepted == 0) {
                notes += DeleteRemoteNote(
                    DeleteRemoteNote.TOMBSTONE_PUBLISH_FAILED,
                    attempted = maxOf(backup?.attempted ?: 0, key?.attempted ?: 0),
                    backupAccepted = backupAccepted,
                    keyAccepted = keyAccepted,
                )
            }
        }

        return DeleteRemoteOutcome(relaysAttempted, relaysAccepted, blossomAttempted, blossomAccepted, notes.toList())
    }

    /** Signs and publishes; null when it could not be signed or the publish threw (Android's "threw"). */
    private suspend fun publishOrNull(kind: Int, tags: List<List<String>>, content: String): PublishStats? = try {
        signer.sign(createdAt = clock.epochSeconds(), kind = kind, tags = tags, content = content)
            ?.let { events.publish(it) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun publishKeyEvent(secret: ByteArray, wrappedDataKey: String): Boolean {
        val dTag = dTag(secret, DTagDeriver.IDENTIFIER_KEY) ?: return false
        val event = signer.sign(
            createdAt = clock.epochSeconds(),
            kind = KIND_PARAMETERIZED_REPLACEABLE,
            tags = listOf(listOf("d", dTag)),
            content = wrappedDataKey,
        ) ?: return false
        return events.publish(event).accepted > 0
    }

    private suspend fun publishPointerEvent(secret: ByteArray, pointer: BackupPointer): Boolean {
        val dTag = dTag(secret, DTagDeriver.IDENTIFIER_BACKUP) ?: return false
        val ciphertext = nip44.encrypt(secret, NostrKeys.publicKeyHex(secret) ?: "", pointer.encode()) ?: return false
        val event = signer.sign(
            createdAt = clock.epochSeconds(),
            kind = KIND_PARAMETERIZED_REPLACEABLE,
            tags = listOf(listOf("d", dTag)),
            content = ciphertext,
        ) ?: return false
        return events.publish(event).accepted > 0
    }

    /** User's kind-10063 list first (upload priority), then the defaults. */
    private suspend fun discoverServers(pubkey: String): List<String> {
        val filter = buildJsonObject {
            put("kinds", buildJsonArray { add(JsonPrimitive(BlossomClient.KIND_BLOSSOM_SERVER_LIST)) })
            put("authors", buildJsonArray { add(JsonPrimitive(pubkey)) })
            put("limit", JsonPrimitive(1))
        }
        val userServers = try {
            verified(events.query(filter.toString(), SERVER_DISCOVERY_TIMEOUT_MS), pubkey)
                .filter { it.kind == BlossomClient.KIND_BLOSSOM_SERVER_LIST }
                .maxByOrNull { it.createdAt }
                ?.let { BlossomClient.serversFromEvent(it) }
                .orEmpty()
        } catch (e: Exception) {
            // A discovery hiccup must never abort a durable write.
            emptyList()
        }
        val merged = LinkedHashSet<String>()
        merged.addAll(userServers)
        merged.addAll(BlossomClient.DEFAULT_SERVERS)
        return merged.toList()
    }

    /**
     * Even a correctly signed pointer may list URLs written while the key was
     * compromised; they are never dialed regardless of their signature.
     */
    private fun usableServers(pointer: BackupPointer): List<String> =
        pointer.servers.filter { UrlValidation.isValidBlossom(it) }

    private fun dTag(secret: ByteArray, identifier: String): String? {
        state.dTag(identifier)?.let { return it }
        val derived = DTagDeriver.derive(hashing, secret, identifier) ?: return null
        state.setDTag(identifier, derived)
        return derived
    }

    /** Relay filters are a bandwidth hint only: author, kind and signature are re-checked. */
    private fun verified(events: List<NostrEvent>, pubkey: String): List<NostrEvent> =
        events.filter { it.pubkey == pubkey && NostrEvents.verify(hashing, it) }

    private fun newestByDTag(events: List<NostrEvent>, dTag: String): NostrEvent? =
        events.asSequence()
            .filter { it.kind == KIND_PARAMETERIZED_REPLACEABLE && it.firstTagValue("d") == dTag }
            .maxByOrNull { it.createdAt }

    companion object {
        const val KIND_PARAMETERIZED_REPLACEABLE = 30078
        const val KIND_DELETION = 5

        /** Sentinel that replaces a live pointer after an opt-out (NIP-01 replaceable semantics). */
        const val TOMBSTONE_CONTENT = "CRUXCOACH_BACKUP_TOMBSTONE_V1"

        /** The NIP-09 deletion's content, as Android writes it. */
        const val DELETION_CONTENT = "backup opt-out"

        /** LAST_BACKUP_SYNC is written after the publish, so the pointer is routinely seconds older. */
        const val STALE_POINTER_TOLERANCE_SEC = 5L * 60L
        const val DOWNLOAD_SLACK_BYTES = 1024L
        const val MAX_PLAINTEXT_BYTES = 64L * 1024 * 1024
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val SERVER_DISCOVERY_TIMEOUT_MS = 5_000L
    }
}
