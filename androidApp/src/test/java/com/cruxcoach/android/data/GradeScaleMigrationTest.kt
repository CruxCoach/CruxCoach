package com.cruxcoach.android.data

import com.cruxcoach.util.GradeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the one-time DataStore remap that shifts persisted board-filter grade
 * indices after the unified scale gained 4a (index 0) and 5a (index 3).
 * The Font grade a stored index named must be preserved across the shift.
 */
class GradeScaleMigrationTest {

    @Test
    fun remap_shiftsIndicesPreservingFontGrade() {
        // old index (floor 4b) -> expected new index (floor 4a)
        val cases = mapOf(
            0 to 1,   // 4b
            1 to 2,   // 4c
            2 to 4,   // 5b
            3 to 5,   // 5c
            14 to 16, // 7c (the default max)
            22 to 24, // 9a (top)
        )
        for ((old, expectedNew) in cases) {
            assertEquals("remap($old)", expectedNew, remapOldGradeIndexToV1(old))
        }
    }

    @Test
    fun remap_preservesTheNamedGrade() {
        // The OLD scale's Font names at these indices, by definition:
        val oldFont = mapOf(0 to "4b", 1 to "4c", 2 to "5b", 14 to "7c", 22 to "9a")
        for ((old, font) in oldFont) {
            assertEquals(
                "index $old still names $font after remap",
                font,
                GradeConverter.indexToFrench(remapOldGradeIndexToV1(old)),
            )
        }
    }

    @Test
    fun remap_staysInRange() {
        for (old in 0..GradeConverter.MAX_INDEX) {
            val new = remapOldGradeIndexToV1(old)
            assertEquals(true, new in 0..GradeConverter.MAX_INDEX)
        }
    }
}
