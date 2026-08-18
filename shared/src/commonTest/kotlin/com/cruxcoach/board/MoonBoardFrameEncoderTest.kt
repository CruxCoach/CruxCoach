package com.cruxcoach.board

import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [MoonBoardFrameEncoder] — the FEAT-027 MoonBoard BLE
 * wire-frame encoder. The serial-position arithmetic is verified against
 * known-good frames for the standard 11×18 boards and the documented
 * A1-first zig-zag installation order for 11×12 Mini boards.
 */
class MoonBoardFrameEncoderTest {

    private val standard = MoonBoardVariant.MOONBOARD_2016   // 11×18
    private val mini = MoonBoardVariant.MINI_2020             // 11×12

    @Test
    fun unknownStoredLedModeFallsBackBelow() {
        assertEquals(MoonBoardLedMode.BELOW, MoonBoardLedMode.fromWire("future-mode"))
        assertEquals(MoonBoardLedMode.BELOW, MoonBoardLedMode.fromWire(null))
    }

    @Test
    fun `MoonBoard 2010 uses the standard 198-position wiring map`() {
        (1..198).forEach { holdId ->
            assertEquals(
                MoonBoardFrameEncoder.serialPosition(holdId, standard),
                MoonBoardFrameEncoder.serialPosition(holdId, MoonBoardVariant.MOONBOARD_2010),
                "holdId=$holdId",
            )
        }
    }

    @Test
    fun `Mini MoonBoard 2025 uses the documented 132-position Mini map`() {
        (1..132).forEach { holdId ->
            assertEquals(
                MoonBoardFrameEncoder.serialPosition(holdId, mini),
                MoonBoardFrameEncoder.serialPosition(holdId, MoonBoardVariant.MINI_2025),
                "holdId=$holdId",
            )
        }
        assertEquals(0, MoonBoardFrameEncoder.serialPosition(1, MoonBoardVariant.MINI_2025))
        assertEquals(11, MoonBoardFrameEncoder.serialPosition(122, MoonBoardVariant.MINI_2025))
        assertEquals(23, MoonBoardFrameEncoder.serialPosition(2, MoonBoardVariant.MINI_2025))
        assertEquals(131, MoonBoardFrameEncoder.serialPosition(132, MoonBoardVariant.MINI_2025))
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(133, MoonBoardVariant.MINI_2025)
        }
    }

    // ── Serial-position arithmetic (standard 11×18 boards) ───────

    @Test
    fun serialPositionAtGridCornersStandard() {
        // hold 1 = A1 (col 0, row 0) — even column, bottom-up → 0
        assertEquals(0, MoonBoardFrameEncoder.serialPosition(1, standard))
        // hold 11 = K1 (col 10, row 0) — even column → 10*18 + 0
        assertEquals(180, MoonBoardFrameEncoder.serialPosition(11, standard))
        // hold 12 = A2 (col 0, row 1) — even column → 0*18 + 1
        assertEquals(1, MoonBoardFrameEncoder.serialPosition(12, standard))
        // hold 198 = K18 (col 10, row 17) — even column → 10*18 + 17
        assertEquals(197, MoonBoardFrameEncoder.serialPosition(198, standard))
    }

    @Test
    fun serialPositionOddColumnRunsTopDownStandard() {
        // hold 2 = B1 (col 1, row 0) — odd column, top-down → 1*18 + (17-0)
        assertEquals(35, MoonBoardFrameEncoder.serialPosition(2, standard))
        // hold 13 = B2 (col 1, row 1) — odd column → 1*18 + (17-1)
        assertEquals(34, MoonBoardFrameEncoder.serialPosition(13, standard))
    }

    @Test
    fun serialPositionRejectsOutOfRangeStandard() {
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(0, standard)
        }
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(199, standard)
        }
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(-5, standard)
        }
    }

    // ── Serial-position arithmetic (Mini 2020, 11×12) ────────────

    @Test
    fun serialPositionAtGridCornersMini() {
        // A1 (col 0, row 0) even → 0
        assertEquals(0, MoonBoardFrameEncoder.serialPosition(1, mini))
        // K1 (col 10, row 0) even → 10*12 + 0
        assertEquals(120, MoonBoardFrameEncoder.serialPosition(11, mini))
        // A2 (col 0, row 1) even → 0*12 + 1
        assertEquals(1, MoonBoardFrameEncoder.serialPosition(12, mini))
        // K12 (col 10, row 11) even → 10*12 + 11 — Mini top-right
        assertEquals(131, MoonBoardFrameEncoder.serialPosition(132, mini))
    }

    @Test
    fun serialPositionOddColumnRunsTopDownMini() {
        // B1 (col 1, row 0) odd, top-down → 1*12 + (12-1-0)
        assertEquals(23, MoonBoardFrameEncoder.serialPosition(2, mini))
        // B2 (col 1, row 1) odd → 1*12 + (12-1-1)
        assertEquals(22, MoonBoardFrameEncoder.serialPosition(13, mini))
    }

    @Test
    fun serialPositionRejectsOutOfRangeMini() {
        // Mini's grid stops at hold 132 (= 11×12); 133 is past it.
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(0, mini)
        }
        assertFailsWith<IllegalArgumentException> {
            MoonBoardFrameEncoder.serialPosition(133, mini)
        }
    }

    // ── Frame encoding (standard) ────────────────────────────────

    @Test
    fun encodesStartHandFinishWithTokens() {
        // A1 start (role 42), A2 hand (43), K18 finish (44).
        val frames = "p1r42p12r43p198r44"
        // serial positions: 1→0, 12→1, 198→197
        assertEquals(
            "l#S0,P1,E197#",
            MoonBoardFrameEncoder.encodeToString(frames, standard),
        )
    }

    @Test
    fun encodeProducesUtf8Bytes() {
        val bytes = MoonBoardFrameEncoder.encode("p1r42p198r44", standard)
        assertEquals("l#S0,E197#", bytes.decodeToString())
    }

    @Test
    fun aboveModeUsesAdjacentLedAndKeepsFinishBelow() {
        assertEquals(
            "l#S0,P2,E197#",
            MoonBoardFrameEncoder.encodeToString(
                "p1r42p12r43p198r44",
                standard,
                ledMode = MoonBoardLedMode.ABOVE,
            ),
        )
    }

    @Test
    fun bothModeEmitsBelowAndAboveForRegularHoldsOnly() {
        assertEquals(
            "l#S0,P1,P2,E197#",
            MoonBoardFrameEncoder.encodeToString(
                "p1r42p12r43p198r44",
                standard,
                ledMode = MoonBoardLedMode.BOTH,
            ),
        )
    }

    @Test
    fun aboveModeFollowsReverseStripDirectionOnOddColumns() {
        assertEquals(
            "l#P33#",
            MoonBoardFrameEncoder.encodeToString(
                "p13r43",
                standard,
                ledMode = MoonBoardLedMode.ABOVE,
            ),
        )
    }

    @Test
    fun finishRoleFallsBackBelowEvenWhenAnUpperPositionExists() {
        assertEquals(
            "l#E1#",
            MoonBoardFrameEncoder.encodeToString(
                "p12r44",
                standard,
                ledMode = MoonBoardLedMode.ABOVE,
            ),
        )
    }

    @Test
    fun emptyFramesEncodeToBareWrapper() {
        assertEquals(
            "l##",
            MoonBoardFrameEncoder.encodeToString("", standard),
        )
    }

    @Test
    fun skipsUnmappedRoleCodes() {
        // Role 45 (foot) + 99 (junk) are not catalogue roles → dropped;
        // start (42) and finish (44) survive.
        val frames = "p1r42p50r45p60r99p198r44"
        assertEquals(
            "l#S0,E197#",
            MoonBoardFrameEncoder.encodeToString(frames, standard),
        )
    }

    @Test
    fun skipsOutOfRangeHoldIds() {
        // hold 250 is past the 11×18 grid → dropped, rest survive.
        val frames = "p1r42p250r43p198r44"
        assertEquals(
            "l#S0,E197#",
            MoonBoardFrameEncoder.encodeToString(frames, standard),
        )
    }

    // ── Frame encoding (Mini 2020) ───────────────────────────────

    @Test
    fun encodesMiniClimbWithVariantSerpentine() {
        // Mini climb: A2 start, B2 hand, K12 finish (all valid Mini holds).
        val frames = "p12r42p13r43p132r44"
        // Serial pos on Mini: 12→1, 13→22 (odd col top-down), 132→131.
        assertEquals(
            "l#S1,P22,E131#",
            MoonBoardFrameEncoder.encodeToString(frames, mini),
        )
    }

    @Test
    fun miniAboveModeUsesItsTwelveRowColumnDirection() {
        assertEquals(
            "l#S2,P21,E131#",
            MoonBoardFrameEncoder.encodeToString(
                "p12r42p13r43p132r44",
                mini,
                ledMode = MoonBoardLedMode.ABOVE,
            ),
        )
    }

    @Test
    fun miniSkipsHoldsAboveItsRange() {
        // hold 198 (K18) is valid on a standard board but past Mini's
        // 132-hold range — must be dropped, not encoded.
        val frames = "p12r42p198r44"
        assertEquals(
            "l#S1#",
            MoonBoardFrameEncoder.encodeToString(frames, mini),
        )
    }

    // ── parseHolds robustness ────────────────────────────────────

    @Test
    fun parseHoldsReadsConcatenatedPairs() {
        assertEquals(
            listOf(1 to 42, 12 to 43, 198 to 44),
            MoonBoardFrameEncoder.parseHolds("p1r42p12r43p198r44"),
        )
    }

    @Test
    fun parseHoldsToleratesTrailingSeparators() {
        // A frames variant with comma separators must still parse —
        // the role is read as its leading digit run.
        assertEquals(
            listOf(1 to 42, 12 to 43),
            MoonBoardFrameEncoder.parseHolds("p1r42,p12r43,"),
        )
    }

    @Test
    fun parseHoldsIgnoresGarbageSegments() {
        assertEquals(
            listOf(5 to 43),
            MoonBoardFrameEncoder.parseHolds("pXr1p5r43prp"),
        )
    }
}
