package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbEditorStateTest {

    @Test
    fun paintWithBrush_assigns_replaces_and_toggles_off() {
        // Empty hold + brush → assign that role
        assertEquals(HoldRole.START, paintWithBrush(currentRole = null, brush = HoldRole.START))
        // Hold with different role + brush → replace with brush
        assertEquals(HoldRole.HAND, paintWithBrush(currentRole = HoldRole.START, brush = HoldRole.HAND))
        // Hold with same role + brush → toggle off (returns null)
        assertNull(paintWithBrush(currentRole = HoldRole.HAND, brush = HoldRole.HAND))
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

    @Test
    fun encodeFrames_moonboard_emits_route_roles_verbatim() {
        // A MoonBoard draft stores brand-native route roles (42/43/44);
        // encodeFrames must emit them unchanged so the result is exactly the
        // p{holdId}r{routeRole} wire format MoonBoardFrameEncoder + the
        // MoonBoard renderer read. No normalization to boulder roles here.
        val state = ClimbEditorState(
            boardBrand = "moonboard",
            selectedHolds = mapOf(
                30 to HoldRole.ROUTE_START,
                7 to HoldRole.ROUTE_START,
                95 to HoldRole.ROUTE_HAND,
                180 to HoldRole.ROUTE_FINISH,
            ),
        )
        assertEquals("p7r42p30r42p95r43p180r44", state.encodeFrames())
    }

    @Test
    fun activeBrush_defaults_to_START_on_fresh_state() {
        // Regression: a freshly-opened editor must pre-select the green
        // Start chip so first-time users discover the chip-row controls
        // which role taps paint. Null-default would silently land them
        // in delete mode where their first tap simply does nothing.
        assertEquals(HoldRole.START, ClimbEditorState().activeBrush)
    }
}
