package com.cruxcoach.util

/**
 * Unified grade conversion between V-Scale, French/Fontainebleau, and a fine-grained index.
 *
 * The unified index (0..24) covers 25 grades used for slider/filter stepping.
 * Font grade names match the official Kilter Board difficulty_grades table and
 * run contiguously from 4a (the lowest graded Kilter/Aurora difficulty, 10) to
 * 8c+ (33), plus a 9a (34) top stop. V-Scale grades map to a subset of these
 * indices; intermediate Font grades (4a, 4c, 5a, 6a+, 6b+, 6c+, 7b) sit between
 * V-Scale steps and are skipped in V-Scale mode.
 *
 * NOTE: index 0 is 4a — the true display floor (KilterGradeMapper clamps any
 * difficulty < 10 to 4a). Keeping 4a as the floor is what makes the filter's
 * "no lower bound" sentinel at index 0 semantically correct, and what stops 4a
 * climbs leaking in under a "from 4b" selection.
 */
object GradeConverter {

    data class GradeEntry(
        val index: Int,
        val french: String,
        val vScale: String?  // null for grades between V-Scale steps
    )

    val GRADES: List<GradeEntry> = listOf(
        GradeEntry(0,  "4a",   null),   // V0 floor — lowest Kilter Font grade (diff 10)
        GradeEntry(1,  "4b",   "V0"),   // V0 stop
        GradeEntry(2,  "4c",   null),   // V0 intermediate
        GradeEntry(3,  "5a",   null),   // V1 intermediate (lower Font grade of the V1 bucket)
        GradeEntry(4,  "5b",   "V1"),   // V1 stop
        GradeEntry(5,  "5c",   "V2"),
        GradeEntry(6,  "6a",   "V3"),
        GradeEntry(7,  "6a+",  null),   // V3 intermediate
        GradeEntry(8,  "6b",   "V4"),
        GradeEntry(9,  "6b+",  null),   // V4 intermediate
        GradeEntry(10, "6c",   "V5"),
        GradeEntry(11, "6c+",  null),   // V5 intermediate
        GradeEntry(12, "7a",   "V6"),
        GradeEntry(13, "7a+",  "V7"),
        GradeEntry(14, "7b",   null),   // V8 intermediate
        GradeEntry(15, "7b+",  "V8"),
        GradeEntry(16, "7c",   "V9"),
        GradeEntry(17, "7c+",  "V10"),
        GradeEntry(18, "8a",   "V11"),
        GradeEntry(19, "8a+",  "V12"),
        GradeEntry(20, "8b",   "V13"),
        GradeEntry(21, "8b+",  "V14"),
        GradeEntry(22, "8c",   "V15"),
        GradeEntry(23, "8c+",  "V16"),
        GradeEntry(24, "9a",   "V17")
    )

    /** Max unified index — derived so it tracks [GRADES] automatically. */
    val MAX_INDEX = GRADES.size - 1

    /** Indices that have a direct V-Scale equivalent (for V-Scale mode stepping) */
    val V_SCALE_INDICES: List<Int> = GRADES.filter { it.vScale != null }.map { it.index }

    // --- Lookup maps ---

    private val FRENCH_TO_INDEX: Map<String, Int> = GRADES.associate { it.french to it.index }

    private val V_SCALE_TO_INDEX: Map<String, Int> =
        GRADES.filter { it.vScale != null }.associate { it.vScale!! to it.index }

    // Old 0-17 numeric mapping (backward compat)
    private val V_SCALE_TO_NUMERIC: Map<String, Int> = (0..17).associate { "V$it" to it }

    // Old V-Scale → French mapping (backward compat for direct V grades)
    private val V_SCALE_TO_FRENCH: Map<String, String> =
        GRADES.filter { it.vScale != null }.associate { it.vScale!! to it.french }

    private val FRENCH_TO_V_SCALE: Map<String, String> =
        V_SCALE_TO_FRENCH.entries.associate { (v, f) -> f to v }

    // =====================================================================
    // Unified index functions
    // =====================================================================

    /** Convert any grade string (V-Scale or French) to unified index (0..22). Returns -1 if invalid. */
    fun gradeToIndex(grade: String): Int {
        return V_SCALE_TO_INDEX[grade] ?: FRENCH_TO_INDEX[grade] ?: -1
    }

    /** Unified index → French grade string */
    fun indexToFrench(index: Int): String {
        return GRADES.getOrNull(index)?.french ?: "?"
    }

    /** Unified index → V-Scale string. For intermediate FB grades, returns nearest lower V-Scale. */
    fun indexToVScale(index: Int): String {
        val entry = GRADES.getOrNull(index) ?: return "V0"
        if (entry.vScale != null) return entry.vScale
        for (i in index downTo 0) {
            GRADES[i].vScale?.let { return it }
        }
        return "V0"
    }

    /** True if this index is an intermediate FB grade (no V-Scale equivalent) */
    fun isIntermediate(index: Int): Boolean {
        return GRADES.getOrNull(index)?.vScale == null
    }

    /** All French grade strings in order */
    fun allFrenchGrades(): List<String> = GRADES.map { it.french }

    // =====================================================================
    // Scale-aware grade stepping (for grade pickers)
    // =====================================================================

    /** Next grade index. In V-Scale mode, skips intermediate FB grades. */
    fun nextIndex(current: Int, frenchMode: Boolean): Int {
        if (frenchMode) return (current + 1).coerceAtMost(MAX_INDEX)
        // V-Scale mode: jump to next V-Scale index
        val next = V_SCALE_INDICES.firstOrNull { it > current }
        return next ?: V_SCALE_INDICES.last()
    }

    /** Previous grade index. In V-Scale mode, skips intermediate FB grades. */
    fun prevIndex(current: Int, frenchMode: Boolean): Int {
        if (frenchMode) return (current - 1).coerceAtLeast(0)
        // V-Scale mode: jump to previous V-Scale index
        val prev = V_SCALE_INDICES.lastOrNull { it < current }
        return prev ?: V_SCALE_INDICES.first()
    }

    /** Whether we can go up from this index */
    fun canGoUp(current: Int): Boolean = current < MAX_INDEX

    /** Whether we can go down from this index */
    fun canGoDown(current: Int): Boolean = current > 0

    // =====================================================================
    // Backward-compatible functions (updated to handle both V-Scale and FB)
    // =====================================================================

    /**
     * Convert any grade string to old 0-17 numeric.
     * Handles V-Scale ("V5" → 5), French ("6c" → 5), and intermediate FB ("6a+" → 3).
     * Returns -1 for invalid input.
     */
    fun vScaleToNumeric(grade: String): Int {
        // Direct V-Scale match
        V_SCALE_TO_NUMERIC[grade]?.let { return it }
        // FB lookup: find index, then map to nearest V-Scale
        val index = FRENCH_TO_INDEX[grade] ?: return -1
        val vScale = indexToVScale(index)
        return V_SCALE_TO_NUMERIC[vScale] ?: -1
    }

    fun numericToVScale(numeric: Int): String {
        return if (numeric in 0..17) "V$numeric" else "V0"
    }

    fun vScaleToFrench(grade: String): String {
        return V_SCALE_TO_FRENCH[grade] ?: "?"
    }

    fun frenchToVScale(grade: String): String {
        // Direct match for V-Scale-equivalent FB grades
        FRENCH_TO_V_SCALE[grade]?.let { return it }
        // Intermediate FB grade: return nearest lower V-Scale
        val index = FRENCH_TO_INDEX[grade] ?: return "?"
        return indexToVScale(index)
    }

    fun isValidGrade(grade: String): Boolean {
        return grade in V_SCALE_TO_NUMERIC || grade in FRENCH_TO_INDEX
    }

    fun isValidVScale(grade: String): Boolean {
        return grade in V_SCALE_TO_NUMERIC
    }

    fun allVScaleGrades(): List<String> {
        return (0..17).map { "V$it" }
    }

    fun lowerGrade(grade: String, steps: Int): String {
        val numeric = vScaleToNumeric(grade)
        if (numeric < 0) return "V0"
        return numericToVScale((numeric - steps).coerceAtLeast(0))
    }

    fun higherGrade(grade: String, steps: Int): String {
        val numeric = vScaleToNumeric(grade)
        if (numeric < 0) return "V0"
        return numericToVScale((numeric + steps).coerceAtMost(17))
    }

    fun compareGrades(grade1: String, grade2: String): Int {
        val idx1 = gradeToIndex(grade1)
        val idx2 = gradeToIndex(grade2)
        return idx1.compareTo(idx2)
    }

    // =====================================================================
    // Conversion helpers for grade storage
    // =====================================================================

    /** Convert old 0-17 numeric to unified index */
    fun oldNumericToIndex(numeric: Int): Int {
        val vScale = numericToVScale(numeric)
        return V_SCALE_TO_INDEX[vScale] ?: 0
    }

    /** Convert unified index to old 0-17 numeric */
    fun indexToOldNumeric(index: Int): Int {
        val vScale = indexToVScale(index)
        return V_SCALE_TO_NUMERIC[vScale] ?: 0
    }

    /**
     * Convert any grade string to its canonical display form for the given mode.
     * frenchMode=true → French string, frenchMode=false → V-Scale string
     */
    fun formatGrade(grade: String, frenchMode: Boolean): String {
        val index = gradeToIndex(grade)
        if (index < 0) return grade
        return if (frenchMode) indexToFrench(index) else indexToVScale(index)
    }
}
