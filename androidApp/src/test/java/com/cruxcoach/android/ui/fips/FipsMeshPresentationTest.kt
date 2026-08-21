package com.cruxcoach.android.ui.fips

import com.cruxcoach.android.boardcell.MeshMembershipTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class FipsMeshPresentationTest {
    @Test fun `only a genuinely missing controller is shown as controller recovery`() {
        assertEquals(
            BoardMembershipDisplayState.CONTROLLER_RECOVERY,
            boardMembershipDisplayState(true, "FROZEN_NEEDS_CONTROLLER", MeshMembershipTransition.IDLE),
        )
        listOf("SETTLING", "FROZEN_NEEDS_SNAPSHOT", "FROZEN_FORK")
            .forEach { availability ->
                assertEquals(
                    BoardMembershipDisplayState.SYNCHRONIZING,
                    boardMembershipDisplayState(true, availability, MeshMembershipTransition.IDLE),
                )
            }
        assertEquals(
            BoardMembershipDisplayState.CONFIRM_BOARD,
            boardMembershipDisplayState(true, "FROZEN_WRITE_RECOVERY", MeshMembershipTransition.IDLE),
        )
    }

    @Test fun `membership transitions take precedence over stale availability`() {
        assertEquals(
            BoardMembershipDisplayState.LEAVING,
            boardMembershipDisplayState(true, "FROZEN_NEEDS_CONTROLLER", MeshMembershipTransition.LEAVING),
        )
        assertEquals(
            BoardMembershipDisplayState.JOINING,
            boardMembershipDisplayState(true, "FROZEN_NEEDS_CONTROLLER", MeshMembershipTransition.JOINING),
        )
        assertEquals(
            BoardMembershipDisplayState.JOINING,
            boardMembershipDisplayState(true, "ACTIVE", MeshMembershipTransition.WAITING_APPROVAL),
        )
    }

    @Test fun `active and inactive membership remain unambiguous`() {
        assertEquals(
            BoardMembershipDisplayState.ACTIVE,
            boardMembershipDisplayState(true, "ACTIVE", MeshMembershipTransition.IDLE),
        )
        assertEquals(
            BoardMembershipDisplayState.INACTIVE,
            boardMembershipDisplayState(false, "FROZEN_NEEDS_CONTROLLER", MeshMembershipTransition.IDLE),
        )
    }
}
