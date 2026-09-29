package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Why a single log is held back instead of blocking the queue. */
@Serializable
enum class KilterRejectionKind {
    /** Kilter refused the row on its own (a request with only this row failed). */
    KILTER,
    /** Kilter already holds this log uuid with different content; bulk cannot update it. */
    CONFLICT,
    /** The row cannot be expressed for Kilter (unparseable timestamp). */
    INVALID,
}

/**
 * One held-back log. Applies only to the exact row version and app build it
 * was decided for: an edit or an app update retries the row by itself, and so
 * does a manual retry or the passing of [KilterUploadLedger.MAX_AGE_MS].
 */
@Serializable
data class KilterUploadRejection(
    val logUuid: String,
    val rowVersion: Long,
    val appVersionCode: Int,
    val kind: KilterRejectionKind,
    val httpStatus: Int? = null,
    val atMs: Long,
    /**
     * False while a row that failed alone has no proof against it yet (no
     * upload of the same run succeeded): it is still retried, and held back
     * only when it fails alone again at least an hour later.
     */
    val confirmed: Boolean = true,
)

/** Per-identity upload decisions that must survive a restart. */
interface KilterUploadLedger {
    suspend fun rejections(): List<KilterUploadRejection>
    suspend fun saveRejections(rejections: List<KilterUploadRejection>)

    /**
     * Entries imported from an Aurora export stay local unless the user opts
     * in: an export of the same account is usually already on Kilter, and the
     * import gave every entry a fresh uuid, so uploading it would duplicate
     * the logbook there.
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
    override val importedUploadEnabled = MutableStateFlow(false)
    override suspend fun rejections() = held
    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        held = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
    }
    override suspend fun setImportedUploadEnabled(enabled: Boolean) { importedUploadEnabled.value = enabled }
    override suspend fun clear() {
        held = emptyList()
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

    override val importedUploadEnabled: Flow<Boolean> = prefs.kilterUploadImportedEnabled
    override suspend fun setImportedUploadEnabled(enabled: Boolean) = prefs.setKilterUploadImportedEnabled(enabled)

    override suspend fun clear() {
        prefs.setKilterUploadRejections(null)
        prefs.setKilterUploadImportedEnabled(false)
    }
}
