package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardAnalyzer
import com.cruxcoach.domain.board.BoardAnalyzer.AscentData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardAnalyzerTest {

    private fun makeAscent(
        angle: Int = 40,
        difficulty: Double = 20.0,
        quality: Int = 3,
        bidCount: Int = 3,
        frames: String = "p100r12p200r13p300r14"
    ) = AscentData(
        climbUuid = "uuid-${angle}-${difficulty.toInt()}",
        angle = angle,
        difficulty = difficulty,
        quality = quality,
        bidCount = bidCount,
        frames = frames
    )

    @Test
    fun emptyAscentsReturnEmptyResult() {
        val result = BoardAnalyzer.analyzeFromAscents(emptyList())
        assertEquals(0, result.totalAscents)
        assertEquals(0, result.totalAttempts)
        assertTrue(result.maxGradeByAngle.isEmpty())
        assertEquals(0f, result.sandbagScore)
    }

    @Test
    fun totalAscentsAndAttempts() {
        val ascents = listOf(
            makeAscent(bidCount = 3),
            makeAscent(bidCount = 5),
            makeAscent(bidCount = 1)
        )
        val result = BoardAnalyzer.analyzeFromAscents(ascents)
        assertEquals(3, result.totalAscents)
        assertEquals(9, result.totalAttempts)
    }

    @Test
    fun maxGradeByAngle() {
        val ascents = listOf(
            makeAscent(angle = 40, difficulty = 18.0),
            makeAscent(angle = 40, difficulty = 22.0),
            makeAscent(angle = 25, difficulty = 15.0)
        )
        val result = BoardAnalyzer.analyzeFromAscents(ascents)
        assertEquals("V6", result.maxGradeByAngle[40])
        assertEquals("V2", result.maxGradeByAngle[25])
    }

    @Test
    fun sandbagScoreNeutralForAverageClimber() {
        val ascents = listOf(
            makeAscent(quality = 3, bidCount = 3),
            makeAscent(quality = 3, bidCount = 3)
        )
        val score = BoardAnalyzer.calculateSandbagScore(ascents)
        assertEquals(0f, score, 0.01f)
    }

    @Test
    fun sandbagScorePositiveForManyAttempts() {
        val ascents = listOf(
            makeAscent(quality = 2, bidCount = 10),
            makeAscent(quality = 2, bidCount = 8)
        )
        val score = BoardAnalyzer.calculateSandbagScore(ascents)
        assertTrue(score > 0, "Sandbag score should be positive for high bid counts")
    }

    @Test
    fun sandbagScoreNegativeForFlashes() {
        // bidCount=1 → attemptFactor = (1-3)/3 = -0.67 (less attempts than avg)
        // quality=5 → qualityFactor = (5-3)/2 = +1.0 (high quality = finds grade appropriate)
        // Sum = -0.67 + 1.0 = +0.33 → actually positive because high quality offsets
        // Use low quality + few attempts: both factors negative
        val ascents = listOf(
            makeAscent(quality = 1, bidCount = 1),
            makeAscent(quality = 1, bidCount = 1)
        )
        val score = BoardAnalyzer.calculateSandbagScore(ascents)
        assertTrue(score < 0, "Sandbag score should be negative for flashes with low quality")
    }

    @Test
    fun holdHeatmapCountsCorrectly() {
        val ascents = listOf(
            makeAscent(frames = "p100r13p200r13"),
            makeAscent(frames = "p100r13p300r14")
        )
        val heatmap = BoardAnalyzer.computeHoldHeatmap(ascents)
        assertEquals(2, heatmap[100]) // appears in both ascents
        assertEquals(1, heatmap[200]) // only in first
        assertEquals(1, heatmap[300]) // only in second
    }

    @Test
    fun weaknessProfileDetectsOverhangWeakness() {
        val ascents = listOf(
            makeAscent(angle = 15, difficulty = 25.0),
            makeAscent(angle = 20, difficulty = 24.0),
            makeAscent(angle = 45, difficulty = 18.0),
            makeAscent(angle = 50, difficulty = 17.0)
        )
        val weaknesses = BoardAnalyzer.getWeaknessProfile(ascents)
        assertTrue(
            weaknesses.any { it.contains("Überhang-Schwäche") },
            "Should detect overhang weakness when steep grades << slab grades"
        )
    }

    @Test
    fun weaknessProfileDetectsSlabWeakness() {
        val ascents = listOf(
            makeAscent(angle = 15, difficulty = 16.0),
            makeAscent(angle = 20, difficulty = 17.0),
            makeAscent(angle = 45, difficulty = 25.0),
            makeAscent(angle = 50, difficulty = 24.0)
        )
        val weaknesses = BoardAnalyzer.getWeaknessProfile(ascents)
        assertTrue(
            weaknesses.any { it.contains("Platten-Schwäche") },
            "Should detect slab weakness when slab grades << steep grades"
        )
    }

    @Test
    fun emptyAscentsYieldNoWeaknesses() {
        val weaknesses = BoardAnalyzer.getWeaknessProfile(emptyList())
        assertTrue(weaknesses.isEmpty())
    }
}
