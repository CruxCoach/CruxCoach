package com.cruxcoach.android.data.kilter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

/** Only fixed categories and counts may cross the diagnostics boundary. */
@Serializable
data class KilterUploadStatus(
    val uploaded: Int = 0,
    val pending: Int = 0,
    val attempted: Int = 0,
    val reason: KilterUploadReason = KilterUploadReason.NONE,
    val httpStatus: Int? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val appVersion: String = "${com.cruxcoach.android.BuildConfig.VERSION_NAME} (${com.cruxcoach.android.BuildConfig.VERSION_CODE})",
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
    /**
     * Rows of a request whose answer was lost that Kilter refuses when sent
     * again: it most likely holds them already. Held, listed.
     */
    val probablyOnKilter: Int = 0,
    /** Number of the automatic follow-up run scheduled after this one, 0 for none ([KilterUploadRetryScheduler]). */
    val nextRetry: Int = 0,
) {
    val failed: Boolean get() = reason != KilterUploadReason.NONE && reason != KilterUploadReason.DISABLED
    val rejected: Int get() = rejectedByKilter + rejectedConflict + rejectedInvalid + probablyOnKilter
    /** Rows not on Kilter after this run that the user can list ([KilterSyncEngine.notUploadedEntries]). */
    val notUploaded: Int get() = rejected + unconfirmed
}

enum class KilterNotUploadedReason {
    /** Kilter refused the climb under every id it may have there, with proof. */
    NOT_ON_KILTER,
    /** Sent in a request whose answer was lost, refused since: Kilter most likely has it already. */
    PROBABLY_ON_KILTER,
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

@Serializable
enum class KilterUploadTrigger { MANUAL, ENABLED, NEW_LOG, APP_START, RETRY }

class KilterUploadException(val status: Int) : Exception("Kilter upload HTTP $status")

/** How a request that got no HTTP answer failed. */
object KilterUploadFailure {
    /** True when the request may have reached Kilter (anything but "never connected"). */
    fun noAnswer(error: Throwable?): Boolean = when (error) {
        null -> false
        is java.net.UnknownHostException, is java.net.ConnectException,
        is java.net.NoRouteToHostException, is java.net.PortUnreachableException,
        is javax.net.ssl.SSLHandshakeException -> false
        is java.net.SocketTimeoutException -> error.message?.contains("connect", ignoreCase = true) != true
        is java.io.IOException -> true
        else -> false
    }

    /** True when Kilter took too long to answer (read or call timeout), as opposed to no connection. */
    fun timedOut(error: Throwable?): Boolean =
        (error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException) && noAnswer(error)
}

/** A Kilter read answered with an HTTP error; the message keeps the former "HTTP <code>: <body>" shape. */
class KilterHttpException(val status: Int, body: String) : Exception("HTTP $status: $body")

/** Bounded, expiring, secret-free upload history; no raw exceptions or API bodies. */
@Singleton
class KilterUploadDiagnostics @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("kilter_upload_diagnostics", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val history = runCatching {
        json.decodeFromString<List<KilterUploadStatus>>(prefs.getString("history", "[]") ?: "[]")
    }.getOrDefault(emptyList()).filter { it.timestampMs >= System.currentTimeMillis() - MAX_AGE }.takeLast(20).toMutableList()
    private val _latest = MutableStateFlow(history.lastOrNull())
    val latest = _latest.asStateFlow()

    init { prefs.edit().putString("history", json.encodeToString(history)).apply() }

    @Synchronized fun record(status: KilterUploadStatus) {
        history.removeAll { it.timestampMs < System.currentTimeMillis() - MAX_AGE }
        history.add(status)
        while (history.size > 20) history.removeAt(0)
        prefs.edit().putString("history", json.encodeToString(history)).apply()
        _latest.value = status
    }

    @Synchronized fun snapshot(): String = history
        .filter { it.timestampMs >= System.currentTimeMillis() - MAX_AGE }
        .joinToString("\n") { diagnosticLine(it) }

    @Synchronized fun clear() {
        history.clear()
        prefs.edit().remove("history").apply()
        _latest.value = null
    }

    companion object {
        private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
        fun diagnosticLine(s: KilterUploadStatus): String =
            "app=${s.appVersion} operation=logs.bulk trigger=${s.trigger} time=${s.timestampMs} durationMs=${s.durationMs} " +
                "attempted=${s.attempted} uploaded=${s.uploaded} pending=${s.pending} reason=${s.reason} http=${s.httpStatus ?: "none"} " +
                "requests=${s.requests} rejectedKilter=${s.rejectedByKilter} rejectedConflict=${s.rejectedConflict} " +
                "rejectedInvalid=${s.rejectedInvalid} heldImported=${s.heldImported} alreadyOnKilter=${s.alreadyOnKilter} " +
                "retryLater=${s.unconfirmed} probablyOnKilter=${s.probablyOnKilter} autoRetry=${s.nextRetry}"
    }
}
