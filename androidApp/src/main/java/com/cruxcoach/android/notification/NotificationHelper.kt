package com.cruxcoach.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cruxcoach.android.MainActivity
import com.cruxcoach.android.R
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.ui.navigation.Routes
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
        val (title, priority) = when (category) {
            "release" -> context.getString(R.string.notification_announcement_release) to NotificationCompat.PRIORITY_HIGH
            "issue" -> context.getString(R.string.notification_announcement_issue) to NotificationCompat.PRIORITY_DEFAULT
            "tip" -> context.getString(R.string.notification_announcement_tip) to NotificationCompat.PRIORITY_LOW
            else -> "CruxCoach" to NotificationCompat.PRIORITY_DEFAULT
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

        notifyIfPermitted(eventId.hashCode(), notification)
    }

    fun showMessageNotification(
        eventId: String,
        type: MessageType,
        preview: String,
        threadRoute: String
    ) {
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
            .setContentTitle(context.getString(replyTitle(type)))
            .setContentText(preview.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_MESSAGES)
            .build()

        notifyIfPermitted(eventId.hashCode(), notification)
        showMessageSummary()
    }

    /** Names the thread a reply belongs to: "Developer · Bug report". */
    @StringRes
    private fun replyTitle(type: MessageType): Int = when (type) {
        MessageType.CHAT -> R.string.notification_reply_chat
        MessageType.BUG -> R.string.notification_reply_bug
        MessageType.FEATURE -> R.string.notification_reply_feature
        MessageType.CRASH -> R.string.notification_reply_crash
    }

    /**
     * Android bundles several replies into one group. Without our own
     * summary it used the system's, whose tap only opened the app and
     * cleared every reply; this one opens "Bugs & feature requests", where
     * the badges show which threads are unread.
     */
    private fun showMessageSummary() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", Routes.SUPPORT_SETTINGS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, MESSAGE_SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(context.getString(R.string.notification_sender_developer))
            .setContentText(context.getString(R.string.notification_replies_summary))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            // The replies themselves already alerted.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        notifyIfPermitted(MESSAGE_SUMMARY_ID, summary)
    }

    private fun notifyIfPermitted(id: Int, notification: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.notify(id, notification)
    }

    companion object {
        const val CHANNEL_ANNOUNCEMENTS = "cruxcoach_announcements"
        const val CHANNEL_MESSAGES = "cruxcoach_messages"
        const val GROUP_MESSAGES = "cruxcoach_dev_replies"
        const val MESSAGE_SUMMARY_ID = 0x5EED
    }
}
