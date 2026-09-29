package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One log Kilter refused on its own (a request with only this row failed).
 * It applies only to the exact content it was decided for
 * ([KilterUploadItem.fingerprint]) and app build: an edit or an app update
 * retries the row by itself, and so does the passing of
 * [KilterUploadLedger.MAX_AGE_MS]. Conflicts and unreadable timestamps are not
 * stored — they cost no request and are recomputed every run.
 */
@Serializable
data class KilterUploadRejection(
    val logUuid: String,
    val fingerprint: Int,
    val appVersionCode: Int,
    val httpStatus: Int? = null,
    val atMs: Long,
    /**
     * False while there is no proof against the row yet: Kilter answers its
     * own failures with HTTP 500 too, so a lone failure only counts once some
     * other upload was accepted after it. Until then the row is retried.
     */
    val confirmed: Boolean = true,
)

/** Per-identity upload decisions that must survive a restart. */
interface KilterUploadLedger {
    suspend fun rejections(): List<KilterUploadRejection>
    suspend fun saveRejections(rejections: List<KilterUploadRejection>)

    /** When Kilter last accepted an upload request — the proof a lone failure needs. */
    suspend fun lastAcceptedAtMs(): Long?
    suspend fun setLastAcceptedAtMs(atMs: Long)

    /**
     * Entries imported from an Aurora export stay local unless the user opts
     * in: an export of the same account is usually already on Kilter, and the
     * import gave every entry a fresh uuid, so uploading it would duplicate
     * the logbook there. The opt-in covers the entries present when it is
     * given; it is withdrawn once a run has worked through them, so a later
     * import asks again.
     */
    val importedUploadEnabled: Flow<Boolean>
    suspend fun setImportedUploadEnabled(enabled: Boolean)

    suspend fun clear()

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_REJECTIONS = 1000
    }
}

class InMemoryKilterUploadLedger : KilterUploadLedger {
    private var held = emptyList<KilterUploadRejection>()
    private var lastAccepted: Long? = null
    override val importedUploadEnabled = MutableStateFlow(false)
    override suspend fun rejections() = held
    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        held = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
    }
    override suspend fun lastAcceptedAtMs() = lastAccepted
    override suspend fun setLastAcceptedAtMs(atMs: Long) { lastAccepted = atMs }
    override suspend fun setImportedUploadEnabled(enabled: Boolean) { importedUploadEnabled.value = enabled }
    override suspend fun clear() {
        held = emptyList()
        lastAccepted = null
        importedUploadEnabled.value = false
    }
}

/** Stored in the identity's key-scoped preferences, next to the other Kilter switches. */
class PreferencesKilterUploadLedger(private val prefs: UserPreferences) : KilterUploadLedger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun rejections(): List<KilterUploadRejection> = runCatching {
        prefs.kilterUploadRejections.first()?.let { json.decodeFromString<List<KilterUploadRejection>>(it) }
    }.getOrNull().orEmpty()

    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        val bounded = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
        prefs.setKilterUploadRejections(if (bounded.isEmpty()) null else json.encodeToString(bounded))
    }

    override suspend fun lastAcceptedAtMs(): Long? = prefs.kilterUploadLastAccepted.first()
    override suspend fun setLastAcceptedAtMs(atMs: Long) = prefs.setKilterUploadLastAccepted(atMs)

    override val importedUploadEnabled: Flow<Boolean> = prefs.kilterUploadImportedEnabled
    override suspend fun setImportedUploadEnabled(enabled: Boolean) = prefs.setKilterUploadImportedEnabled(enabled)

    override suspend fun clear() {
        prefs.setKilterUploadRejections(null)
        prefs.setKilterUploadLastAccepted(null)
        prefs.setKilterUploadImportedEnabled(false)
    }
}
