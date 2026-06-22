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
        assertEquals("V17", KilterGradeMapper.difficultyToVScale(34))
    }

    @Test
    fun difficultyToVScale_belowMin_returnsV0() {
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(5))
        assertEquals("V0", KilterGradeMapper.difficultyToVScale(0))
    }

    @Test
    fun difficultyToVScale_aboveMax_returnsV17() {
        // The table now tops out at 34 = V17 (9a); anything beyond clamps to V17.
        assertEquals("V17", KilterGradeMapper.difficultyToVScale(40))
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
        assertEquals("9a", KilterGradeMapper.difficultyToFont(34.0))
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
        // 6a is unified index 6. V3 covers difficulties 16-17. With ROUND, range is [15.5, 17.49]
        val v3Min = KilterGradeMapper.indexToFilterMin(6, frenchMode = false)
        val v3Max = KilterGradeMapper.indexToFilterMax(6, frenchMode = false)
        assertTrue(v3Min <= 15.5, "V3 min should be <= 15.5, was $v3Min")
        assertTrue(v3Max >= 17.49, "V3 max should be >= 17.49, was $v3Max")
        assertTrue(v3Max < 17.51, "V3 max should be < 17.51, was $v3Max")
    }

    @Test
    fun filterBoundaries_font_midpoints() {
        // Between 6a (index 6, diff 16) and 6a+ (index 7, diff 17): midpoint = 16.5
        val sixAMax = KilterGradeMapper.indexToFilterMax(6, frenchMode = true)
        val sixAplusMin = KilterGradeMapper.indexToFilterMin(7, frenchMode = true)
        assertTrue(sixAMax < 16.5, "6a max should be < 16.5, was $sixAMax")
        assertTrue(sixAplusMin >= 16.5, "6a+ min should be >= 16.5, was $sixAplusMin")
    }

    // ── Regression coverage for the low-end grade-filter bug ──────────────
    // (4a/5a leaked under "from 4b"/"to 4c" before the scale gained those grades)

    @Test
    fun fontFilter_from4b_excludes4a() {
        // 4b is index 1. A 4a climb (difficulty_average rounds to 10, i.e. < 10.5)
        // must NOT satisfy the lower bound when the user picks "from 4b".
        val min = KilterGradeMapper.indexToFilterMin(1, frenchMode = true)
        assertTrue(min >= 10.5, "from-4b lower bound should exclude 4a (>=10.5), was $min")
    }

    @Test
    fun fontFilter_from4a_isFloorCatchAll() {
        // Index 0 == 4a == the display floor: "no lower bound" sentinel.
        assertEquals(0.0, KilterGradeMapper.indexToFilterMin(0, frenchMode = true))
        assertEquals(0.0, KilterGradeMapper.indexToFilterMin(0, frenchMode = false))
    }

    @Test
    fun vScaleFilter_fromV0_includes4a() {
        // 4a IS V0, so in V-Scale mode "from V0" (index 1 = 4b) must still admit
        // difficulty 10. Lower bound sits at 9.5 (V0's ROUND floor).
        val min = KilterGradeMapper.indexToFilterMin(1, frenchMode = false)
        assertTrue(min <= 9.5, "from-V0 should include 4a (<=9.5), was $min")
    }

    @Test
    fun fontFilter_5aBoundaries_areClean() {
        // 5b is index 4, 4c is index 2; 5a (diff 13) must fall outside both.
        val from5b = KilterGradeMapper.indexToFilterMin(4, frenchMode = true)
        val to4c = KilterGradeMapper.indexToFilterMax(2, frenchMode = true)
        assertTrue(from5b >= 13.5, "from-5b should exclude 5a (>=13.5), was $from5b")
        assertTrue(to4c < 13.0, "to-4c should exclude 5a (<13.0), was $to4c")
    }

    @Test
    fun vScaleFilter_fromV1_includes5a() {
        // 5a IS V1, so "from V1" (index 4 = 5b) must admit difficulty 13.
        val min = KilterGradeMapper.indexToFilterMin(4, frenchMode = false)
        assertTrue(min <= 12.5, "from-V1 should include 5a (<=12.5), was $min")
    }

    @Test
    fun indexDifficulty_floorAndTop() {
        assertEquals(10.0, KilterGradeMapper.indexToDifficulty(0))   // 4a floor
        assertEquals(13.0, KilterGradeMapper.indexToDifficulty(3))   // 5a
        assertEquals(34.0, KilterGradeMapper.indexToDifficulty(24))  // 9a top
        // Top stop is the upper catch-all.
        assertEquals(99.0, KilterGradeMapper.indexToFilterMax(24, frenchMode = true))
    }

    @Test
    fun difficultyToIndex_nearestNeighbour() {
        assertEquals(0, KilterGradeMapper.difficultyToIndex(10.0))   // 4a
        assertEquals(3, KilterGradeMapper.difficultyToIndex(13.0))   // 5a
        assertEquals(6, KilterGradeMapper.difficultyToIndex(16.0))   // 6a
        assertEquals(24, KilterGradeMapper.difficultyToIndex(34.0))  // 9a
    }
}
