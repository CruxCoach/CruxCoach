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
        return try {
            // DataStore reads are inside the try so an IOException (e.g.
            // disk full / corruption) becomes Result.retry() instead of
            // Result.failure() — failure is a permanent give-up that never
            // re-runs, retry honors the WorkManager backoff. Pre-fix the
            // gate reads happened outside the try and the worker silently
            // gave up on transient disk hiccups.
            if (!preferences.isBackupFeatureEnabled()) {
                Log.i(TAG, "event=killswitch_off")
                return Result.failure()
            }
            if (!preferences.isBackupEnabled()) {
                // Disabled is a permanent state per this scheduling cycle —
                // success here lets the existing schedule() cancel logic
                // remove the periodic run.
                return Result.success()
            }
            backupRepository.performFullBackup(trigger = "periodic")
            Result.success()
        } catch (e: BackupException) {
            Log.w(TAG, "event=backup_done_retry reason=${e.message}", e)
            Result.retry()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation must always propagate. Catching it as
            // "just another exception" and returning retry() breaks the
            // structured-concurrency contract — WorkManager calling stop()
            // would re-queue the work instead of dropping it. Rethrow so
            // the framework handles the cancel correctly.
            throw e
        } catch (e: Exception) {
            // Programming bugs (NPE, IllegalStateException from a typed
            // misuse, etc.) get the same retry treatment as transient I/O
            // failures so a bad release doesn't permanently kill the
            // periodic backup. The retry storm is bounded by WorkManager's
            // exponential backoff (30 min × 1.5^n, hard-capped at ~5 h).
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
