package com.cruxcoach.android.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPollCursorTest {
    @Test
    fun `completed relay poll may advance its cursor`() {
        assertEquals(123L, completedPollCursorOrNull(completed = true, cursor = 123L))
    }

    @Test
    fun `timed out relay poll retains its prior cursor`() {
        assertNull(completedPollCursorOrNull(completed = false, cursor = 123L))
    }
}
