package com.cruxcoach.domain.playlist

import com.cruxcoach.domain.board.KilterGradeMapper
import kotlin.math.roundToInt

/**
 * Naming a distance in V-grades. **Display and tests only.**
 *
 * The planner does not use this and must not start: it computes in Aurora
 * points, which are Font grades and evenly spaced. The V-scale is not — V0
 * covers three points, V5 two, V9 and up a single each — so it is a way to
 * *describe* a difficulty to a climber, not a unit to plan in. Putting it in
 * the arithmetic is what made the power-endurance band land three grades too
 * easy for strong climbers.
 *
 * Tests use [distanceInGrades] to state what a band comes out as in the units
 * a climber reads, which is worth asserting even though nothing computes in
 * them.
 */
object VGradeOffsets {

    /**
     * The distinct V-grades of the scale, in order, each with the difficulty
     * range it covers. Derived from the mapper's own table so there is one
     * source of truth for what a V-grade is.
     */
    private val bands: List<Pair<String, ClosedFloatingPointRange<Double>>> = run {
        val byGrade = LinkedHashMap<String, MutableList<Int>>()
        for (d in TrainingRanges.MIN_DIFFICULTY.toInt()..TrainingRanges.MAX_DIFFICULTY.toInt()) {
            byGrade.getOrPut(KilterGradeMapper.difficultyToVScale(d)) { mutableListOf() }.add(d)
        }
        byGrade.map { (grade, points) ->
            grade to (points.min().toDouble()..points.max().toDouble())
        }
    }

    private fun bandIndexOf(difficulty: Double): Int {
        val clamped = difficulty.coerceIn(TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY)
        val exact = bands.indexOfFirst { clamped in it.second }
        if (exact >= 0) return exact
        // Between two bands only through rounding; take the nearer one.
        return bands.indices.minByOrNull { i ->
            val range = bands[i].second
            minOf(kotlin.math.abs(clamped - range.start), kotlin.math.abs(clamped - range.endInclusive))
        } ?: 0
    }

    /**
     * [grades] V-grades below [difficulty], as the HARDEST point still in that
     * grade — the conservative end for a band's upper bound.
     */
    fun below(difficulty: Double, grades: Int): Double =
        bands[(bandIndexOf(difficulty) - grades).coerceIn(bands.indices)]
            .second.endInclusive

    /**
     * [grades] V-grades below [difficulty], as the EASIEST point in that grade
     * — the honest end for a band's lower bound, so "3 V below" really does
     * include the whole grade rather than only its hard half.
     */
    fun belowFloor(difficulty: Double, grades: Int): Double =
        bands[(bandIndexOf(difficulty) - grades).coerceIn(bands.indices)]
            .second.start

    /** [grades] V-grades above [difficulty], as the easiest point in that grade. */
    fun above(difficulty: Double, grades: Int): Double =
        bands[(bandIndexOf(difficulty) + grades).coerceIn(bands.indices)]
            .second.start

    /**
     * [grades] V-grades above [difficulty], as the HARDEST point in that grade
     * — a ceiling, where "one grade above max" should still allow all of that
     * grade rather than only its easiest climb.
     */
    fun aboveTop(difficulty: Double, grades: Int): Double =
        bands[(bandIndexOf(difficulty) + grades).coerceIn(bands.indices)]
            .second.endInclusive

    /**
     * How many V-grades apart two difficulties are. Used by tests and by the
     * warm-up ladder, which reasons in grades rather than points.
     */
    fun distanceInGrades(from: Double, to: Double): Int =
        bandIndexOf(to) - bandIndexOf(from)

    /** The difficulty one V-grade below [difficulty] — the ladder's step. */
    fun oneGradeBelow(difficulty: Double): Double = below(difficulty, 1)

    /** Rounded to the scale's own granularity; the DB stores integers. */
    fun snap(difficulty: Double): Double = difficulty.roundToInt().toDouble()
}
