package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

/**
 * Who repairs a gap, when, and how quickly it stops asking.
 *
 * The rule that matters is the first one: a replica that has noticed missing
 * canonical state asks again immediately rather than waiting for the 2 s
 * maintenance tick, because a member standing at the wall with a silently
 * stale playlist is exactly the failure this exists to shorten.
 */
class BoardCellGapRepairPolicyTest {

    private val board = PhysicalBoardId("board-gap")
    private val cell = BoardCellId.forPhysical(board)

    private fun snapshot(
        availability: BoardCellAvailability,
        controller: String = "controller",
        members: Set<String> = setOf("controller", "member"),
    ) = BoardCellSnapshot(
        cellId = cell, physicalBoardId = board, epoch = 1, sequence = 5,
        controllerId = controller, controllerTerm = 1, lineageId = "lineage",
        members = members, availability = availability,
    ).withComputedHash()

    @Test fun `the first retry is immediate and the backoff then widens`() {
        assertEquals(0L, BoardCellGapRepairPolicy.nextDelayMs(0))
        assertEquals(250L, BoardCellGapRepairPolicy.nextDelayMs(1))
        assertEquals(500L, BoardCellGapRepairPolicy.nextDelayMs(2))
        assertEquals(1_000L, BoardCellGapRepairPolicy.nextDelayMs(3))
        assertEquals(BoardCellGapRepairPolicy.MAX_BACKOFF_MS,
            BoardCellGapRepairPolicy.nextDelayMs(9))
        assertEquals(BoardCellGapRepairPolicy.MAX_BACKOFF_MS,
            BoardCellGapRepairPolicy.nextDelayMs(99))
    }

    @Test fun `the repair loop looks far more often than the maintenance loop`() {
        assertTrue(BoardCellGapRepairPolicy.TICK_MS < 2_000L)
        assertTrue(BoardCellGapRepairPolicy.MAX_BACKOFF_MS <= 2_000L)
    }

    @Test fun `only a member that is missing state repairs`() {
        assertTrue(BoardCellGapRepairPolicy.needsRepair(
            snapshot(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT), "member"))
        assertFalse(BoardCellGapRepairPolicy.needsRepair(
            snapshot(BoardCellAvailability.ACTIVE), "member"))
        assertFalse(BoardCellGapRepairPolicy.needsRepair(null, "member"))
    }

    /** Asking itself for canonical state would strand the controller for ever. */
    @Test fun `the controller never asks anybody for the state it owns`() {
        assertFalse(BoardCellGapRepairPolicy.needsRepair(
            snapshot(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT), "controller"))
    }

    @Test fun `a node that is no longer a member does not keep asking`() {
        assertFalse(BoardCellGapRepairPolicy.needsRepair(
            snapshot(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT,
                members = setOf("controller")), "member"))
    }
}
