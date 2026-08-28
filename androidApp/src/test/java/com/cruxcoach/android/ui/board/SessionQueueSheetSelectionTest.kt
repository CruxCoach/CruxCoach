package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionQueueSheetSelectionTest {

    private val queue = listOf(
        QueueItem("first", 30),
        QueueItem("second", 40),
        QueueItem("first", 30),
    )

    @Test
    fun `player selection changes queue position without opening detail`() {
        val navState = ClimbNavigationState()
        var selectedIndex: Int? = null
        var navigated = false

        selectQueueClimb(
            index = 1,
            item = queue[1],
            queue = queue,
            climbNavState = navState,
            onSelectClimb = { selectedIndex = it },
            onNavigateToClimb = { _, _ -> navigated = true },
        )

        assertEquals(1, selectedIndex)
        assertFalse(navigated)
        assertTrue(navState.climbUuids.isEmpty())
    }

    @Test
    fun `standalone queue selection retains detail navigation`() {
        val navState = ClimbNavigationState()
        var destination: Pair<String, Int>? = null

        selectQueueClimb(
            index = 1,
            item = queue[1],
            queue = queue,
            climbNavState = navState,
            onSelectClimb = null,
            onNavigateToClimb = { uuid, angle -> destination = uuid to angle },
        )

        assertEquals("second" to 40, destination)
        assertEquals(listOf("first", "second"), navState.climbUuids)
        assertEquals(40, navState.angle)
        assertEquals(ClimbNavigationSource.QUEUE, navState.source)
    }
}
