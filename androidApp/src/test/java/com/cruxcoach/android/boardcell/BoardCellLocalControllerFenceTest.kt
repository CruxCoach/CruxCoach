package com.cruxcoach.android.boardcell

import com.cruxcoach.android.ble.ConnectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardCellLocalControllerFenceTest {
    private val expected = PhysicalBoardId("board-a")

    @Test
    fun `connected exact physical board fences controller recovery`() {
        assertTrue(BoardCellLocalControllerFence.isHeld(
            expected, PhysicalBoardId("board-a"), ConnectionState.CONNECTED,
        ))
        assertTrue(BoardCellLocalControllerFence.isHeld(
            expected, PhysicalBoardId("board-a"), ConnectionState.SENDING,
        ))
    }

    @Test
    fun `wrong absent or idle board cannot fence recovery`() {
        assertFalse(BoardCellLocalControllerFence.isHeld(
            expected, PhysicalBoardId("board-b"), ConnectionState.CONNECTED,
        ))
        assertFalse(BoardCellLocalControllerFence.isHeld(
            expected, null, ConnectionState.CONNECTED,
        ))
        assertFalse(BoardCellLocalControllerFence.isHeld(
            expected, PhysicalBoardId("board-a"), ConnectionState.DISCONNECTED,
        ))
    }
}
