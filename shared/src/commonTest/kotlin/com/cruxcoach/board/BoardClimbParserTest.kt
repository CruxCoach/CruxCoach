package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardClimbParserTest {

    @Test
    fun parseFrames_validInput_returnsCorrectHolds() {
        val frames = "p1091r15p1096r15p1163r12p1229r12p1276r13p1393r14"
        val holds = BoardClimbParser.parseFrames(frames)

        assertEquals(6, holds.size)
        assertEquals(BoardHold(1091, 15), holds[0])
        assertEquals(BoardHold(1096, 15), holds[1])
        assertEquals(BoardHold(1163, 12), holds[2])
        assertEquals(BoardHold(1229, 12), holds[3])
        assertEquals(BoardHold(1276, 13), holds[4])
        assertEquals(BoardHold(1393, 14), holds[5])
    }

    @Test
    fun parseFrames_emptyString_returnsEmptyList() {
        assertTrue(BoardClimbParser.parseFrames("").isEmpty())
        assertTrue(BoardClimbParser.parseFrames("  ").isEmpty())
    }

    @Test
    fun parseFrames_singleHold_returnsOneHold() {
        val holds = BoardClimbParser.parseFrames("p100r12")
        assertEquals(1, holds.size)
        assertEquals(BoardHold(100, 12), holds[0])
    }

    @Test
    fun strictSingleFrameRejectsAnyUnparsedOrMultiFrameContent() {
        assertEquals(
            listOf(BoardHold(100, 12), BoardHold(200, 14)),
            BoardClimbParser.parseSingleFrameStrict("p100r12p200r14"),
        )
        assertEquals(
            listOf(BoardHold(100, 12)),
            BoardClimbParser.parseSingleFrameStrict("h100p12"),
        )
        assertNull(BoardClimbParser.parseSingleFrameStrict("p100r12BROKEN"))
        assertNull(BoardClimbParser.parseSingleFrameStrict("BROKENp100r12"))
        assertNull(BoardClimbParser.parseSingleFrameStrict("p100r12,p200r14"))
        assertNull(BoardClimbParser.parseSingleFrameStrict("p999999999999999999999r12"))
        assertNull(BoardClimbParser.parseSingleFrameStrict(""))
    }

    @Test
    fun strictSingleFrameAcceptsQuantumXlCatalogueFrames() {
        val frames = "p1006021r12p1006036r12p1001018r13p1001021r13p1001026r13" +
            "p1004031r13p1005018r13p1005021r13p1005041r13p1005051r13" +
            "p1005057r13p1005069r13p1004084r14p1005082r14"

        assertEquals(14, BoardClimbParser.parseSingleFrameStrict(frames)?.size)
    }

    @Test
    fun encodeFrames_roundtrip() {
        val original = "p1091r15p1096r15p1163r12p1229r12p1276r13p1393r14"
        val holds = BoardClimbParser.parseFrames(original)
        val encoded = BoardClimbParser.encodeFrames(holds)
        assertEquals(original, encoded)
    }

    @Test
    fun countByRole_correctCounts() {
        val frames = "p1091r15p1096r15p1163r12p1229r12p1276r13p1393r14"
        val holds = BoardClimbParser.parseFrames(frames)
        val counts = BoardClimbParser.countByRole(holds)

        assertEquals(2, counts[HoldRole.FOOT])   // r15
        assertEquals(2, counts[HoldRole.START])   // r12
        assertEquals(1, counts[HoldRole.HAND])    // r13
        assertEquals(1, counts[HoldRole.FINISH])  // r14
    }

    @Test
    fun getHandHolds_excludesFeetOnly() {
        val frames = "p1091r15p1096r15p1163r12p1229r12p1276r13p1393r14"
        val holds = BoardClimbParser.parseFrames(frames)
        val handHolds = BoardClimbParser.getHandHolds(holds)

        assertEquals(4, handHolds.size) // 2 start + 1 hand + 1 finish
        assertTrue(handHolds.none { it.roleId == HoldRole.FOOT })
    }

    @Test
    fun estimateMoveCount_correctForTypicalClimb() {
        // 2 start + 3 hand + 1 finish = 6 hand holds, minus 2 starts = 4 moves
        val frames = "p100r12p101r12p200r13p201r13p202r13p300r14"
        val holds = BoardClimbParser.parseFrames(frames)
        assertEquals(4, BoardClimbParser.estimateMoveCount(holds))
    }

    @Test
    fun estimateMoveCount_singleHold_returnsZero() {
        val holds = BoardClimbParser.parseFrames("p100r12")
        assertEquals(0, BoardClimbParser.estimateMoveCount(holds))
    }

    @Test
    fun roleClass_foldsAuroraKilterAndRouteCodesToCanonicalClass() {
        // Aurora-family codes 1-4 (start/middle/finish/foot).
        assertEquals(HoldRole.START, HoldRole.roleClass(1))
        assertEquals(HoldRole.HAND, HoldRole.roleClass(2))
        assertEquals(HoldRole.FINISH, HoldRole.roleClass(3))
        assertEquals(HoldRole.FOOT, HoldRole.roleClass(4))
        // Aurora mirrored set 5-8.
        assertEquals(HoldRole.START, HoldRole.roleClass(5))
        assertEquals(HoldRole.HAND, HoldRole.roleClass(6))
        assertEquals(HoldRole.FINISH, HoldRole.roleClass(7))
        assertEquals(HoldRole.FOOT, HoldRole.roleClass(8))
        // Kilter boulder codes pass through unchanged.
        assertEquals(HoldRole.START, HoldRole.roleClass(12))
        assertEquals(HoldRole.FOOT, HoldRole.roleClass(15))
        // Kilter route codes 42-45 fold like normalize().
        assertEquals(HoldRole.START, HoldRole.roleClass(42))
        assertEquals(HoldRole.FINISH, HoldRole.roleClass(44))
        // Genuinely unknown codes return themselves (exact-match callers safe).
        assertEquals(99, HoldRole.roleClass(99))
    }

    @Test
    fun parseFrames_auroraCodes_preserveRawRoleIds_andClassifyCorrectly() {
        // Regression guard: parseFrames must NOT mutate Aurora role codes
        // (the editor + AuroraImporter round-trip frames verbatim, and the
        // placement_roles colour map is keyed by the raw 1-4 ids).
        val aurora = "p123r1p124r2p125r3p126r4"
        val holds = BoardClimbParser.parseFrames(aurora)
        assertEquals(listOf(1, 2, 3, 4), holds.map { it.roleId })
        assertEquals(aurora, BoardClimbParser.encodeFrames(holds))
        // …but they classify to the canonical roles for comparison/colour.
        assertEquals(
            listOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH, HoldRole.FOOT),
            holds.map { HoldRole.roleClass(it.roleId) },
        )
    }
}
