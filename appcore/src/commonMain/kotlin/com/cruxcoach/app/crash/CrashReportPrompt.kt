package com.cruxcoach.app.crash

import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a crash report left the device. */
enum class CrashSendOutcome {
    /** At least one relay stored the report: the maintainer can read it. */
    DELIVERED,

    /** Kept in the local message list, but no relay took it. */
    NOT_DELIVERED,

    /** Nothing could be built (no key) or the send threw. */
    FAILED,
}

/** Swift-facing snapshot of the start-up crash prompt. */
class CrashPromptUi(
    /** idle | ask | sending */
    val phaseCode: String,
    /** The pending report while [phaseCode] is ask or sending; "" otherwise. */
    val reportText: String,
    /** none | sent | sendFailed: a one-shot note after a send from the prompt. */
    val noticeCode: String,
)

/**
 * Android's start-up crash handling (`MainActivity`, the `CrashReportDialog`):
 * once per launch, a report left by the last crash is offered, sent or
 * dropped according to the "send crash reports" choice.
 *
 * - choice not made → ask (send / no thanks / view, "remember choice");
 * - on → send without asking; a report no relay took stays for the next launch;
 * - off → the report is deleted unread.
 *
 * Unlike Android, whose message queue retries later, iOS has no delivery queue
 * for these messages, so the file is the retry: it is deleted only once a relay
 * has the report or the user declined.
 */
class CrashReportPromptModel(
    private val files: CrashReportFiles,
    private val readOptIn: () -> Boolean?,
    private val writeOptIn: (Boolean) -> Unit,
    private val sender: suspend (String) -> CrashSendOutcome,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val state = MutableStateFlow(CrashPromptUi(PHASE_IDLE, "", NOTICE_NONE))
    private var started = false
    private var work: Job? = null

    val currentState: CrashPromptUi get() = state.value

    fun watch(onState: (CrashPromptUi) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Reads the pending report and acts on the stored choice. Runs once per model. */
    fun start() {
        if (started) return
        started = true
        work = scope.launch {
            val report = try {
                withContext(io) { files.readOrDiscard() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(io) { runCatching { files.delete() } }
                null
            } ?: return@launch
            try {
                when (readOptIn()) {
                    null -> state.value = CrashPromptUi(PHASE_ASK, report, NOTICE_NONE)
                    true -> {
                        // Automatic: no prompt, no notice. Kept on any failure.
                        if (sendSafely(report) == CrashSendOutcome.DELIVERED) {
                            withContext(io) { files.delete() }
                        }
                    }
                    false -> withContext(io) { files.delete() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Android: a failure while handling the report drops it rather
                // than offering it on every start.
                withContext(io) { runCatching { files.delete() } }
            }
        }
    }

    /** "Send". With [rememberChoice], a delivered report switches automatic sending on. */
    fun send(rememberChoice: Boolean) {
        val current = state.value
        if (current.phaseCode != PHASE_ASK) return
        val report = current.reportText
        state.value = CrashPromptUi(PHASE_SENDING, report, NOTICE_NONE)
        work = scope.launch {
            val outcome = sendSafely(report)
            if (outcome == CrashSendOutcome.DELIVERED) {
                withContext(io) { files.delete() }
                if (rememberChoice) writeOptIn(true)
                state.value = CrashPromptUi(PHASE_IDLE, "", NOTICE_SENT)
            } else {
                // Android's crash_send_failure: offered again on the next launch.
                state.value = CrashPromptUi(PHASE_IDLE, "", NOTICE_SEND_FAILED)
            }
        }
    }

    /** "No, thanks" (also a dismissed prompt). Deletes the report. */
    fun decline(rememberChoice: Boolean) {
        val current = state.value
        if (current.phaseCode != PHASE_ASK) return
        if (rememberChoice) writeOptIn(false)
        state.value = CrashPromptUi(PHASE_IDLE, "", NOTICE_NONE)
        work = scope.launch { withContext(io) { runCatching { files.delete() } } }
    }

    fun consumeNotice() = state.update { CrashPromptUi(it.phaseCode, it.reportText, NOTICE_NONE) }

    fun close() = scope.cancel()

    private suspend fun sendSafely(report: String): CrashSendOutcome = try {
        withContext(io) { sender(report) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        CrashSendOutcome.FAILED
    }

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_ASK = "ask"
        const val PHASE_SENDING = "sending"
        const val NOTICE_NONE = "none"
        const val NOTICE_SENT = "sent"
        const val NOTICE_SEND_FAILED = "sendFailed"
    }
}

/**
 * Android's "Send crash reports" switch (developer-contact section of the
 * settings). Off until chosen, as Android shows an unset choice.
 */
class CrashReportSettingsModel(
    private val readOptIn: () -> Boolean?,
    private val writeOptIn: (Boolean) -> Unit,
) {
    fun isAutoSendOn(): Boolean = readOptIn() ?: false

    fun setAutoSend(on: Boolean) = writeOptIn(on)
}

/**
 * Sends a report the way Android's `sendCrashReport` does: a NIP-17 message
 * of type crash-report to the maintainer, listed under the user's own crash
 * reports.
 */
class DevContactCrashSender(private val repository: com.cruxcoach.app.messages.DevContactRepository) {
    suspend fun send(report: String): CrashSendOutcome {
        val outcome = repository.send(com.cruxcoach.app.messages.DevMessageType.CRASH, report)
            ?: return CrashSendOutcome.FAILED
        return if (outcome.accepted > 0) CrashSendOutcome.DELIVERED else CrashSendOutcome.NOT_DELIVERED
    }
}
