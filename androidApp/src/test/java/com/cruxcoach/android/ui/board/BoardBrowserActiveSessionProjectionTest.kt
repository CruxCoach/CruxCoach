package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.QueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardBrowserActiveSessionProjectionTest {
    @Test
    fun `queue identity preserves uuid and angle without inventing mirror state`() {
        val climb = QueueItem("exact-uuid", 40).toActiveSessionClimb("Quiet Riot")!!

        assertEquals("exact-uuid", climb.uuid)
        assertEquals(40L, climb.angle)
        assertNull(climb.isMirrored)
    }

    @Test
    fun `unresolved queue name does not invent a current climb`() {
        assertNull(QueueItem("exact-uuid", 40).toActiveSessionClimb(null))
    }
}
