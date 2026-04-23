package com.cruxcoach.android.nostr.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cruxcoach.android.data.SyncInterval
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic / one-shot runner for [BackupRepository.performFullBackup].
 *
 * Mirrors [com.cruxcoach.android.notification.BoardSyncWorker]'s layout so
 * both jobs have the same retry + constraint semantics. Backup uses
 * `NetworkType.CONNECTED` (not UNMETERED) because the typical payload is
 * 100-500 KB — small enough to run off mobile data without surprising the
 * user, and running only on WiFi would defeat "daily backup" for users
 * who rarely hit WiFi.
 */
@HiltWorker
class BackupSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val preferences: BackupPreferences,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)
        if (!preferences.isBackupFeatureEnabled()) {
            Log.i(TAG, "event=killswitch_off")
            return Result.failure()
        }
        if (!preferences.isBackupEnabled()) {
            return Result.success()   // nothing to do; schedule will cancel the periodic
        }
        return try {
            backupRepository.performFullBackup()
            Result.success()
        } catch (e: BackupException) {
            // Manual trigger (user tapped "Jetzt sichern") returns FAILURE
            // immediately so the UI observer sees a terminal state and can
            // surface the error. Periodic runs return RETRY so the
            // WorkManager backoff kicks in for transient failures.
            Log.w(TAG, "event=backup_done_${if (isManual) "failure" else "retry"} reason=${e.message}", e)
            if (isManual) Result.failure(errorData(e)) else Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "event=backup_done_${if (isManual) "failure" else "retry"}_exception", e)
            if (isManual) Result.failure(errorData(e)) else Result.retry()
        }
    }

    private fun errorData(e: Throwable) = workDataOf(
        KEY_ERROR to (e.message ?: e.javaClass.simpleName),
    )

    companion object {
        private const val TAG = "BackupSync"
        const val WORK_NAME_PERIODIC = "backup_sync_periodic"
        const val WORK_NAME_ONESHOT = "backup_sync_oneshot"
        private const val KEY_IS_MANUAL = "is_manual"
        const val KEY_ERROR = "error"

        /** Schedule / cancel the periodic backup worker based on current settings. */
        fun schedule(context: Context, enabled: Boolean, interval: SyncInterval) {
            val wm = WorkManager.getInstance(context)
            if (!enabled || interval == SyncInterval.MANUAL) {
                wm.cancelUniqueWork(WORK_NAME_PERIODIC)
                Log.d(TAG, "event=backup_cancelled reason=disabled")
                return
            }
            val repeatHours = when (interval) {
                SyncInterval.DAILY -> 24L
                SyncInterval.WEEKLY -> 168L
                SyncInterval.MANUAL -> return
            }
            val request = PeriodicWorkRequestBuilder<BackupSyncWorker>(
                repeatHours, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.d(TAG, "event=backup_scheduled interval=${interval.name}")
        }

        /** "Jetzt sichern" — fires a one-off worker. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                // is_manual signals doWork to return FAILURE (not RETRY) on
                // error — so the Settings observer sees a terminal state and
                // can surface "Backup fehlgeschlagen" immediately instead
                // of leaving the UI stuck in a retry loop.
                .setInputData(workDataOf(KEY_IS_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                // REPLACE (not KEEP) so a stale enqueued run from a prior
                // tap can't block the fresh one.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
