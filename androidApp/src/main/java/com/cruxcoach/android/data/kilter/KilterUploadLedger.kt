package com.cruxcoach.android.data.kilter

import com.cruxcoach.android.data.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One log Kilter refused on its own (a request with only this row failed, for
 * every id the row could name). It applies only to the exact content it was
 * decided for ([KilterUploadItem.fingerprint]) and app build: an edit or an
 * app update retries the row by itself, and so does the passing of
 * [KilterUploadLedger.MAX_AGE_MS]. Conflicts and unreadable timestamps are not
 * held — they cost no request and are recomputed every run.
 */
@Serializable
data class KilterUploadRejection(
    val logUuid: String,
    val fingerprint: Int,
    val appVersionCode: Int,
    val httpStatus: Int? = null,
    val atMs: Long,
    /**
     * False while there is no proof against the row: Kilter answers its own
     * failures with HTTP 500 too, so a lone failure only counts once Kilter
     * accepted another request after it in the same run. Until then the row is
     * retried — first thing in the next run that sends anything, otherwise not
     * before [retryAtMs].
     */
    val confirmed: Boolean = true,
    val retryAtMs: Long = 0,
    /** Lone failures on schedule without other proof; spaces the retries out (never holds the row on its own). */
    val unproven: Int = 0,
    /** [ClimbUuid.normKey] of the row's climb: its other rows are sent one by one. */
    val climbKey: String? = null,
    /** The last id Kilter refused for the climb, for the list the user can report. */
    val wireId: String? = null,
)

/** Rows of the last run Kilter could not take for reasons other than a refusal. */
@Serializable
data class KilterUploadOutcome(
    val conflicts: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * An upload request that got no answer (timeout, lost connection, the app
 * stopped mid-request). Kilter writes a bulk request as a whole or not at all,
 * so one of its rows in Kilter's logbook proves all of them are there, also
 * attempts Kilter does not list. Saved before the request goes out and
 * dropped once Kilter answered it.
 */
@Serializable
data class KilterDoubtfulRequest(
    val atMs: Long,
    val rows: List<KilterDoubtfulRow>,
)

@Serializable
data class KilterDoubtfulRow(
    val logUuid: String,
    /** The local row's version when it was sent: an edit since means Kilter holds an older copy. */
    val rowVersion: Long,
    val ascent: Boolean,
)

/** Per-identity upload decisions that must survive a restart. */
interface KilterUploadLedger {
    suspend fun rejections(): List<KilterUploadRejection>
    suspend fun saveRejections(rejections: List<KilterUploadRejection>)

    /** normKey → the id Kilter took for a climb whose own id it refused, or which it keeps under two. */
    suspend fun learnedWireIds(): Map<String, String>
    suspend fun saveLearnedWireIds(learned: Map<String, String>)

    suspend fun lastOutcome(): KilterUploadOutcome
    suspend fun saveLastOutcome(outcome: KilterUploadOutcome)

    /** Requests that got no answer: Kilter may have written them ([KilterDoubtfulRequest]). */
    suspend fun doubtfulRequests(): List<KilterDoubtfulRequest>
    suspend fun saveDoubtfulRequests(requests: List<KilterDoubtfulRequest>)

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
        const val MAX_LEARNED = 2000
        const val MAX_OUTCOME = 500
        const val MAX_DOUBTFUL_REQUESTS = 40
    }
}

class InMemoryKilterUploadLedger : KilterUploadLedger {
    private var held = emptyList<KilterUploadRejection>()
    private var learned = emptyMap<String, String>()
    private var outcome = KilterUploadOutcome()
    private var doubtful = emptyList<KilterDoubtfulRequest>()
    override val importedUploadEnabled = MutableStateFlow(false)
    override suspend fun rejections() = held
    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        held = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
    }
    override suspend fun learnedWireIds() = learned
    override suspend fun saveLearnedWireIds(learned: Map<String, String>) {
        this.learned = learned.entries.toList().takeLast(KilterUploadLedger.MAX_LEARNED).associate { it.toPair() }
    }
    override suspend fun lastOutcome() = outcome
    override suspend fun saveLastOutcome(outcome: KilterUploadOutcome) {
        this.outcome = outcome.bounded()
    }
    override suspend fun doubtfulRequests() = doubtful
    override suspend fun saveDoubtfulRequests(requests: List<KilterDoubtfulRequest>) {
        doubtful = requests.takeLast(KilterUploadLedger.MAX_DOUBTFUL_REQUESTS)
    }
    override suspend fun setImportedUploadEnabled(enabled: Boolean) { importedUploadEnabled.value = enabled }
    override suspend fun clear() {
        held = emptyList()
        learned = emptyMap()
        outcome = KilterUploadOutcome()
        doubtful = emptyList()
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

    override suspend fun learnedWireIds(): Map<String, String> = runCatching {
        prefs.kilterUploadLearned.first()?.let { json.decodeFromString<Map<String, String>>(it) }
    }.getOrNull().orEmpty()

    override suspend fun saveLearnedWireIds(learned: Map<String, String>) {
        val bounded = learned.entries.toList().takeLast(KilterUploadLedger.MAX_LEARNED).associate { it.toPair() }
        prefs.setKilterUploadLearned(if (bounded.isEmpty()) null else json.encodeToString(bounded))
    }

    override suspend fun lastOutcome(): KilterUploadOutcome = runCatching {
        prefs.kilterUploadLastOutcome.first()?.let { json.decodeFromString<KilterUploadOutcome>(it) }
    }.getOrNull() ?: KilterUploadOutcome()

    override suspend fun saveLastOutcome(outcome: KilterUploadOutcome) {
        val bounded = outcome.bounded()
        prefs.setKilterUploadLastOutcome(
            if (bounded.conflicts.isEmpty() && bounded.invalid.isEmpty()) null else json.encodeToString(bounded),
        )
    }

    override suspend fun doubtfulRequests(): List<KilterDoubtfulRequest> = runCatching {
        prefs.kilterUploadInDoubt.first()?.let { json.decodeFromString<List<KilterDoubtfulRequest>>(it) }
    }.getOrNull().orEmpty()

    override suspend fun saveDoubtfulRequests(requests: List<KilterDoubtfulRequest>) {
        val bounded = requests.takeLast(KilterUploadLedger.MAX_DOUBTFUL_REQUESTS)
        prefs.setKilterUploadInDoubt(if (bounded.isEmpty()) null else json.encodeToString(bounded))
    }

    override val importedUploadEnabled: Flow<Boolean> = prefs.kilterUploadImportedEnabled
    override suspend fun setImportedUploadEnabled(enabled: Boolean) = prefs.setKilterUploadImportedEnabled(enabled)

    override suspend fun clear() {
        prefs.setKilterUploadRejections(null)
        prefs.setKilterUploadLearned(null)
        prefs.setKilterUploadLastOutcome(null)
        prefs.setKilterUploadInDoubt(null)
        prefs.setKilterUploadImportedEnabled(false)
    }
}

private fun KilterUploadOutcome.bounded() = KilterUploadOutcome(
    conflicts = conflicts.take(KilterUploadLedger.MAX_OUTCOME),
    invalid = invalid.take(KilterUploadLedger.MAX_OUTCOME),
)
