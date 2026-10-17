package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    /** ClimbUuid.normKey of the row's climb: its other rows are sent one by one. */
    val climbKey: String? = null,
    /** The last id Kilter refused for the climb, for the list the user can report. */
    val wireId: String? = null,
)

/** Rows of the last run Kilter could not take for reasons other than a refusal. */
@Serializable
data class KilterUploadLastOutcome(
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

/** Per-identity upload decisions that must survive a restart. Port of Android's `KilterUploadLedger`. */
interface KilterUploadLedger {
    suspend fun rejections(): List<KilterUploadRejection>
    suspend fun saveRejections(rejections: List<KilterUploadRejection>)

    /** normKey → the id Kilter took for a climb whose own id it refused, or which it keeps under two. */
    suspend fun learnedWireIds(): Map<String, String>
    suspend fun saveLearnedWireIds(learned: Map<String, String>)

    suspend fun lastOutcome(): KilterUploadLastOutcome
    suspend fun saveLastOutcome(outcome: KilterUploadLastOutcome)

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
    suspend fun importedUploadEnabled(): Boolean
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
    private var outcome = KilterUploadLastOutcome()
    private var doubtful = emptyList<KilterDoubtfulRequest>()
    var importedEnabled = false
    override suspend fun rejections() = held
    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        held = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
    }
    override suspend fun learnedWireIds() = learned
    override suspend fun saveLearnedWireIds(learned: Map<String, String>) {
        this.learned = learned.boundedLearned()
    }
    override suspend fun lastOutcome() = outcome
    override suspend fun saveLastOutcome(outcome: KilterUploadLastOutcome) {
        this.outcome = outcome.bounded()
    }
    override suspend fun doubtfulRequests() = doubtful
    override suspend fun saveDoubtfulRequests(requests: List<KilterDoubtfulRequest>) {
        doubtful = requests.takeLast(KilterUploadLedger.MAX_DOUBTFUL_REQUESTS)
    }
    override suspend fun importedUploadEnabled() = importedEnabled
    override suspend fun setImportedUploadEnabled(enabled: Boolean) { importedEnabled = enabled }
    override suspend fun clear() {
        held = emptyList()
        learned = emptyMap()
        outcome = KilterUploadLastOutcome()
        doubtful = emptyList()
        importedEnabled = false
    }
}

/**
 * Stored in the platform preferences (NSUserDefaults on iOS) under keys scoped
 * to the identity, as Android keeps them in the key-scoped preferences. Holds
 * no secret: log uuids, climb ids and counts only. Unreadable values read as
 * empty, which retries rows rather than holding them.
 */
class KeyValueKilterUploadLedger(
    private val store: KeyValueStore,
    private val identityPrefix: String,
) : KilterUploadLedger {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val rejectionList = ListSerializer(KilterUploadRejection.serializer())
    private val learnedMap = MapSerializer(String.serializer(), String.serializer())
    private val doubtList = ListSerializer(KilterDoubtfulRequest.serializer())

    private fun key(name: String) = "kilter_${identityPrefix}_upload_$name"

    override suspend fun rejections(): List<KilterUploadRejection> = read(REJECTIONS) {
        json.decodeFromString(rejectionList, it)
    }.orEmpty()

    override suspend fun saveRejections(rejections: List<KilterUploadRejection>) {
        val bounded = rejections.takeLast(KilterUploadLedger.MAX_REJECTIONS)
        write(REJECTIONS, if (bounded.isEmpty()) null else json.encodeToString(rejectionList, bounded))
    }

    override suspend fun learnedWireIds(): Map<String, String> = read(LEARNED) {
        json.decodeFromString(learnedMap, it)
    }.orEmpty()

    override suspend fun saveLearnedWireIds(learned: Map<String, String>) {
        val bounded = learned.boundedLearned()
        write(LEARNED, if (bounded.isEmpty()) null else json.encodeToString(learnedMap, bounded))
    }

    override suspend fun lastOutcome(): KilterUploadLastOutcome = read(OUTCOME) {
        json.decodeFromString(KilterUploadLastOutcome.serializer(), it)
    } ?: KilterUploadLastOutcome()

    override suspend fun saveLastOutcome(outcome: KilterUploadLastOutcome) {
        val bounded = outcome.bounded()
        write(
            OUTCOME,
            if (bounded.conflicts.isEmpty() && bounded.invalid.isEmpty()) null
            else json.encodeToString(KilterUploadLastOutcome.serializer(), bounded),
        )
    }

    override suspend fun doubtfulRequests(): List<KilterDoubtfulRequest> = read(IN_DOUBT) {
        json.decodeFromString(doubtList, it)
    }.orEmpty()

    override suspend fun saveDoubtfulRequests(requests: List<KilterDoubtfulRequest>) {
        val bounded = requests.takeLast(KilterUploadLedger.MAX_DOUBTFUL_REQUESTS)
        write(IN_DOUBT, if (bounded.isEmpty()) null else json.encodeToString(doubtList, bounded))
    }

    override suspend fun importedUploadEnabled(): Boolean = store.getString(key(IMPORTED)) == "true"

    override suspend fun setImportedUploadEnabled(enabled: Boolean) =
        store.putString(key(IMPORTED), if (enabled) "true" else null)

    override suspend fun clear() {
        for (name in listOf(REJECTIONS, LEARNED, OUTCOME, IN_DOUBT, IMPORTED)) store.putString(key(name), null)
    }

    private inline fun <T> read(name: String, decode: (String) -> T): T? = try {
        store.getString(key(name))?.let(decode)
    } catch (e: Exception) {
        null
    }

    private fun write(name: String, value: String?) = store.putString(key(name), value)

    private companion object {
        const val REJECTIONS = "rejections"
        const val LEARNED = "learned"
        const val OUTCOME = "last_outcome"
        const val IN_DOUBT = "in_doubt"
        const val IMPORTED = "imported_enabled"
    }
}

private fun Map<String, String>.boundedLearned(): Map<String, String> =
    entries.toList().takeLast(KilterUploadLedger.MAX_LEARNED).associate { it.key to it.value }

private fun KilterUploadLastOutcome.bounded() = KilterUploadLastOutcome(
    conflicts = conflicts.take(KilterUploadLedger.MAX_OUTCOME),
    invalid = invalid.take(KilterUploadLedger.MAX_OUTCOME),
)
