package com.cruxcoach.app.ui

import com.cruxcoach.app.messages.CrashReportList
import com.cruxcoach.app.messages.DevContactRepository
import com.cruxcoach.app.messages.DevMessage
import com.cruxcoach.app.messages.DevMessageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A maintainer reply in a crash report's thread (Android shows it on the thread page). */
class CrashReplyRowUi(
    val id: String,
    val content: String,
    val createdAtEpochSeconds: Long,
    val read: Boolean,
)

/** One row of Android's `CrashReportListScreen`. */
class CrashReportRowUi(
    val id: String,
    /** True for an own report; false for a maintainer reply no listed report could take. */
    val isReport: Boolean,
    /** Android's collapsed card: the first line of the report. */
    val firstLine: String,
    /** The whole report, without the wire prefix. */
    val content: String,
    val createdAtEpochSeconds: Long,
    /**
     * delivered | queued (no relay took it yet; its sealed wraps are kept and go out again) |
     * notDelivered for a report; "" for a lone reply. Android's three statuses.
     */
    val statusCode: String,
    /** Android's retry button: a report no relay took whose sealed wraps are kept. */
    val canRetry: Boolean,
    /** Android's delete button: shown on every undelivered report. */
    val canDelete: Boolean,
    val replies: List<CrashReplyRowUi>,
    val unreadReplies: Int,
)

class CrashReportListState(
    val isLoading: Boolean,
    val reports: List<CrashReportRowUi>,
    /** The report a retry or delete is running for; "" when none. */
    val busyId: String,
    /** none | retryFailed: a one-shot note after a retry no relay took. */
    val noticeCode: String,
)

/**
 * Swift-facing crash report list (Android `CrashReportListScreen` + the parts of
 * `DevContactViewModel` it uses): the reports sent from the start-up prompt, with delivery
 * status, retry and delete for undelivered ones. Android opens a sent report's thread page; iOS
 * has none, so the replies of a report travel with its row and opening it reads them.
 */
class CrashReportListModel internal constructor(
    private val repository: DevContactRepository,
    main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(CrashReportListState(true, emptyList(), "", NOTICE_NONE))
    private var started = false

    val currentState: CrashReportListState get() = state.value

    fun watch(onState: (CrashReportListState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /**
     * Loads the list, then, as Android on open, sends every kept message once more: a relay
     * that now takes one turns it from not delivered to delivered.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            safely { repository.dropUndeliveredCrashCopies() }
            reload()
            val delivered = safely { repository.retryQueued() } ?: 0
            if (delivered > 0) reload()
        }
    }

    /** Android's retry button. */
    fun retry(id: String) = act(id) {
        val ok = safely { repository.retry(id) } ?: false
        if (!ok) state.update { withState(it, notice = NOTICE_RETRY_FAILED) }
    }

    /** Android's delete button. */
    fun delete(id: String) = act(id) { safely { repository.delete(id) } }

    /** Opening a report reads its thread (Android's thread page marks it read). */
    fun open(id: String) {
        val row = state.value.reports.firstOrNull { it.id == id } ?: return
        if (row.unreadReplies == 0) return
        scope.launch {
            safely { repository.markThreadRead(id) }
            reload()
        }
    }

    fun consumeNotice() = state.update { withState(it, notice = NOTICE_NONE) }

    fun close() = scope.cancel()

    private fun act(id: String, work: suspend () -> Unit) {
        if (state.value.busyId.isNotEmpty()) return
        state.update { withState(it, busyId = id) }
        scope.launch {
            try {
                work()
            } finally {
                state.update { withState(it, busyId = "") }
            }
            reload()
        }
    }

    private suspend fun reload() {
        val rows = safely {
            val messages = repository.messages()
            val listed = CrashReportList.of(messages)
            // A reply listed on its own has no thread to be read in: listing it reads it (Android).
            listed.filter { !it.outgoing && !it.read }.forEach { repository.markRead(it.id) }
            listed.map { row(it, messages) }
        }
        state.update {
            CrashReportListState(false, rows ?: it.reports, it.busyId, it.noticeCode)
        }
    }

    private fun row(message: DevMessage, all: List<DevMessage>): CrashReportRowUi {
        val text = CrashReportList.text(message)
        val replies = if (message.outgoing) {
            all.filter { !it.outgoing && it.replyToId == message.id }.sortedBy { it.createdAt }
        } else {
            emptyList()
        }
        return CrashReportRowUi(
            id = message.id,
            isReport = message.outgoing,
            firstLine = text.lineSequence().firstOrNull() ?: text.take(80),
            content = text,
            createdAtEpochSeconds = message.createdAt,
            statusCode = when {
                !message.outgoing -> ""
                message.relayAccepted -> "delivered"
                message.retryable -> "queued"
                else -> "notDelivered"
            },
            canRetry = message.outgoing && !message.relayAccepted && message.retryable,
            canDelete = message.outgoing && !message.relayAccepted,
            replies = replies.map { CrashReplyRowUi(it.id, it.content, it.createdAt, it.read) },
            unreadReplies = replies.count { !it.read },
        )
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        withContext(io) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun withState(
        s: CrashReportListState,
        busyId: String = s.busyId,
        notice: String = s.noticeCode,
    ) = CrashReportListState(s.isLoading, s.reports, busyId, notice)

    companion object {
        const val NOTICE_NONE = "none"
        const val NOTICE_RETRY_FAILED = "retryFailed"
    }
}

/**
 * Android's count badge on the settings row "Crash reports" (`DevContactSection`): unread
 * maintainer replies to crash reports. Read again each time the settings page appears; opening
 * a report's thread in the list reads its replies.
 */
class CrashReportBadge internal constructor(
    private val repository: DevContactRepository,
    main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    /** [onCount] runs on the main dispatcher; 0 when the database cannot be read. */
    fun load(onCount: (Int) -> Unit) {
        scope.launch {
            val count = try {
                withContext(io) { repository.unreadCount(DevMessageType.CRASH).toInt() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                0
            }
            onCount(count)
        }
    }
}
