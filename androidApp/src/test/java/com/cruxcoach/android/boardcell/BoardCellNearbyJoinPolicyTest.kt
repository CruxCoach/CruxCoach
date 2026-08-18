package com.cruxcoach.android.boardcell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCellNearbyJoinPolicyTest {
    @Test
    fun `running physical owner keeps matching realm during snapshot restore`() {
        assertTrue(BoardCellNearbyJoinPolicy.keepsActivePhysicalRealm(
            targetRealmId = "cell-a",
            activeRealmId = "cell-a",
            runtimeRunning = true,
            physicalBoardOwnerHeld = true,
        ))
    }

    @Test
    fun `foreign realm may still be joined`() {
        assertFalse(BoardCellNearbyJoinPolicy.keepsActivePhysicalRealm(
            targetRealmId = "cell-b",
            activeRealmId = "cell-a",
            runtimeRunning = true,
            physicalBoardOwnerHeld = true,
        ))
    }

    @Test
    fun `stopped or unowned realm may be freshly joined`() {
        assertFalse(BoardCellNearbyJoinPolicy.keepsActivePhysicalRealm(
            targetRealmId = "cell-a",
            activeRealmId = "cell-a",
            runtimeRunning = false,
            physicalBoardOwnerHeld = true,
        ))
        assertFalse(BoardCellNearbyJoinPolicy.keepsActivePhysicalRealm(
            targetRealmId = "cell-a",
            activeRealmId = "cell-a",
            runtimeRunning = true,
            physicalBoardOwnerHeld = false,
        ))
    }

    @Test
    fun `join waits for active canonical membership`() {
        val cell = BoardCellId("cell-a")
        val active = BoardCellSnapshot(
            cellId = cell,
            physicalBoardId = PhysicalBoardId("board-a"),
            epoch = 1,
            sequence = 1,
            controllerId = "controller",
            lineageId = "lineage-a",
            members = setOf("controller", "participant"),
        ).withComputedHash()

        assertTrue(BoardCellNearbyJoinPolicy.hasActiveMembership(
            active, cell, "participant"))
        assertFalse(BoardCellNearbyJoinPolicy.hasActiveMembership(
            active.copy(availability = BoardCellAvailability.FROZEN_NEEDS_CONTROLLER),
            cell,
            "participant",
        ))
        assertFalse(BoardCellNearbyJoinPolicy.hasActiveMembership(
            active, BoardCellId("cell-b"), "participant"))
        assertFalse(BoardCellNearbyJoinPolicy.hasActiveMembership(
            active, cell, "stranger"))
        assertEquals(45_000L, BoardCellNearbyJoinPolicy.HOST_READINESS_TIMEOUT_MS)
    }
}
