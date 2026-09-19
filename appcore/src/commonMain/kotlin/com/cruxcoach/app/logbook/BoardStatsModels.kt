package com.cruxcoach.app.logbook

import kotlinx.datetime.LocalDate

/** Days are part of the Android contract; Swift owns the labels. */
enum class StatsTimeInterval(val days: Int?) {
    ALL(null),
    DAYS_30(30),
    DAYS_90(90),
    YEAR_1(365),
}

enum class LogbookOutcomeFilter { ALL, SENDS, ATTEMPTS }

data class BoardGradePyramidEntry(val grade: String, val count: Int, val difficultyInt: Int)

data class AngleDistEntry(val angle: Int, val count: Int)

enum class TimeBucketKind { DAY, ISO_WEEK, MONTH }

/**
 * One sends-over-time bucket. Android renders DAY as "dd.MM", ISO_WEEK as
 * "CW n" and MONTH as "Mon 'yy"; here only the numbers travel. Fields that do
 * not apply to [kind] are 0. List order already matches Android.
 */
data class TimeBucketEntry(
    val kind: TimeBucketKind,
    val year: Int,
    val month: Int,
    val day: Int,
    val isoWeek: Int,
    val count: Int,
)

/** Distinct problems per grade, classified by best outcome: flash / sent / attempted. */
data class GradeOutcomeEntry(
    val grade: String,
    val difficultyInt: Int,
    val flashCount: Int,
    val redpointCount: Int,
    val attemptCount: Int,
) {
    val total: Int get() = flashCount + redpointCount + attemptCount
}

/** Distinct problem outcomes across all grades, including ungraded problems. */
data class OutcomeDistribution(val flashes: Int, val redpoints: Int, val attempts: Int) {
    val total: Int get() = flashes + redpoints + attempts
}

data class WeeklyVolumeEntry(
    val weekBasedYear: Int,
    val isoWeek: Int,
    val easyCount: Int,
    val mediumCount: Int,
    val hardCount: Int,
    val eliteCount: Int,
) {
    val total: Int get() = easyCount + mediumCount + hardCount + eliteCount
}

/** Rolling level from the best distinct sends of the preceding four ISO weeks.
 *  The real Monday preserves inactivity gaps on the chart axis. */
data class GradeProgressionPoint(
    val isoWeek: Int,
    val weekBasedYear: Int,
    /** Android shows the year suffix for every interval except DAYS_30. */
    val showYear: Boolean,
    val weekStart: LocalDate,
    val performanceDifficulty: Double,
) {
    val weekStartIso: String get() = weekStart.toString()
}

data class UniqueClimbEntry(val grade: String, val difficultyInt: Int, val uniqueCount: Int, val totalSends: Int)

/** Period-over-period deltas; the periods are [interval] and the one before it. */
data class PeriodComparison(
    val totalSendsDelta: Int,
    val flashRateDelta: Float,
    val hardestGradeDelta: Int,
    val uniqueClimbsDelta: Int,
    val interval: StatsTimeInterval,
)

data class PersonalRecords(
    val hardestFlashGrade: String? = null,
    val hardestFlashDifficulty: Int = 0,
    val mostSendsInDay: Int = 0,
    val mostSendsDate: String? = null,
    /** Distinct active days (sends OR attempts) per week over the last 8 weeks. */
    val avgSessionsPerWeek: Double = 0.0,
    /** Consecutive ISO weeks with a session, reaching into this or last week. */
    val weekStreak: Int = 0,
)

data class BoardComparisonEntry(
    val boardBrand: String,
    val sendCount: Int,
    val attemptCount: Int,
    val hardestGrade: String?,
    val hardestDifficultyInt: Int,
)

data class BoardLogbookStats(
    val hardestGrade: String? = null,
    val hardestDifficultyInt: Int = 0,
    val totalSends: Int = 0,
    val totalAttempts: Int = 0,
    val boulderSends: Int = 0,
    val routeSends: Int = 0,
    val flashRate: Float = 0f,
    val uniqueClimbs: Int = 0,
    val sessionCount: Int = 0,
    val gradePyramid: List<BoardGradePyramidEntry> = emptyList(),
    val angleDistribution: List<AngleDistEntry> = emptyList(),
    val sendsOverTime: List<TimeBucketEntry> = emptyList(),
    /** ISO date → logbook entries that day (sends and attempts). */
    val activityMap: Map<String, Int> = emptyMap(),
    val gradeOutcomes: List<GradeOutcomeEntry> = emptyList(),
    val outcomeDistribution: OutcomeDistribution = OutcomeDistribution(0, 0, 0),
    val weeklyVolume: List<WeeklyVolumeEntry> = emptyList(),
    val gradeProgression: List<GradeProgressionPoint> = emptyList(),
    val uniqueClimbsByGrade: List<UniqueClimbEntry> = emptyList(),
    val periodComparison: PeriodComparison? = null,
    val personalRecords: PersonalRecords = PersonalRecords(),
)

/** X position (0..1) of a progression point on a real time axis, so idle weeks stay visible gaps. */
fun progressionXFraction(entries: List<GradeProgressionPoint>, index: Int): Float {
    if (entries.size < 2 || index !in entries.indices) return 0f
    val firstDay = entries.first().weekStart.toEpochDays()
    val span = (entries.last().weekStart.toEpochDays() - firstDay).coerceAtLeast(1L)
    return ((entries[index].weekStart.toEpochDays() - firstDay).toDouble() / span).toFloat()
}
