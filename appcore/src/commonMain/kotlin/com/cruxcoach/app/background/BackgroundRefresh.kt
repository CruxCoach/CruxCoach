package com.cruxcoach.app.background

import com.cruxcoach.app.platform.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One leg of the background refresh, the counterpart of one Android WorkManager job
 * (`NotificationPollWorker`, `KilterUploadRetryWorker`, `CommunityPublishRetryWorker`,
 * `BackupSyncWorker`, `BoardSyncWorker`).
 */
interface BackgroundStep {
    /** Stable code; also names the step's persisted run times. */
    val code: String

    /**
     * Minimum spacing of successful runs in ms: Android's periodic interval. 0 when the step
     * keeps its own schedule (the Kilter follow-up chain, the catalogue cadence).
     */
    fun intervalMs(): Long

    /** After a failed run the next try waits this long: Android's initial back-off. */
    fun retryAfterMs(): Long

    /** The step's own gate (a user switch, a sign-in, a due follow-up). False: skipped, nothing recorded. */
    suspend fun enabled(trigger: String): Boolean

    /** Does the work. Throws only for cancellation; anything else counts as a failed run. */
    suspend fun run(): BackgroundStepResult
}

class BackgroundStepResult(val ok: Boolean, val notified: Int) {
    companion object {
        val OK = BackgroundStepResult(true, 0)
        val FAILED = BackgroundStepResult(false, 0)
    }
}

/**
 * What one refresh did. [ran], [failed] and [skipped] hold step codes. [cancelled] is true when
 * the run was stopped (the system ended the background time) before every step was through.
 */
class BackgroundRefreshSummary(
    val trigger: String,
    val ran: List<String>,
    val failed: List<String>,
    val skipped: List<String>,
    val notified: Int,
    val cancelled: Boolean,
) {
    /** For `BGTask.setTaskCompleted(success:)`: true only when nothing failed and nothing was cut off. */
    val success: Boolean get() = !cancelled && failed.isEmpty()
}

/**
 * Best-effort periodic work on iOS. Android schedules each job with WorkManager; iOS gives an
 * app only short, system-chosen background slots (`BGAppRefreshTask`, about 30 s, no
 * guarantee) and its own returns to the foreground. Both call [run]; every step keeps
 * Android's spacing through persisted run times, so a refresh that comes early does nothing
 * for the steps that are not due.
 *
 * Steps run one after the other in Android's order. [cancel] stops the run before its next
 * request and answers the waiting callers at once, so the background task can be completed
 * inside its expiration handler.
 *
 * [scope] delivers the callbacks (the main thread on iOS); the steps run on [io]. [run] and
 * [cancel] must be called from [scope]'s thread.
 */
class BackgroundRefresh(
    private val steps: List<BackgroundStep>,
    private val keyValues: KeyValueStore,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val runLock = Mutex()
    private var job: Job? = null
    private var waiting = ArrayList<(BackgroundRefreshSummary) -> Unit>()
    private var runningTrigger = ""

    /** True while a run is under way. */
    val isRunning: Boolean get() = job != null

    /**
     * Starts a refresh, or joins the one under way. [onDone] is called exactly once, on [scope]'s
     * thread, also when the run is cancelled. [trigger] is [TRIGGER_BACKGROUND] or [TRIGGER_FOREGROUND].
     */
    fun run(trigger: String, onDone: (BackgroundRefreshSummary) -> Unit) {
        waiting.add(onDone)
        if (job != null) return
        runningTrigger = trigger
        // Lazy, so [job] is set before the body can run on an immediate dispatcher.
        val started = scope.launch(start = CoroutineStart.LAZY) {
            val summary = try {
                withContext(io) { runSteps(trigger) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A step never throws, so this is a programming error; answer instead of hanging the task.
                BackgroundRefreshSummary(trigger, emptyList(), listOf(FAILED_INTERNAL), emptyList(), 0, false)
            }
            // A run that [cancel] already answered must not answer the callers of a newer one.
            if (job === currentCoroutineContext()[Job]) {
                job = null
                deliver(summary)
            }
        }
        job = started
        started.start()
    }

    /** Stops the run before its next request; the waiting callers get a cancelled summary now. */
    fun cancel() {
        val running = job ?: return
        job = null
        running.cancel()
        deliver(BackgroundRefreshSummary(runningTrigger, emptyList(), emptyList(), emptyList(), 0, true))
    }

    private fun deliver(summary: BackgroundRefreshSummary) {
        val callers = waiting
        waiting = ArrayList()
        for (callback in callers) callback(summary)
    }

    /** The steps in order; suspend core of [run], for tests and for a host that has its own coroutine. */
    suspend fun runSteps(trigger: String): BackgroundRefreshSummary = runLock.withLock {
        val ran = ArrayList<String>()
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        var notified = 0
        for (step in steps) {
            currentCoroutineContext().ensureActive()
            val gate = try {
                step.enabled(trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!gate || !isDue(step, clock())) {
                skipped.add(step.code)
                continue
            }
            keyValues.putString(tryKey(step.code), clock().toString())
            val result = try {
                step.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackgroundStepResult.FAILED
            }
            notified += result.notified
            if (result.ok) {
                keyValues.putString(okKey(step.code), clock().toString())
                ran.add(step.code)
            } else {
                failed.add(step.code)
            }
        }
        BackgroundRefreshSummary(trigger, ran, failed, skipped, notified, false)
    }

    /**
     * Due when the last good run is an interval back and, after a failed try, the back-off has
     * passed. A clock set back counts as due rather than blocking the step until it catches up.
     */
    internal fun isDue(step: BackgroundStep, now: Long): Boolean {
        val lastOk = keyValues.getString(okKey(step.code))?.toLongOrNull()
        val lastTry = keyValues.getString(tryKey(step.code))?.toLongOrNull()
        val interval = step.intervalMs()
        val intervalPassed = lastOk == null || now < lastOk || now - lastOk >= interval
        val lastFailed = lastTry != null && (lastOk == null || lastTry > lastOk)
        val backoffPassed = lastTry == null || !lastFailed || now < lastTry || now - lastTry >= step.retryAfterMs()
        return intervalPassed && backoffPassed
    }

    companion object {
        const val TRIGGER_BACKGROUND = "background"
        const val TRIGGER_FOREGROUND = "foreground"

        /** Reported in [BackgroundRefreshSummary.failed] when the run itself broke. */
        const val FAILED_INTERNAL = "internal"

        /**
         * The earliest next background slot to ask for: Android's shortest period, the 15-minute
         * notification poll. iOS treats it as a lower bound and picks the time itself.
         */
        const val NEXT_RUN_SECONDS = 15 * 60

        internal fun okKey(code: String) = "bg_refresh_ok_$code"
        internal fun tryKey(code: String) = "bg_refresh_try_$code"
    }
}
