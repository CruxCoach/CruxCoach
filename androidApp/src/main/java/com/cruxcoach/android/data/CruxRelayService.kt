package com.cruxcoach.android.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * `connectedDevice` foreground service for CruxRelay board sharing (FEAT-044 §12).
 *
 * Keeps BLE advertising alive while the phone fronts the real board (Android
 * 12+ throttles background advertising) and shows the mandatory persistent
 * "sharing" notification with the live client count and a one-tap stop that
 * stops only the relay transport via [CruxRelayManager.disable].
 *
 * Lifecycle is slaved to [CruxRelayManager]: the manager starts this service
 * when the relay comes up and the service stops itself the moment the manager
 * reports sharing off — it never keeps itself alive on its own.
 */
@AndroidEntryPoint
class CruxRelayService : android.app.Service() {

    companion object {
        /** Shared with [CruxRelayManager]'s final "sharing stopped" notification. */
        const val CHANNEL_ID = "cruxrelay_sharing"
        private const val NOTIFICATION_ID = 4401
        const val ACTION_STOP_SHARING = "com.cruxcoach.android.relay.ACTION_STOP_SHARING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CruxRelayService::class.java))
        }
    }

    @Inject lateinit var relayManager: CruxRelayManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            // One-tap stop from the notification. Queue and direct board
            // connection remain independent; the collector shuts this service down.
            relayManager.disable()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(relayManager.state.value.clientCount),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        )
        if (stateJob == null) {
            stateJob = scope.launch {
                relayManager.state.collect { state ->
                    if (!state.enabled) {
                        stopSelf()
                    } else {
                        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        mgr.notify(NOTIFICATION_ID, buildNotification(state.clientCount))
                    }
                }
            }
        }
        // Not sticky: sharing is a deliberate runtime action — after a process
        // death the relay is off by design, so a resurrected service would
        // only show a stale notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.relay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.relay_notification_channel_desc)
            }
        )
    }

    private fun buildNotification(clientCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CruxRelayService::class.java).setAction(ACTION_STOP_SHARING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.relay_notification_title))
            .setContentText(getString(R.string.relay_notification_text, clientCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.relay_notification_stop), stopIntent)
            .build()
    }
}
