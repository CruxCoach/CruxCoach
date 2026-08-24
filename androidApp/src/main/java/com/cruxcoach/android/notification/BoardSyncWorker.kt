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

    // On API < 31 an expedited worker runs as a FOREGROUND SERVICE, and
    // WorkManager calls getForegroundInfo() up front to start it. Without this
    // override the CoroutineWorker default throws IllegalStateException("Not
    // implemented"), so the whole board sync fails before doWork ever runs
    // (observed on Android 9 / API 28 — board DB never downloads). API 31+ runs
    // expedited as a real job and doesn't need this, which is why it only bit
    // older devices.
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

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
        const val WORK_NAME_ONESHOT = "board_sync_oneshot"

        /**
         * Run a sync NOW under a foreground service so it survives the
         * app being backgrounded. Every eager trigger (manual "sync
         * now", onboarding, app-start stale sync) goes through this
         * instead of driving BoardSyncManager directly in its process
         * scope — which the OS freezes/kills once the app leaves the
         * foreground, aborting the multi-minute download + import.
         * KEEP so repeated taps / overlapping triggers don't stack a
         * second import on top of a running one.
         */
        /**
         * Runs a sync the user just asked for.
         *
         * REPLACE, not KEEP. KEEP drops the new request whenever one under the
         * same name is still ENQUEUED or RUNNING — and a one-shot that the
         * system deferred (Doze, battery optimisation, an exhausted expedited
         * quota) or that is sitting out its retry backoff stays ENQUEUED
         * indefinitely. Every later tap on "re-download" was then discarded
         * without a trace: no sync, no error, nothing on screen. A deliberate
         * tap has to win over whatever is stale in the queue.
         *
         * Safe against interrupting a live sync: the caller only gets here
         * while [BoardSyncManager] reports no sync in progress.
         */
        fun enqueueExpedited(context: Context, allowMetered: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<BoardSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

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
