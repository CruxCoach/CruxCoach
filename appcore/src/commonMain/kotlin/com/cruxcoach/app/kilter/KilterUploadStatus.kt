package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class KilterUploadReason {
    NONE, DISABLED, AUTHENTICATION, WALL_CONTEXT,
    /** No connection to Kilter, or it broke off mid-request. */
    NETWORK,
    /** Kilter took longer to answer than the app waits; the request's rows may be on Kilter. */
    TIMEOUT,
    HTTP, CONFLICT, INTERNAL,
}

@Serializable
enum class KilterUploadTrigger { MANUAL, ENABLED, NEW_LOG, APP_START, RETRY }

/** Only fixed categories and counts may cross the diagnostics boundary. Port of Android's `KilterUploadStatus`. */
@Serializable
data class KilterUploadStatus(
    val uploaded: Int = 0,
    val pending: Int = 0,
    val attempted: Int = 0,
    val reason: KilterUploadReason = KilterUploadReason.NONE,
    val httpStatus: Int? = null,
    val timestampMs: Long = 0,
    val durationMs: Long = 0,
    val appVersion: String = "",
    val trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL,
    /** Rows Kilter refused one by one; held back so they no longer block the rest. */
    val rejectedByKilter: Int = 0,
    /** Rows whose uuid Kilter already holds with different content. */
    val rejectedConflict: Int = 0,
    /** Rows that cannot be expressed for Kilter (timestamp). */
    val rejectedInvalid: Int = 0,
    /** Aurora-imported rows that stay local until the user opts in. */
    val heldImported: Int = 0,
    /** Rows Kilter already had (same uuid or the same ascent), marked synced without a request. */
    val alreadyOnKilter: Int = 0,
    /** Bulk requests sent in this run. */
    val requests: Int = 0,
    /** Pending rows Kilter refused alone without proof yet (no upload accepted after them); retried, not held. */
    val unconfirmed: Int = 0,
    /** Copies Kilter held under a row's uuid (edited since, or a lost answer) that were deleted to send the row again. */
    val replaced: Int = 0,
    /** Number of the automatic follow-up run scheduled after this one, 0 for none ([KilterUploadRetryScheduler]). */
    val nextRetry: Int = 0,
) {
    val failed: Boolean get() = reason != KilterUploadReason.NONE && reason != KilterUploadReason.DISABLED
    val rejected: Int get() = rejectedByKilter + rejectedConflict + rejectedInvalid
    /** Rows not on Kilter after this run that the user can list ([KilterUploadEngine.notUploadedEntries]). */
    val notUploaded: Int get() = rejected + unconfirmed
}

enum class KilterNotUploadedReason {
    /** Kilter refused the climb under every id it may have there, with proof. */
    NOT_ON_KILTER,
    /** Kilter refused it alone, but nothing was accepted after; tried again later. */
    RETRY_LATER,
    /** Kilter holds this entry's uuid with different content. */
    CONFLICT,
    /** The entry's date cannot be expressed for Kilter. */
    INVALID_DATE,
}

/** One logbook entry the upload could not hand to Kilter, for the list the user can report. */
data class KilterNotUploadedEntry(
    val logUuid: String,
    val climbUuid: String,
    val climbName: String?,
    val angle: Int,
    val climbedAt: String,
    val isAscent: Boolean,
    val reason: KilterNotUploadedReason,
    /** The last id Kilter refused for the climb. */
    val wireId: String? = null,
    val httpStatus: Int? = null,
)

/**
 * How one bulk request ended, without the response body (it can echo private
 * log data). [httpStatus] 0 means no HTTP answer arrived.
 */
class KilterBulkOutcome(
    val httpStatus: Int,
    /** Why no answer arrived: NONE when one did. */
    val failure: KilterFailure,
    /** True when the request may have reached Kilter although no answer arrived (Android's `noAnswer`). */
    val mayHaveReachedKilter: Boolean,
) {
    val accepted: Boolean get() = httpStatus in 200..299
}

/**
 * Tells a request that never reached Kilter apart from one whose answer was
 * lost, from what the platform transport reports. Port of Android's
 * `KilterUploadFailure.noAnswer`, which reads Java exception types; iOS
 * reports an `NSURLErrorDomain:<code>` detail instead.
 */
object KilterUploadFailure {
    /** NSURLError codes raised before a request body can have left the device. */
    private val NEVER_SENT_CODES = setOf(
        -1009, // NotConnectedToInternet
        -1003, // CannotFindHost
        -1004, // CannotConnectToHost
        -1006, // DNSLookupFailed
        -1020, // DataNotAllowed
        -1018, // InternationalRoamingOff
        -1019, // CallIsActive
        -1200, -1201, -1202, -1203, -1204, -1205, -1206, // TLS handshake / certificate
    )

    /**
     * True when the request may have reached Kilter (anything but "never
     * connected"). The transport's [HttpFailure] category is not enough:
     * OFFLINE also covers NSURLErrorNetworkConnectionLost, which can strike
     * mid-request, and TIMEOUT does not say whether the connection was ever
     * made. Unknown failures count as reached, which only costs a later check.
     */
    fun mayHaveReachedKilter(detail: String): Boolean {
        val code = detail.takeIf { it.startsWith("NSURLErrorDomain:") }
            ?.substringAfter(':')?.toIntOrNull()
        return code == null || code !in NEVER_SENT_CODES
    }

    /** The stop reason Android derives from the exception type. */
    fun reasonFor(failure: HttpFailure): KilterFailure = when (failure) {
        HttpFailure.OFFLINE -> KilterFailure.OFFLINE
        HttpFailure.TIMEOUT -> KilterFailure.TIMEOUT
        HttpFailure.CANCELLED, HttpFailure.TOO_LARGE, HttpFailure.OTHER, HttpFailure.INSECURE_URL -> KilterFailure.SERVER_ERROR
    }
}

/**
 * Bounded, expiring, secret-free upload history; no raw errors or API bodies.
 * Port of Android's `KilterUploadDiagnostics`, kept in the platform
 * preferences under the identity's prefix.
 */
class KilterUploadDiagnostics(
    private val store: KeyValueStore,
    private val identityPrefix: String,
    private val nowMs: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(KilterUploadStatus.serializer())
    private val lock = Mutex()
    private val key get() = "kilter_${identityPrefix}_upload_diagnostics"
    private var history: MutableList<KilterUploadStatus> = load()
    private val _latest = MutableStateFlow(history.lastOrNull())
    val latest: StateFlow<KilterUploadStatus?> = _latest.asStateFlow()

    private fun load(): MutableList<KilterUploadStatus> = try {
        val stored = store.getString(key)?.let { json.decodeFromString(serializer, it) }.orEmpty()
        stored.filter { it.timestampMs >= nowMs() - MAX_AGE }.takeLast(MAX_ENTRIES).toMutableList()
    } catch (e: Exception) {
        ArrayList()
    }

    suspend fun record(status: KilterUploadStatus) = lock.withLock {
        history.removeAll { it.timestampMs < nowMs() - MAX_AGE }
        history.add(status)
        while (history.size > MAX_ENTRIES) history.removeAt(0)
        store.putString(key, json.encodeToString(serializer, history))
        _latest.value = status
    }

    suspend fun snapshot(): String = lock.withLock {
        history.filter { it.timestampMs >= nowMs() - MAX_AGE }.joinToString("\n") { diagnosticLine(it) }
    }

    suspend fun clear() = lock.withLock {
        history.clear()
        store.putString(key, null)
        _latest.value = null
    }

    companion object {
        private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 20

        fun diagnosticLine(s: KilterUploadStatus): String =
            "app=${s.appVersion} operation=logs.bulk trigger=${s.trigger} time=${s.timestampMs} durationMs=${s.durationMs} " +
                "attempted=${s.attempted} uploaded=${s.uploaded} pending=${s.pending} reason=${s.reason} http=${s.httpStatus ?: "none"} " +
                "requests=${s.requests} rejectedKilter=${s.rejectedByKilter} rejectedConflict=${s.rejectedConflict} " +
                "rejectedInvalid=${s.rejectedInvalid} heldImported=${s.heldImported} alreadyOnKilter=${s.alreadyOnKilter} " +
                "retryLater=${s.unconfirmed} replaced=${s.replaced} autoRetry=${s.nextRetry}"
    }
}
