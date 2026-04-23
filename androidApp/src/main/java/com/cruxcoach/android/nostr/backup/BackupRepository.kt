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
    suspend fun performFullBackup() {
        val pubkey = nostrSigner.getPublicKeyHex()
        val deviceId = preferences.getOrCreateDeviceId()
        Log.d(
            TAG,
            "event=backup_start trigger=periodic signerMode=${nostrSigner.getStoredSignerMode().name}",
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
            throw BackupException("Blob upload failed on all $total servers")
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

    /** Restore-dialog detection. Returns `null` if no backup could be found. */
    suspend fun checkForBackup(timeoutMs: Long = 10_000L): BackupInfo? {
        Log.d(TAG, "event=restore_check_start signerMode=${nostrSigner.getStoredSignerMode().name}")
        val pubkey = nostrSigner.getPublicKeyHex()
        val mode = nostrSigner.getStoredSignerMode()

        val (pointerEvent, keyEvent) = when (mode) {
            SignerMode.LOCAL -> fetchByDTagHmac(pubkey, timeoutMs) ?: return missLog("no-pointer")
            SignerMode.AMBER -> fetchByQueryAllAmber(pubkey, timeoutMs) ?: return missLog("no-pointer")
        }

        // Decrypt pointer
        val pointer = try {
            decryptPointer(pointerEvent.content, pubkey)
        } catch (e: Exception) {
            Log.w(TAG, "event=restore_check_miss reason=no-pointer", e)
            return null
        }

        Log.d(
            TAG,
            "event=restore_check_hit sizeKb=${pointer.size / 1024} ageHours=${(System.currentTimeMillis() / 1000 - pointer.updatedAt) / 3600}",
        )
        return BackupInfo(pointer = pointer, pointerEvent = pointerEvent, keyEvent = keyEvent)
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

    private suspend fun getOrCreateDataKey(): ByteArray {
        preferences.getWrappedDataKey()?.let { wrapped ->
            return try {
                val hex = nip44DecryptToSelf(wrapped)
                hex.hexToByteArray().also {
                    require(it.size == 32) { "Unwrapped dataKey is not 32 bytes" }
                }
            } catch (e: Exception) {
                Log.w(TAG, "event=key_cache_miss reason=corrupt", e)
                throw e   // pipeline will retry; a future refactor may re-fetch from relays
            }
        }
        // First-time setup
        val fresh = BackupCrypto.generateKey()
        val freshHex = fresh.toHexString()
        val wrapped = nip44EncryptToSelf(freshHex)
        preferences.setWrappedDataKey(wrapped)
        publishKeyEvent(wrapped)
        return fresh
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

    private fun missLog(reason: String): BackupInfo? {
        Log.d(TAG, "event=restore_check_miss reason=$reason")
        return null
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
