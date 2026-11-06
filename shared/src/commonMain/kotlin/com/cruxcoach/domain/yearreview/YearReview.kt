package com.cruxcoach.domain.yearreview

import com.cruxcoach.athlete.model.Benchmark
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * One calendar year of climbing in local time (#64): the board logbook of every
 * board, imported logbooks included, climbing days logged by hand and off-board
 * training. Computed when the review opens, by [YearReviewComputer]; nothing of
 * it is stored.
 *
 * Grades are difficulties on the shared scale; the app formats them in the
 * user's grade scale. Boards are the raw wire values.
 */
data class YearReview(
    val year: Int,
    /** The last day counted: today while the year runs, else 31 December. */
    val until: LocalDate,
    /** False while the year is still running, when the review is "so far". */
    val isFinal: Boolean,
    /** Enough data for the full review; below it the app shows a short "your year so far". */
    val hasEnoughData: Boolean,
    val volume: YearVolume,
    /** Most entries first. */
    val boards: List<YearBoard>,
    /** Last year over the same span, null when nothing was logged then. */
    val previous: YearComparison?,
    val rhythm: YearRhythm,
    val highlights: YearHighlights,
    val training: YearTraining,
    /** Null below the minimum: the short review has no type. */
    val climberType: ClimberTypeResult?,
)

data class YearVolume(
    /** Days with a board entry or a climbing day logged by hand. */
    val climbingDays: Int,
    /** Days with at least one board entry. */
    val boardDays: Int,
    /** Timed board sessions that were ended, and their active minutes without pauses. */
    val timedSessions: Int,
    val sessionMinutes: Int,
    /** Every try: a send counts its tries, at least one, as in the statistics. */
    val tries: Int,
    /** Every successful entry, repeats included, as in the statistics. */
    val sends: Int,
    /** Problems tried, each (board, problem) once. */
    val problems: Int,
    /** Problems sent for the first time ever this year, per (board, problem, angle). */
    val firstSends: Int,
    /** First sends on the first try with no earlier entry on that problem. */
    val flashes: Int,
)

data class YearBoard(
    val board: String,
    val entries: Int,
    val tries: Int,
    val sends: Int,
    val days: Int,
    /** Entries per angle, most first. */
    val angles: List<AngleCount>,
    /** The hardest problems sent on this board, each once, hardest first; at most [YearReviewComputer.TOP_SENDS]. */
    val hardest: List<YearSend>,
    /** Sends with a grade: how many sends [hardest] was picked from. */
    val gradedSends: Int,
)

data class AngleCount(val angle: Int, val entries: Int)

/** A sent problem. [tries] is the tries up to its first send, null for a repeat; [difficulty] is null without a grade. */
data class YearSend(
    val name: String,
    val board: String,
    val angle: Int,
    val difficulty: Double?,
    val date: LocalDate,
    val flash: Boolean,
    val tries: Int?,
)

/** The headline numbers of a span, for the comparison with last year. */
data class YearTotals(
    val climbingDays: Int,
    val tries: Int,
    val sends: Int,
    val firstSends: Int,
    val flashes: Int,
    /** The hardest send per board. */
    val hardestByBoard: Map<String, Double>,
)

/** Last year from 1 January to [until], the same span as this year's review. */
data class YearComparison(
    val year: Int,
    val until: LocalDate,
    val totals: YearTotals,
    /** Displayed grades from last year's hardest send to this year's, per board on both; positive is harder. */
    val gradeStepsByBoard: Map<String, Int>,
)

data class YearRhythm(
    /** Climbing days per month, January first; months after [YearReview.until] count zero. */
    val daysPerMonth: List<Int>,
    /** 1–12; the earlier month wins a tie. Null without climbing days. */
    val busiestMonth: Int?,
    /** Weeks (Monday to Sunday) with at least one climbing day. */
    val activeWeeks: Int,
    /** Weeks of the year up to [YearReview.until], the first and last partial ones included. */
    val weeks: Int,
    /** The longest run of weeks in a row with a climbing day, and the Monday it started. */
    val longestWeekStreak: Int,
    val longestWeekStreakStart: LocalDate?,
    /** The weekday with the most climbing days; the earlier weekday wins a tie. */
    val favouriteWeekday: DayOfWeek?,
    val favouriteWeekdayDays: Int,
)

data class YearHighlights(
    /** The hardest flash per board, hardest first. */
    val hardestFlashes: List<YearSend>,
    /** The day with the most sends; the earlier day wins a tie. */
    val biggestDay: LocalDate?,
    val biggestDaySends: Int,
    /** The problem sent most often this year, at least twice. */
    val mostRepeated: RepeatedProblem?,
    /** The first send that took the most tries, at least [YearReviewComputer.PROJECT_MIN_TRIES]. */
    val longestProject: ProjectSend?,
)

data class RepeatedProblem(
    val name: String,
    val board: String,
    val angle: Int,
    val difficulty: Double?,
    val sends: Int,
)

data class ProjectSend(
    val send: YearSend,
    val tries: Int,
    /** The day of the first entry on the problem, which can lie in an earlier year. */
    val firstTry: LocalDate,
    /** Days with an entry on the problem up to the send. */
    val sessions: Int,
)

data class YearTraining(
    /** Finished off-board workouts, the days they were on and their minutes. */
    val workouts: Int,
    val workoutDays: Int,
    val workoutMinutes: Int,
    /** The newest measured best value per exercise this year, newest first; self-estimates are left out. */
    val bestValues: List<Benchmark>,
    /** Comeback plans that reached their last stage and were ended this year. */
    val comebacksCompleted: Int,
) {
    val isEmpty: Boolean get() = workouts == 0 && bestValues.isEmpty() && comebacksCompleted == 0
}

/** A timed board session: when it started (as stored) and how long it was active. */
data class TimedSession(val startedAt: String, val activeSeconds: Long, val ended: Boolean = true)
