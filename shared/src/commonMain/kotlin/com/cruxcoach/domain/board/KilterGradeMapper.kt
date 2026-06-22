package com.cruxcoach.domain.board

import kotlin.math.abs

/**
 * Maps Kilter difficulty integers (10–34) to V-Scale and Font grades.
 * Grade mappings match the official difficulty_grades table from the Kilter Board app.
 *
 * Display uses ROUND(difficulty_average) → lookup, matching Kilter's SQL:
 *   SELECT boulder_name FROM difficulty_grades WHERE difficulty = ROUND(display_difficulty)
 */
object KilterGradeMapper {

    /** Default setter grade applied when the editor's grade slider was never
     *  touched. 20 = V5 / 6c, the slider's visible default value. Owned by
     *  the data layer so every persistence path (saveDraft, updateDraft,
     *  future autosave / Fork-and-Edit / tooling) gets the same fallback
     *  without relying on a UI-side LaunchedEffect to seed editor state. */
    const val DEFAULT_SETTER_GRADE_ID = 20

    private val DIFFICULTY_TO_VSCALE = mapOf(
        10 to "V0", 11 to "V0", 12 to "V0",
        13 to "V1", 14 to "V1", 15 to "V2",
        16 to "V3", 17 to "V3", 18 to "V4",
        19 to "V4", 20 to "V5", 21 to "V5",
        22 to "V6", 23 to "V7", 24 to "V8",
        25 to "V8", 26 to "V9", 27 to "V10",
        28 to "V11", 29 to "V12", 30 to "V13",
        31 to "V14", 32 to "V15", 33 to "V16",
        34 to "V17"
    )

    /** Font grade for each integer difficulty (from Kilter DB difficulty_grades.boulder_name). */
    private val DIFFICULTY_TO_FONT = mapOf(
        10 to "4a", 11 to "4b", 12 to "4c",
        13 to "5a", 14 to "5b", 15 to "5c",
        16 to "6a", 17 to "6a+", 18 to "6b",
        19 to "6b+", 20 to "6c", 21 to "6c+",
        22 to "7a", 23 to "7a+", 24 to "7b",
        25 to "7b+", 26 to "7c", 27 to "7c+",
        28 to "8a", 29 to "8a+", 30 to "8b",
        31 to "8b+", 32 to "8c", 33 to "8c+",
        34 to "9a"
    )

    private val VSCALE_TO_DIFFICULTY = mapOf(
        "V0" to 11, "V1" to 14, "V2" to 15,
        "V3" to 17, "V4" to 19, "V5" to 21,
        "V6" to 22, "V7" to 23, "V8" to 25,
        "V9" to 26, "V10" to 27, "V11" to 28,
        "V12" to 29, "V13" to 30, "V14" to 31,
        "V15" to 32, "V16" to 33, "V17" to 34
    )

    // Display-aligned boundaries: lowest/highest integer difficulty per V-grade
    // (derived from DIFFICULTY_TO_VSCALE so filter matches display exactly)
    private val VSCALE_LOWER: Map<String, Int> = DIFFICULTY_TO_VSCALE.entries
        .groupBy { it.value }
        .mapValues { (_, entries) -> entries.minOf { it.key } }
    private val VSCALE_UPPER: Map<String, Int> = DIFFICULTY_TO_VSCALE.entries
        .groupBy { it.value }
        .mapValues { (_, entries) -> entries.maxOf { it.key } }

    /** Round half-up (matches SQLite ROUND for positive values). */
    private fun roundHalfUp(d: Double): Int = (d + 0.5).toInt()

    fun difficultyToVScale(difficulty: Int): String {
        return DIFFICULTY_TO_VSCALE[difficulty] ?: when {
            difficulty < 10 -> "V0"
            difficulty > 34 -> "V17"
            else -> "V${(difficulty - 10) / 2}"
        }
    }

    /** Round difficulty_average then look up V-Scale (matches Kilter app ROUND behavior). */
    fun difficultyToVScale(difficulty: Double): String {
        return difficultyToVScale(roundHalfUp(difficulty))
    }

    /** Round difficulty_average then look up Font grade (matches Kilter app ROUND behavior). */
    fun difficultyToFont(difficulty: Double): String {
        val rounded = roundHalfUp(difficulty)
        return DIFFICULTY_TO_FONT[rounded] ?: when {
            rounded < 10 -> "4a"
            rounded > 34 -> "9a"
            else -> "?"
        }
    }

    fun vScaleToDifficulty(grade: String): Int {
        return VSCALE_TO_DIFFICULTY[grade] ?: 11
    }

    /**
     * Get the difficulty range (min, max) for a V-Scale grade.
     */
    fun gradeToRange(grade: String): Pair<Double, Double> {
        val center = VSCALE_TO_DIFFICULTY[grade]?.toDouble() ?: 11.0
        return (center - 1.0) to (center + 1.0)
    }

    /**
     * Map unified grade index (0..24) to Kilter difficulty value. Contiguous
     * 10..34, so [GradeConverter.GRADES] and this array stay in lockstep — every
     * Font grade from 4a up has its own index (no skipped difficulties).
     * Each value is the exact integer difficulty from the Kilter DB difficulty_grades table.
     */
    private val INDEX_TO_DIFFICULTY = doubleArrayOf(
        10.0,  // 0  = 4a   / V0  (floor)
        11.0,  // 1  = 4b   / V0
        12.0,  // 2  = 4c
        13.0,  // 3  = 5a   / V1
        14.0,  // 4  = 5b   / V1
        15.0,  // 5  = 5c   / V2
        16.0,  // 6  = 6a   / V3
        17.0,  // 7  = 6a+
        18.0,  // 8  = 6b   / V4
        19.0,  // 9  = 6b+
        20.0,  // 10 = 6c   / V5
        21.0,  // 11 = 6c+
        22.0,  // 12 = 7a   / V6
        23.0,  // 13 = 7a+  / V7
        24.0,  // 14 = 7b
        25.0,  // 15 = 7b+  / V8
        26.0,  // 16 = 7c   / V9
        27.0,  // 17 = 7c+  / V10
        28.0,  // 18 = 8a   / V11
        29.0,  // 19 = 8a+  / V12
        30.0,  // 20 = 8b   / V13
        31.0,  // 21 = 8b+  / V14
        32.0,  // 22 = 8c   / V15
        33.0,  // 23 = 8c+  / V16
        34.0   // 24 = 9a   / V17
    )

    fun indexToDifficulty(index: Int): Double {
        return INDEX_TO_DIFFICULTY[index.coerceIn(0, INDEX_TO_DIFFICULTY.lastIndex)]
    }

    /**
     * Map a difficulty_average to the nearest unified grade index (0..22).
     * Uses nearest-neighbor against the Kilter difficulty integers.
     */
    fun difficultyToIndex(difficulty: Double): Int {
        var bestIndex = 0
        var bestDist = Double.MAX_VALUE
        for (i in INDEX_TO_DIFFICULTY.indices) {
            val dist = abs(difficulty - INDEX_TO_DIFFICULTY[i])
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return bestIndex
    }

    /**
     * Lower filter bound for a grade index.
     *
     * @param frenchMode true = Font-Scale (midpoint boundaries),
     *                   false = V-Scale (ROUND boundaries: lower - 0.5)
     */
    fun indexToFilterMin(index: Int, frenchMode: Boolean = false): Double {
        // index 0 == 4a == the display floor, so "no lower bound" is correct here:
        // anything below difficulty 10 is clamped to 4a anyway.
        if (index <= 0) return 0.0
        val idx = index.coerceIn(0, INDEX_TO_DIFFICULTY.lastIndex)
        if (frenchMode) {
            // Midpoint to previous index
            val prevDiff = INDEX_TO_DIFFICULTY[idx - 1]
            return (INDEX_TO_DIFFICULTY[idx] + prevDiff) / 2.0
        }
        // V-Scale: ROUND boundary (grade starts at lower - 0.5)
        val vGrade = difficultyToVScale(INDEX_TO_DIFFICULTY[idx].toInt())
        return (VSCALE_LOWER[vGrade] ?: INDEX_TO_DIFFICULTY[idx].toInt()).toDouble() - 0.5
    }

    /**
     * Upper filter bound for a grade index.
     *
     * @param frenchMode true = Font-Scale (midpoint boundaries),
     *                   false = V-Scale (ROUND boundaries: upper + 0.49)
     */
    fun indexToFilterMax(index: Int, frenchMode: Boolean = false): Double {
        if (index >= INDEX_TO_DIFFICULTY.lastIndex) return 99.0
        val idx = index.coerceIn(0, INDEX_TO_DIFFICULTY.lastIndex)
        if (frenchMode) {
            // Midpoint to next index minus epsilon
            val nextDiff = INDEX_TO_DIFFICULTY[idx + 1]
            return (INDEX_TO_DIFFICULTY[idx] + nextDiff) / 2.0 - 0.01
        }
        // V-Scale: ROUND boundary (grade ends at upper + 0.49)
        val vGrade = difficultyToVScale(INDEX_TO_DIFFICULTY[idx].toInt())
        return (VSCALE_UPPER[vGrade] ?: INDEX_TO_DIFFICULTY[idx].toInt()).toDouble() + 0.49
    }

    /**
     * Get display string with community grade and V-scale.
     */
    fun formatGrade(difficultyAvg: Double): String {
        val vScale = difficultyToVScale(difficultyAvg)
        return "$vScale (${"%.1f".format(difficultyAvg)})"
    }
}
