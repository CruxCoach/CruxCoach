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
 * "Sync now" until it goes through.
 */
interface KilterUploadRetryScheduler {
    /** [attempt] counts consecutive automatic runs, from 1. */
    fun schedule(attempt: Int)
    fun cancel()

    companion object {
        /** Spacing of consecutive automatic runs; after the last one it waits for the next trigger. */
        val DELAYS_MIN = longArrayOf(2, 5, 15, 30, 60, 120)

        val NONE = object : KilterUploadRetryScheduler {
            override fun schedule(attempt: Int) = Unit
            override fun cancel() = Unit
        }
    }
}

class WorkManagerKilterUploadRetryScheduler(private val context: Context) : KilterUploadRetryScheduler {
    override fun schedule(attempt: Int) {
        val delay = KilterUploadRetryScheduler.DELAYS_MIN.getOrNull(attempt - 1) ?: return
        val request = OneTimeWorkRequestBuilder<KilterUploadRetryWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KilterUploadRetryWorker.KEY_ATTEMPT to attempt))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Log.w(TAG, "could not schedule upload retry (${it.javaClass.simpleName})") }
        Log.i(TAG, "upload retry $attempt in $delay min")
    }

    override fun cancel() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    private companion object {
        const val TAG = "KilterUploadRetry"
        const val WORK_NAME = "kilter-upload-retry"
    }
}

@HiltWorker
class KilterUploadRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: KilterSyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // The run schedules its own follow-up (or cancels it); a failure here is not WorkManager's to retry.
        syncEngine.uploadPendingLogs(KilterUploadTrigger.RETRY, retryAttempt = inputData.getInt(KEY_ATTEMPT, 1))
        return Result.success()
    }

    companion object {
        const val KEY_ATTEMPT = "attempt"
    }
}
