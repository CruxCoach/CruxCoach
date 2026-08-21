package com.cruxcoach.android.boardcell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshMembershipAttemptGateTest {
    @Test fun `a newer membership attempt invalidates every older completion`() {
        val gate = MeshMembershipAttemptGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun `an external lifecycle transition supersedes an in flight attempt`() {
        val gate = MeshMembershipAttemptGate()
        val joining = gate.begin()

        gate.supersede()

        assertFalse(gate.isCurrent(joining))
    }

    @Test fun `delayed admission denial cannot downgrade active or completed membership`() {
        assertFalse(BoardCellAdmissionResultPolicy.shouldApplyRejection(
            "local", "local", approved = false, activeMembership = true,
            transition = MeshMembershipTransition.JOINING,
        ))
        assertFalse(BoardCellAdmissionResultPolicy.shouldApplyRejection(
            "local", "local", approved = false, activeMembership = false,
            transition = MeshMembershipTransition.IDLE,
        ))
        assertTrue(BoardCellAdmissionResultPolicy.shouldApplyRejection(
            "local", "local", approved = false, activeMembership = false,
            transition = MeshMembershipTransition.WAITING_APPROVAL,
        ))
    }

    @Test fun `terminal UI transitions always have a bounded return to idle`() {
        assertTrue(MeshMembershipTransitionPolicy.resetDelayMs(
            MeshMembershipTransition.ERROR, 0, 10_000,
        ) == 5_000L)
        assertTrue(MeshMembershipTransitionPolicy.resetDelayMs(
            MeshMembershipTransition.COOLDOWN, 70_000, 10_000,
        ) == 60_000L)
        assertTrue(MeshMembershipTransitionPolicy.resetDelayMs(
            MeshMembershipTransition.JOINING, 0, 10_000,
        ) == null)
    }
}
