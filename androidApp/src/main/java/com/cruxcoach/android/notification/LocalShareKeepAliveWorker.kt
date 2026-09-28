package com.cruxcoach.android.notification

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cruxcoach.android.data.BoardSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Carries a nearby-share receive the way [BoardSyncWorker] carries a download.
 *
 * The share runs in [BoardSyncManager]'s own scope, where its Wi-Fi request, progress and
 * trust checks live. A coroutine alone keeps neither the CPU awake nor the process alive,
 * though: with the screen switched off, a Nokia 6.1 suspended 30 times during one share and
 * slept 12 of its 16 minutes, the import frozen each time, and a backgrounded app is frozen
 * outright. This worker does no work of its own. It holds the same foreground dataSync
 * service a download runs under, and with it WorkManager's wake lock, until the share gives
 * the sync slot back.
 */
@HiltWorker
class LocalShareKeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: BoardSyncManager,
    private val notificationService: AppNotificationService,
) : CoroutineWorker(appContext, workerParams) {

    // Expedited work runs as a foreground service below API 31; see BoardSyncWorker.
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    override suspend fun doWork(): Result {
        // Without the foreground service an expedited job still holds its wake lock, so a
        // refused promotion (a background start on API 31+) only loses the backgrounded case.
        runCatching { setForeground(createForegroundInfo()) }
            .onFailure { Log.w(TAG, "Share keep-alive runs without a foreground service", it) }
        syncManager.state.filter { !it.isSyncing }.first()
        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = notificationService.buildSyncProgressNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                AppNotificationService.Id.SYNC_PROGRESS,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(AppNotificationService.Id.SYNC_PROGRESS, notification)
        }
    }

    companion object {
        private const val TAG = "LocalShareKeepAlive"
        private const val WORK_NAME = "local_share_keep_alive"

        /**
         * REPLACE: the previous share's worker may still be winding down after its slot was
         * released, and KEEP would then drop the request for the share that just started.
         */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<LocalShareKeepAliveWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
