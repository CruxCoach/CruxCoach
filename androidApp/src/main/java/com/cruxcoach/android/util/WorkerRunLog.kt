package com.cruxcoach.android.util

import android.os.SystemClock
import android.util.Log
import androidx.work.ListenableWorker

internal fun formatWorkerResult(
    worker: String,
    outcome: String,
    attempt: Int,
    durationMs: Long,
    errorClass: String? = null,
): String = buildString {
    append("event=worker_result worker=").append(worker)
    append(" outcome=").append(outcome)
    append(" attempt=").append(attempt)
    append(" durationMs=").append(durationMs)
    if (errorClass != null) append(" errorClass=").append(errorClass)
}

internal fun workerOutcome(result: ListenableWorker.Result): String = when (result.javaClass) {
    ListenableWorker.Result.success().javaClass -> "success"
    ListenableWorker.Result.retry().javaClass -> "retry"
    ListenableWorker.Result.failure().javaClass -> "failure"
    else -> "unknown"
}

/** One privacy-bounded terminal line for every WorkManager invocation. */
object WorkerRunLog {
    fun started(): Long = SystemClock.elapsedRealtime()

    fun finished(
        tag: String,
        worker: String,
        attempt: Int,
        startedAt: Long,
        result: ListenableWorker.Result,
        errorClass: String? = null,
    ): ListenableWorker.Result {
        val outcome = workerOutcome(result)
        val line = formatWorkerResult(
            worker = worker,
            outcome = outcome,
            attempt = attempt,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            errorClass = errorClass,
        )
        if (outcome == "retry" || outcome == "failure") Log.w(tag, line) else Log.i(tag, line)
        return result
    }
}
