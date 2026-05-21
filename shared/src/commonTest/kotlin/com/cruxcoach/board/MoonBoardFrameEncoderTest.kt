package com.cruxcoach.board

import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [MoonBoardFrameEncoder] — the FEAT-027 MoonBoard BLE
 * wire-frame encoder. The serial-position arithmetic is verified
 * against BoardSesh's `getMoonboardSerialPosition` reference.
 */
class MoonBoardFrameEncoderTest {

    // ── Serial-position arithmetic (serpentine 11x18 strip) ──────

    @Test
    fun serialPositionAtGridCorners() {
        // hold 1 = A1 (col 0, row 0) — even column, bottom-up → 0
        assertEquals(0, MoonBoardFrameEncoder.serialPosition(1))
        // hold 11 = K1 (col 10, row 0) — even column → 10*18 + 0
        assertEquals(180, MoonBoardFrameEncoder.serialPosition(11))
        // hold 12 = A2 (col 0, row 1) — even column → 0*18 + 1
        assertEquals(1, MoonBoardFrameEncoder.serialPosition(12))
        // hold 198 = K18 (col 10, row 17) — even column → 10*18 + 17
        assertEquals(197, MoonBoardFrameEncoder.serialPosition(198))
    }

    @Test
    fun serialPositionOddColumnRunsTopDown() {
        // hold 2 = B1 (col 1, row 0) — odd column, top-down → 1*18 + (17-0)
        assertEquals(35, MoonBoardFrameEncoder.serialPosition(2))
        // hold 13 = B2 (col 1, row 1) — odd column → 1*18 + (17-1)
        assertEquals(34, MoonBoardFrameEncoder.serialPosition(13))
    }

    @Test
    fun serialPositionRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> { MoonBoardFrameEncoder.serialPosition(0) }
        assertFailsWith<IllegalArgumentException> { MoonBoardFrameEncoder.serialPosition(199) }
        assertFailsWith<IllegalArgumentException> { MoonBoardFrameEncoder.serialPosition(-5) }
    }

    // ── Frame encoding ───────────────────────────────────────────

    @Test
    fun encodesStartHandFinishWithTokens() {
        // A1 start (role 42), A2 hand (43), K18 finish (44).
        val frames = "p1r42p12r43p198r44"
        // serial positions: 1→0, 12→1, 198→197
        assertEquals("l#S0,P1,E197#", MoonBoardFrameEncoder.encodeToString(frames))
    }

    @Test
    fun encodeProducesUtf8Bytes() {
        val bytes = MoonBoardFrameEncoder.encode("p1r42p198r44")
        assertEquals("l#S0,E197#", bytes.decodeToString())
    }

    @Test
    fun emptyFramesEncodeToBareWrapper() {
        assertEquals("l##", MoonBoardFrameEncoder.encodeToString(""))
    }

    @Test
    fun skipsUnmappedRoleCodes() {
        // Role 45 (foot) + 99 (junk) are not catalogue roles → dropped;
        // start (42) and finish (44) survive.
        val frames = "p1r42p50r45p60r99p198r44"
        assertEquals("l#S0,E197#", MoonBoardFrameEncoder.encodeToString(frames))
    }

    @Test
    fun skipsOutOfRangeHoldIds() {
        // hold 250 is past the 11x18 grid → dropped, rest survive.
        val frames = "p1r42p250r43p198r44"
        assertEquals("l#S0,E197#", MoonBoardFrameEncoder.encodeToString(frames))
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
