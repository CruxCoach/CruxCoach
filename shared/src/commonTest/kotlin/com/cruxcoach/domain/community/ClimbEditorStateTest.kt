package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun parseHoldsForEditor_folds_aurora_catalogue_roles_into_kilter_palette() {
        // Aurora-family catalogue frames use board-local roles 1-4 (mirrored
        // 5-8). Remixing one must seed the editor in the Kilter boulder
        // palette (12-15) so the brushes, chip counters and ClimbValidation
        // (all exact-match on 12-15) recognise the holds — pre-fix a Tension
        // remix reported NoStartHold+NoFinishHold and stayed unpublishable.
        val holds = parseHoldsForEditor(
            "p100r1p101r2p102r3p103r4p104r6",
            BoardBrand.TENSION,
        )
        assertEquals(
            mapOf(
                100 to HoldRole.START,
                101 to HoldRole.HAND,
                102 to HoldRole.FINISH,
                103 to HoldRole.FOOT,
                // Mirrored hand role (6) folds to HAND too.
                104 to HoldRole.HAND,
            ),
            holds,
        )
        assertTrue(
            ClimbValidation.isValid(
                holds, name = "Tension Remix", description = "", angle = 40,
                setterGradeId = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
            )
        )
    }

    @Test
    fun parseHoldsForEditor_keeps_moonboard_route_roles_verbatim() {
        // MoonBoard stays brand-native: route roles (42/43/44) must survive
        // so encodeFrames round-trips to the wire format the MoonBoard
        // renderer + BLE encoder read.
        val holds = parseHoldsForEditor("p5r42p7r43p9r44", BoardBrand.MOONBOARD)
        assertEquals(
            mapOf(
                5 to HoldRole.ROUTE_START,
                7 to HoldRole.ROUTE_HAND,
                9 to HoldRole.ROUTE_FINISH,
            ),
            holds,
        )
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
