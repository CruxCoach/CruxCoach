package com.cruxcoach.android.util

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.util.GradeConverter

object GradeDisplayHelper {

    /**
     * Format a grade string (V-Scale or French) according to selected scale.
     * Handles both "V5" and "6c" input.
     */
    fun formatGrade(grade: String, scale: GradeScale): String {
        val index = GradeConverter.gradeToIndex(grade)
        if (index < 0) return grade
        return when (scale) {
            GradeScale.V_SCALE -> GradeConverter.indexToVScale(index)
            GradeScale.FRENCH -> GradeConverter.indexToFrench(index)
        }
    }

    /**
     * Format a numeric grade (0..17 old system) according to selected scale.
     */
    fun formatGradeNumeric(numeric: Int, scale: GradeScale): String {
        val index = GradeConverter.oldNumericToIndex(numeric)
        return formatByIndex(index, scale)
    }

    /**
     * Format a unified index (0..22) according to selected scale.
     */
    fun formatByIndex(index: Int, scale: GradeScale): String {
        return when (scale) {
            GradeScale.V_SCALE -> GradeConverter.indexToVScale(index)
            GradeScale.FRENCH -> GradeConverter.indexToFrench(index)
        }
    }

    /**
     * Format a unified index with its alternative in parentheses.
     * e.g. index=8, V_SCALE → "V5 (6c)", FRENCH → "6c (V5)"
     * For intermediate FB grades: index=5, V_SCALE → "V3 (6a+)", FRENCH → "6a+ (V3)"
     */
    fun formatByIndexWithAlt(index: Int, scale: GradeScale): String {
        val french = GradeConverter.indexToFrench(index)
        val vScale = GradeConverter.indexToVScale(index)
        return when (scale) {
            GradeScale.V_SCALE -> "$vScale ($french)"
            GradeScale.FRENCH -> "$french ($vScale)"
        }
    }

    /**
     * Format an Aurora difficulty_average value according to selected scale.
     * Both modes use ROUND(difficulty) → lookup, matching the Kilter Board app exactly.
     */
    fun formatDifficulty(diffAvg: Double, scale: GradeScale): String {
        return when (scale) {
            GradeScale.V_SCALE -> KilterGradeMapper.difficultyToVScale(diffAvg)
            GradeScale.FRENCH -> KilterGradeMapper.difficultyToFont(diffAvg)
        }
    }

    /**
     * Format a grade string with its alternative in parentheses.
     * Handles both V-Scale and French input.
     */
    fun formatGradeWithAlt(grade: String, scale: GradeScale): String {
        val index = GradeConverter.gradeToIndex(grade)
        if (index < 0) return grade
        return formatByIndexWithAlt(index, scale)
    }

    /**
     * Format an Aurora difficulty with detailed raw value.
     */
    fun formatDifficultyDetailed(diffAvg: Double, scale: GradeScale): String {
        val grade = formatDifficulty(diffAvg, scale)
        return "$grade (${"%.1f".format(diffAvg)})"
    }

    /**
     * Get the old 0-17 numeric for color calculation from a unified index.
     */
    fun indexToColorNumeric(index: Int): Int {
        return GradeConverter.indexToOldNumeric(index)
    }
}
