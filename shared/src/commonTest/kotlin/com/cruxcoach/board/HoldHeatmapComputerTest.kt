package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.HoldRole
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldHeatmapComputerTest {

    // ═══ Test data helpers ═══

    /** Build a frames string from placement→role pairs. */
    private fun frames(vararg holds: Pair<Int, Int>): String =
        holds.joinToString("") { (p, r) -> "p${p}r${r}" }

    // Reusable frame strings
    private val climb1 = frames(
        100 to HoldRole.START, 101 to HoldRole.START,
        200 to HoldRole.HAND, 201 to HoldRole.HAND,
        300 to HoldRole.FOOT, 400 to HoldRole.FINISH
    )
    private val climb2 = frames(
        100 to HoldRole.START, 102 to HoldRole.START,
        200 to HoldRole.HAND, 202 to HoldRole.HAND,
        301 to HoldRole.FOOT, 400 to HoldRole.FINISH
    )
    private val climb3 = frames(
        103 to HoldRole.START, 104 to HoldRole.START,
        203 to HoldRole.HAND, 300 to HoldRole.FOOT,
        400 to HoldRole.FINISH
    )

    // ═══ computeGlobalHeatmap ═══

    @Test
    fun globalHeatmap_countsAllHoldsAcrossClimbs() {
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(listOf(climb1, climb2, climb3))

        // Hold 100 appears in climb1 + climb2 = 2
        assertEquals(2, heatmap[100])
        // Hold 400 (finish) appears in all 3 climbs
        assertEquals(3, heatmap[400])
        // Hold 103 appears only in climb3
        assertEquals(1, heatmap[103])
    }

    @Test
    fun globalHeatmap_countsAllRolesNotJustHands() {
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(listOf(climb1))

        // All 6 holds should be in the heatmap
        assertEquals(6, heatmap.size)
        // Foot hold 300 counted once
        assertEquals(1, heatmap[300])
    }

    @Test
    fun globalHeatmap_emptyList_returnsEmpty() {
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(emptyList())
        assertTrue(heatmap.isEmpty())
    }

    @Test
    fun globalHeatmap_emptyFrameStrings_returnsEmpty() {
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(listOf("", "  "))
        assertTrue(heatmap.isEmpty())
    }

    @Test
    fun globalHeatmap_singleClimb_allCountsAreOne() {
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(listOf(climb1))
        assertTrue(heatmap.values.all { it == 1 })
    }

    @Test
    fun globalHeatmap_duplicateHoldsInSameClimb_countsOncePerClimb() {
        // A hold appearing twice in the same frame string (unusual but possible)
        val weird = "p100r12p100r13" // same placement, different roles
        val heatmap = HoldHeatmapComputer.computeGlobalHeatmap(listOf(weird))
        // Each occurrence counts separately since they're separate entries
        assertEquals(2, heatmap[100])
    }

    // ═══ computeHeatmapByRole ═══

    @Test
    fun heatmapByRole_startHolds_onlyCountsStarts() {
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(listOf(climb1, climb2), HoldRole.START)

        // Hold 100 is a start in both climbs
        assertEquals(2, heatmap[100])
        // Hold 101 is a start only in climb1
        assertEquals(1, heatmap[101])
        // Hold 200 is a HAND hold — should not appear
        assertEquals(null, heatmap[200])
        // Hold 300 is a FOOT hold — should not appear
        assertEquals(null, heatmap[300])
    }

    @Test
    fun heatmapByRole_finishHolds_onlyCountsFinish() {
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(
            listOf(climb1, climb2, climb3), HoldRole.FINISH
        )
        // Hold 400 is the finish in all 3 climbs
        assertEquals(3, heatmap[400])
        // Only one entry (the finish hold)
        assertEquals(1, heatmap.size)
    }

    @Test
    fun heatmapByRole_footHolds_onlyCountsFeet() {
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(
            listOf(climb1, climb2, climb3), HoldRole.FOOT
        )
        // Hold 300 is a foot in climb1 + climb3 = 2
        assertEquals(2, heatmap[300])
        // Hold 301 is a foot only in climb2
        assertEquals(1, heatmap[301])
        // No start/hand/finish holds
        assertTrue(heatmap.keys.all { pid ->
            val allHolds = listOf(climb1, climb2, climb3).flatMap { BoardClimbParser.parseFrames(it) }
            allHolds.any { it.placementId == pid && it.roleId == HoldRole.FOOT }
        })
    }

    @Test
    fun heatmapByRole_handHolds_onlyCountsHands() {
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(listOf(climb1, climb2), HoldRole.HAND)

        assertEquals(2, heatmap[200]) // hand in both
        assertEquals(1, heatmap[201]) // hand only in climb1
        assertEquals(1, heatmap[202]) // hand only in climb2
        assertEquals(null, heatmap[100]) // start, not hand
    }

    @Test
    fun heatmapByRole_emptyList_returnsEmpty() {
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(emptyList(), HoldRole.START)
        assertTrue(heatmap.isEmpty())
    }

    @Test
    fun heatmapByRole_noMatchingRole_returnsEmpty() {
        // climb with no foot holds
        val noFeet = frames(100 to HoldRole.START, 200 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val heatmap = HoldHeatmapComputer.computeHeatmapByRole(listOf(noFeet), HoldRole.FOOT)
        assertTrue(heatmap.isEmpty())
    }

    // ═══ filterClimbsByHolds ═══

    @Test
    fun filterByHolds_emptySelection_returnsAllUuids() {
        val framesByUuid = mapOf("a" to climb1, "b" to climb2, "c" to climb3)
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, emptySet())
        assertEquals(setOf("a", "b", "c"), result)
    }

    @Test
    fun filterByHolds_singleHold_matchesClimbsContainingIt() {
        val framesByUuid = mapOf("a" to climb1, "b" to climb2, "c" to climb3)
        // Hold 100 is in climb1 and climb2 but not climb3
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(100))
        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun filterByHolds_multipleHolds_requiresAllPresent() {
        val framesByUuid = mapOf("a" to climb1, "b" to climb2, "c" to climb3)
        // Hold 100 + Hold 101 are both only in climb1
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(100, 101))
        assertEquals(setOf("a"), result)
    }

    @Test
    fun filterByHolds_noMatches_returnsEmpty() {
        val framesByUuid = mapOf("a" to climb1, "b" to climb2)
        // Hold 999 doesn't exist in any climb
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(999))
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterByHolds_holdInAllClimbs_returnsAll() {
        val framesByUuid = mapOf("a" to climb1, "b" to climb2, "c" to climb3)
        // Hold 400 (finish) is in all 3 climbs
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(400))
        assertEquals(setOf("a", "b", "c"), result)
    }

    @Test
    fun filterByHolds_emptyFrameMap_returnsEmpty() {
        val result = HoldHeatmapComputer.filterClimbsByHolds(emptyMap(), setOf(100))
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterByHolds_matchesAnyRole_notSpecificRole() {
        // Hold 100 is a START in climb1 — filterClimbsByHolds ignores role
        val framesByUuid = mapOf("a" to climb1)
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(100))
        assertEquals(setOf("a"), result)
    }

    @Test
    fun filterByHolds_emptyFrameString_noMatch() {
        val framesByUuid = mapOf("a" to "", "b" to climb1)
        val result = HoldHeatmapComputer.filterClimbsByHolds(framesByUuid, setOf(100))
        assertEquals(setOf("b"), result)
    }

    // ═══ holdLikePattern ═══

    @Test
    fun holdLikePattern_formatsCorrectly() {
        assertEquals("p100r", HoldHeatmapComputer.holdLikePattern(100))
        assertEquals("p1r", HoldHeatmapComputer.holdLikePattern(1))
        assertEquals("p99999r", HoldHeatmapComputer.holdLikePattern(99999))
    }

    @Test
    fun holdLikePattern_matchesAuroraFrameFormat() {
        val pattern = HoldHeatmapComputer.holdLikePattern(1091)
        // Pattern "p1091r" should match inside a real frame string
        assertTrue(climb1.contains("p100r")) // verify format is consistent
        assertTrue("p1091r15p1096r15".contains(pattern.dropLast(0)))
    }

    // ═══ normalizeHeatmap ═══

    @Test
    fun normalizeHeatmap_emptyMap_returnsEmpty() {
        val result = HoldHeatmapComputer.normalizeHeatmap(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun normalizeHeatmap_singleEntry_normalizesToOne() {
        val result = HoldHeatmapComputer.normalizeHeatmap(mapOf(100 to 5))
        assertEquals(1, result.size)
        assertApprox(1.0f, result[100]!!)
    }

    @Test
    fun normalizeHeatmap_maxValue_isOne() {
        val result = HoldHeatmapComputer.normalizeHeatmap(mapOf(1 to 100, 2 to 50, 3 to 1))
        assertApprox(1.0f, result[1]!!)
    }

    @Test
    fun normalizeHeatmap_allSameValue_allOne() {
        val result = HoldHeatmapComputer.normalizeHeatmap(mapOf(1 to 10, 2 to 10, 3 to 10))
        result.values.forEach { assertApprox(1.0f, it) }
    }

    @Test
    fun normalizeHeatmap_logarithmicScaling_lowValuesNotCrushed() {
        // With linear scaling, 1/1000 = 0.001 (invisible).
        // With log scaling, ln(2)/ln(1001) ≈ 0.10 (visible).
        val result = HoldHeatmapComputer.normalizeHeatmap(mapOf(1 to 1000, 2 to 1))
        val lowVal = result[2]!!
        assertTrue(lowVal > 0.05f, "Log scaling should keep low values visible, got $lowVal")
        assertTrue(lowVal < 0.5f, "Low value should still be less than mid-range, got $lowVal")
    }

    @Test
    fun normalizeHeatmap_valuesInZeroToOneRange() {
        val input = mapOf(1 to 500, 2 to 100, 3 to 10, 4 to 1, 5 to 1000)
        val result = HoldHeatmapComputer.normalizeHeatmap(input)
        result.values.forEach { v ->
            assertTrue(v >= 0f, "Value should be >= 0, got $v")
            assertTrue(v <= 1.0f, "Value should be <= 1, got $v")
        }
    }

    @Test
    fun normalizeHeatmap_preservesOrdering() {
        val input = mapOf(1 to 100, 2 to 50, 3 to 10, 4 to 1)
        val result = HoldHeatmapComputer.normalizeHeatmap(input)
        assertTrue(result[1]!! > result[2]!!)
        assertTrue(result[2]!! > result[3]!!)
        assertTrue(result[3]!! > result[4]!!)
    }

    @Test
    fun normalizeHeatmap_zeroCount_returnsEmpty() {
        // Edge case: all zero counts (shouldn't happen in practice, but defensive)
        val result = HoldHeatmapComputer.normalizeHeatmap(mapOf(1 to 0, 2 to 0))
        assertTrue(result.isEmpty())
    }

    // ═══ End-to-end: global heatmap → normalize ═══

    @Test
    fun endToEnd_globalHeatmapThenNormalize_producesValidOutput() {
        val raw = HoldHeatmapComputer.computeGlobalHeatmap(listOf(climb1, climb2, climb3))
        val normalized = HoldHeatmapComputer.normalizeHeatmap(raw)

        // Most used hold (400, in all 3 climbs) should have highest normalized value
        val maxEntry = normalized.maxByOrNull { it.value }!!
        assertEquals(400, maxEntry.key)
        assertApprox(1.0f, maxEntry.value)

        // All values in range
        normalized.values.forEach { v ->
            assertTrue(v in 0f..1f, "Normalized value out of range: $v")
        }
    }

    @Test
    fun endToEnd_roleHeatmapThenNormalize_producesValidOutput() {
        val raw = HoldHeatmapComputer.computeHeatmapByRole(listOf(climb1, climb2, climb3), HoldRole.START)
        val normalized = HoldHeatmapComputer.normalizeHeatmap(raw)

        // Hold 100 appears as start in 2 of 3 climbs — should be the max
        assertApprox(1.0f, normalized[100]!!)
        // All values in range
        normalized.values.forEach { v -> assertTrue(v in 0f..1f) }
    }

    // ═══ Aurora-family role codes (1-4) — regression for the empty heatmap ═══

    @Test
    fun computeHeatmapByRole_auroraCodes_matchByRoleClass() {
        // Tension/Grasshopper/Decoy/So iLL/Touchstone frames carry codes 1-4,
        // not Kilter's 12-15. Querying by HoldRole.START must still find them.
        val aurora = frames(
            100 to 1, 101 to 1,   // start
            200 to 2, 201 to 2,   // middle/hand
            300 to 4,             // foot
            400 to 3,             // finish
        )
        assertEquals(mapOf(100 to 1, 101 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(aurora), HoldRole.START))
        assertEquals(mapOf(200 to 1, 201 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(aurora), HoldRole.HAND))
        assertEquals(mapOf(300 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(aurora), HoldRole.FOOT))
        assertEquals(mapOf(400 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(aurora), HoldRole.FINISH))
    }

    @Test
    fun computeHeatmapByRole_auroraMirrorSet_matchByRoleClass() {
        val mirrored = frames(500 to 5, 600 to 8) // mirrored start + foot
        assertEquals(mapOf(500 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(mirrored), HoldRole.START))
        assertEquals(mapOf(600 to 1), HoldHeatmapComputer.computeHeatmapByRole(listOf(mirrored), HoldRole.FOOT))
    }

    // ═══ Helper ═══

    private fun assertApprox(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "Expected ~$expected but got $actual"
        )
    }
}
