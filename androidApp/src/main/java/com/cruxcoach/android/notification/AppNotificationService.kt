package com.cruxcoach.android.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
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
        if (!hasPermission()) return
        manager.notify(id, notification)
    }

    // ── Rest Timer ──────────────────────────────────────────────

    fun notifyRestTimerFinished() {
        // Vibration comes from the notification channel (enableVibration=true)
        // — no extra vibrate() call needed, that caused double vibration.
        val notification = NotificationCompat.Builder(context, Channel.REST_TIMER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notification_rest_timer_title))
            .setContentText(context.getString(R.string.notification_rest_timer_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        showIfPermitted(Id.REST_TIMER, notification)
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

        mgr.createNotificationChannel(NotificationChannel(
            Channel.REST_TIMER,
            context.getString(R.string.notification_channel_rest_timer),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_rest_timer_desc)
            enableVibration(true)
            vibrationPattern = PATTERN_ALERT
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
        const val REST_TIMER = "rest_timer"
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
        val PATTERN_ALERT = longArrayOf(0, 300, 200, 300, 200, 300)
    }
}
