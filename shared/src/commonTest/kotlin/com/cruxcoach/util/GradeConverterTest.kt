package com.cruxcoach.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GradeConverterTest {

    @Test
    fun vScaleToNumeric_returnsCorrectValues() {
        assertEquals(0, GradeConverter.vScaleToNumeric("V0"))
        assertEquals(5, GradeConverter.vScaleToNumeric("V5"))
        assertEquals(10, GradeConverter.vScaleToNumeric("V10"))
        assertEquals(17, GradeConverter.vScaleToNumeric("V17"))
    }

    @Test
    fun vScaleToNumeric_handlesFrenchGrades() {
        assertEquals(0, GradeConverter.vScaleToNumeric("4b"))   // 4b = V0
        assertEquals(3, GradeConverter.vScaleToNumeric("6a"))   // 6a = V3
        assertEquals(5, GradeConverter.vScaleToNumeric("6c"))   // 6c = V5
        assertEquals(7, GradeConverter.vScaleToNumeric("7a+"))  // 7a+ = V7
    }

    @Test
    fun vScaleToNumeric_handlesIntermediateFrenchGrades() {
        // Intermediate FB grades round down to nearest V-Scale
        assertEquals(0, GradeConverter.vScaleToNumeric("4a"))   // 4a → V0 floor → V0 = 0
        assertEquals(0, GradeConverter.vScaleToNumeric("4c"))   // 4c → V0 intermediate → V0 = 0
        assertEquals(3, GradeConverter.vScaleToNumeric("6a+"))  // 6a+ → V3 intermediate → V3 = 3
        assertEquals(4, GradeConverter.vScaleToNumeric("6b+"))  // 6b+ → V4 intermediate → V4 = 4
        assertEquals(5, GradeConverter.vScaleToNumeric("6c+"))  // 6c+ → V5 intermediate → V5 = 5
        assertEquals(7, GradeConverter.vScaleToNumeric("7b"))   // 7b → V8 intermediate → V7 = 7
    }

    @Test
    fun vScaleToNumeric_returnsMinusOneForInvalid() {
        assertEquals(-1, GradeConverter.vScaleToNumeric("V18"))
        assertEquals(-1, GradeConverter.vScaleToNumeric(""))
        assertEquals(-1, GradeConverter.vScaleToNumeric("xyz"))
    }

    @Test
    fun numericToVScale_returnsCorrectGrade() {
        assertEquals("V0", GradeConverter.numericToVScale(0))
        assertEquals("V7", GradeConverter.numericToVScale(7))
        assertEquals("V17", GradeConverter.numericToVScale(17))
    }

    @Test
    fun numericToVScale_clampsOutOfRange() {
        assertEquals("V0", GradeConverter.numericToVScale(-1))
        assertEquals("V0", GradeConverter.numericToVScale(18))
    }

    @Test
    fun vScaleToFrench_mapsCorrectly() {
        assertEquals("4b", GradeConverter.vScaleToFrench("V0"))
        assertEquals("6c", GradeConverter.vScaleToFrench("V5"))
        assertEquals("7a+", GradeConverter.vScaleToFrench("V7"))
        assertEquals("8a", GradeConverter.vScaleToFrench("V11"))
    }

    @Test
    fun frenchToVScale_mapsCorrectly() {
        assertEquals("V0", GradeConverter.frenchToVScale("4b"))
        assertEquals("V5", GradeConverter.frenchToVScale("6c"))
        assertEquals("V7", GradeConverter.frenchToVScale("7a+"))
    }

    @Test
    fun frenchToVScale_handlesIntermediateGrades() {
        assertEquals("V0", GradeConverter.frenchToVScale("4c"))   // rounds down
        assertEquals("V3", GradeConverter.frenchToVScale("6a+"))
        assertEquals("V4", GradeConverter.frenchToVScale("6b+"))
        assertEquals("V5", GradeConverter.frenchToVScale("6c+"))
        assertEquals("V7", GradeConverter.frenchToVScale("7b"))
    }

    @Test
    fun isValidVScale_recognizesValidGrades() {
        assertTrue(GradeConverter.isValidVScale("V0"))
        assertTrue(GradeConverter.isValidVScale("V10"))
        assertTrue(GradeConverter.isValidVScale("V17"))
    }

    @Test
    fun isValidVScale_rejectsInvalidGrades() {
        assertFalse(GradeConverter.isValidVScale("V18"))
        assertFalse(GradeConverter.isValidVScale("6a"))  // FB grade, not V-Scale
        assertFalse(GradeConverter.isValidVScale(""))
    }

    @Test
    fun isValidGrade_recognizesBothFormats() {
        assertTrue(GradeConverter.isValidGrade("V5"))
        assertTrue(GradeConverter.isValidGrade("6c"))
        assertTrue(GradeConverter.isValidGrade("4a"))    // new low Font grade
        assertTrue(GradeConverter.isValidGrade("5a"))    // new low Font grade
        assertTrue(GradeConverter.isValidGrade("6a+"))   // intermediate FB
        assertTrue(GradeConverter.isValidGrade("7b"))    // intermediate FB
        assertFalse(GradeConverter.isValidGrade("xyz"))
        assertFalse(GradeConverter.isValidGrade(""))
    }

    @Test
    fun lowerGrade_reducesCorrectly() {
        assertEquals("V3", GradeConverter.lowerGrade("V5", 2))
        assertEquals("V0", GradeConverter.lowerGrade("V2", 5))
        assertEquals("V0", GradeConverter.lowerGrade("V0", 1))
    }

    @Test
    fun higherGrade_increasesCorrectly() {
        assertEquals("V7", GradeConverter.higherGrade("V5", 2))
        assertEquals("V17", GradeConverter.higherGrade("V15", 5))
    }

    @Test
    fun compareGrades_worksCorrectly() {
        assertTrue(GradeConverter.compareGrades("V5", "V3") > 0)
        assertTrue(GradeConverter.compareGrades("V3", "V5") < 0)
        assertEquals(0, GradeConverter.compareGrades("V5", "V5"))
    }

    @Test
    fun compareGrades_worksCrossFormat() {
        // V5 = 6c (index 10), V3 = 6a (index 6)
        assertTrue(GradeConverter.compareGrades("6c", "V3") > 0)
        assertTrue(GradeConverter.compareGrades("V3", "6c") < 0)
        assertEquals(0, GradeConverter.compareGrades("V5", "6c"))  // same grade
    }

    @Test
    fun compareGrades_intermediateGradesBetweenVScales() {
        // 6a+ (index 7) is between V3=6a (index 6) and V4=6b (index 8)
        assertTrue(GradeConverter.compareGrades("6a+", "6a") > 0)
        assertTrue(GradeConverter.compareGrades("6a+", "6b") < 0)
    }

    @Test
    fun compareGrades_newLowGradesOrderBelow4b() {
        // 4a < 4b < 4c < 5a < 5b — the contiguous low end
        assertTrue(GradeConverter.compareGrades("4a", "4b") < 0)
        assertTrue(GradeConverter.compareGrades("4c", "5a") < 0)
        assertTrue(GradeConverter.compareGrades("5a", "5b") < 0)
    }

    @Test
    fun allVScaleGrades_returnsAll18Grades() {
        val grades = GradeConverter.allVScaleGrades()
        assertEquals(18, grades.size)
        assertEquals("V0", grades.first())
        assertEquals("V17", grades.last())
    }

    @Test
    fun allFrenchGrades_returnsAll25Grades() {
        val grades = GradeConverter.allFrenchGrades()
        assertEquals(25, grades.size)
        assertEquals("4a", grades.first())
        assertEquals("9a", grades.last())
        // Check the newly added low grades + some intermediates are present
        assertTrue("4a" in grades)
        assertTrue("5a" in grades)
        assertTrue("6a+" in grades)
        assertTrue("6b+" in grades)
        assertTrue("6c+" in grades)
        assertTrue("4c" in grades)
        assertTrue("7b" in grades)
    }

    @Test
    fun roundTrip_numericToVScaleAndBack() {
        for (i in 0..17) {
            val grade = GradeConverter.numericToVScale(i)
            assertEquals(i, GradeConverter.vScaleToNumeric(grade))
        }
    }

    // --- Unified index tests ---

    @Test
    fun gradeToIndex_vScaleInput() {
        assertEquals(1, GradeConverter.gradeToIndex("V0"))   // V0 stop = 4b @ index 1
        assertEquals(10, GradeConverter.gradeToIndex("V5"))
        assertEquals(24, GradeConverter.gradeToIndex("V17"))
    }

    @Test
    fun gradeToIndex_frenchInput() {
        assertEquals(0, GradeConverter.gradeToIndex("4a"))
        assertEquals(1, GradeConverter.gradeToIndex("4b"))
        assertEquals(2, GradeConverter.gradeToIndex("4c"))
        assertEquals(3, GradeConverter.gradeToIndex("5a"))
        assertEquals(6, GradeConverter.gradeToIndex("6a"))
        assertEquals(7, GradeConverter.gradeToIndex("6a+"))
        assertEquals(10, GradeConverter.gradeToIndex("6c"))
        assertEquals(14, GradeConverter.gradeToIndex("7b"))
    }

    @Test
    fun gradeToIndex_invalidReturnsMinusOne() {
        assertEquals(-1, GradeConverter.gradeToIndex("xyz"))
        assertEquals(-1, GradeConverter.gradeToIndex(""))
    }

    @Test
    fun indexToFrench_allIndices() {
        assertEquals("4a", GradeConverter.indexToFrench(0))
        assertEquals("4b", GradeConverter.indexToFrench(1))
        assertEquals("4c", GradeConverter.indexToFrench(2))
        assertEquals("5a", GradeConverter.indexToFrench(3))
        assertEquals("5b", GradeConverter.indexToFrench(4))
        assertEquals("6a", GradeConverter.indexToFrench(6))
        assertEquals("6a+", GradeConverter.indexToFrench(7))
        assertEquals("7b", GradeConverter.indexToFrench(14))
        assertEquals("9a", GradeConverter.indexToFrench(24))
    }

    @Test
    fun indexToVScale_directMapping() {
        assertEquals("V0", GradeConverter.indexToVScale(1))   // 4b
        assertEquals("V1", GradeConverter.indexToVScale(4))   // 5b
        assertEquals("V3", GradeConverter.indexToVScale(6))   // 6a
        assertEquals("V5", GradeConverter.indexToVScale(10))  // 6c
        assertEquals("V17", GradeConverter.indexToVScale(24)) // 9a
    }

    @Test
    fun indexToVScale_intermediateRoundsDown() {
        assertEquals("V0", GradeConverter.indexToVScale(0))   // 4a (floor) → V0
        assertEquals("V0", GradeConverter.indexToVScale(2))   // 4c → V0
        assertEquals("V3", GradeConverter.indexToVScale(7))   // 6a+ → V3
        assertEquals("V4", GradeConverter.indexToVScale(9))   // 6b+ → V4
        assertEquals("V5", GradeConverter.indexToVScale(11))  // 6c+ → V5
        assertEquals("V7", GradeConverter.indexToVScale(14))  // 7b → V7
    }

    @Test
    fun isIntermediate_correctlyIdentifies() {
        assertTrue(GradeConverter.isIntermediate(0))    // 4a (floor, below V0 stop)
        assertFalse(GradeConverter.isIntermediate(1))   // 4b = V0
        assertTrue(GradeConverter.isIntermediate(2))    // 4c
        assertTrue(GradeConverter.isIntermediate(3))    // 5a
        assertFalse(GradeConverter.isIntermediate(4))   // 5b = V1
        assertFalse(GradeConverter.isIntermediate(6))   // 6a = V3
        assertTrue(GradeConverter.isIntermediate(7))    // 6a+
        assertTrue(GradeConverter.isIntermediate(14))   // 7b
        assertFalse(GradeConverter.isIntermediate(15))  // 7b+ = V8
    }

    @Test
    fun nextIndex_frenchMode_stepsOneByOne() {
        assertEquals(1, GradeConverter.nextIndex(0, frenchMode = true))
        assertEquals(5, GradeConverter.nextIndex(4, frenchMode = true))
        assertEquals(24, GradeConverter.nextIndex(24, frenchMode = true))  // stays at max
    }

    @Test
    fun nextIndex_vScaleMode_skipsIntermediates() {
        // From V0 (index 1) → V1 (index 4), skipping 4c (2) and 5a (3)
        assertEquals(4, GradeConverter.nextIndex(1, frenchMode = false))
        // From V3 (index 6) → V4 (index 8), skipping 6a+ (index 7)
        assertEquals(8, GradeConverter.nextIndex(6, frenchMode = false))
        // From V7 (index 13) → V8 (index 15), skipping 7b (index 14)
        assertEquals(15, GradeConverter.nextIndex(13, frenchMode = false))
    }

    @Test
    fun prevIndex_frenchMode_stepsOneByOne() {
        assertEquals(0, GradeConverter.prevIndex(1, frenchMode = true))
        assertEquals(4, GradeConverter.prevIndex(5, frenchMode = true))
        assertEquals(0, GradeConverter.prevIndex(0, frenchMode = true))  // stays at min
    }

    @Test
    fun prevIndex_vScaleMode_skipsIntermediates() {
        // From V1 (index 4) → V0 (index 1), skipping 5a (3) and 4c (2)
        assertEquals(1, GradeConverter.prevIndex(4, frenchMode = false))
        // From V4 (index 8) → V3 (index 6), skipping 6b+ (index... ) / 6a+ (7)
        assertEquals(6, GradeConverter.prevIndex(8, frenchMode = false))
    }

    @Test
    fun oldNumericToIndex_andBack() {
        for (i in 0..17) {
            val index = GradeConverter.oldNumericToIndex(i)
            val backToNumeric = GradeConverter.indexToOldNumeric(index)
            assertEquals(i, backToNumeric, "Round-trip failed for old numeric $i")
        }
    }

    @Test
    fun vScaleIndices_has18Entries() {
        assertEquals(18, GradeConverter.V_SCALE_INDICES.size)
        assertEquals(1, GradeConverter.V_SCALE_INDICES.first())   // V0 stop = 4b @ index 1
        assertEquals(24, GradeConverter.V_SCALE_INDICES.last())   // V17 = 9a @ index 24
    }

    @Test
    fun grades_has25Entries() {
        assertEquals(25, GradeConverter.GRADES.size)
        assertEquals(24, GradeConverter.MAX_INDEX)
    }
}
