package com.cruxcoach.android.updater

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.notification.AppNotificationService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Enable" on the updater banner must land where the toggle can be switched on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class UpdateNotificationNudgeIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(AppNotificationService.Channel.UPDATER, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @Test
    fun `muted channel opens the channel page`() {
        shadowOf(manager).setNotificationsEnabled(true)

        val intent = UpdateNotificationReliabilityHelper.nudgeIntent(context)

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
    }

    @Test
    fun `app-wide block opens the app page instead of the dead-end channel page`() {
        shadowOf(manager).setNotificationsEnabled(false)

        val intent = UpdateNotificationReliabilityHelper.nudgeIntent(context)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
    }
}
