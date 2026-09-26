package com.cruxcoach.android.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.nostr.model.MessageType
import com.cruxcoach.android.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A developer reply says which thread it belongs to, and several replies
 * group under a summary that opens "Bugs & feature requests" instead of the
 * system's, which only opened the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReplyNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun Notification.isSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    @Test
    fun `a reply names its thread and joins the replies group`() {
        NotificationHelper(context).showMessageNotification("a1", MessageType.BUG, "bestätigt", "message_thread/a1")

        val reply = shadowOf(manager).allNotifications.single { !it.isSummary() }
        assertEquals("Developer · Bug report", reply.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(NotificationHelper.GROUP_MESSAGES, reply.group)
    }

    @Test
    fun `the replies group opens bugs and feature requests`() {
        val helper = NotificationHelper(context)
        helper.showMessageNotification("a1", MessageType.BUG, "bestätigt", "message_thread/a1")
        helper.showMessageNotification("b2", MessageType.CRASH, "angekommen", "message_thread/b2")

        val notifications = shadowOf(manager).allNotifications
        assertEquals(2, notifications.count { !it.isSummary() })
        val summary = notifications.single { it.isSummary() }
        assertEquals(NotificationHelper.GROUP_MESSAGES, summary.group)
        assertEquals(
            Routes.SUPPORT_SETTINGS,
            shadowOf(summary.contentIntent).savedIntent.getStringExtra("navigate_to"),
        )
    }
}
