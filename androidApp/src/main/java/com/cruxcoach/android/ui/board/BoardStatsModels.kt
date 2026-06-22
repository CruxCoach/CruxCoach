package com.cruxcoach.android.ui.board

/** Outcome breakdown per grade: flash / redpoint / attempt counts */
data class GradeOutcomeEntry(
    val grade: String,
    val difficultyInt: Int,
    val flashCount: Int,
    val redpointCount: Int,
    val attemptCount: Int
) {
    val total: Int get() = flashCount + redpointCount + attemptCount
}

/** Overall outcome distribution across all grades */
data class OutcomeDistribution(
    val flashes: Int,
    val redpoints: Int,
    val attempts: Int
) {
    val total: Int get() = flashes + redpoints + attempts
}

/** Weekly volume entry grouped by grade band */
data class WeeklyVolumeEntry(
    val weekLabel: String,
    val easyCount: Int,
    val mediumCount: Int,
    val hardCount: Int,
    val eliteCount: Int
) {
    val total: Int get() = easyCount + mediumCount + hardCount + eliteCount
}

/** Grade progression point — hardest send per time bucket */
data class GradeProgressionPoint(
    val label: String,
    val hardestDifficulty: Double
)

/** Unique climb count vs total sends per grade */
data class UniqueClimbEntry(
    val grade: String,
    val difficultyInt: Int,
    val uniqueCount: Int,
    val totalSends: Int
)

/** Period-over-period comparison deltas */
data class PeriodComparison(
    val totalSendsDelta: Int,
    val flashRateDelta: Float,
    val hardestGradeDelta: Int,
    val uniqueClimbsDelta: Int,
    val currentLabel: String,
    val previousLabel: String
)

/** Personal records across all time */
data class PersonalRecords(
    val hardestFlashGrade: String? = null,
    val hardestFlashDifficulty: Int = 0,
    val mostSendsInDay: Int = 0,
    val mostSendsDate: String? = null,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0
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

/** Chart view selectors for dropdown sections */
enum class GradeChartView {
    PYRAMID,
    FLASH_SEND_ATTEMPT,
    OUTCOME_DONUT,
    UNIQUE_CLIMBS
}

enum class TimeChartView {
    SENDS_OVER_TIME,
    WEEKLY_VOLUME,
    GRADE_PROGRESSION
}

enum class DistributionChartView {
    ANGLE,
    PERIOD_COMPARISON
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
    val gradeDistribution: List<BoardGradePyramidEntry> = emptyList()
) {
    val zoneTotal: Int get() = warmupCount + optimalCount + limitCount
}
