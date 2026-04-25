package com.cruxcoach.android.updater

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Backstop check runner (§6.12). Flex interval so WorkManager picks the
 * exact moment inside the 24 h window — we never pin a clock time and
 * never fire while offline.
 *
 * Explicitly `setRequiresDeviceIdle(false)` + `setRequiresCharging(false)`
 * because idle-constrained jobs on OEM-killer devices (Xiaomi/Huawei/Oppo)
 * are often deferred for days. First-run expedited (`OneTimeWorkRequest`)
 * is scheduled separately so a fresh install knows within minutes
 * whether it's already stale.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdaterRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!repository.selfUpdateAllowed()) {
            Log.i(TAG, "event=periodic_check outcome=skipped reason=install_source_gated")
            return Result.success()
        }
        val outcome = repository.checkNow(UpdateChecker.Trigger.PERIODIC)
        val outcomeName = when (outcome) {
            is UpdateChecker.CheckOutcome.Error -> "error:${outcome.message}"
            is UpdateChecker.CheckOutcome.Skipped -> "skipped:${outcome.reason}"
            is UpdateChecker.CheckOutcome.Throttled -> "throttled"
            UpdateChecker.CheckOutcome.NotModified -> "not_modified"
            UpdateChecker.CheckOutcome.NoUpdate -> "no_update"
            is UpdateChecker.CheckOutcome.Update -> "update:${outcome.info.versionName}"
        }
        Log.i(TAG, "event=periodic_check outcome=$outcomeName")
        return if (outcome is UpdateChecker.CheckOutcome.Error) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        private const val PERIODIC_NAME = "cruxcoach.updater.periodic"
        private const val FIRST_RUN_NAME = "cruxcoach.updater.first_run"

        fun enqueue(context: Context) {
            val periodicConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()

            val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                repeatInterval = 24, repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 6, flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .setConstraints(periodicConstraints)
                .build()

            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
            Log.d(TAG, "event=periodic_scheduled flex=6h repeat=24h")

            // Expedited jobs only accept network + storage constraints.
            val firstRunConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val firstRun = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(firstRunConstraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            wm.enqueueUniqueWork(
                FIRST_RUN_NAME,
                ExistingWorkPolicy.KEEP,
                firstRun,
            )
            Log.d(TAG, "event=expedited_first_run_scheduled")
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(PERIODIC_NAME)
            wm.cancelUniqueWork(FIRST_RUN_NAME)
            Log.d(TAG, "event=periodic_cancelled")
        }
    }
}
