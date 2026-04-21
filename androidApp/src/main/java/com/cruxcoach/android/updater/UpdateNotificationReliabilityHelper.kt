package com.cruxcoach.android.updater

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.cruxcoach.android.notification.AppNotificationService

/**
 * Mirrors [com.cruxcoach.android.notification.NotificationReliabilityHelper]
 * but for the updater's notification surface (§6.13).
 *
 * The whole feature collapses into silent failure when notifications are
 * disabled at the app or channel level. The Settings UI uses [isBlocked]
 * to render a permission-nudge banner that opens the right system
 * settings page in one tap.
 *
 * Side-effect free: the UI queries state and opens intents only when the
 * user acts.
 */
object UpdateNotificationReliabilityHelper {

    /**
     * True when either app-level notifications are disabled (or runtime
     * permission denied on Android 13+) OR the updater channel is
     * explicitly muted by the user (`IMPORTANCE_NONE`).
     */
    fun isBlocked(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val channel = mgr.getNotificationChannel(AppNotificationService.Channel.UPDATER)
            ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Best-fit Settings intent: prefer the per-channel page (so the user
     * lands one tap away from the right toggle); fall back to per-app
     * notification settings; finally the generic app-info page.
     */
    fun nudgeIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = mgr?.getNotificationChannel(AppNotificationService.Channel.UPDATER)
            if (channel != null) {
                return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, AppNotificationService.Channel.UPDATER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
