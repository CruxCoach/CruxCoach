package com.cruxcoach.android.notification

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.cruxcoach.android.data.BoardDatabaseImporter
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.util.WorkerRunLog
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
        val workerStartedAt = WorkerRunLog.started()
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "event=sync_start attempt=$runAttemptCount")
        var errorClass: String? = null
        val result = try {
            setForeground(createForegroundInfo())

            // Delegate to the shared BoardSyncManager so the in-app banner
            // reflects sync progress regardless of source (manual or auto).
            syncManager.startBackgroundSync()

            // Wait until the sync finishes (success or error)
            val finalState = syncManager.state
                .filter { !it.isSyncing }
                .first()

            val durationMs = System.currentTimeMillis() - startedAt
            val imported = importer.isImported()
            when {
                finalState.errorMessage == null -> {
                    Log.i(TAG, "event=sync_done outcome=ok durationMs=$durationMs")
                    Result.success()
                }
                !imported -> {
                    Log.w(TAG, "event=sync_done outcome=retry reason=no_local_data durationMs=$durationMs")
                    Result.retry()
                }
                else -> {
                    // Deliberate policy: stale, usable data beats an endless retry loop.
                    Log.w(TAG, "event=sync_done outcome=stale_data_accepted durationMs=$durationMs")
                    Result.success()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorClass = e.javaClass.simpleName
            Log.e(
                TAG,
                "event=sync_done outcome=exception type=${e.javaClass.simpleName} " +
                    "durationMs=${System.currentTimeMillis() - startedAt}",
            )
            Result.retry()
        }
        return WorkerRunLog.finished(
            TAG,
            WORK_NAME,
            runAttemptCount,
            workerStartedAt,
            result,
            errorClass,
        )
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
        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<BoardSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.KEEP,
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
