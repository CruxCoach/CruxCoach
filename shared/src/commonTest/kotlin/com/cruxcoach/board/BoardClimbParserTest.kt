package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
