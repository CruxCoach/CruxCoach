package com.cruxcoach.android.led

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * `connectedDevice` foreground service that keeps a running LED effect going
 * while another app is open — the user switched to the camera to film the climb.
 *
 * Only when the user turned on "Keep running in other apps", only while its
 * notification can be seen (so Stop is always within reach), and for no longer
 * than the engine's time limit per climb. The claim on the engine's background
 * half comes from `CruxCoachApp` on the way out of the app; this service ends
 * itself as soon as the claim is released (the app came back) or the effect
 * stops (time limit, Stop, the link went away). There is deliberately no wake
 * lock: an OS that freezes the app stops the effect, which is the accepted
 * boundary.
 *
 * Stop ends the effect for the climb that is lit and puts its own colours back;
 * the setting stays, so the next climb sent moves again.
 */
@AndroidEntryPoint
class LedAnimationService : Service() {

    @Inject
    lateinit var engine: LedAnimationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, whatever the intent: a service started with
        // startForegroundService that ends before calling startForeground takes
        // the app down on several Android versions.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
        }.onFailure {
            Log.w(TAG, "LED effect keep-alive refused by Android; the effect stops with the background", it)
        }.isSuccess
        if (!foreground) {
            engine.releaseBackground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            engine.stopForCurrentClimb()
            engine.releaseBackground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (watchJob == null) {
            watchJob = scope.launch {
                combine(engine.isRunning, engine.backgroundClaimed) { running, claimed -> running && claimed }
                    .first { keep -> !keep }
                stopSelf()
            }
        }
        // Not sticky: an effect is a live session, not a preference to resurrect.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        engine.releaseBackground()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LedAnimationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = (LedAnimationEngine.MAX_EFFECT_MS / 60_000L).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.led_anim_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.led_anim_notification_text, minutes, minutes))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.led_anim_notification_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "LedAnimationService"
        private const val CHANNEL_ID = "led_animation"
        private const val NOTIFICATION_ID = 4712
        private const val ACTION_STOP = "com.cruxcoach.android.led.ACTION_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LedAnimationService::class.java))
        }

        /**
         * True when the service's notification would be seen — app notifications
         * allowed (on Android 13+ the permission granted) and the channel not
         * blocked. Without it Stop would be out of reach, so the effect is not kept
         * running outside the app.
         */
        fun notificationsVisible(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
            return true
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.led_anim_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.led_anim_notification_channel_desc)
                },
            )
        }
    }
}
