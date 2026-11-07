package com.cruxcoach.android.ui.board

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The days the statistics cover: the chosen period, from the first entry at
 * the earliest, up to today or the end of a custom range. Its length decides
 * whether the charts over time count per week or, over more than half a
 * year, per month.
 */
data class StatsPeriod(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    val perMonth: Boolean get() = days > WEEKLY_MAX_DAYS

    /** The first day the charts draw: the Monday of the first week, or the first of the first month. */
    val axisStart: LocalDate
        get() = if (perMonth) start.withDayOfMonth(1) else start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The last day the charts draw: the Sunday of the last week, or the last of the last month. */
    val axisEnd: LocalDate
        get() = if (perMonth) end.with(TemporalAdjusters.lastDayOfMonth()) else end.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    companion object {
        /** Periods longer than this are counted per month instead of per week. */
        const val WEEKLY_MAX_DAYS = 26 * 7
    }
}

/** Distinct problems per grade, classified by best outcome: flash / sent / attempted. */
data class GradeOutcomeEntry(
    val grade: String,
    val difficultyInt: Int,
    val flashCount: Int,
    val redpointCount: Int,
    val attemptCount: Int
) {
    val total: Int get() = flashCount + redpointCount + attemptCount
}

/** The period's headline numbers, for the comparison with the period before. */
data class PeriodTotals(
    val sends: Int,
    val attempts: Int,
    val sessions: Int,
    val problems: Int,
    val flashRate: Float,
    val hardestDifficulty: Double? = null,
)

/**
 * Log entries of one time bucket by grade band: a week (from its Monday) or,
 * over long periods, a month (from its first day). Buckets without entries
 * are left out. Entries without a grade count apart, so the buckets add up
 * to all entries.
 */
data class VolumeBucket(
    val start: java.time.LocalDate,
    /** Length in days, so a chart can place the bucket on a time axis. */
    val days: Int,
    val easyCount: Int,
    val mediumCount: Int,
    val hardCount: Int,
    val eliteCount: Int,
    val ungradedCount: Int = 0,
) {
    val total: Int get() = easyCount + mediumCount + hardCount + eliteCount + ungradedCount
}

/** Rolling performance level from the best distinct sends in the preceding
 * four ISO calendar weeks. The real Monday preserves inactivity gaps. */
data class GradeProgressionPoint(
    val label: String,
    val weekStart: java.time.LocalDate,
    val performanceDifficulty: Double,
)

/** The bests of the chosen period and board. */
data class PersonalRecords(
    val hardestFlashGrade: String? = null,
    val hardestFlashDifficulty: Int = 0,
    val mostSendsInDay: Int = 0,
    val mostSendsDate: String? = null,
    /** Climbing days (days with sends OR attempts) per week on average over
     *  the period — day-streaks are meaningless for climbing (rest days are
     *  part of training); consistency per week is the honest signal. */
    val daysPerWeek: Double = 0.0,
    /** The most consecutive calendar weeks (ISO, Mon–Sun) with at least one
     *  climbing day inside the period. */
    val longestWeekStreak: Int = 0
)

/** One board family's headline numbers for the multi-board comparison row
 *  (shown only when the user has logged on >1 board). [boardBrand] is the
 *  raw wire value; the UI resolves it to a display name. */
data class BoardComparisonEntry(
    val boardBrand: String,
    val sendCount: Int,
    val attemptCount: Int,
    val hardestGrade: String?,
    val hardestDifficultyInt: Int
)

/**
 * Climbing days in one week, or per week on average in one month over long
 * periods; a month the period only partly covers counts only its days inside.
 */
data class DaysPerWeek(
    val start: java.time.LocalDate,
    /** Length in days of the week or month, for its place on a time axis. */
    val days: Int,
    val perWeek: Double,
)

/**
 * The views of the activity card: the days as a calendar, climbing days per
 * week over time, all entries or only the sends over time.
 */
enum class ActivityView {
    CALENDAR,
    DAYS_PER_WEEK,
    ENTRIES,
    SENDS,
}

/** The views of the grades card: each problem once, or every send with repeats. */
enum class GradesView {
    PROBLEMS,
    SENDS,
}

enum class CrossBoardView {
    TRIES_UNTIL_SEND,
    TRIES_PER_GRADE,
    PER_MONTH,
}

/** Enhanced session summary for end-of-session sheet */
data class EnhancedSessionSummary(
    val warmupCount: Int = 0,
    val optimalCount: Int = 0,
    val limitCount: Int = 0,
    val sessionType: com.cruxcoach.domain.board.SessionType = com.cruxcoach.domain.board.SessionType.PYRAMID_SESSION,
    val hardestSendGrade: String? = null,
    val hardestSendName: String? = null,
    val flashCount: Int = 0,
    val totalSends: Int = 0,
    val totalAttempts: Int = 0,
    val uniqueClimbs: Int = 0,
    val gradeDistribution: List<BoardGradePyramidEntry> = emptyList(),
    /** The shareable card of this session; null without the logbook history. */
    val recap: SessionRecap? = null,
) {
    val zoneTotal: Int get() = warmupCount + optimalCount + limitCount
}
