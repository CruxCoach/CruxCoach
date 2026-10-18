package com.cruxcoach.app.background

/**
 * Swift-facing facade of [BackgroundRefresh]: no suspend function, no Flow, nothing thrown.
 * One instance per core, made by `AppCore.makeBackgroundRefresh`. Call from the main thread.
 */
class BackgroundRefreshModel internal constructor(private val refresh: BackgroundRefresh) {
    /** Seconds to ask for as the next `BGAppRefreshTaskRequest.earliestBeginDate`. */
    val nextRunSeconds: Int = BackgroundRefresh.NEXT_RUN_SECONDS

    val isRunning: Boolean get() = refresh.isRunning

    /** The refresh of a `BGAppRefreshTask`. [onDone] is called once, on the main thread. */
    fun runInBackground(onDone: (BackgroundRefreshSummary) -> Unit) =
        refresh.run(BackgroundRefresh.TRIGGER_BACKGROUND, onDone)

    /** The refresh of a return to the foreground; also starts the due catalogue updates. */
    fun runInForeground(onDone: (BackgroundRefreshSummary) -> Unit) =
        refresh.run(BackgroundRefresh.TRIGGER_FOREGROUND, onDone)

    /** The task's time is up: stop before the next request; the waiting callers are answered now. */
    fun cancel() = refresh.cancel()
}
