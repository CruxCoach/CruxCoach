package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import kotlin.math.ln
import kotlin.math.sqrt

/** How much harder a board feels than the reference, in displayed grades, and the standard error of that. */
data class BoardOffset(val grades: Double, val error: Double)

/** How the boards feel against [reference], the board with the most first sends. */
data class BoardFeel(
    val reference: String,
    val offsets: Map<String, BoardOffset>,
)

/**
 * Estimates how hard a board feels for the climber against their main board,
 * from the tries their first sends took ([CrossBoardStatsComputer.firstSends]).
 *
 * Per board pair a line is fitted through ln(tries) over the difficulty, with
 * one slope for both boards and its own height for each: the slope says how
 * many more tries a harder grade takes, the gap in height how many more tries
 * the same grade takes on the other board. The gap divided by the slope is the
 * difficulty the other board adds, turned into displayed grades near the
 * climber's usual grade.
 *
 * Shown only when it says something: enough problems on both boards, sends at
 * grades both boards share, tries that clearly rise with the grade, and a
 * standard error of at most [MAX_ERROR] grades.
 */
object BoardFeelEstimator {

    const val MIN_PROBLEMS = 8
    const val MIN_SHARED_GRADES = 2
    const val MAX_ERROR = 1.0
    private const val MIN_SLOPE_T = 2.0

    internal fun estimate(
        firstSends: List<CrossBoardStatsComputer.FirstSend>,
        gradeScale: GradeScale,
    ): BoardFeel? {
        val byBoard = firstSends
            .filter { it.send.difficultyAverage != null }
            .groupBy { it.send.boardBrand }
            .filterValues { it.size >= MIN_PROBLEMS }
        if (byBoard.size < 2) return null
        val reference = byBoard.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<CrossBoardStatsComputer.FirstSend>>> { it.value.size }.thenBy { it.key })
            .first().key
        val main = byBoard.getValue(reference)
        val unitsPerGrade = unitsPerGrade(median(main.map { it.send.difficultyAverage!! }), gradeScale)

        val offsets = byBoard
            .filterKeys { it != reference }
            .mapNotNull { (board, sends) ->
                if (sharedGrades(main, sends, gradeScale) < MIN_SHARED_GRADES) return@mapNotNull null
                val units = offsetInDifficulty(main, sends) ?: return@mapNotNull null
                val offset = BoardOffset(units.grades / unitsPerGrade, units.error / unitsPerGrade)
                board.takeIf { offset.error <= MAX_ERROR }?.let { it to offset }
            }
            .toMap()
        return if (offsets.isEmpty()) null else BoardFeel(reference, offsets)
    }

    private fun sharedGrades(
        a: List<CrossBoardStatsComputer.FirstSend>,
        b: List<CrossBoardStatsComputer.FirstSend>,
        gradeScale: GradeScale,
    ): Int {
        fun grades(s: List<CrossBoardStatsComputer.FirstSend>) =
            s.mapTo(HashSet()) { GradeDisplayHelper.formatDifficulty(it.send.difficultyAverage!!, gradeScale) }
        return grades(a).intersect(grades(b)).size
    }

    /**
     * Least squares of ln(tries) = a + c·other + b·difficulty. Returns c / b
     * with its standard error (delta method), in difficulty units, or null
     * when the tries do not rise clearly with the difficulty.
     */
    internal fun offsetInDifficulty(
        main: List<CrossBoardStatsComputer.FirstSend>,
        other: List<CrossBoardStatsComputer.FirstSend>,
    ): BoardOffset? {
        val rows = main.map { Triple(0.0, it.send.difficultyAverage!!, ln(it.tries.toDouble())) } +
            other.map { Triple(1.0, it.send.difficultyAverage!!, ln(it.tries.toDouble())) }
        val n = rows.size
        if (n <= 3) return null
        val xtx = Array(3) { DoubleArray(3) }
        val xty = DoubleArray(3)
        rows.forEach { (d, x, y) ->
            val v = doubleArrayOf(1.0, d, x)
            for (i in 0..2) {
                xty[i] += v[i] * y
                for (j in 0..2) xtx[i][j] += v[i] * v[j]
            }
        }
        val inv = invert3(xtx) ?: return null
        val beta = DoubleArray(3) { i -> (0..2).sumOf { j -> inv[i][j] * xty[j] } }
        val rss = rows.sumOf { (d, x, y) ->
            val r = y - (beta[0] + beta[1] * d + beta[2] * x)
            r * r
        }
        val sigma2 = rss / (n - 3)
        val c = beta[1]
        val b = beta[2]
        val varC = sigma2 * inv[1][1]
        val varB = sigma2 * inv[2][2]
        val covCB = sigma2 * inv[1][2]
        if (b <= 0.0 || b < MIN_SLOPE_T * sqrt(varB)) return null
        val offset = c / b
        val variance = varC / (b * b) + c * c * varB / (b * b * b * b) - 2 * c * covCB / (b * b * b)
        return BoardOffset(offset, sqrt(variance.coerceAtLeast(0.0)))
    }

    /** Difficulty units per displayed grade around [difficulty]: 1 for Font, more where grades are wider. */
    internal fun unitsPerGrade(difficulty: Double, gradeScale: GradeScale): Double {
        val center = Math.round(difficulty).toInt()
        val span = 6
        val grades = (center - span / 2..center + span / 2)
            .map { GradeDisplayHelper.formatDifficulty(it.toDouble(), gradeScale) }
            .distinct().size
        return span.toDouble() / (grades - 1).coerceAtLeast(1)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun invert3(m: Array<DoubleArray>): Array<DoubleArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (kotlin.math.abs(det) < 1e-9) return null
        val c = Array(3) { DoubleArray(3) }
        for (i in 0..2) for (j in 0..2) {
            val r0 = (0..2).filter { it != i }
            val c0 = (0..2).filter { it != j }
            val minor = m[r0[0]][c0[0]] * m[r0[1]][c0[1]] - m[r0[0]][c0[1]] * m[r0[1]][c0[0]]
            // Adjugate: transposed cofactors.
            c[j][i] = (if ((i + j) % 2 == 0) 1 else -1) * minor / det
        }
        return c
    }
}
