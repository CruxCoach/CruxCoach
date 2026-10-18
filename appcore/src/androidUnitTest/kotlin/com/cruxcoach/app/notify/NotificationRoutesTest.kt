package com.cruxcoach.app.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The routes a tapped notification may carry: exactly the ones the app's own notifications post
 * (Android `MainActivity.safeNavigateToRoute` for these codes), nothing that reaches an import sink.
 */
class NotificationRoutesTest {

    @Test
    fun `the codes are Androids navigate_to values`() {
        assertEquals("today", NotificationRoutes.TODAY)
        assertEquals("body", NotificationRoutes.BODY)
        assertEquals("announcements", NotificationRoutes.ANNOUNCEMENTS)
        assertEquals("dev_chat", NotificationRoutes.DEV_CHAT)
    }

    @Test
    fun `announcement and reply taps are known and lead to the developer contact`() {
        assertTrue(NotificationRoutes.isKnown(NotificationRoutes.ANNOUNCEMENTS))
        assertTrue(NotificationRoutes.isKnown(NotificationRoutes.DEV_CHAT))
        assertTrue(NotificationRoutes.isDevContact(NotificationRoutes.ANNOUNCEMENTS))
        assertTrue(NotificationRoutes.isDevContact(NotificationRoutes.DEV_CHAT))
        // The reminders keep their own places.
        assertTrue(NotificationRoutes.isKnown(NotificationRoutes.TODAY))
        assertTrue(NotificationRoutes.isKnown(NotificationRoutes.BODY))
        assertFalse(NotificationRoutes.isDevContact(NotificationRoutes.TODAY))
        assertFalse(NotificationRoutes.isDevContact(NotificationRoutes.BODY))
    }

    @Test
    fun `anything else is refused exactly`() {
        for (route in listOf(
            "", "DEV_CHAT", "dev_chat ", "announcements/1", "message_thread/ab",
            "board_sync?localDbUrl=http://10.0.0.1/db", "settings",
        )) {
            assertFalse(NotificationRoutes.isKnown(route), route)
            assertFalse(NotificationRoutes.isDevContact(route), route)
        }
    }
}
