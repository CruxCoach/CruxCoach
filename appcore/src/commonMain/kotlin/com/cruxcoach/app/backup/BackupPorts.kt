package com.cruxcoach.app.backup

import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.util.toHex

/** Why a backup or restore could not complete. Swift maps these to text. */
enum class BackupFailure {
    NO_IDENTITY,
    SIGNING_FAILED,
    NO_POINTER,
    TOMBSTONED,
    POINTER_DECRYPT_FAILED,
    POINTER_INVALID,
    STALE_POINTER,
    NO_USABLE_SERVERS,
    BLOB_UNREACHABLE,
    DATA_KEY_UNWRAP_FAILED,
    DOWNLOAD_FAILED,
    BLOB_DECRYPT_FAILED,
    DECOMPRESS_FAILED,
    PAYLOAD_INVALID,
    IDENTITY_MISMATCH,
    IMPORT_FAILED,
    EXPORT_FAILED,
    COMPRESS_FAILED,
    ENCRYPT_FAILED,
    UPLOAD_FAILED,
    BLOB_NOT_VISIBLE,
    KEY_EVENT_NOT_DURABLE,
    POINTER_EVENT_NOT_DURABLE,
    KEY_FETCH_AMBIGUOUS,
}

/** `(attempted, accepted)` relay accounting for one publish. */
class PublishStats(val attempted: Int, val accepted: Int)

/**
 * The relay side of the pipeline. Implementations must return only events
 * that already passed [com.cruxcoach.app.nostr.NostrEvents.verify]; the
 * repository re-checks author and kind regardless.
 */
interface BackupEventSource {
    suspend fun query(filter: String, timeoutMs: Long): List<NostrEvent>
    suspend fun publish(event: NostrEvent): PublishStats
}

/** Counts of what a restore actually wrote, for the Swift summary screen. */
class BackupImportSummary(
    val rowsImported: Int,
    val skippedDuplicates: Int,
    val ascentsInBackup: Int,
    val bidsInBackup: Int,
    val listsInBackup: Int,
)

/**
 * The database side of the pipeline. The real implementation delegates to
 * `CruxCoachBackup` in `:shared` — the payload format is already portable and
 * is not reimplemented here.
 */
interface BackupPayloadStore {
    /** Serialised backup of every category, or null when export failed. */
    fun exportJson(exportedAt: String, nostrPubkey: String): String?

    /** Writes the payload. [expectedNostrPubkey] binds it to the active identity. */
    fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary?
}

/** Signs with the local key, fetched fresh from the Keychain for each call. */
class LocalEventSigner(
    private val hashing: Hashing,
    private val secretKeyProvider: () -> ByteArray?,
) : EventSigner {
    override fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent? {
        val secret = secretKeyProvider() ?: return null
        try {
            return NostrKeys.sign(hashing, secret, createdAt, kind, tags, content)
        } finally {
            secret.fill(0)
        }
    }
}

/**
 * Non-secret backup bookkeeping, the iOS counterpart of Android's
 * `BackupPreferences`. The wrapped data key is NIP-44 ciphertext and the
 * d-tags are pseudorandom, so `NSUserDefaults` is the right home for them —
 * the Keychain holds only the private key itself.
 */
class BackupState(private val store: KeyValueStore, private val hashing: Hashing) {
    var wrappedDataKey: String?
        get() = store.getString(KEY_WRAPPED_DATA_KEY)
        set(value) = store.putString(KEY_WRAPPED_DATA_KEY, value)

    var previousBlobSha256: String?
        get() = store.getString(KEY_PREVIOUS_BLOB)
        set(value) = store.putString(KEY_PREVIOUS_BLOB, value)

    var lastBackupSync: Long?
        get() = store.getString(KEY_LAST_SYNC)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_SYNC, value?.toString())

    var lastKeyEventPublish: Long?
        get() = store.getString(KEY_LAST_KEY_PUBLISH)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_KEY_PUBLISH, value?.toString())

    var backupEnabled: Boolean
        get() = store.getString(KEY_ENABLED) == "true"
        set(value) = store.putString(KEY_ENABLED, if (value) "true" else "false")

    fun dTag(identifier: String): String? = store.getString(KEY_DTAG_PREFIX + identifier)

    fun setDTag(identifier: String, value: String) = store.putString(KEY_DTAG_PREFIX + identifier, value)

    /** Stable per-install id carried in the pointer; not a tracking identifier. */
    fun orCreateDeviceId(): String {
        store.getString(KEY_DEVICE_ID)?.let { return it }
        val random = hashing.randomBytes(16).toHex()
        val fresh = if (random.length == 32) {
            "${random.substring(0, 8)}-${random.substring(8, 12)}-${random.substring(12, 16)}-" +
                "${random.substring(16, 20)}-${random.substring(20)}"
        } else {
            "unknown"
        }
        store.putString(KEY_DEVICE_ID, fresh)
        return fresh
    }

    /** Dropped on key import / sign-out: everything below belongs to one identity. */
    fun clearIdentityState() {
        store.putString(KEY_WRAPPED_DATA_KEY, null)
        store.putString(KEY_PREVIOUS_BLOB, null)
        store.putString(KEY_LAST_SYNC, null)
        store.putString(KEY_LAST_KEY_PUBLISH, null)
        store.putString(KEY_DTAG_PREFIX + DTagDeriver.IDENTIFIER_BACKUP, null)
        store.putString(KEY_DTAG_PREFIX + DTagDeriver.IDENTIFIER_KEY, null)
        store.putString(KEY_ENABLED, "false")
    }

    private companion object {
        const val KEY_WRAPPED_DATA_KEY = "backup_wrapped_data_key"
        const val KEY_PREVIOUS_BLOB = "backup_previous_blob_sha256"
        const val KEY_LAST_SYNC = "backup_last_sync"
        const val KEY_LAST_KEY_PUBLISH = "backup_last_key_publish"
        const val KEY_DEVICE_ID = "backup_device_id"
        const val KEY_ENABLED = "backup_enabled"
        const val KEY_DTAG_PREFIX = "backup_dtag_"
    }
}
