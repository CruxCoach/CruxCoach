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
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.launch

/**
 * `connectedDevice` foreground service that keeps a running LED animation alive
 * while the app is backgrounded — the user switched to the camera to film the
 * climb, and the wall keeps pulsing.
 *
 * The service is the permission to write: while it is up it claims the
 * background half of [LedAnimationEngine]'s visibility gate, and when it dies —
 * the app came back, the effect stopped, the link died, Stop was tapped, or
 * Android finally reclaimed the process — the claim goes with it and the engine
 * falls silent exactly like it used to. There is deliberately no wake lock: an
 * effect keeps running while the phone is awake enough to run the loop, and an
 * OS that freezes the app stops the animation, which is the accepted boundary.
 *
 * Lifecycle is slaved to [LedAnimationEngine.isRunning] the way
 * `CruxRelayService` is slaved to its manager: [start] happens only with an
 * effect mid-flight (the keep-alive hook in `CruxCoachApp`), and the moment the
 * engine stops pushing frames the service takes itself down.
 */
@AndroidEntryPoint
class LedAnimationService : Service() {

    @Inject
    lateinit var engine: LedAnimationEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runningJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine.claimBackground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stops the keep-alive, not the setting: the wall falls silent with
            // the claim, and returning to the app animates again as usual.
            stopSelf()
            return START_NOT_STICKY
        }
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
        if (runningJob == null) {
            runningJob = scope.launch {
                engine.isRunning.collect { running ->
                    if (!running) stopSelf()
                }
            }
        }
        // Not sticky: an animation is a live session, not a preference to
        // resurrect — after a process death the board is dark and the climb
        // unlit, and a restarted service would only hold a stale notification.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runningJob?.cancel()
        scope.cancel()
        engine.releaseBackground()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.led_anim_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.led_anim_notification_channel_desc)
            },
        )
    }

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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.led_anim_notification_title))
            .setContentText(getString(R.string.led_anim_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.led_anim_notification_stop), stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "led_animation"
        private const val NOTIFICATION_ID = 4712
        private const val ACTION_STOP = "com.cruxcoach.android.led.ACTION_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LedAnimationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LedAnimationService::class.java))
        }
    }
}
