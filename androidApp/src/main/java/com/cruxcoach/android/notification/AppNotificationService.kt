package com.cruxcoach.android.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cruxcoach.android.R

/**
 * Central notification service for the entire app.
 *
 * Responsibilities:
 * - Creates all notification channels at init
 * - Centralizes permission checks and vibration
 * - Provides typed methods per feature (rest timer, training, sync)
 *
 * To add a new notification type:
 * 1. Add channel constant + ID constant
 * 2. Register channel in [createAllChannels]
 * 3. Add a typed notify/cancel method pair
 */
class AppNotificationService(private val context: Context) {

    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)
    private var restRingtone: Ringtone? = null

    init {
        createAllChannels()
    }

    // ── Permission ──────────────────────────────────────────────

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ── Vibration ───────────────────────────────────────────────

    fun vibrate(pattern: LongArray = PATTERN_ALERT) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() != true) return

            // An attribute-less vibration is USAGE_UNKNOWN and gets
            // silently dropped by the system in several ringer/haptic
            // policy states (notably on Android 13+). Tag it USAGE_ALARM
            // so that once we've decided (per ringer mode) to vibrate,
            // the haptic actually plays.
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build()
                    vibrator.vibrate(effect, attrs)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    val effect = VibrationEffect.createWaveform(pattern, -1)
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, attrs)
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("NotificationService", "Vibration failed", e)
        }
    }

    // ── Generic ─────────────────────────────────────────────────

    fun cancel(id: Int) {
        manager.cancel(id)
    }

    private fun showIfPermitted(id: Int, notification: Notification) {
        if (!hasPermission()) {
            android.util.Log.w(
                TAG,
                "event=notification_dropped reason=post_notifications_denied id=$id",
            )
            return
        }
        manager.notify(id, notification)
        android.util.Log.i(TAG, "event=notification_posted id=$id")
    }

    // ── Rest Timer ──────────────────────────────────────────────

    /**
     * Fires the finished-rest-timer alert while respecting the system
     * ringer mode (like a notification, not an alarm):
     *  - NORMAL  → short tone + vibration
     *  - VIBRATE → vibration only
     *  - SILENT  → nothing audible/tactile
     * The visual notification is always posted (best-effort, gated by
     * POST_NOTIFICATIONS). Centralizes the policy so the receiver just
     * calls this.
     */
    fun alertRestTimerFinished() {
        val ringer = (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.ringerMode
        when (ringer) {
            AudioManager.RINGER_MODE_NORMAL -> { playRestAlarmSound(); vibrate() }
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> { /* SILENT / unknown → stay silent, respect the system */ }
        }
        notifyRestTimerFinished()
    }

    /**
     * Starts the finished-rest-timer tone. Uses the default *alarm*
     * sound (more attention-grabbing than the notification blip) but
     * does NOT rely on it ending by itself — the alarm sound loops /
     * runs for tens of seconds. [RestTimerAlarmReceiver] hard-stops it
     * via [stopRestAlarmSound] after a short cap so it's noticeable +
     * a bit longer, yet never "permanent". USAGE_NOTIFICATION keeps it
     * on the notification volume; ringer-mode gating is done by
     * [alertRestTimerFinished] (raw Ringtone playback doesn't
     * auto-silence in vibrate/silent profiles).
     */
    fun playRestAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            restRingtone?.stop()
            restRingtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            android.util.Log.w("NotificationService", "Rest alarm sound failed", e)
        }
    }

    /** Stops the tone started by [playRestAlarmSound] (called by the
     *  receiver after the short cap so it never plays "permanently"). */
    fun stopRestAlarmSound() {
        try {
            restRingtone?.stop()
            restRingtone = null
        } catch (e: Exception) {
            android.util.Log.w("NotificationService", "Rest alarm stop failed", e)
        }
    }

    fun notifyRestTimerFinished() {
        // Visual heads-up only. Sound + vibration are fired explicitly
        // by RestTimerAlarmReceiver (see playRestAlarmSound/vibrate) so
        // the timer alerts even when notifications are denied and isn't
        // doubled by the channel.
        val notification = NotificationCompat.Builder(context, Channel.REST_TIMER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_rest_timer_title))
            .setContentText(context.getString(R.string.notification_rest_timer_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(appLaunchPendingIntent())
            .setAutoCancel(true)
            .build()
        showIfPermitted(Id.REST_TIMER, notification)
    }

    /** Tapping a notification re-opens the app on its launcher entry. */
    private fun appLaunchPendingIntent(): PendingIntent? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return null
        return PendingIntent.getActivity(
            context,
            Id.REST_TIMER,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun cancelRestTimer() = cancel(Id.REST_TIMER)

    // ── Training Reminder ───────────────────────────────────────

    fun notifyTrainingReminder(title: String, message: String) {
        val notification = NotificationCompat.Builder(context, Channel.TRAINING_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        showIfPermitted(Id.TRAINING_REMINDER, notification)
    }

    // ── Board Sync ──────────────────────────────────────────────

    /** Returns a Notification (not shown) — caller wraps in ForegroundInfo. */
    fun buildSyncProgressNotification(): Notification {
        return NotificationCompat.Builder(context, Channel.BOARD_SYNC)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(context.getString(R.string.notification_sync_title))
            .setContentText(context.getString(R.string.notification_sync_message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    fun notifySyncResult(title: String, message: String, isError: Boolean = false) {
        val notification = NotificationCompat.Builder(context, Channel.BOARD_SYNC)
            .setSmallIcon(
                if (isError) android.R.drawable.ic_dialog_alert
                else android.R.drawable.ic_popup_sync
            )
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        showIfPermitted(Id.SYNC_RESULT, notification)
    }

    // ── Channel Registration ────────────────────────────────────

    private fun createAllChannels() {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Drop the legacy channel whose immutable (silent) settings
        // shipped before this fix.
        mgr.deleteNotificationChannel("rest_timer")
        mgr.createNotificationChannel(NotificationChannel(
            Channel.REST_TIMER,
            context.getString(R.string.notification_channel_rest_timer),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_rest_timer_desc)
            // The alert (alarm-stream sound + vibration) is owned by
            // RestTimerAlarmReceiver so it fires even without
            // POST_NOTIFICATIONS and is never doubled. The channel stays
            // HIGH purely for heads-up visibility.
            setSound(null, null)
            enableVibration(false)
        })

        mgr.createNotificationChannel(NotificationChannel(
            Channel.TRAINING_REMINDER,
            context.getString(R.string.notification_channel_training_reminder),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_training_reminder_desc)
        })

        mgr.createNotificationChannel(NotificationChannel(
            Channel.BOARD_SYNC,
            context.getString(R.string.notification_channel_board_sync),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_board_sync_desc)
        })

        mgr.createNotificationChannel(NotificationChannel(
            Channel.UPDATER,
            context.getString(R.string.notification_channel_updater),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_updater_desc)
        })
    }

    // ── Constants ────────────────────────────────────────────────

    object Channel {
        // v2: a notification channel's sound/vibration settings are
        // immutable after first creation, so installs that created the
        // old "rest_timer" channel kept its (silent) config no matter
        // what the code said. New id forces the corrected settings; the
        // legacy channel is deleted in createAllChannels().
        const val REST_TIMER = "rest_timer_v2"
        const val TRAINING_REMINDER = "training_reminder"
        const val BOARD_SYNC = "board_sync"
        const val UPDATER = "updater"
    }

    object Id {
        const val REST_TIMER = 3001
        const val TRAINING_REMINDER = 1001
        const val SYNC_PROGRESS = 2001
        const val SYNC_RESULT = 2002
        const val UPDATE = 4001
    }

    companion object {
        private const val TAG = "NotificationService"
        val PATTERN_ALERT = longArrayOf(0, 300, 200, 300, 200, 300)
    }
}
