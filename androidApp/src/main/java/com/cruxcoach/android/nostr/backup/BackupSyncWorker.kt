package com.cruxcoach.android.nostr.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cruxcoach.android.data.SyncInterval
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic runner for [BackupRepository.performFullBackup]. Manual
 * "Jetzt sichern" runs are handled inline in the Settings ViewModel so
 * that Amber's approval dialog can attach to a foreground Activity —
 * WorkManager has no Activity context and would throw "No activity to
 * launch from." the moment Amber needs confirmation.
 *
 * Periodic runs still go through WorkManager and therefore only succeed
 * when the user has enabled "always approve" in Amber (or uses a local
 * signer); otherwise the periodic backup waits until the next manual
 * backup — consistent with Amber's background-signing limitation.
 */
@HiltWorker
class BackupSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val preferences: BackupPreferences,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!preferences.isBackupFeatureEnabled()) {
            Log.i(TAG, "event=killswitch_off")
            return Result.failure()
        }
        if (!preferences.isBackupEnabled()) {
            return Result.success()   // nothing to do; schedule will cancel the periodic
        }
        return try {
            backupRepository.performFullBackup(trigger = "periodic")
            Result.success()
        } catch (e: BackupException) {
            Log.w(TAG, "event=backup_done_retry reason=${e.message}", e)
            Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "event=backup_done_retry_exception", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupSync"
        const val WORK_NAME_PERIODIC = "backup_sync_periodic"

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
    }
}
