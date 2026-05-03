package com.cruxcoach.android.ui.board

import android.content.Context
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.KilterGradeMapper
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure stateless computation of all board statistics from a list of ascents/bids.
 * Extracted from BoardLogbookViewModel to keep it under 500 lines and enable unit testing.
 */
object BoardStatsComputer {

    private val DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE
    private val WEEK_FIELD = WeekFields.of(Locale.GERMANY).weekOfWeekBasedYear()
    private val MONTH_NAMES_FALLBACK = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val MONTH_RES_IDS = arrayOf(
        0, R.string.month_jan, R.string.month_feb, R.string.month_mar,
        R.string.month_apr, R.string.month_may, R.string.month_jun,
        R.string.month_jul, R.string.month_aug, R.string.month_sep,
        R.string.month_oct, R.string.month_nov, R.string.month_dec
    )

    private fun monthName(month: Int, context: Context?): String {
        if (month < 1 || month > 12) return ""
        return context?.getString(MONTH_RES_IDS[month]) ?: MONTH_NAMES_FALLBACK[month]
    }

    // Grade band thresholds (Kilter difficulty values)
    // Easy: V0-V2 (diff < 16), Medium: V3-V5 (16..20), Hard: V6-V8 (21..26), Elite: V9+ (27+)
    private const val MEDIUM_THRESHOLD = 16.0
    private const val HARD_THRESHOLD = 21.0
    private const val ELITE_THRESHOLD = 27.0

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
        val bids = filtered.filter { !it.isSend }
        val totalSends = sends.size
        val boulderSends = sends.count { it.framesCount <= 1L }
        val routeSends = sends.count { it.framesCount > 1L }
        val flashCount = sends.count { it.bidCount <= 1L }
        val flashRate = if (totalSends > 0) flashCount.toFloat() / totalSends * 100f else 0f
        val uniqueClimbs = filtered.map { it.climbUuid }.distinct().size
        val sessionCount = filtered.map { it.climbedAt.take(10) }.distinct().size

        val hardestDiff = sends.mapNotNull { it.difficultyAverage }.maxOrNull()
        val hardestGrade = hardestDiff?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) }
        val hardestDiffInt = hardestDiff?.toInt() ?: 0

        val gradePyramid = computeGradePyramid(sends, gradeScale)
        val angleDist = computeAngleDistribution(filtered)
        val sendsOverTime = computeSendsOverTime(filtered, interval, context)
        val activityMap = computeActivityMap(filtered)

        // New extended stats
        val gradeOutcomes = computeGradeOutcomes(sends, bids, gradeScale)
        val outcomeDistribution = computeOutcomeDistribution(sends)
        val weeklyVolume = computeWeeklyVolume(filtered)
        val gradeProgression = computeGradeProgression(sends, interval, context)
        val uniqueClimbsByGrade = computeUniqueClimbsByGrade(sends, gradeScale)
        val periodComparison = computePeriodComparison(ascents, interval, gradeScale, context, clock)
        val personalRecords = computePersonalRecords(ascents, gradeScale, clock)

        return BoardLogbookStats(
            hardestGrade = hardestGrade,
            hardestDifficultyInt = hardestDiffInt,
            totalSends = totalSends,
            totalAttempts = bids.size,
            boulderSends = boulderSends,
            routeSends = routeSends,
            flashRate = flashRate,
            uniqueClimbs = uniqueClimbs,
            sessionCount = sessionCount,
            gradePyramid = gradePyramid,
            angleDistribution = angleDist,
            sendsOverTime = sendsOverTime,
            activityMap = activityMap,
            gradeOutcomes = gradeOutcomes,
            outcomeDistribution = outcomeDistribution,
            weeklyVolume = weeklyVolume,
            gradeProgression = gradeProgression,
            uniqueClimbsByGrade = uniqueClimbsByGrade,
            periodComparison = periodComparison,
            personalRecords = personalRecords
        )
    }

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
            val to = customTo.plusDays(1).toString() // inclusive end
            return ascents.filter { it.climbedAt.take(10) in from..to }
        }
        val cutoffDays = interval.days ?: return ascents
        val cutoff = LocalDate.now(clock).minusDays(cutoffDays.toLong()).toString()
        return ascents.filter { it.climbedAt.take(10) >= cutoff }
    }

    private fun computeGradePyramid(
        sends: List<AscentWithClimb>,
        gradeScale: GradeScale
    ): List<BoardGradePyramidEntry> {
        return sends
            .filter { it.difficultyAverage != null }
            .groupBy { KilterGradeMapper.difficultyToVScale(it.difficultyAverage!!) }
            .map { (vGrade, list) ->
                BoardGradePyramidEntry(
                    grade = GradeDisplayHelper.formatGrade(vGrade, gradeScale),
                    count = list.size,
                    difficultyInt = list.first().difficultyAverage!!.toInt()
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

    fun computeSendsOverTime(
        ascents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        context: Context? = null
    ): List<TimeBucketEntry> {
        if (ascents.isEmpty()) return emptyList()
        val parsed = ascents.mapNotNull { parseDate(it.climbedAt) }
        if (parsed.isEmpty()) return emptyList()

        return when (interval) {
            StatsTimeInterval.DAYS_30 -> {
                val dayFmt = DateTimeFormatter.ofPattern("dd.MM")
                parsed.groupBy { it }
                    .map { (date, list) -> TimeBucketEntry(date.format(dayFmt), list.size) }
                    .sortedBy { it.label }
            }
            StatsTimeInterval.DAYS_90 -> {
                parsed.groupBy {
                    context?.getString(R.string.calendar_week_short, it.get(WEEK_FIELD))
                        ?: "CW ${it.get(WEEK_FIELD)}"
                }
                    .toSortedMap()
                    .map { (label, list) -> TimeBucketEntry(label, list.size) }
            }
            StatsTimeInterval.YEAR_1, StatsTimeInterval.ALL -> {
                parsed.groupBy { it.year to it.monthValue }
                    .toSortedMap(compareBy({ it.first }, { it.second }))
                    .map { (key, list) ->
                        TimeBucketEntry("${monthName(key.second, context)} '${key.first % 100}", list.size)
                    }
            }
        }
    }

    private fun computeActivityMap(
        filtered: List<AscentWithClimb>
    ): Map<LocalDate, Int> {
        return filtered.mapNotNull { parseDate(it.climbedAt) }
            .groupBy { it }
            .mapValues { it.value.size }
    }

    // --- New extended stats ---

    private fun computeGradeOutcomes(
        sends: List<AscentWithClimb>,
        bids: List<AscentWithClimb>,
        gradeScale: GradeScale
    ): List<GradeOutcomeEntry> {
        // Group all entries by V-Scale grade
        val allWithDiff = (sends + bids).filter { it.difficultyAverage != null }
        val grouped = allWithDiff.groupBy {
            KilterGradeMapper.difficultyToVScale(it.difficultyAverage!!)
        }

        return grouped.map { (vGrade, entries) ->
            val diffInt = entries.first().difficultyAverage!!.toInt()
            val flashes = entries.count { it.isSend && it.bidCount <= 1L }
            val redpoints = entries.count { it.isSend && it.bidCount > 1L }
            val attempts = entries.count { !it.isSend }
            GradeOutcomeEntry(
                grade = GradeDisplayHelper.formatGrade(vGrade, gradeScale),
                difficultyInt = diffInt,
                flashCount = flashes,
                redpointCount = redpoints,
                attemptCount = attempts
            )
        }.sortedBy { it.difficultyInt }
    }

    private fun computeOutcomeDistribution(
        sends: List<AscentWithClimb>
    ): OutcomeDistribution {
        val flashes = sends.count { it.bidCount <= 1L }
        val redpoints = sends.count { it.bidCount > 1L }
        // Note: attempts (bids) are tracked via totalAttempts in the main stats
        return OutcomeDistribution(flashes = flashes, redpoints = redpoints, attempts = 0)
    }

    private fun computeWeeklyVolume(
        filtered: List<AscentWithClimb>
    ): List<WeeklyVolumeEntry> {
        if (filtered.isEmpty()) return emptyList()

        data class WeekKey(val year: Int, val week: Int) : Comparable<WeekKey> {
            override fun compareTo(other: WeekKey): Int =
                compareValuesBy(this, other, { it.year }, { it.week })
        }

        val withDate = filtered.mapNotNull { a ->
            val date = parseDate(a.climbedAt) ?: return@mapNotNull null
            val diff = a.difficultyAverage ?: return@mapNotNull null
            Triple(WeekKey(date.year, date.get(WEEK_FIELD)), diff, a)
        }

        return withDate.groupBy { it.first }
            .toSortedMap()
            .map { (weekKey, entries) ->
                val diffs = entries.map { it.second }
                WeeklyVolumeEntry(
                    weekLabel = "KW ${weekKey.week}",
                    easyCount = diffs.count { it < MEDIUM_THRESHOLD },
                    mediumCount = diffs.count { it >= MEDIUM_THRESHOLD && it < HARD_THRESHOLD },
                    hardCount = diffs.count { it >= HARD_THRESHOLD && it < ELITE_THRESHOLD },
                    eliteCount = diffs.count { it >= ELITE_THRESHOLD }
                )
            }
    }

    private fun computeGradeProgression(
        sends: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        context: Context? = null
    ): List<GradeProgressionPoint> {
        if (sends.isEmpty()) return emptyList()
        val withDateAndDiff = sends.mapNotNull { a ->
            val date = parseDate(a.climbedAt) ?: return@mapNotNull null
            val diff = a.difficultyAverage ?: return@mapNotNull null
            date to diff
        }
        if (withDateAndDiff.isEmpty()) return emptyList()

        // Bucket by week for all intervals — gives readable trend
        return withDateAndDiff
            .groupBy { (date, _) -> date.year to date.get(WEEK_FIELD) }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (key, pairs) ->
                val label = when (interval) {
                    StatsTimeInterval.DAYS_30 -> context?.getString(R.string.calendar_week_short, key.second)
                        ?: "CW ${key.second}"
                    else -> context?.getString(R.string.calendar_week_short_year, key.second, key.first % 100)
                        ?: "CW ${key.second}/${key.first % 100}"
                }
                GradeProgressionPoint(label, pairs.maxOf { it.second })
            }
    }

    private fun computeUniqueClimbsByGrade(
        sends: List<AscentWithClimb>,
        gradeScale: GradeScale
    ): List<UniqueClimbEntry> {
        return sends
            .filter { it.difficultyAverage != null }
            .groupBy { KilterGradeMapper.difficultyToVScale(it.difficultyAverage!!) }
            .map { (vGrade, entries) ->
                val diffInt = entries.first().difficultyAverage!!.toInt()
                UniqueClimbEntry(
                    grade = GradeDisplayHelper.formatGrade(vGrade, gradeScale),
                    difficultyInt = diffInt,
                    uniqueCount = entries.map { it.climbUuid }.distinct().size,
                    totalSends = entries.size
                )
            }
            .sortedBy { it.difficultyInt }
    }

    private fun computePeriodComparison(
        allAscents: List<AscentWithClimb>,
        interval: StatsTimeInterval,
        gradeScale: GradeScale,
        context: Context? = null,
        clock: Clock = Clock.systemDefaultZone(),
    ): PeriodComparison? {
        val days = interval.days ?: return null // No comparison for "ALL"
        val now = LocalDate.now(clock)
        val currentStart = now.minusDays(days.toLong())
        val previousStart = currentStart.minusDays(days.toLong())

        val currentStr = currentStart.toString()
        val previousStr = previousStart.toString()
        val nowStr = now.toString()

        val current = allAscents.filter { it.climbedAt.take(10) in currentStr..nowStr }
        val previous = allAscents.filter { it.climbedAt.take(10) in previousStr..currentStr }

        if (current.isEmpty() && previous.isEmpty()) return null

        val curSends = current.count { it.isSend }
        val prevSends = previous.count { it.isSend }
        val curFlashes = current.count { it.isSend && it.bidCount <= 1L }
        val prevFlashes = previous.count { it.isSend && it.bidCount <= 1L }
        val curFlashRate = if (curSends > 0) curFlashes.toFloat() / curSends * 100f else 0f
        val prevFlashRate = if (prevSends > 0) prevFlashes.toFloat() / prevSends * 100f else 0f
        val curHardest = current.filter { it.isSend }.mapNotNull { it.difficultyAverage?.toInt() }.maxOrNull() ?: 0
        val prevHardest = previous.filter { it.isSend }.mapNotNull { it.difficultyAverage?.toInt() }.maxOrNull() ?: 0
        val curUnique = current.map { it.climbUuid }.distinct().size
        val prevUnique = previous.map { it.climbUuid }.distinct().size

        return PeriodComparison(
            totalSendsDelta = curSends - prevSends,
            flashRateDelta = curFlashRate - prevFlashRate,
            hardestGradeDelta = curHardest - prevHardest,
            uniqueClimbsDelta = curUnique - prevUnique,
            currentLabel = context?.getString(interval.labelResId) ?: "",
            previousLabel = context?.let { ctx ->
                val label = ctx.getString(interval.labelResId)
                ctx.getString(R.string.stats_previous_interval, label)
            } ?: ""
        )
    }

    private fun computePersonalRecords(
        allAscents: List<AscentWithClimb>,
        gradeScale: GradeScale,
        clock: Clock = Clock.systemDefaultZone(),
    ): PersonalRecords {
        val sends = allAscents.filter { it.isSend }
        if (sends.isEmpty()) return PersonalRecords()

        // Hardest flash
        val flashSends = sends.filter { it.bidCount <= 1L && it.difficultyAverage != null }
        val hardestFlashDiff = flashSends.maxByOrNull { it.difficultyAverage!! }
        val hardestFlashGrade = hardestFlashDiff?.difficultyAverage?.let {
            GradeDisplayHelper.formatDifficulty(it, gradeScale)
        }

        // Most sends in a single day
        val sendsByDay = sends.groupBy { it.climbedAt.take(10) }
        val bestDay = sendsByDay.maxByOrNull { it.value.size }
        val mostSendsInDay = bestDay?.value?.size ?: 0
        val mostSendsDate = bestDay?.key

        // Streaks (consecutive days with at least one send)
        val sendDates = sendsByDay.keys.mapNotNull { parseDate(it) }.sorted()
        val (currentStreak, longestStreak) = computeStreaks(sendDates, clock)

        return PersonalRecords(
            hardestFlashGrade = hardestFlashGrade,
            hardestFlashDifficulty = hardestFlashDiff?.difficultyAverage?.toInt() ?: 0,
            mostSendsInDay = mostSendsInDay,
            mostSendsDate = mostSendsDate,
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

    private fun computeStreaks(
        sortedDates: List<LocalDate>,
        clock: Clock = Clock.systemDefaultZone(),
    ): Pair<Int, Int> {
        if (sortedDates.isEmpty()) return 0 to 0

        var longest = 1
        var current = 1
        val today = LocalDate.now(clock)

        for (i in 1 until sortedDates.size) {
            if (ChronoUnit.DAYS.between(sortedDates[i - 1], sortedDates[i]) == 1L) {
                current++
                if (current > longest) longest = current
            } else {
                current = 1
            }
        }

        // Current streak only counts if it includes today or yesterday
        val lastDate = sortedDates.last()
        val daysSinceLast = ChronoUnit.DAYS.between(lastDate, today)
        val activeStreak = if (daysSinceLast <= 1) current else 0

        return activeStreak to longest
    }

    private fun parseDate(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr.take(10), DATE_FMT)
        } catch (_: Exception) {
            null
        }
    }
}
