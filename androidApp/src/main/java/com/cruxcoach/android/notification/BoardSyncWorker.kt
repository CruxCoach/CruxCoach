package com.cruxcoach.android.notification

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.cruxcoach.android.data.BoardDatabaseImporter
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.SyncInterval
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import java.util.concurrent.TimeUnit

@HiltWorker
class BoardSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: BoardSyncManager,
    private val importer: BoardDatabaseImporter,
    private val notificationService: AppNotificationService
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            setForeground(createForegroundInfo())

            // Delegate to the shared BoardSyncManager so the in-app banner
            // reflects sync progress regardless of source (manual or auto).
            syncManager.startBackgroundSync()

            // Wait until the sync finishes (success or error)
            val finalState = syncManager.state
                .filter { !it.isSyncing }
                .first()

            if (finalState.errorMessage != null && !importer.isImported()) {
                // Only retry if we have no data at all — stale data is better than retrying endlessly
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = notificationService.buildSyncProgressNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                AppNotificationService.Id.SYNC_PROGRESS,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(AppNotificationService.Id.SYNC_PROGRESS, notification)
        }
    }

    companion object {
        private const val TAG = "BoardSyncWorker"
        const val WORK_NAME = "board_sync_periodic"

        fun schedule(context: Context, interval: SyncInterval) {
            if (interval == SyncInterval.MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val repeatInterval = when (interval) {
                SyncInterval.DAILY -> 24L
                SyncInterval.WEEKLY -> 168L
                SyncInterval.MANUAL -> return
            }

            val request = PeriodicWorkRequestBuilder<BoardSyncWorker>(
                repeatInterval, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
