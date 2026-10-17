package com.cruxcoach.app.kilter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts the next upload run on its own after a run stopped for a reason that
 * passes (offline, no answer in time, Kilter overloaded) or ran out of its
 * request budget with entries left — the user should not have to tap
 * "Sync now" until it goes through — and wakes a run for rows held back for a
 * pause (a refusal without proof). Port of Android's `KilterUploadRetryScheduler`.
 */
interface KilterUploadRetryScheduler {
    /**
     * [attempt] counts consecutive automatic runs, from 1. [replace]: the run
     * of the chain moves it on; any other run leaves a chain under way as it
     * is, so that an app start or a new entry does not restart its back-off.
     */
    fun schedule(attempt: Int, replace: Boolean)

    /** One run after [delayMs], for rows that may go again then. */
    fun wakeAfter(delayMs: Long)

    fun cancel()

    companion object {
        /** Spacing of consecutive automatic runs in minutes; after the last one it waits for the next trigger. */
        val DELAYS_MIN = longArrayOf(2, 5, 15, 30, 60, 120)

        val NONE = object : KilterUploadRetryScheduler {
            override fun schedule(attempt: Int, replace: Boolean) = Unit
            override fun wakeAfter(delayMs: Long) = Unit
            override fun cancel() = Unit
        }
    }
}

/**
 * The iOS scheduler: timers in the app's own process. Android hands the same
 * schedule to WorkManager, which also runs it while the app is closed; iOS has
 * no equivalent that runs network work on a fixed delay, so a follow-up that
 * falls due while the app is suspended runs at the next upload instead.
 *
 * A timer that fires starts its run in a coroutine of its own, so the run's
 * scheduling of the next timer cannot cancel the run itself.
 */
class CoroutineKilterUploadRetryScheduler(
    private val scope: CoroutineScope,
    /** Starts an automatic run; [attempt] is 0 for a wake-up run. */
    private val run: suspend (attempt: Int) -> Unit,
) : KilterUploadRetryScheduler {
    private var retry: Job? = null
    private var retryAttempt = 0
    private var wake: Job? = null

    override fun schedule(attempt: Int, replace: Boolean) {
        val delayMin = KilterUploadRetryScheduler.DELAYS_MIN.getOrNull(attempt - 1) ?: return
        if (!replace && retry?.isActive == true) return
        retry?.cancel()
        retryAttempt = attempt
        retry = scope.launch {
            delay(delayMin * 60_000L)
            fire(attempt)
        }
    }

    override fun wakeAfter(delayMs: Long) {
        wake?.cancel()
        wake = scope.launch {
            delay(delayMs)
            fire(0)
        }
    }

    override fun cancel() {
        retry?.cancel()
        retry = null
        wake?.cancel()
        wake = null
    }

    /** The attempt the pending follow-up carries, 0 when none is scheduled. */
    val scheduledAttempt: Int get() = if (retry?.isActive == true) retryAttempt else 0

    private fun fire(attempt: Int) {
        scope.launch { run(attempt) }
    }
}
