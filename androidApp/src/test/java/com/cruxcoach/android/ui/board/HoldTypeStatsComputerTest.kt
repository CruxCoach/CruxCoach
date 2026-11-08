package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.holdtypes.HoldMix
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeConfidence
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.HoldTypeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTypeStatsComputerTest {

    private fun entry(placement: Int, type: String, confidence: Double = 0.7, footOnly: Boolean = false, layoutId: Int = 1) =
        HoldTypeEntry(
            placement = placement,
            layoutId = layoutId,
            setId = 1,
            type = type,
            footOnly = footOnly,
            confidence = HoldTypeConfidence(type = confidence),
        )

    private val kilter = HoldTypeDataset(
        format = HoldTypeDataset.FORMAT,
        board = "kilter",
        version = "test",
        holds = listOf(
            entry(1, "edge"), entry(2, "edge"), entry(3, "jug"), entry(4, "sloper"),
            entry(5, "pinch", confidence = 0.2), entry(6, "foot_chip"), entry(7, "edge", footOnly = true),
        ),
    )

    private fun ascent(
        climb: String,
        frames: String,
        send: Boolean = true,
        grade: Double? = 20.0,
        board: String = "kilter",
        layoutId: Long? = 1L,
        angle: Long = 40,
    ) = AscentWithClimb(
        uuid = "$climb-$angle-$send-$grade",
        climbUuid = climb,
        angle = angle,
        isMirror = false,
        bidCount = 1,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = "2026-11-01 10:00:00",
        climbName = climb,
        climbFrames = frames,
        difficultyAverage = grade,
        isSend = send,
        boardBrand = board,
        layoutId = layoutId,
    )

    @Test
    fun problems_countOncePerClimbAcrossAngles_withoutFootHolds() {
        val problems = HoldTypeStatsComputer.problems(
            listOf(
                ascent("a", "p1r12p2r13p3r14p7r15", send = false, grade = 18.0, angle = 30),
                ascent("a", "p1r12p2r13p3r14p7r15", send = true, grade = 21.0, angle = 40),
                ascent("a", "p1r12p2r13p3r14p7r15", send = false, grade = 24.0, angle = 50),
                ascent("b", "p4r5p4r6p6r8", send = false, grade = 19.0),
            ),
            board = "kilter",
        )
        assertEquals(2, problems.size)
        val a = problems.first { 1 in it.handHolds }
        assertTrue(a.sent)
        // The hardest send's grade, not the hardest try's.
        assertEquals(21.0, a.grade!!, 0.0)
        assertEquals(listOf(1, 2, 3), a.handHolds)
        val b = problems.first { 4 in it.handHolds }
        assertFalse(b.sent)
        assertEquals(19.0, b.grade!!, 0.0)
        // Aurora-family roles: 5 start, 6 hand, 8 foot.
        assertEquals(listOf(4), b.handHolds)
    }

    @Test
    fun problems_attemptsWithoutFrames_takeThemFromTheMap_byNormalizedUuid() {
        // The logbook's attempts carry no frames; their climbs' frames come from the catalogue.
        val problems = HoldTypeStatsComputer.problems(
            listOf(ascent("ABC-1", "", send = false, grade = 19.0), ascent("ABC-1", "", send = false, grade = 19.0, angle = 45)),
            board = "kilter",
            frames = mapOf("abc1" to "p1r12p3r14p7r15"),
        )
        val problem = problems.single()
        assertFalse(problem.sent)
        assertEquals(listOf(1, 3), problem.handHolds)
    }

    @Test
    fun problems_layoutUnknown_kilterIsOriginal_moonBoardDropped_othersKeptWithoutComparison() {
        assertEquals(1, HoldTypeStatsComputer.problems(listOf(ascent("k", "p1r12", layoutId = null)), "kilter").single().layoutId)
        assertTrue(HoldTypeStatsComputer.problems(listOf(ascent("m", "p1r42", board = "moonboard", layoutId = null)), "moonboard").isEmpty())
        assertEquals(
            HoldTypeStatsComputer.UNKNOWN_LAYOUT,
            HoldTypeStatsComputer.problems(listOf(ascent("t", "p1r5", board = "tension", layoutId = null)), "tension").single().layoutId,
        )
        assertEquals(5, HoldTypeStatsComputer.problems(listOf(ascent("m", "p1r42", board = "moonboard", layoutId = 5)), "moonboard").single().layoutId)
    }

    @Test
    fun cardBoard_filterWinsWhenLabelled_elseTheBoardWithMostProblems() {
        val ascents = listOf(
            ascent("k1", ""), ascent("k1", "", angle = 50),
            ascent("m1", "", board = "moonboard"), ascent("m2", "", board = "moonboard"),
            ascent("t1", "", board = "touchstone"), ascent("t2", "", board = "touchstone"), ascent("t3", "", board = "touchstone"),
        )
        val typed = setOf("kilter", "moonboard")
        val asked = mutableListOf<String>()
        val hasTypes = { board: String -> asked += board; board in typed }
        // Touchstone has the most problems but no typed holds: the next board with typed holds wins.
        assertEquals("moonboard", HoldTypeStatsComputer.cardBoard(ascents, null, hasTypes))
        assertEquals(listOf("touchstone", "moonboard"), asked)
        assertEquals("kilter", HoldTypeStatsComputer.cardBoard(ascents, "kilter", hasTypes))
        assertNull(HoldTypeStatsComputer.cardBoard(ascents, "touchstone", hasTypes))
        assertNull(HoldTypeStatsComputer.cardBoard(emptyList(), null, hasTypes))
    }

    @Test
    fun gradeRanges_yourGradesOneStepWider_perKnownLayout() {
        val ranges = HoldTypeStatsComputer.gradeRanges(
            listOf(
                HoldTypeStatsComputer.typedClimb("kilter", 1, "", grade = 18.0),
                HoldTypeStatsComputer.typedClimb("kilter", 1, "", grade = 22.5),
                HoldTypeStatsComputer.typedClimb("kilter", 8, "", grade = null),
                HoldTypeStatsComputer.typedClimb("kilter", HoldTypeStatsComputer.UNKNOWN_LAYOUT, "", grade = 30.0),
            ),
        )
        assertEquals(setOf(1, 8), ranges.keys)
        // One step wider, out to whole steps.
        assertEquals(17.0, ranges.getValue(1).start, 0.0)
        assertEquals(24.0, ranges.getValue(1).endInclusive, 0.0)
        assertEquals(0.0, ranges.getValue(8).start, 0.0)
        assertEquals(Double.MAX_VALUE, ranges.getValue(8).endInclusive, 0.0)
    }

    @Test
    fun compute_mixAndBoardShares_layoutsWeightedByYourProblems() {
        val yours = listOf(
            // Layout 1: two problems, all edges.
            HoldTypeStatsComputer.typedClimb("kilter", 1, "p1r12p2r14", sent = true, grade = 20.0),
            HoldTypeStatsComputer.typedClimb("kilter", 1, "p1r12p2r14", sent = true, grade = 20.0),
            // Layout 8: one problem, half jug, half pinch. The foot chip and the foot-only edge do not count.
            HoldTypeStatsComputer.typedClimb("kilter", 8, "p3r12p5r13p6r13p7r14", sent = false, grade = 22.0),
        )
        val boardMixes = mapOf(
            1 to mix(HoldType.EDGE to 0.5, HoldType.SLOPER to 0.5, problems = 100),
            8 to mix(HoldType.JUG to 1.0, problems = 10),
        )
        val card = HoldTypeStatsComputer.compute("kilter", kilter, yours, boardMixes)!!
        assertEquals(3, card.problems)
        assertEquals(6, card.countedHolds)
        assertEquals(8, card.handHolds)
        assertEquals(110, card.boardProblems)
        assertEquals(HoldTypeStats.HAND_TYPES, card.rows.map { it.type })
        val edge = card.rows.first { it.type == HoldType.EDGE }
        assertEquals(2.0 / 3, edge.yours, 1e-9)
        // Layout 1 weighs two of your problems, layout 8 one.
        assertEquals(0.5 * 2 / 3, edge.board!!, 1e-9)
        assertEquals(1.0 / 3, card.rows.first { it.type == HoldType.JUG }.board!!, 1e-9)
        assertEquals(0.5 / 3, card.rows.first { it.type == HoldType.PINCH }.yours, 1e-9)
        assertEquals(2.0 / 3, card.overall.sendRate!!, 1e-9)
    }

    @Test
    fun compute_withoutBoardProblems_hasNoBoardShare_andWithoutTypedHolds_isNull() {
        val yours = listOf(HoldTypeStatsComputer.typedClimb("kilter", 1, "p1r12p3r14"))
        val card = HoldTypeStatsComputer.compute("kilter", kilter, yours, mapOf(1 to mix(problems = 0)))!!
        assertEquals(0, card.boardProblems)
        assertTrue(card.rows.all { it.board == null })
        assertNull(HoldTypeStatsComputer.compute("kilter", kilter, listOf(HoldTypeStatsComputer.typedClimb("kilter", 1, "p6r12p7r13")), emptyMap()))
    }

    private fun mix(vararg shares: Pair<HoldType, Double>, problems: Int) =
        HoldMix(shares = HoldTypeStats.HAND_TYPES.associateWith { 0.0 } + shares, problems = problems, countedHolds = 0, handHolds = 0)
}
