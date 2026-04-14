package com.cruxcoach.util

/**
 * Unified grade conversion between V-Scale, French/Fontainebleau, and a fine-grained index.
 *
 * The unified index (0..22) covers 23 grades used for slider/filter stepping.
 * Font grade names match the official Kilter Board difficulty_grades table.
 * V-Scale grades map to a subset of these indices; intermediate Font grades
 * (6a+, 6b+, 6c+, 7b) sit between V-Scale steps and are skipped in V-Scale mode.
 */
object GradeConverter {

    data class GradeEntry(
        val index: Int,
        val french: String,
        val vScale: String?  // null for grades between V-Scale steps
    )

    val GRADES: List<GradeEntry> = listOf(
        GradeEntry(0,  "4b",   "V0"),
        GradeEntry(1,  "4c",   null),   // V0 intermediate (slider uses 4b as V0 stop)
        GradeEntry(2,  "5b",   "V1"),
        GradeEntry(3,  "5c",   "V2"),
        GradeEntry(4,  "6a",   "V3"),
        GradeEntry(5,  "6a+",  null),   // V3 intermediate
        GradeEntry(6,  "6b",   "V4"),
        GradeEntry(7,  "6b+",  null),   // V4 intermediate
        GradeEntry(8,  "6c",   "V5"),
        GradeEntry(9,  "6c+",  null),   // V5 intermediate
        GradeEntry(10, "7a",   "V6"),
        GradeEntry(11, "7a+",  "V7"),
        GradeEntry(12, "7b",   null),   // V8 intermediate
        GradeEntry(13, "7b+",  "V8"),
        GradeEntry(14, "7c",   "V9"),
        GradeEntry(15, "7c+",  "V10"),
        GradeEntry(16, "8a",   "V11"),
        GradeEntry(17, "8a+",  "V12"),
        GradeEntry(18, "8b",   "V13"),
        GradeEntry(19, "8b+",  "V14"),
        GradeEntry(20, "8c",   "V15"),
        GradeEntry(21, "8c+",  "V16"),
        GradeEntry(22, "9a",   "V17")
    )

    /** Max unified index */
    const val MAX_INDEX = 22

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
