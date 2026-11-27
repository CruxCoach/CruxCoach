package com.cruxcoach.android.ui.board

import android.content.Context
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/** Problems sent for the first time and the tries it took to send them. */
data class TriesToSend(
    val problems: Int,
    val tries: Int,
) {
    val averageTries: Double get() = if (problems > 0) tries.toDouble() / problems else 0.0
}

/** One displayed grade: tries to send per board (boards without a send there are absent). */
data class CrossBoardGradeRow(
    val grade: String,
    val difficultyInt: Int,
    val byBoard: Map<String, TriesToSend>,
)

/** One board in one month: the tries of its first sends. */
data class CrossBoardMonthCell(
    val tries: TriesToSend,
)

data class CrossBoardMonthRow(
    val month: YearMonth,
    val label: String,
    val byBoard: Map<String, CrossBoardMonthCell>,
)

/** First sends by the tries they took: a flash, 2, 3, 4–5, and 6 or more. */
data class TriesBuckets(val counts: List<Int>) {
    val problems: Int get() = counts.sum()

    companion object {
        /** The fewest tries of each bucket. */
        val LOWEST = listOf(1, 2, 3, 4, 6)

        fun bucketOf(tries: Int): Int = LOWEST.indexOfLast { tries >= it }.coerceAtLeast(0)

        fun of(tries: List<Int>): TriesBuckets {
            val counts = IntArray(LOWEST.size)
            tries.forEach { counts[bucketOf(it)]++ }
            return TriesBuckets(counts.toList())
        }
    }
}

/**
 * Stats across boards (#44): how many tries a grade takes on each board, and
 * how the tries develop month by month ([months] holds only months with a
 * first send, newest first). Boards are the raw wire values, ordered by first
 * sends in the interval, most first.
 */
data class CrossBoardStats(
    val boards: List<String> = emptyList(),
    val grades: List<CrossBoardGradeRow> = emptyList(),
    val months: List<CrossBoardMonthRow> = emptyList(),
    /** How many tries the first sends took, per board. */
    val sendCurve: Map<String, TriesBuckets> = emptyMap(),
    /** How hard the boards feel against the main one; null without enough data. */
    val feel: BoardFeel? = null,
    /** The days of the chosen period, so the months without a first send still show. */
    val period: StatsPeriod? = null,
)

/**
 * Pure computation of [CrossBoardStats], next to [BoardStatsComputer].
 *
 * The unit is a problem's first send: one per (board, climb, angle), the same
 * key the flash detection uses. Its tries are every attempt logged on that
 * problem up to and including the send, across sessions. A quick-log session
 * folds its attempts into the send's bid count; attempts from earlier
 * sessions stay as separate rows and are added. Repeats after the first send
 * are left out: they say nothing about how hard the grade is on that board.
 *
 * All boards share one difficulty scale, so a grade means the same on every
 * board here.
 */
object CrossBoardStatsComputer {

    /** Fewer sent problems than this in a cell is shown as a small sample. */
    const val SMALL_SAMPLE = 3

    internal data class FirstSend(val send: AscentWithClimb, val tries: Int)

    fun compute(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        context: Context? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): CrossBoardStats {
        val allFirstSends = firstSends(ascents)
        // The interval picks problems by the day of their first send; their
        // tries still count attempts from before the interval.
        val inInterval = BoardStatsComputer.filterByInterval(
            allFirstSends.map { it.send }, interval, customFrom, customTo, clock,
        ).mapTo(HashSet()) { it.uuid }
        val firstSends = allFirstSends.filter { it.send.uuid in inInterval }
        if (firstSends.isEmpty()) return CrossBoardStats()

        val boards = firstSends.groupingBy { it.send.boardBrand }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

        val graded = firstSends.filter { it.send.difficultyAverage != null }
        val grades = graded
            .groupBy { GradeDisplayHelper.formatDifficulty(it.send.difficultyAverage!!, gradeScale) }
            .map { (grade, sends) ->
                CrossBoardGradeRow(
                    grade = grade,
                    difficultyInt = sends.minOf { Math.round(it.send.difficultyAverage!!).toInt() },
                    byBoard = sends.groupBy { it.send.boardBrand }.mapValues { (_, s) -> triesOf(s) },
                )
            }
            .sortedBy { it.difficultyInt }

        val months = firstSends
            .mapNotNull { first -> monthOf(first.send.climbedAt)?.let { it to first } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(reverseOrder())
            .map { (month, sends) ->
                CrossBoardMonthRow(
                    month = month,
                    label = monthLabel(month, context),
                    byBoard = sends.groupBy { it.send.boardBrand }.mapValues { (_, s) ->
                        CrossBoardMonthCell(tries = triesOf(s))
                    },
                )
            }

        val sendCurve = firstSends.groupBy { it.send.boardBrand }
            .mapValues { (_, s) -> TriesBuckets.of(s.map { it.tries }) }

        return CrossBoardStats(
            boards = boards,
            grades = grades,
            months = months,
            sendCurve = sendCurve,
            feel = BoardFeelEstimator.estimate(firstSends, gradeScale),
            period = BoardStatsComputer.periodOf(
                ascents,
                BoardStatsComputer.filterByInterval(ascents, interval, customFrom, customTo, clock),
                interval, customFrom, customTo, clock,
            ),
        )
    }

    /**
     * The first send of every problem with its tries. Attempts logged at the
     * same moment as the send count before it.
     */
    internal fun firstSends(ascents: List<AscentWithClimb>): List<FirstSend> = ascents
        .groupBy { Triple(it.boardBrand, it.climbUuid, it.angle) }
        .values
        .mapNotNull { rows ->
            val ordered = rows.sortedWith(
                compareBy<AscentWithClimb> { it.climbedAt }.thenBy { it.isSend }.thenBy { it.uuid },
            )
            val index = ordered.indexOfFirst { it.isSend }
            if (index < 0) return@mapNotNull null
            FirstSend(
                send = ordered[index],
                tries = ordered.subList(0, index + 1).sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            )
        }

    private fun triesOf(sends: List<FirstSend>) =
        TriesToSend(problems = sends.size, tries = sends.sumOf { it.tries })

    private fun monthOf(climbedAt: String): YearMonth? = try {
        YearMonth.parse(climbedAt.take(7))
    } catch (_: Exception) {
        null
    }

    private val MONTH_RES_IDS = intArrayOf(
        R.string.month_jan, R.string.month_feb, R.string.month_mar,
        R.string.month_apr, R.string.month_may, R.string.month_jun,
        R.string.month_jul, R.string.month_aug, R.string.month_sep,
        R.string.month_oct, R.string.month_nov, R.string.month_dec,
    )

    private fun monthLabel(month: YearMonth, context: Context?): String {
        val name = context?.getString(MONTH_RES_IDS[month.monthValue - 1])
            ?: month.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return "$name '${month.year % 100}"
    }
}
