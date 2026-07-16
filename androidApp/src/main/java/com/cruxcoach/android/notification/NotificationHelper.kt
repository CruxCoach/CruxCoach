package com.cruxcoach.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    private val context: Context
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ANNOUNCEMENTS,
            context.getString(R.string.notification_channel_announcements),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_announcements_desc)
        })

        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_messages_desc)
        })
    }

    fun showAnnouncementNotification(
        eventId: String,
        category: String,
        content: String
    ) {
        if (!hasPermission()) {
            Log.w(TAG, "event=notification_dropped kind=announcement reason=post_notifications_denied")
            return
        }

        val (title, priority) = when (category) {
            "release" -> context.getString(R.string.notification_announcement_release) to NotificationCompat.PRIORITY_HIGH
            "issue" -> context.getString(R.string.notification_announcement_issue) to NotificationCompat.PRIORITY_DEFAULT
            "tip" -> context.getString(R.string.notification_announcement_tip) to NotificationCompat.PRIORITY_LOW
            else -> context.getString(R.string.app_name) to NotificationCompat.PRIORITY_DEFAULT
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "announcements")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.take(500)))
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(eventId.hashCode(), notification)
        Log.i(TAG, "event=notification_posted kind=announcement")
    }

    fun showMessageNotification(
        eventId: String,
        senderName: String,
        preview: String,
        threadRoute: String
    ) {
        if (!hasPermission()) {
            Log.w(TAG, "event=notification_dropped kind=message reason=post_notifications_denied")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", threadRoute)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(senderName)
            .setContentText(preview.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(eventId.hashCode(), notification)
        Log.i(TAG, "event=notification_posted kind=message")
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_ANNOUNCEMENTS = "cruxcoach_announcements"
        const val CHANNEL_MESSAGES = "cruxcoach_messages"
    }
}
