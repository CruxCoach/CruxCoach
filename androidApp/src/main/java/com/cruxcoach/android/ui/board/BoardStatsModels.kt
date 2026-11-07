package com.cruxcoach.android.ui.board

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
 * are left out.
 */
data class VolumeBucket(
    val start: java.time.LocalDate,
    /** Length in days, so a chart can place the bucket on a time axis. */
    val days: Int,
    val easyCount: Int,
    val mediumCount: Int,
    val hardCount: Int,
    val eliteCount: Int
) {
    val total: Int get() = easyCount + mediumCount + hardCount + eliteCount
}

/** Rolling performance level from the best distinct sends in the preceding
 * four ISO calendar weeks. The real Monday preserves inactivity gaps. */
data class GradeProgressionPoint(
    val label: String,
    val weekStart: java.time.LocalDate,
    val performanceDifficulty: Double,
)

/** Personal records across all time */
data class PersonalRecords(
    val hardestFlashGrade: String? = null,
    val hardestFlashDifficulty: Int = 0,
    val mostSendsInDay: Int = 0,
    val mostSendsDate: String? = null,
    /** Average training sessions (distinct active days, sends OR attempts)
     *  per week over the last 8 weeks — day-streaks are meaningless for
     *  climbing (rest days are part of training); consistency per week is
     *  the honest signal. */
    val avgSessionsPerWeek: Double = 0.0,
    /** Consecutive calendar weeks (ISO, Mon–Sun) with ≥1 session, counting
     *  only if the run reaches into this or the previous week. */
    val weekStreak: Int = 0
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

/** Climbing days per week in one week, or on average in one month over long periods. */
data class DaysPerWeek(
    val start: java.time.LocalDate,
    /** Length in days of the week or month. */
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
