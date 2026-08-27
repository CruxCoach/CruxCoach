package com.cruxcoach.android.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPollWorkerPrivacyTest {
    @Test
    fun `disabled announcements reject background fetch`() {
        assertFalse(AnnouncementPollingPolicy.allowsBackgroundFetch(false))
        assertTrue(AnnouncementPollingPolicy.allowsBackgroundFetch(true))
    }
}
