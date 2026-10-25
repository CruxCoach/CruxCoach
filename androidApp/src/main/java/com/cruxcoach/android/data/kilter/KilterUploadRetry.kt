package com.cruxcoach.android.data.kilter

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Starts the next upload run on its own after a run stopped for a reason that
 * passes (offline, no answer in time, Kilter overloaded) or ran out of its
 * request budget with entries left — the user should not have to tap
 * "Sync now" until it goes through — and wakes a run for rows held back for a
 * pause (a refusal without proof).
 */
interface KilterUploadRetryScheduler {
    /**
     * [attempt] counts consecutive automatic runs, from 1. [replace]: the run
     * of the chain moves it on; any other run leaves a chain under way as it
     * is, so that an app start or a new entry does not restart its back-off.
     */
    fun schedule(attempt: Int, replace: Boolean = true)

    /** One run after [delayMs], for rows that may go again then. */
    fun wakeAfter(delayMs: Long)

    fun cancel()

    companion object {
        /** Spacing of consecutive automatic runs; after the last one it waits for the next trigger. */
        val DELAYS_MIN = longArrayOf(2, 5, 15, 30, 60, 120)

        val NONE = object : KilterUploadRetryScheduler {
            override fun schedule(attempt: Int, replace: Boolean) = Unit
            override fun wakeAfter(delayMs: Long) = Unit
            override fun cancel() = Unit
        }
    }
}

class WorkManagerKilterUploadRetryScheduler(private val context: Context) : KilterUploadRetryScheduler {
    override fun schedule(attempt: Int, replace: Boolean) {
        val delay = KilterUploadRetryScheduler.DELAYS_MIN.getOrNull(attempt - 1) ?: return
        enqueue(RETRY_WORK, attempt, TimeUnit.MINUTES.toMillis(delay), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
        Log.i(TAG, "upload retry $attempt in $delay min")
    }

    override fun wakeAfter(delayMs: Long) {
        enqueue(WAKE_WORK, 0, delayMs, ExistingWorkPolicy.REPLACE)
        Log.i(TAG, "upload wake in ${delayMs / 60_000} min")
    }

    override fun cancel() {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(RETRY_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(WAKE_WORK)
        }
    }

    private fun enqueue(name: String, attempt: Int, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<KilterUploadRetryWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KilterUploadRetryWorker.KEY_ATTEMPT to attempt))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }.onFailure { Log.w(TAG, "could not schedule upload run (${it.javaClass.simpleName})") }
    }

    private companion object {
        const val TAG = "KilterUploadRetry"
        const val RETRY_WORK = "kilter-upload-retry"
        const val WAKE_WORK = "kilter-upload-wake"
    }
}

@HiltWorker
class KilterUploadRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: KilterSyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // The run schedules its own follow-up (or cancels it), and outlives this
        // worker if WorkManager stops it: cut short, it would lose Kilter's answer.
        syncEngine.uploadPendingLogsDetached(KilterUploadTrigger.RETRY, retryAttempt = inputData.getInt(KEY_ATTEMPT, 0))
        return Result.success()
    }

    companion object {
        const val KEY_ATTEMPT = "attempt"
    }
}
