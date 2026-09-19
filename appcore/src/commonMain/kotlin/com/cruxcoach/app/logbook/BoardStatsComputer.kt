package com.cruxcoach.app.logbook

import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.KilterGradeMapper
import kotlin.math.floor
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * Port of Android `BoardStatsComputer`. The denominators differ per statistic
 * on purpose and are reproduced exactly:
 *  - totalSends / flashRate / pyramid / progression / unique-by-grade: send ROWS in the period
 *  - totalAttempts: sum of bidCount (min 1) over ALL rows in the period
 *  - uniqueClimbs / sessionCount / angle distribution / activity / weekly volume: all rows
 *  - grade outcomes / outcome distribution: ONE best outcome per (board family, climb)
 *  - flash eligibility, period comparison, personal records: the FULL history
 */
object BoardStatsComputer {

    const val MEDIUM_THRESHOLD = 16.0
    const val HARD_THRESHOLD = 21.0
    const val ELITE_THRESHOLD = 27.0
    const val AVG_WINDOW_WEEKS = 8
    private const val DAYS_PER_WEEK = 7

    fun systemToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

    fun formatDifficulty(diffAvg: Double, scale: GradeScale): String = when (scale) {
        GradeScale.V_SCALE -> KilterGradeMapper.difficultyToVScale(diffAvg)
        GradeScale.FRENCH -> KilterGradeMapper.difficultyToFont(diffAvg)
    }

    fun computeStats(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        today: LocalDate = systemToday(),
    ): BoardLogbookStats {
        val filtered = filterByInterval(ascents, interval, customFrom, customTo, today)
        if (filtered.isEmpty()) return BoardLogbookStats()

        val sends = filtered.filter { it.isSend }
        val totalSends = sends.size
        // True flashes need the FULL history: a first-try send today after
        // attempts in an earlier session is a redpoint.
        val flashUuids = trueFlashUuids(ascents)
        val flashCount = sends.count { it.uuid in flashUuids }
        val hardestDiff = sends.mapNotNull { it.difficultyAverage }.maxOrNull()

        val problemOutcomes = distinctProblemOutcomes(filtered, flashUuids)
        val outcomeSends = problemOutcomes.filter { it.isSend }
        val outcomeBids = problemOutcomes.filterNot { it.isSend }

        return BoardLogbookStats(
            hardestGrade = hardestDiff?.let { formatDifficulty(it, gradeScale) },
            hardestDifficultyInt = hardestDiff?.toInt() ?: 0,
            totalSends = totalSends,
            // bidCount is attempts-to-outcome for open bids and sends alike;
            // counting rows would make "X, X, send" look like one attempt.
            totalAttempts = filtered.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            boulderSends = sends.count { it.framesCount <= 1L },
            routeSends = sends.count { it.framesCount > 1L },
            flashRate = if (totalSends > 0) flashCount.toFloat() / totalSends * 100f else 0f,
            uniqueClimbs = filtered.map { it.climbUuid }.distinct().size,
            sessionCount = filtered.map { it.climbedAt.take(10) }.distinct().size,
            gradePyramid = computeGradePyramid(sends, gradeScale),
            angleDistribution = filtered.groupBy { it.angle.toInt() }
                .map { (angle, list) -> AngleDistEntry(angle, list.size) }
                .sortedBy { it.angle },
            sendsOverTime = computeSendsOverTime(filtered, interval),
            activityMap = filtered.mapNotNull { parseDate(it.climbedAt) }
                .groupBy { it.toString() }
                .mapValues { it.value.size },
            gradeOutcomes = computeGradeOutcomes(outcomeSends + outcomeBids, gradeScale, flashUuids),
            outcomeDistribution = OutcomeDistribution(
                flashes = outcomeSends.count { it.uuid in flashUuids },
                redpoints = outcomeSends.count { it.uuid !in flashUuids },
                attempts = outcomeBids.size,
            ),
            weeklyVolume = computeWeeklyVolume(filtered),
            gradeProgression = computeGradeProgression(sends, interval),
            uniqueClimbsByGrade = computeUniqueClimbsByGrade(sends, gradeScale),
            periodComparison = computePeriodComparison(ascents, interval, today, flashUuids),
            personalRecords = computePersonalRecords(ascents, gradeScale, today, flashUuids),
        )
    }

    /** Per-board headline numbers; deliberately NOT scoped to a board filter. */
    fun computeBoardComparison(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        today: LocalDate = systemToday(),
    ): List<BoardComparisonEntry> =
        filterByInterval(ascents, interval, customFrom, customTo, today)
            .groupBy { it.boardBrand }
            .map { (brand, entries) ->
                val sends = entries.filter { it.isSend }
                val hardestDiff = sends.mapNotNull { it.difficultyAverage }.maxOrNull()
                BoardComparisonEntry(
                    boardBrand = brand,
                    sendCount = sends.size,
                    attemptCount = entries.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
                    hardestGrade = hardestDiff?.let { formatDifficulty(it, gradeScale) },
                    hardestDifficultyInt = hardestDiff?.toInt() ?: 0,
                )
            }
            .sortedWith(
                compareByDescending<BoardComparisonEntry> { it.sendCount }.thenByDescending { it.attemptCount }
            )

    fun filterByInterval(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        today: LocalDate = systemToday(),
    ): List<AscentWithClimb> {
        if (customFrom != null && customTo != null) {
            val from = customFrom.toString()
            val to = customTo.plus(DatePeriod(days = 1)).toString()
            return ascents.filter { it.climbedAt.take(10) in from..to }
        }
        val cutoffDays = interval.days ?: return ascents
        val cutoff = today.minus(DatePeriod(days = cutoffDays)).toString()
        return ascents.filter { it.climbedAt.take(10) >= cutoff }
    }

    /** Grouped by the DISPLAYED grade label so Font grades sharing a V bucket stay apart. */
    fun computeGradePyramid(sends: List<AscentWithClimb>, gradeScale: GradeScale): List<BoardGradePyramidEntry> =
        sends.filter { it.difficultyAverage != null }
            .groupBy { formatDifficulty(it.difficultyAverage!!, gradeScale) }
            .map { (grade, list) ->
                BoardGradePyramidEntry(grade, list.size, list.minOf { javaRound(it.difficultyAverage!!) })
            }
            .sortedBy { it.difficultyInt }

    /**
     * Sends only. (docs/releases/0.2.3-pre-release.md still lists "includes
     * failed-attempt entries" as an open finding; the pinned Android code and
     * its unit test already exclude attempts, and the code is authoritative.)
     *
     * Android orders DAY buckets by their "dd.MM" text and ISO_WEEK buckets by
     * their "CW n" text, and merges equal week numbers of different years;
     * that ordering is kept so both charts read identically.
     */
    fun computeSendsOverTime(ascents: List<AscentWithClimb>, interval: StatsTimeInterval): List<TimeBucketEntry> {
        val parsed = ascents.filter { it.isSend }.mapNotNull { parseDate(it.climbedAt) }
        if (parsed.isEmpty()) return emptyList()
        return when (interval) {
            StatsTimeInterval.DAYS_30 ->
                parsed.groupBy { it }
                    .map { (date, list) ->
                        TimeBucketEntry(TimeBucketKind.DAY, date.year, monthNumber(date), date.day, 0, list.size)
                    }
                    .sortedBy { twoDigits(it.day) + "." + twoDigits(it.month) }
            StatsTimeInterval.DAYS_90 ->
                parsed.groupBy { isoWeek(it) }
                    .map { (week, list) -> TimeBucketEntry(TimeBucketKind.ISO_WEEK, 0, 0, 0, week, list.size) }
                    .sortedBy { it.isoWeek.toString() }
            StatsTimeInterval.YEAR_1, StatsTimeInterval.ALL ->
                parsed.groupBy { it.year to monthNumber(it) }
                    .map { (key, list) -> TimeBucketEntry(TimeBucketKind.MONTH, key.first, key.second, 0, 0, list.size) }
                    .sortedWith(compareBy({ it.year }, { it.month }))
        }
    }

    /** One problem per board family, regardless of repeats or angle changes:
     *  its best result within the period (flash > send > attempt). */
    private fun distinctProblemOutcomes(
        entries: List<AscentWithClimb>,
        flashUuids: Set<String>,
    ): List<AscentWithClimb> = entries
        .groupBy { it.boardBrand to it.climbUuid }
        .values.map { problem ->
            problem.minWith(
                compareByDescending<AscentWithClimb> {
                    when {
                        it.uuid in flashUuids -> 2
                        it.isSend -> 1
                        else -> 0
                    }
                }.thenBy { it.climbedAt }.thenBy { it.uuid },
            )
        }

    private fun computeGradeOutcomes(
        outcomes: List<AscentWithClimb>,
        gradeScale: GradeScale,
        flashUuids: Set<String>,
    ): List<GradeOutcomeEntry> =
        outcomes.filter { it.difficultyAverage != null }
            .groupBy { formatDifficulty(it.difficultyAverage!!, gradeScale) }
            .map { (grade, entries) ->
                GradeOutcomeEntry(
                    grade = grade,
                    difficultyInt = entries.minOf { javaRound(it.difficultyAverage!!) },
                    flashCount = entries.count { it.isSend && it.uuid in flashUuids },
                    redpointCount = entries.count { it.isSend && it.uuid !in flashUuids },
                    attemptCount = entries.count { !it.isSend },
                )
            }
            .sortedBy { it.difficultyInt }

    private fun computeWeeklyVolume(filtered: List<AscentWithClimb>): List<WeeklyVolumeEntry> =
        filtered.mapNotNull { a ->
            val date = parseDate(a.climbedAt) ?: return@mapNotNull null
            val diff = a.difficultyAverage ?: return@mapNotNull null
            (weekBasedYear(date) to isoWeek(date)) to diff
        }
            .groupBy({ it.first }, { it.second })
            .map { (key, diffs) ->
                WeeklyVolumeEntry(
                    weekBasedYear = key.first,
                    isoWeek = key.second,
                    easyCount = diffs.count { it < MEDIUM_THRESHOLD },
                    mediumCount = diffs.count { it >= MEDIUM_THRESHOLD && it < HARD_THRESHOLD },
                    hardCount = diffs.count { it >= HARD_THRESHOLD && it < ELITE_THRESHOLD },
                    eliteCount = diffs.count { it >= ELITE_THRESHOLD },
                )
            }
            .sortedWith(compareBy({ it.weekBasedYear }, { it.isoWeek }))

    private class DatedSend(val date: LocalDate, val difficulty: Double, val climbUuid: String, val angle: Long)

    private fun computeGradeProgression(
        sends: List<AscentWithClimb>,
        interval: StatsTimeInterval,
    ): List<GradeProgressionPoint> {
        val datedSends = sends.mapNotNull { ascent ->
            val date = parseDate(ascent.climbedAt) ?: return@mapNotNull null
            val difficulty = ascent.difficultyAverage ?: return@mapNotNull null
            DatedSend(date, difficulty, ascent.climbUuid, ascent.angle)
        }
        if (datedSends.isEmpty()) return emptyList()

        return datedSends.map { mondayOf(it.date) }.distinct().sorted().map { weekStart ->
            val windowStart = weekStart.minus(DatePeriod(days = 3 * DAYS_PER_WEEK))
            val windowEnd = weekStart.plus(DatePeriod(days = 6))
            val top = datedSends
                .filter { it.date >= windowStart && it.date <= windowEnd }
                .groupBy { it.climbUuid to it.angle }
                .values
                .map { repeats -> repeats.maxOf { it.difficulty } }
                .sortedDescending()
                .take(3)
            // Median-like: one outlier must not dominate confirmed lower sends.
            val level = when (top.size) {
                1 -> top[0]
                2 -> top.average()
                else -> top[1]
            }
            GradeProgressionPoint(
                isoWeek = isoWeek(weekStart),
                weekBasedYear = weekBasedYear(weekStart),
                showYear = interval != StatsTimeInterval.DAYS_30,
                weekStart = weekStart,
                performanceDifficulty = level,
            )
        }
    }

    private fun computeUniqueClimbsByGrade(sends: List<AscentWithClimb>, gradeScale: GradeScale): List<UniqueClimbEntry> =
        sends.filter { it.difficultyAverage != null }
            .groupBy { formatDifficulty(it.difficultyAverage!!, gradeScale) }
            .map { (grade, entries) ->
                UniqueClimbEntry(
                    grade = grade,
                    difficultyInt = entries.minOf { javaRound(it.difficultyAverage!!) },
                    uniqueCount = entries.map { it.climbUuid }.distinct().size,
                    totalSends = entries.size,
                )
            }
            .sortedBy { it.difficultyInt }

    private fun computePeriodComparison(
        allAscents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        today: LocalDate,
        flashUuids: Set<String>,
    ): PeriodComparison? {
        val days = interval.days ?: return null
        val currentStart = today.minus(DatePeriod(days = days))
        val currentStr = currentStart.toString()
        val previousStr = currentStart.minus(DatePeriod(days = days)).toString()
        val nowStr = today.toString()

        // Both ranges are closed, so the boundary day belongs to both periods, as on Android.
        val current = allAscents.filter { it.climbedAt.take(10) in currentStr..nowStr }
        val previous = allAscents.filter { it.climbedAt.take(10) in previousStr..currentStr }
        if (current.isEmpty() && previous.isEmpty()) return null

        fun flashRate(rows: List<AscentWithClimb>): Float {
            val sends = rows.count { it.isSend }
            val flashes = rows.count { it.isSend && it.uuid in flashUuids }
            return if (sends > 0) flashes.toFloat() / sends * 100f else 0f
        }
        fun hardest(rows: List<AscentWithClimb>): Int =
            rows.filter { it.isSend }.mapNotNull { it.difficultyAverage?.toInt() }.maxOrNull() ?: 0

        return PeriodComparison(
            totalSendsDelta = current.count { it.isSend } - previous.count { it.isSend },
            flashRateDelta = flashRate(current) - flashRate(previous),
            hardestGradeDelta = hardest(current) - hardest(previous),
            uniqueClimbsDelta = current.map { it.climbUuid }.distinct().size -
                previous.map { it.climbUuid }.distinct().size,
            interval = interval,
        )
    }

    private fun computePersonalRecords(
        allAscents: List<AscentWithClimb>,
        gradeScale: GradeScale,
        today: LocalDate,
        flashUuids: Set<String>,
    ): PersonalRecords {
        // A projecting-only day is a session even without a send.
        val sessionDates = allAscents.mapNotNull { parseDate(it.climbedAt) }.distinct().sorted()
        val avgSessionsPerWeek = computeAvgSessionsPerWeek(sessionDates, today)
        val weekStreak = computeWeekStreak(sessionDates, today)

        val sends = allAscents.filter { it.isSend }
        if (sends.isEmpty()) return PersonalRecords(avgSessionsPerWeek = avgSessionsPerWeek, weekStreak = weekStreak)

        val hardestFlash = sends.filter { it.uuid in flashUuids && it.difficultyAverage != null }
            .maxByOrNull { it.difficultyAverage!! }
        val bestDay = sends.groupBy { it.climbedAt.take(10) }.maxByOrNull { it.value.size }

        return PersonalRecords(
            hardestFlashGrade = hardestFlash?.difficultyAverage?.let { formatDifficulty(it, gradeScale) },
            hardestFlashDifficulty = hardestFlash?.difficultyAverage?.toInt() ?: 0,
            mostSendsInDay = bestDay?.value?.size ?: 0,
            mostSendsDate = bestDay?.key,
            avgSessionsPerWeek = avgSessionsPerWeek,
            weekStreak = weekStreak,
        )
    }

    /**
     * Uuids of TRUE flashes: the very first logbook contact with that
     * (board, climb, angle) over the FULL history is a send with at most one
     * attempt.
     */
    fun trueFlashUuids(allAscents: List<AscentWithClimb>): Set<String> =
        allAscents
            .groupBy { Triple(it.boardBrand, it.climbUuid, it.angle) }
            .mapNotNull { (_, entries) ->
                val first = entries.minByOrNull { it.climbedAt } ?: return@mapNotNull null
                first.uuid.takeIf { first.isSend && first.bidCount <= 1L }
            }
            .toSet()

    /** Distinct session days in the last 8 weeks divided by the elapsed window, not the active span. */
    internal fun computeAvgSessionsPerWeek(sessionDates: List<LocalDate>, today: LocalDate): Double {
        if (sessionDates.isEmpty()) return 0.0
        val windowStart = today.minus(DatePeriod(days = AVG_WINDOW_WEEKS * DAYS_PER_WEEK - 1))
        val recent = sessionDates.count { it >= windowStart }
        if (recent == 0) return 0.0
        val daysSpanned = windowStart.daysUntil(today) + 1
        val weeks = (daysSpanned.toDouble() / DAYS_PER_WEEK).coerceAtLeast(1.0)
        return recent / weeks
    }

    /** Consecutive ISO weeks with a session; rest days never break it, a full week off does. */
    fun computeWeekStreak(sessionDates: List<LocalDate>, today: LocalDate): Int {
        if (sessionDates.isEmpty()) return 0
        val weekStarts = sessionDates.map { mondayOf(it) }.distinct().sorted()
        if (weekStarts.last().daysUntil(mondayOf(today)) > DAYS_PER_WEEK) return 0
        var streak = 1
        for (i in weekStarts.size - 1 downTo 1) {
            if (weekStarts[i - 1].daysUntil(weekStarts[i]) == DAYS_PER_WEEK) streak++ else break
        }
        return streak
    }

    internal fun parseDate(dateStr: String): LocalDate? = try {
        LocalDate.parse(dateStr.take(10))
    } catch (_: Exception) {
        null
    }

    internal fun mondayOf(date: LocalDate): LocalDate = date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))

    // ISO-8601: a week belongs to the year that holds its Thursday.
    internal fun weekBasedYear(date: LocalDate): Int = mondayOf(date).plus(DatePeriod(days = 3)).year

    internal fun isoWeek(date: LocalDate): Int = (mondayOf(date).plus(DatePeriod(days = 3)).dayOfYear - 1) / 7 + 1

    private fun monthNumber(date: LocalDate): Int = date.month.ordinal + 1

    private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()

    /** `Math.round(double)`: half up, unlike kotlin.math.round (half even). */
    private fun javaRound(value: Double): Int = floor(value + 0.5).toInt()
}
