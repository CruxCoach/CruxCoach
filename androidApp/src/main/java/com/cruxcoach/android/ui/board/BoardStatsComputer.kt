package com.cruxcoach.android.ui.board

import android.content.Context
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure stateless computation of all board statistics from a list of ascents/bids.
 * Extracted from BoardLogbookViewModel to keep it under 500 lines and enable unit testing.
 */
object BoardStatsComputer {

    private val WEEK_FIELD = WeekFields.ISO.weekOfWeekBasedYear()
    private val WEEK_BASED_YEAR_FIELD = WeekFields.ISO.weekBasedYear()

    // Grade band thresholds (Kilter difficulty values)
    // Easy: V0-V2 (diff < 16), Medium: V3-V5 (16..20), Hard: V6-V8 (21..26), Elite: V9+ (27+)
    private const val MEDIUM_THRESHOLD = 16.0
    private const val HARD_THRESHOLD = 21.0
    private const val ELITE_THRESHOLD = 27.0
    private const val AVG_WINDOW_WEEKS = 8
    private const val DAYS_PER_WEEK = 7L
    /** The shortest period with an average of climbing days per week. */
    internal const val MIN_AVERAGE_DAYS = 14

    fun computeStats(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        context: Context? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): BoardLogbookStats {
        val filtered = filterByInterval(ascents, interval, customFrom, customTo, clock)
        if (filtered.isEmpty()) return BoardLogbookStats()

        val sends = filtered.filter { it.isSend }
        val totalSends = sends.size
        val boulderSends = sends.count { it.framesCount <= 1L }
        val routeSends = sends.count { it.framesCount > 1L }
        // True flashes need the FULL history — a first-try send today after
        // attempts in an earlier session is a redpoint, not a flash.
        val flashUuids = trueFlashUuids(ascents)
        val flashCount = sends.count { it.uuid in flashUuids }
        val flashRate = if (totalSends > 0) flashCount.toFloat() / totalSends * 100f else 0f
        // A problem is a climb on one board, as in the grades chart.
        val uniqueClimbs = filtered.map { it.boardBrand to it.climbUuid }.distinct().size
        val sessionCount = filtered.map { it.climbedAt.take(10) }.distinct().size

        val hardestDiff = sends.mapNotNull { it.difficultyAverage }.maxOrNull()
        val hardestGrade = hardestDiff?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) }
        val hardestDiffInt = hardestDiff?.toInt() ?: 0

        val angleDist = computeAngleDistribution(filtered)
        val activityMap = computeActivityMap(filtered)

        // New extended stats
        val problemOutcomes = distinctProblemOutcomes(filtered, flashUuids)
        val outcomeSends = problemOutcomes.filter { it.isSend }
        val outcomeBids = problemOutcomes.filterNot { it.isSend }
        val gradeOutcomes = computeGradeOutcomes(outcomeSends, outcomeBids, gradeScale, flashUuids)
        val period = periodOf(ascents, filtered, interval, customFrom, customTo, clock)
        val daysPerWeek = computeDaysPerWeek(filtered, period)
        val entriesOverTime = computeVolume(filtered, period)
        val sendsOverTime = computeVolume(sends, period)
        val gradeProgression = computeGradeProgression(sends, interval, context)
        val personalRecords = computePersonalRecords(filtered, period, gradeScale, flashUuids)

        return BoardLogbookStats(
            hardestGrade = hardestGrade,
            hardestDifficultyInt = hardestDiffInt,
            hardestDifficulty = hardestDiff,
            totalSends = totalSends,
            // bidCount is attempts-to-outcome for both open bids and sends.
            // Counting rows made a consolidated "X, X, send" sequence look
            // like one attempt (or zero after the bid became a send).
            totalAttempts = filtered.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            boulderSends = boulderSends,
            routeSends = routeSends,
            flashRate = flashRate,
            uniqueClimbs = uniqueClimbs,
            sessionCount = sessionCount,
            angleDistribution = angleDist,
            activityMap = activityMap,
            gradeOutcomes = gradeOutcomes,
            period = period,
            daysPerWeek = daysPerWeek,
            entriesOverTime = entriesOverTime,
            sendsOverTime = sendsOverTime,
            gradePyramid = computeGradePyramid(sends, gradeScale),
            previous = previousTotals(ascents, interval, customFrom, customTo, clock, flashUuids),
            gradeProgression = gradeProgression,
            personalRecords = personalRecords
        )
    }

    /**
     * The headline numbers of the period just before the chosen one, of the
     * same length: for 30 or 90 days, a year or a custom range. Null for the
     * whole logbook, or when nothing was logged in that period.
     */
    private fun previousTotals(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        customFrom: LocalDate?,
        customTo: LocalDate?,
        clock: Clock,
        flashUuids: Set<String>,
    ): PeriodTotals? {
        val (from, to) = if (customFrom != null && customTo != null) {
            val days = ChronoUnit.DAYS.between(customFrom, customTo) + 1
            customFrom.minusDays(days) to customFrom.minusDays(1)
        } else {
            val days = interval.days?.toLong() ?: return null
            val start = intervalStart(interval, clock) ?: return null
            start.minusDays(days) to start.minusDays(1)
        }
        val before = ascents.filter { it.climbedAt.take(10) in from.toString()..to.toString() }
        if (before.isEmpty()) return null
        val sends = before.filter { it.isSend }
        return PeriodTotals(
            sends = sends.size,
            attempts = before.sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
            sessions = before.map { it.climbedAt.take(10) }.distinct().size,
            problems = before.map { it.boardBrand to it.climbUuid }.distinct().size,
            flashRate = if (sends.isNotEmpty()) sends.count { it.uuid in flashUuids } * 100f / sends.size else 0f,
            hardestDifficulty = sends.mapNotNull { it.difficultyAverage }.maxOrNull(),
        )
    }

    /** Sessions per week over the last eight weeks or since the first, for the logbook overview (see [computeAvgSessionsPerWeek]). */
    fun sessionsPerWeekOf(ascents: List<AscentWithClimb>, clock: Clock = Clock.systemDefaultZone()): Double =
        computeAvgSessionsPerWeek(ascents.mapNotNull { parseDate(it.climbedAt) }.distinct().sorted(), clock)

    /**
     * Per-board summary for the multi-board comparison row in the stats sheet:
     * one entry per board family the user has logged on, with its send count
     * and hardest sent grade over the selected interval. Deliberately NOT
     * scoped to the per-board filter — the whole point is to compare boards
     * side by side. Sorted by send count desc so the most-used board leads.
     */
    fun computeBoardComparison(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<BoardComparisonEntry> {
        val filtered = filterByInterval(ascents, interval, customFrom, customTo, clock)
        return filtered
            .groupBy { it.boardBrand }
            .map { (brand, entries) ->
                val sends = entries.filter { it.isSend }
                val hardestDiff = sends.mapNotNull { it.difficultyAverage }.maxOrNull()
                BoardComparisonEntry(
                    boardBrand = brand,
                    sendCount = sends.size,
                    attemptCount = entries
                        .sumOf { it.bidCount.coerceAtLeast(1L) }.toInt(),
                    hardestGrade = hardestDiff?.let {
                        GradeDisplayHelper.formatDifficulty(it, gradeScale)
                    },
                    hardestDifficultyInt = hardestDiff?.toInt() ?: 0,
                )
            }
            .sortedWith(compareByDescending<BoardComparisonEntry> { it.sendCount }
                .thenByDescending { it.attemptCount })
    }

    /**
     * The rolling level of [computeGradeProgression] for each board over the
     * interval, so a progress chart can draw one line per board.
     */
    fun gradeProgressionByBoard(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        context: Context? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): Map<String, List<GradeProgressionPoint>> =
        filterByInterval(ascents, interval, customFrom, customTo, clock)
            .filter { it.isSend }
            .groupBy { it.boardBrand }
            .mapValues { (_, sends) -> computeGradeProgression(sends, interval, context) }
            .filterValues { it.isNotEmpty() }

    /** Consecutive weeks with a session up to now, over [ascents] (see [computeWeekStreak]). */
    fun weekStreakOf(ascents: List<AscentWithClimb>, clock: Clock = Clock.systemDefaultZone()): Int =
        computeWeekStreak(ascents.mapNotNull { parseDate(it.climbedAt) }.distinct().sorted(), clock)

    fun filterByInterval(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<AscentWithClimb> {
        // Custom date range overrides interval
        if (customFrom != null && customTo != null) {
            val from = customFrom.toString()
            // Both bounds are calendar days compared against the entry's date prefix, so the
            // end day is already inclusive. Adding a day counted the following day as well.
            val to = customTo.toString()
            return ascents.filter { it.climbedAt.take(10) in from..to }
        }
        val cutoff = intervalStart(interval, clock)?.toString() ?: return ascents
        return ascents.filter { it.climbedAt.take(10) >= cutoff }
    }

    /** The first day of [interval]: "30 days" are today and the 29 days before. Null for the whole logbook. */
    private fun intervalStart(interval: StatsTimeInterval, clock: Clock): LocalDate? =
        interval.days?.let { LocalDate.now(clock).minusDays(it - 1L) }

    /**
     * The days [filtered] covers: the chosen period, but not before the first
     * entry of [ascents], up to today or the end of a custom range (or a later
     * entry). Null without entries.
     */
    internal fun periodOf(
        ascents: List<AscentWithClimb>,
        filtered: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        customFrom: LocalDate? = null,
        customTo: LocalDate? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): StatsPeriod? {
        val days = filtered.mapNotNull { parseDate(it.climbedAt) }
        if (days.isEmpty()) return null
        val firstLogged = ascents.mapNotNull { parseDate(it.climbedAt) }.minOrNull() ?: days.min()
        val untilNow = maxOf(LocalDate.now(clock), days.max())
        val (from, to) = if (customFrom != null && customTo != null) {
            customFrom to minOf(customTo, untilNow)
        } else {
            intervalStart(interval, clock) to untilNow
        }
        val start = if (from == null || from.isBefore(firstLogged)) firstLogged else from
        return StatsPeriod(minOf(start, to), to)
    }

    /** Shared with [SessionSummaryBuilder] so the session summary groups its
     *  pyramid exactly like the stats sheet. */
    fun computeGradePyramid(
        sends: List<AscentWithClimb>,
        gradeScale: GradeScale
    ): List<BoardGradePyramidEntry> {
        // Group by the DISPLAYED grade label (difficulty → scale directly).
        // The old detour difficulty → V-scale → Font merged Font grades that
        // share a V bucket (7b and 7b+ are both V8) and re-labelled them with
        // the wrong half — a 7b top send showed as "7b+" in the pyramid while
        // the climb detail said 7b.
        return sends
            .filter { it.difficultyAverage != null }
            .groupBy { GradeDisplayHelper.formatDifficulty(it.difficultyAverage!!, gradeScale) }
            .map { (grade, list) ->
                BoardGradePyramidEntry(
                    grade = grade,
                    count = list.size,
                    difficultyInt = list.minOf { Math.round(it.difficultyAverage!!).toInt() },
                    // A problem is a climb on one board, as in the grades chart.
                    problems = list.map { it.boardBrand to it.climbUuid }.distinct().size,
                )
            }
            .sortedBy { it.difficultyInt }
    }

    private fun computeAngleDistribution(
        filtered: List<AscentWithClimb>
    ): List<AngleDistEntry> {
        return filtered
            .groupBy { it.angle.toInt() }
            .map { (angle, list) -> AngleDistEntry(angle, list.size) }
            .sortedBy { it.angle }
    }

    private fun computeActivityMap(
        filtered: List<AscentWithClimb>
    ): Map<LocalDate, Int> {
        return filtered.mapNotNull { parseDate(it.climbedAt) }
            .groupBy { it }
            .mapValues { it.value.size }
    }

    // --- New extended stats ---

    /** One problem per board, regardless of repeat sessions or angle changes.
     * Choose its best result within the interval; flash eligibility still uses full history. */
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
        sends: List<AscentWithClimb>,
        bids: List<AscentWithClimb>,
        gradeScale: GradeScale,
        flashUuids: Set<String>,
    ): List<GradeOutcomeEntry> {
        // Group by displayed grade label (see computeGradePyramid).
        val allWithDiff = (sends + bids).filter { it.difficultyAverage != null }
        val grouped = allWithDiff.groupBy {
            GradeDisplayHelper.formatDifficulty(it.difficultyAverage!!, gradeScale)
        }

        return grouped.map { (grade, entries) ->
            val diffInt = entries.minOf { Math.round(it.difficultyAverage!!).toInt() }
            val flashes = entries.count { it.isSend && it.uuid in flashUuids }
            val redpoints = entries.count { it.isSend && it.uuid !in flashUuids }
            val attempts = entries.count { !it.isSend }
            GradeOutcomeEntry(
                grade = grade,
                difficultyInt = diffInt,
                flashCount = flashes,
                redpointCount = redpoints,
                attemptCount = attempts
            )
        }.sortedBy { it.difficultyInt }
    }

    /**
     * Climbing days in every week of [period], weeks without a session as
     * zero; over more than half a year, per week on average in each month,
     * counting only the days of the month inside the period (so the current
     * month and the period's first one are not divided by days that are not
     * part of it).
     */
    internal fun computeDaysPerWeek(entries: List<AscentWithClimb>, period: StatsPeriod?): List<DaysPerWeek> {
        if (period == null) return emptyList()
        val days = entries.mapNotNull { parseDate(it.climbedAt) }.distinct()
        return if (period.perMonth) {
            val perMonth = days.groupingBy { it.withDayOfMonth(1) }.eachCount()
            generateSequence(period.axisStart) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(period.end) }
                .map { month ->
                    val from = maxOf(month, period.start)
                    val to = minOf(month.with(TemporalAdjusters.lastDayOfMonth()), period.end)
                    val inside = ChronoUnit.DAYS.between(from, to) + 1
                    DaysPerWeek(month, month.lengthOfMonth(), (perMonth[month] ?: 0) * DAYS_PER_WEEK.toDouble() / inside)
                }
                .toList()
        } else {
            val toMonday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            val perWeek = days.groupingBy { it.with(toMonday) }.eachCount()
            generateSequence(period.axisStart) { it.plusWeeks(1) }
                .takeWhile { !it.isAfter(period.end) }
                .map { week -> DaysPerWeek(week, DAYS_PER_WEEK.toInt(), (perWeek[week] ?: 0).toDouble()) }
                .toList()
        }
    }

    /**
     * Climbing days per week on average over the whole [period]; none for a
     * period shorter than [MIN_AVERAGE_DAYS], where two days would read as 7 a week.
     */
    private fun averageDaysPerWeek(entries: List<AscentWithClimb>, period: StatsPeriod?): Double {
        if (period == null || period.days < MIN_AVERAGE_DAYS) return 0.0
        val days = entries.mapNotNull { parseDate(it.climbedAt) }.distinct().size
        return days * DAYS_PER_WEEK.toDouble() / period.days
    }

    /**
     * Entries per week by grade band, or per month over periods of more than
     * half a year, so a long logbook does not end up as hairline columns.
     * Entries without a grade count apart, so the columns add up to all entries.
     */
    internal fun computeVolume(entries: List<AscentWithClimb>, period: StatsPeriod?): List<VolumeBucket> {
        val withDate = entries.mapNotNull { a ->
            val date = parseDate(a.climbedAt) ?: return@mapNotNull null
            date to a.difficultyAverage
        }
        if (withDate.isEmpty() || period == null) return emptyList()
        val perMonth = period.perMonth
        val toMonday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        return withDate
            .groupBy { (date, _) -> if (perMonth) date.withDayOfMonth(1) else date.with(toMonday) }
            .toSortedMap()
            .map { (start, inBucket) ->
                val diffs = inBucket.mapNotNull { it.second }
                VolumeBucket(
                    start = start,
                    days = if (perMonth) start.lengthOfMonth() else DAYS_PER_WEEK.toInt(),
                    easyCount = diffs.count { it < MEDIUM_THRESHOLD },
                    mediumCount = diffs.count { it >= MEDIUM_THRESHOLD && it < HARD_THRESHOLD },
                    hardCount = diffs.count { it >= HARD_THRESHOLD && it < ELITE_THRESHOLD },
                    eliteCount = diffs.count { it >= ELITE_THRESHOLD },
                    ungradedCount = inBucket.size - diffs.size,
                )
            }
    }

    private fun computeGradeProgression(
        sends: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        context: Context? = null
    ): List<GradeProgressionPoint> {
        if (sends.isEmpty()) return emptyList()
        data class DatedSend(
            val date: LocalDate,
            val difficulty: Double,
            val climbUuid: String,
            val angle: Long,
        )
        val datedSends = sends.mapNotNull { ascent ->
            val date = parseDate(ascent.climbedAt) ?: return@mapNotNull null
            val difficulty = ascent.difficultyAverage ?: return@mapNotNull null
            DatedSend(date, difficulty, ascent.climbUuid, ascent.angle)
        }
        if (datedSends.isEmpty()) return emptyList()

        val toMonday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val activeWeeks = datedSends.map { it.date.with(toMonday) }.distinct().sorted()
        return activeWeeks.map { weekStart ->
            val windowStart = weekStart.minusWeeks(3)
            val windowEnd = weekStart.plusDays(6)
            val top = datedSends.asSequence()
                .filter { !it.date.isBefore(windowStart) && !it.date.isAfter(windowEnd) }
                .groupBy { it.climbUuid to it.angle }
                .values
                .map { repeats -> repeats.maxOf { it.difficulty } }
                .sortedDescending()
                .take(3)
            val level = when (top.size) {
                1 -> top[0]
                2 -> top.average()
                else -> top[1]
            }
            val week = weekStart.get(WEEK_FIELD)
            val weekBasedYear = weekStart.get(WEEK_BASED_YEAR_FIELD)
            val label = when (interval) {
                StatsTimeInterval.DAYS_30 -> context?.getString(R.string.calendar_week_short, week)
                    ?: "CW $week"
                else -> context?.getString(
                    R.string.calendar_week_short_year, week, weekBasedYear % 100,
                ) ?: "CW $week/${weekBasedYear % 100}"
            }
            GradeProgressionPoint(label, weekStart, level)
        }
    }

    /**
     * The bests of the period in [filtered]: the hardest flash, the most sends
     * in one day (the first day to reach it), climbing days per week over the
     * whole [period] and the longest run of weeks with a climbing day in it.
     * Flashes are judged by [flashUuids], over the whole history.
     */
    private fun computePersonalRecords(
        filtered: List<AscentWithClimb>,
        period: StatsPeriod?,
        gradeScale: GradeScale,
        flashUuids: Set<String>,
    ): PersonalRecords {
        // Session metrics count attempts too — a projecting-only day at
        // the board is a session even without a send.
        val sessionDates = filtered
            .mapNotNull { parseDate(it.climbedAt) }
            .distinct()
            .sorted()

        val sends = filtered.filter { it.isSend }
        val hardestFlash = sends
            .filter { it.uuid in flashUuids && it.difficultyAverage != null }
            .maxByOrNull { it.difficultyAverage!! }
        val bestDay = sends.groupBy { it.climbedAt.take(10) }
            .entries
            .maxWithOrNull(compareBy<Map.Entry<String, List<AscentWithClimb>>> { it.value.size }.thenByDescending { it.key })

        return PersonalRecords(
            hardestFlashGrade = hardestFlash?.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) },
            hardestFlashDifficulty = hardestFlash?.difficultyAverage?.toInt() ?: 0,
            mostSendsInDay = bestDay?.value?.size ?: 0,
            mostSendsDate = bestDay?.key,
            daysPerWeek = averageDaysPerWeek(filtered, period),
            longestWeekStreak = longestWeekStreak(sessionDates),
        )
    }

    /** The most consecutive ISO weeks (Mon–Sun) among [sessionDates] with at least one session. */
    private fun longestWeekStreak(sessionDates: List<LocalDate>): Int {
        val toMonday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val weekStarts = sessionDates.map { it.with(toMonday) }.distinct().sorted()
        var longest = 0
        var run = 0
        weekStarts.forEachIndexed { i, week ->
            run = if (i > 0 && ChronoUnit.DAYS.between(weekStarts[i - 1], week) == DAYS_PER_WEEK) run + 1 else 1
            longest = maxOf(longest, run)
        }
        return longest
    }

    /**
     * Uuids of sends that are TRUE flashes: the very first logbook contact
     * with that (climb, angle) — over the FULL history, not the filtered
     * interval — is a send with at most one attempt. A first-try send in
     * today's session after attempts in an earlier session is a repeat/
     * redpoint, not a flash.
     */
    fun trueFlashUuids(allAscents: List<AscentWithClimb>): Set<String> {
        return allAscents
            .groupBy { Triple(it.boardBrand, it.climbUuid, it.angle) }
            .mapNotNull { (_, entries) ->
                val first = entries.minByOrNull { it.climbedAt } ?: return@mapNotNull null
                first.uuid.takeIf { first.isSend && first.bidCount <= 1L }
            }
            .toSet()
    }

    /**
     * Sessions per week over the last [AVG_WINDOW_WEEKS] weeks, or since the
     * first session when the logbook is younger: distinct session days in
     * that span divided by its weeks, so a three-week-old logbook isn't
     * divided by eight. None before [MIN_AVERAGE_DAYS] of history, where a
     * single day would read as 0.1 or 7 a week, like the period's average.
     */
    private fun computeAvgSessionsPerWeek(
        sessionDates: List<LocalDate>,
        clock: Clock,
    ): Double {
        val first = sessionDates.minOrNull() ?: return 0.0
        val today = LocalDate.now(clock)
        val history = ChronoUnit.DAYS.between(first, today) + 1
        if (history < MIN_AVERAGE_DAYS) return 0.0
        val span = minOf(history, AVG_WINDOW_WEEKS * DAYS_PER_WEEK)
        val spanStart = today.minusDays(span - 1)
        val recent = sessionDates.count { !it.isBefore(spanStart) }
        return recent * DAYS_PER_WEEK.toDouble() / span
    }

    /**
     * Consecutive ISO calendar weeks (Mon–Sun) with at least one session.
     * Active only while the run reaches into this or the previous week —
     * rest DAYS never break it, a full week off does.
     */
    private fun computeWeekStreak(
        sessionDates: List<LocalDate>,
        clock: Clock,
    ): Int {
        if (sessionDates.isEmpty()) return 0
        val toMonday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val weekStarts = sessionDates.map { it.with(toMonday) }.distinct().sorted()
        val thisWeek = LocalDate.now(clock).with(toMonday)
        if (ChronoUnit.DAYS.between(weekStarts.last(), thisWeek) > DAYS_PER_WEEK) return 0
        var streak = 1
        for (i in weekStarts.size - 1 downTo 1) {
            if (ChronoUnit.DAYS.between(weekStarts[i - 1], weekStarts[i]) == DAYS_PER_WEEK) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    /**
     * The day of a logbook timestamp ("yyyy-MM-dd…"), or null when it has none.
     * Read by hand rather than with [LocalDate.parse]: the statistics read the day
     * of every entry several times, and the formatter took most of their time on a
     * long logbook. Impossible dates are null, as with ISO_LOCAL_DATE.
     */
    internal fun parseDate(dateStr: String): LocalDate? {
        if (dateStr.length < 10 || dateStr[4] != '-' || dateStr[7] != '-') return null
        fun digits(from: Int, to: Int): Int {
            var value = 0
            for (i in from until to) {
                val c = dateStr[i]
                if (c !in '0'..'9') return -1
                value = value * 10 + (c - '0')
            }
            return value
        }
        val year = digits(0, 4)
        val month = digits(5, 7)
        val day = digits(8, 10)
        if (year < 0 || month < 0 || day < 0) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }
}
