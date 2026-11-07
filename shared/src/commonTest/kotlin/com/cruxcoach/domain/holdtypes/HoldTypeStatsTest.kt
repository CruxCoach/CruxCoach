package com.cruxcoach.domain.holdtypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoldTypeStatsTest {

    private fun hold(placement: Int, type: String?, conf: Double = 0.5, footOnly: Boolean = false, layout: Int = 1) =
        HoldTypeEntry(
            placement = placement, layoutId = layout, setId = 1, type = type, footOnly = footOnly,
            confidence = HoldTypeConfidence(type = conf, direction = 0.5),
        )

    private val kilter = HoldTypeDataset(
        format = HoldTypeDataset.FORMAT, board = "kilter", version = "v1",
        holds = listOf(
            hold(1, "jug"), hold(2, "jug"), hold(3, "edge"), hold(4, "edge"), hold(5, "sloper"),
            hold(6, "pinch", conf = 0.3),          // too unsure to count
            hold(7, "foot_chip"),                  // a foot chip is never a hand type
            hold(8, "edge", footOnly = true),      // foot only
            hold(9, null),                         // no picture
        ),
    )

    private val moon = HoldTypeDataset(
        format = HoldTypeDataset.FORMAT, board = "moonboard", version = "v1",
        holds = listOf(hold(5, "sloper", layout = 2), hold(5, "pocket", layout = 4)),
    )

    private val datasets = mapOf("kilter" to kilter, "moonboard" to moon)

    @Test
    fun framesGiveHandHoldsWithoutFootRoles() {
        assertEquals(listOf(1, 2, 3), HoldTypeStats.handHolds("p1r12p2r13p3r14p4r15", footRoles = setOf(15, 45)))
        assertEquals(listOf(1), HoldTypeStats.handHolds("p1r13p1r13", footRoles = setOf(15)))
    }

    @Test
    fun onlyConfidentHandTypesCount() {
        val climb = TypedClimb("kilter", 1, handHolds = (1..9).toList())
        assertEquals(
            listOf(HoldType.JUG, HoldType.JUG, HoldType.EDGE, HoldType.EDGE, HoldType.SLOPER),
            HoldTypeStats.countedTypes(climb, kilter),
        )
    }

    @Test
    fun mixWeightsEveryProblemEqually() {
        val mix = HoldTypeStats.holdMix(
            listOf(
                TypedClimb("kilter", 1, listOf(1, 2, 3, 4)),   // half jug, half edge
                TypedClimb("kilter", 1, listOf(5)),            // all sloper
                TypedClimb("kilter", 1, listOf(9)),            // nothing counted: left out
            ),
            datasets,
        )
        assertEquals(2, mix.problems)
        assertEquals(5, mix.countedHolds)
        assertEquals(6, mix.handHolds)
        assertEquals(0.25, mix.share(HoldType.JUG))
        assertEquals(0.25, mix.share(HoldType.EDGE))
        assertEquals(0.5, mix.share(HoldType.SLOPER))
        assertEquals(1.0, HoldTypeStats.HAND_TYPES.sumOf { mix.share(it) }, 1e-9)
    }

    @Test
    fun moonBoardHoldsAreLookedUpInTheirGeneration() {
        val mix = HoldTypeStats.holdMix(
            listOf(TypedClimb("moonboard", 2, listOf(5)), TypedClimb("moonboard", 4, listOf(5))),
            datasets,
        )
        assertEquals(0.5, mix.share(HoldType.SLOPER))
        assertEquals(0.5, mix.share(HoldType.POCKET))
    }

    @Test
    fun compareGivesPercentagePoints() {
        val yours = HoldTypeStats.holdMix(listOf(TypedClimb("kilter", 1, listOf(5))), datasets)
        val board = HoldTypeStats.holdMix(listOf(TypedClimb("kilter", 1, listOf(1, 5))), datasets)
        val sloper = HoldTypeStats.compareMix(yours, board).first { it.type == HoldType.SLOPER }
        assertEquals(50.0, sloper.points, 1e-9)
    }

    @Test
    fun strengthComparesDominatedProblemsWithAll() {
        fun jugs(sent: Boolean, grade: Double) = TypedClimb("kilter", 1, listOf(1, 2, 3), sent = sent, grade = grade)
        fun slopers(sent: Boolean) = TypedClimb("kilter", 1, listOf(5, 5, 3), sent = sent, grade = 20.0)
        val tried = List(6) { jugs(sent = true, grade = 18.0 + it) } + List(6) { slopers(sent = it == 0) }
        val result = HoldTypeStats.typeStrengths(tried, kilter)
        assertEquals(12, result.overall.problems)
        assertEquals(7.0 / 12, result.overall.sendRate!!, 1e-9)
        val jug = result.types.first { it.type == HoldType.JUG }
        assertEquals(6, jug.level.problems)
        assertEquals(23.0, jug.level.hardestSend)
        assertEquals(HoldTypeStats.Strength.STRONG, jug.strength)
        val sloper = result.types.first { it.type == HoldType.SLOPER }
        assertEquals(HoldTypeStats.Strength.WEAK, sloper.strength)
        // edges make up a third of every problem: below the 40 % that makes a type dominate
        val edge = result.types.first { it.type == HoldType.EDGE }
        assertEquals(0, edge.level.problems)
        assertEquals(HoldTypeStats.Strength.TOO_FEW, edge.strength)
        assertNull(edge.level.sendRate)
    }
}
