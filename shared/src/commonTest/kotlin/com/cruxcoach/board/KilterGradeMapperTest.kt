package com.cruxcoach.board

import com.cruxcoach.domain.board.KilterGradeMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KilterGradeMapperTest {

    @Test
    fun difficultyToVScale_knownValues() {
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(10))
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(12))
        assertEquals("V1", KilterGradeMapper.difficultyToVScale(13))
        assertEquals("V3", KilterGradeMapper.difficultyToVScale(16))
        assertEquals("V5", KilterGradeMapper.difficultyToVScale(20))
        assertEquals("V6", KilterGradeMapper.difficultyToVScale(22))
        assertEquals("V8", KilterGradeMapper.difficultyToVScale(24))
        assertEquals("V10", KilterGradeMapper.difficultyToVScale(27))
        assertEquals("V13", KilterGradeMapper.difficultyToVScale(30))
        assertEquals("V16", KilterGradeMapper.difficultyToVScale(33))
    }

    @Test
    fun difficultyToVScale_belowMin_returnsV0() {
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(5))
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(0))
    }

    @Test
    fun difficultyToVScale_aboveMax_returnsV16() {
        assertEquals("V16", KilterGradeMapper.difficultyToVScale(40))
    }

    @Test
    fun difficultyToVScale_doubleUsesRound() {
        // ROUND(20.5) = 21 → V5, ROUND(20.4) = 20 → V5
        assertEquals("V5", KilterGradeMapper.difficultyToVScale(20.5))
        assertEquals("V5", KilterGradeMapper.difficultyToVScale(20.4))
        // ROUND(21.5) = 22 → V6 (boundary between V5 and V6)
        assertEquals("V6", KilterGradeMapper.difficultyToVScale(21.5))
        assertEquals("V6", KilterGradeMapper.difficultyToVScale(22.3))
        // ROUND(17.5) = 18 → V4 (not V3!)
        assertEquals("V4", KilterGradeMapper.difficultyToVScale(17.5))
        assertEquals("V3", KilterGradeMapper.difficultyToVScale(17.4))
    }

    @Test
    fun difficultyToFont_matchesKilterDb() {
        assertEquals("4a", KilterGradeMapper.difficultyToFont(10.0))
        assertEquals("4b", KilterGradeMapper.difficultyToFont(11.0))
        assertEquals("4c", KilterGradeMapper.difficultyToFont(12.0))
        assertEquals("5a", KilterGradeMapper.difficultyToFont(13.0))
        assertEquals("6a", KilterGradeMapper.difficultyToFont(16.0))
        assertEquals("6a+", KilterGradeMapper.difficultyToFont(17.0))
        assertEquals("6b", KilterGradeMapper.difficultyToFont(18.0))
        assertEquals("6c", KilterGradeMapper.difficultyToFont(20.0))
        assertEquals("6c+", KilterGradeMapper.difficultyToFont(21.0))
        assertEquals("7a", KilterGradeMapper.difficultyToFont(22.0))
        assertEquals("8c+", KilterGradeMapper.difficultyToFont(33.0))
    }

    @Test
    fun difficultyToFont_roundsCorrectly() {
        // ROUND(16.4) = 16 → 6a, ROUND(16.5) = 17 → 6a+
        assertEquals("6a", KilterGradeMapper.difficultyToFont(16.4))
        assertEquals("6a+", KilterGradeMapper.difficultyToFont(16.5))
        // ROUND(20.5) = 21 → 6c+
        assertEquals("6c+", KilterGradeMapper.difficultyToFont(20.5))
    }

    @Test
    fun vScaleToDifficulty_roundtrip() {
        for (grade in listOf("V0", "V3", "V5", "V8", "V10", "V13", "V16")) {
            val difficulty = KilterGradeMapper.vScaleToDifficulty(grade)
            val back = KilterGradeMapper.difficultyToVScale(difficulty)
            assertEquals(grade, back, "Roundtrip failed for $grade")
        }
    }

    @Test
    fun gradeToRange_returnsReasonableRange() {
        val (min, max) = KilterGradeMapper.gradeToRange("V5")
        assertTrue(min < max)
        assertTrue(min >= 19.0)
        assertTrue(max <= 23.0)
    }

    @Test
    fun formatGrade_includesVScaleAndDifficulty() {
        val result = KilterGradeMapper.formatGrade(22.3)
        assertTrue(result.contains("V6"))
        // Handle locale differences (22.3 or 22,3)
        assertTrue(result.contains("22"))
    }

    @Test
    fun filterBoundaries_vScale_matchRound() {
        // V3 covers difficulties 16-17. With ROUND, range is [15.5, 17.49]
        val v3Min = KilterGradeMapper.indexToFilterMin(4, frenchMode = false)
        val v3Max = KilterGradeMapper.indexToFilterMax(4, frenchMode = false)
        assertTrue(v3Min <= 15.5, "V3 min should be <= 15.5, was $v3Min")
        assertTrue(v3Max >= 17.49, "V3 max should be >= 17.49, was $v3Max")
        assertTrue(v3Max < 17.51, "V3 max should be < 17.51, was $v3Max")
    }

    @Test
    fun filterBoundaries_font_midpoints() {
        // Between 6a (diff 16) and 6a+ (diff 17): midpoint = 16.5
        val sixAMax = KilterGradeMapper.indexToFilterMax(4, frenchMode = true)
        val sixAplusMin = KilterGradeMapper.indexToFilterMin(5, frenchMode = true)
        assertTrue(sixAMax < 16.5, "6a max should be < 16.5, was $sixAMax")
        assertTrue(sixAplusMin >= 16.5, "6a+ min should be >= 16.5, was $sixAplusMin")
    }
}
