package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbEditorStateTest {

    @Test
    fun cycleHoldRole_progresses_then_clears() {
        assertEquals(HoldRole.START, cycleHoldRole(null))
        assertEquals(HoldRole.HAND, cycleHoldRole(HoldRole.START))
        assertEquals(HoldRole.FOOT, cycleHoldRole(HoldRole.HAND))
        assertEquals(HoldRole.FINISH, cycleHoldRole(HoldRole.FOOT))
        assertNull(cycleHoldRole(HoldRole.FINISH))
    }

    @Test
    fun encodeFrames_sorts_by_placement_id() {
        val state = ClimbEditorState(
            selectedHolds = mapOf(
                1185 to HoldRole.START,
                1164 to HoldRole.START,
                1233 to HoldRole.HAND,
                1392 to HoldRole.FINISH,
            ),
        )
        // Sorted ascending by placementId: 1164, 1185, 1233, 1392
        assertEquals("p1164r12p1185r12p1233r13p1392r14", state.encodeFrames())
    }

    @Test
    fun encodeFrames_empty() {
        val state = ClimbEditorState(selectedHolds = emptyMap())
        assertEquals("", state.encodeFrames())
    }
}
