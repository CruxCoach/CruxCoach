package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Side
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Working (or flash) level in one week, on the unified difficulty scale. */
data class GradePoint(val weekStart: LocalDate, val difficulty: Double)

/** Where a strength value comes from: a logged set, a stored performance value, or a self-estimate. */
enum class StrengthSource { LOG, BENCHMARK, ESTIMATE }

/** A strength value in % of body weight on a day. */
data class StrengthPoint(val day: LocalDate, val pctBw: Double, val source: StrengthSource)

/** The athlete's newest value placed into the grade band (see [GradeStrengthNorms]). */
data class NormPosition(
    val metric: GradeStrengthNorms.Metric,
    val latestPctBw: Double,
    val workingDifficulty: Double?,
    val position: GradeStrengthNorms.Position?,
    /** Grade at which this value sits in the middle of the band. */
    val typicalDifficulty: Double,
)

/** Pearson r between weekly working grade and finger strength. */
data class Correlation(
    val r: Double?,
    val pairedWeeks: Int,
    /** Paired weeks still missing before r is shown. */
    val missingWeeks: Int,
)

enum class BottleneckArea { FINGER, PULL, POWER, ENDURANCE, CORE }

enum class BottleneckReason {
    BELOW_TYPICAL, WITHIN_TYPICAL, ABOVE_TYPICAL,
    /** Flash level far below the working grade: projecting works, flashing lags. */
    LARGE_FLASH_GAP,
    /** Flash level close to the working grade: hard tries at the limit are rare. */
    SMALL_FLASH_GAP,
    /** Data exists, but there is no grade norm to compare it with. */
    NO_NORM,
    NO_DATA,
}

/** One area of the profile; [score] −1 … +1 relative to grade-typical (negative = weaker), null without a norm. */
data class Bottleneck(val area: BottleneckArea, val score: Double?, val reason: BottleneckReason)

data class ClimberProfileData(
    val summary: LogbookSummary,
    val gradeTimeline: List<GradePoint> = emptyList(),
    val flashTimeline: List<GradePoint> = emptyList(),
    /** Working minus flash anchor in difficulty steps (one step per Font sub-grade). */
    val flashGap: Double? = null,
    /** Mean attempts per send on the primary board over the last twelve weeks. */
    val attemptsPerSend: Double? = null,
    /** Board angle → sends on the primary board. */
    val angleDistribution: Map<Int, Int> = emptyMap(),
    /** Two-hand max hang, total load in % BW, normalised to a 20 mm edge. */
    val finger: List<StrengthPoint> = emptyList(),
    /** Weighted pull-up 1RM, total load in % BW (estimated from body-weight reps when no loaded data). */
    val pull: List<StrengthPoint> = emptyList(),
    /** One-arm pick-up per hand, % BW lifted for ~10 s; own timeline only. */
    val pickupLeft: List<StrengthPoint> = emptyList(),
    val pickupRight: List<StrengthPoint> = emptyList(),
    val fingerPosition: NormPosition? = null,
    val pullPosition: NormPosition? = null,
    val correlation: Correlation = Correlation(null, 0, PerformanceProfiles.MIN_PAIRED_WEEKS),
    val bottlenecks: List<Bottleneck> = emptyList(),
    val bodyweightKg: Double? = null,
    val hasRepeaterData: Boolean = false,
    val hasCoreData: Boolean = false,
) {
    val isEmpty: Boolean get() = gradeTimeline.isEmpty() && finger.isEmpty() && pull.isEmpty() &&
        pickupLeft.isEmpty() && pickupRight.isEmpty() && summary.workingDifficulty == null
}

/**
 * The climber profile behind the "Kletterprofil" screen: how the working
 * grade moves, how strong fingers and pulling are relative to body weight,
 * where those values sit in the grade band, and how grade and finger strength
 * move together for this athlete. Pure: the service hands in logbook rows,
 * completed sets with their day, performance values and the weight trend.
 */
object PerformanceProfiles {

    const val MIN_PAIRED_WEEKS = 6
    /** A week's working grade needs this many different sent climbs in the trailing window. */
    private const val MIN_WINDOW_CLIMBS = 5
    private const val WINDOW_WEEKS = 8
    private const val MAX_WEEKS = 52
    /** A strength value counts for a week if measured in that week or the week before. */
    private const val PAIR_TOLERANCE_DAYS = 13

    const val MAX_HANG = "finger.max_hang"
    const val MAX_HANG_SMALL = "finger.max_hang_small_edge"
    const val WEIGHTED_PULL = "pull.weighted_pull_up"
    const val PULL_UP = "pull.pull_up"
    const val ONE_ARM_PICKUP = "finger.one_arm_pickup"
    const val REPEATERS = "finger.repeaters"
    private val CORE_SLUGS = setOf("core.hollow_hold", "core.tuck_hollow_hold", "core.l_sit", "core.tuck_l_sit",
        "pull.tuck_front_lever", "pull.advanced_tuck_front_lever", "pull.straddle_front_lever", "pull.front_lever")

    /** Slugs whose history feeds the profile; the service only needs to load these. */
    val RELEVANT_SLUGS: Set<String> = setOf(MAX_HANG, MAX_HANG_SMALL, WEIGHTED_PULL, PULL_UP, ONE_ARM_PICKUP, REPEATERS) + CORE_SLUGS

    /**
     * @param sets completed sets of [RELEVANT_SLUGS] with the day they belong to.
     * @param weights body-weight trend (day → kg), used when a set or value has no snapshot.
     */
    fun build(
        rows: List<ClimbRow>,
        summary: LogbookSummary,
        sets: List<Pair<LocalDate, ExerciseSet>>,
        benchmarks: List<Benchmark>,
        catalog: ExerciseCatalog,
        weights: List<Pair<LocalDate, Double>>,
        today: LocalDate,
        dayOfMillis: (Long) -> LocalDate,
    ): ClimberProfileData {
        val primary = rows.filter { it.brand == summary.primaryBrand }
        val gradeTimeline = weeklyAnchors(primary.filter { it.sent }, today, flashOnly = false)
        val flashTimeline = weeklyAnchors(primary.filter { it.sent && it.flash }, today, flashOnly = true)
        val twelveWeeksAgo = today.minus(DatePeriod(days = 84)).toString()
        val recentPrimary = primary.filter { it.day >= twelveWeeksAgo }
        val recentSends = recentPrimary.count { it.sent }
        val attemptsPerSend = if (recentSends == 0) null else recentPrimary.sumOf { it.tries }.toDouble() / recentSends
        val angles = primary.filter { it.sent }.groupingBy { it.angle }.eachCount()

        val sortedWeights = weights.sortedBy { it.first }
        fun bwAt(day: LocalDate, snapshot: Double?): Double? =
            snapshot?.takeIf { it > 0 } ?: sortedWeights.lastOrNull { it.first <= day }?.second ?: sortedWeights.firstOrNull()?.second

        val finger = strengthSeries(listOf(MAX_HANG, MAX_HANG_SMALL), sets, benchmarks, catalog, ::bwAt, dayOfMillis, side = null) { def, cap, edge ->
            // Normalise to a 20 mm edge: about 2.5 % more total load per mm of extra edge depth.
            if (def.unilateral) null else cap * (1.0 + 0.025 * (20.0 - (edge ?: 20.0).coerceIn(8.0, 35.0)))
        }
        val weighted = strengthSeries(listOf(WEIGHTED_PULL), sets, benchmarks, catalog, ::bwAt, dayOfMillis, side = null) { _, cap, _ -> cap }
        val pull = weighted.ifEmpty {
            // Body-weight reps only: Epley turns max reps into a 1RM estimate of the body weight.
            strengthSeries(listOf(PULL_UP), sets, benchmarks, catalog, ::bwAt, dayOfMillis, side = null, repsAsMultiple = true) { _, cap, _ -> cap }
        }
        val pickupLeft = strengthSeries(listOf(ONE_ARM_PICKUP), sets, benchmarks, catalog, ::bwAt, dayOfMillis, side = Side.LEFT) { _, cap, _ -> cap }
        val pickupRight = strengthSeries(listOf(ONE_ARM_PICKUP), sets, benchmarks, catalog, ::bwAt, dayOfMillis, side = Side.RIGHT) { _, cap, _ -> cap }

        val working = summary.workingDifficulty
        val fingerPosition = finger.lastOrNull()?.let { position(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, it.pctBw, working) }
        val pullPosition = pull.lastOrNull()?.let { position(GradeStrengthNorms.Metric.PULL_UP_1RM, it.pctBw, working) }

        val flashGap = summary.workingDifficulty?.let { w -> summary.flashDifficulty?.let { f -> w - f } }
        val hasRepeaters = sets.any { it.second.exerciseSlug == REPEATERS } || benchmarks.any { it.exerciseSlug == REPEATERS }
        val hasCore = sets.any { it.second.exerciseSlug in CORE_SLUGS } || benchmarks.any { it.exerciseSlug in CORE_SLUGS }

        return ClimberProfileData(
            summary = summary,
            gradeTimeline = gradeTimeline,
            flashTimeline = flashTimeline,
            flashGap = flashGap,
            attemptsPerSend = attemptsPerSend,
            angleDistribution = angles,
            finger = finger,
            pull = pull,
            pickupLeft = pickupLeft,
            pickupRight = pickupRight,
            fingerPosition = fingerPosition,
            pullPosition = pullPosition,
            correlation = correlation(gradeTimeline, finger),
            bottlenecks = bottlenecks(fingerPosition, pullPosition, flashGap, hasRepeaters, hasCore),
            bodyweightKg = sortedWeights.lastOrNull()?.second,
            hasRepeaterData = hasRepeaters,
            hasCoreData = hasCore,
        )
    }

    private fun position(metric: GradeStrengthNorms.Metric, pct: Double, working: Double?) = NormPosition(
        metric = metric,
        latestPctBw = pct,
        workingDifficulty = working,
        position = working?.let { GradeStrengthNorms.position(metric, it, pct) },
        typicalDifficulty = GradeStrengthNorms.typicalDifficulty(metric, pct),
    )

    /** Monday of the ISO week of [day]. */
    fun weekStart(day: LocalDate): LocalDate = ConsistencyStreak.weekStart(day)

    /**
     * One anchor per week: the mean of the hardest sends (one value per climb)
     * in the trailing [WINDOW_WEEKS] weeks — the same estimator the playlist
     * planner uses, unrounded so the line can move between grades.
     */
    fun weeklyAnchors(sends: List<ClimbRow>, today: LocalDate, flashOnly: Boolean): List<GradePoint> {
        val graded = sends.filter { it.difficulty != null }
            .mapNotNull { r -> runCatching { LocalDate.parse(r.day) }.getOrNull()?.let { it to r } }
        if (graded.isEmpty()) return emptyList()
        val first = weekStart(graded.minOf { it.first })
        val last = weekStart(today)
        val starts = generateSequence(last) { it.minus(DatePeriod(days = 7)) }
            .takeWhile { it >= first }.take(MAX_WEEKS).toList().reversed()
        val minClimbs = if (flashOnly) 3 else MIN_WINDOW_CLIMBS
        return starts.mapNotNull { start ->
            val end = start.plus(DatePeriod(days = 6))
            val from = end.minus(DatePeriod(days = WINDOW_WEEKS * 7 - 1))
            val best = graded.filter { it.first in from..end }
                .groupBy { it.second.climbUuid }
                .map { (_, group) -> group.maxOf { it.second.difficulty!! } }
            if (best.size < minClimbs) return@mapNotNull null
            val k = (best.size * 0.10).roundToInt().coerceIn(3, 25).coerceAtMost(best.size)
            GradePoint(start, best.sortedDescending().take(k).average())
        }
    }

    private fun strengthSeries(
        slugs: List<String>,
        sets: List<Pair<LocalDate, ExerciseSet>>,
        benchmarks: List<Benchmark>,
        catalog: ExerciseCatalog,
        bwAt: (LocalDate, Double?) -> Double?,
        dayOfMillis: (Long) -> LocalDate,
        side: Side?,
        repsAsMultiple: Boolean = false,
        transform: (ExerciseDefinition, Double, Double?) -> Double?,
    ): List<StrengthPoint> {
        val points = mutableListOf<StrengthPoint>()
        slugs.forEach { slug ->
            val def = catalog[slug] ?: return@forEach
            sets.filter { it.second.exerciseSlug == slug && (side == null || it.second.side == side) }.forEach { (day, set) ->
                val bw = bwAt(day, set.bodyweightKg) ?: return@forEach
                val cap = BenchmarkMath.implied(def, set) ?: return@forEach
                val total = if (repsAsMultiple) bw * (1.0 + cap.value / 30.0) else cap.value
                val value = transform(def, total, set.edgeMm) ?: return@forEach
                points += StrengthPoint(day, value / bw * 100.0, StrengthSource.LOG)
            }
            benchmarks.filter { it.exerciseSlug == slug && (side == null || it.side == side) }.forEach { b ->
                val day = dayOfMillis(b.measuredAt)
                val bw = bwAt(day, b.bodyweightKg) ?: return@forEach
                val cap = BenchmarkMath.capacity(def, b, bw) ?: return@forEach
                val total = if (repsAsMultiple) bw * (1.0 + cap.value / 30.0) else cap.value
                val value = transform(def, total, b.edgeMm) ?: return@forEach
                val source = if (b.source == BenchmarkSource.ESTIMATE) StrengthSource.ESTIMATE else StrengthSource.BENCHMARK
                points += StrengthPoint(day, value / bw * 100.0, source)
            }
        }
        // One value per day: the best of the day; a measured value beats an estimate on the same day.
        return points.groupBy { it.day }
            .map { (_, list) -> list.filter { it.source != StrengthSource.ESTIMATE }.maxByOrNull { it.pctBw } ?: list.maxBy { it.pctBw } }
            .sortedBy { it.day }
    }

    /** Pearson r over weeks where a working grade and a finger value measured near the week's end both exist. */
    fun correlation(grades: List<GradePoint>, finger: List<StrengthPoint>): Correlation {
        val measured = finger.filter { it.source != StrengthSource.ESTIMATE }
        val pairs = grades.mapNotNull { g ->
            val end = g.weekStart.plus(DatePeriod(days = 6))
            val near = measured.filter { it.day in end.minus(DatePeriod(days = PAIR_TOLERANCE_DAYS))..end }
                .maxByOrNull { it.day }
            near?.let { g.difficulty to it.pctBw }
        }
        val missing = (MIN_PAIRED_WEEKS - pairs.size).coerceAtLeast(0)
        if (pairs.size < MIN_PAIRED_WEEKS) return Correlation(null, pairs.size, missing)
        return Correlation(pearson(pairs), pairs.size, 0)
    }

    fun pearson(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 2) return null
        val mx = pairs.map { it.first }.average()
        val my = pairs.map { it.second }.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        pairs.forEach { (x, y) ->
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        if (sxx < 1e-9 || syy < 1e-9) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }

    private fun bottlenecks(
        finger: NormPosition?,
        pull: NormPosition?,
        flashGap: Double?,
        hasRepeaters: Boolean,
        hasCore: Boolean,
    ): List<Bottleneck> {
        fun normScore(p: NormPosition?, area: BottleneckArea): Bottleneck {
            val w = p?.workingDifficulty ?: return Bottleneck(area, null, BottleneckReason.NO_DATA)
            val band = GradeStrengthNorms.band(p.metric, w) ?: return Bottleneck(area, null, BottleneckReason.NO_NORM)
            val spread = band.high - band.center
            val score = ((p.latestPctBw - band.center) / (spread * 1.5)).coerceIn(-1.0, 1.0)
            val reason = when (p.position) {
                GradeStrengthNorms.Position.BELOW -> BottleneckReason.BELOW_TYPICAL
                GradeStrengthNorms.Position.ABOVE -> BottleneckReason.ABOVE_TYPICAL
                else -> BottleneckReason.WITHIN_TYPICAL
            }
            return Bottleneck(area, score, reason)
        }
        val power = when {
            flashGap == null -> Bottleneck(BottleneckArea.POWER, null, BottleneckReason.NO_DATA)
            flashGap <= 1.0 -> Bottleneck(BottleneckArea.POWER, -0.5, BottleneckReason.SMALL_FLASH_GAP)
            flashGap >= 5.0 -> Bottleneck(BottleneckArea.POWER, -0.3, BottleneckReason.LARGE_FLASH_GAP)
            else -> Bottleneck(BottleneckArea.POWER, 0.0, BottleneckReason.WITHIN_TYPICAL)
        }
        val list = listOf(
            normScore(finger, BottleneckArea.FINGER),
            normScore(pull, BottleneckArea.PULL),
            power,
            Bottleneck(BottleneckArea.ENDURANCE, null, if (hasRepeaters) BottleneckReason.NO_NORM else BottleneckReason.NO_DATA),
            Bottleneck(BottleneckArea.CORE, null, if (hasCore) BottleneckReason.NO_NORM else BottleneckReason.NO_DATA),
        )
        // Weakest first; areas without a score at the end.
        return list.sortedWith(compareBy<Bottleneck> { it.score == null }.thenBy { it.score ?: 0.0 })
    }
}
