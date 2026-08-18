package com.cruxcoach.android.boardcell

import org.junit.Assert.assertFalse
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
}
