package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The tissues the coach tracks load for; each recovers on its own clock. */
enum class LoadStructure { FINGER, SKIN, SHOULDER }

/** Acute load against the athlete's own normal: a soft signal, always explained, never a block. */
enum class LoadTrend { LOW, NORMAL, RISING, SPIKE }

data class StructureLoad(
    /** Mean daily units over the last 7 days. */
    val acute7: Double,
    /** Mean daily units over the last 28 days. */
    val chronic28: Double,
    /** acute ÷ chronic; null while there is too little history to compare against. */
    val ratio: Double?,
    val trend: LoadTrend,
)

data class LoadStatus(
    val structures: Map<LoadStructure, StructureLoad>,
    /** Daily units per structure, oldest first, [ClimbingLoad.SERIES_DAYS] days ending today. */
    val series: Map<LoadStructure, List<Pair<LocalDate, Double>>>,
)

/**
 * Climbing load from the board logbook, climbing logged outside the app and
 * off-wall training, in one comparable unit per structure.
 *
 * **Unit:** one *attempt-equivalent* — a single board attempt on a problem
 * around the climber's own flash level. Harder attempts count more
 * ([intensityFactor]), steeper angles count more for the shoulder, a minute
 * of gym climbing counts as a third of an attempt (about one go every three
 * minutes) and a 10-second hang at body weight is one finger unit. The
 * numbers only need to be consistent with themselves: the coach compares the
 * athlete with their own history (acute vs chronic), never across people.
 */
object ClimbingLoad {

    const val SERIES_DAYS = 56
    private const val ACUTE_DAYS = 7
    private const val CHRONIC_DAYS = 28
    /** Below this mean daily load there is no meaningful baseline to compare against. */
    private const val MIN_CHRONIC = 0.5

    const val RISING_RATIO = 1.3
    const val SPIKE_RATIO = 1.5
    const val LOW_RATIO = 0.6

    /** A class needs this many attempts, and this share of the day's attempts, to define the day. */
    private const val MEANINGFUL_TRIES = 3
    private const val MEANINGFUL_SHARE = 0.15

    /** Finger/skin weight of one attempt at an intensity; a flash-level attempt is 1. */
    fun intensityFactor(intensity: ClimbIntensity): Double = when (intensity) {
        ClimbIntensity.LIGHT -> 0.6
        ClimbIntensity.VOLUME -> 1.0
        ClimbIntensity.HARD -> 1.4
        ClimbIntensity.LIMIT -> 1.8
    }

    private fun skinFactor(intensity: ClimbIntensity): Double = when (intensity) {
        ClimbIntensity.LIGHT -> 0.6
        ClimbIntensity.VOLUME -> 1.0
        ClimbIntensity.HARD -> 1.3
        ClimbIntensity.LIMIT -> 1.6
    }

    /** Intensity of one attempt relative to the climber's anchors; null without anchor or grade. */
    fun classifyAttempt(difficulty: Double?, summary: LogbookSummary): ClimbIntensity? {
        val working = summary.workingDifficulty ?: return null
        val d = difficulty ?: return null
        // Without flashes the flash level sits three steps under the working grade (about one Font grade).
        val flash = summary.flashDifficulty ?: (working - 3.0)
        return when {
            d >= working -> ClimbIntensity.LIMIT
            d >= working - 1.0 -> ClimbIntensity.HARD
            d <= flash - 2.0 -> ClimbIntensity.LIGHT
            else -> ClimbIntensity.VOLUME
        }
    }

    /**
     * How hard a board day was: the hardest class that holds a meaningful
     * share of the day's attempts. One stray try on a limit problem during a
     * volume session does not make it a limit day. Only rows of the board the
     * anchors come from are graded; null without any graded row.
     */
    fun classifyDay(rows: List<ClimbRow>, summary: LogbookSummary): ClimbIntensity? {
        val graded = rows.filter { comparable(it, summary) }
            .mapNotNull { r -> classifyAttempt(r.difficulty, summary)?.let { it to r.tries.coerceAtLeast(1) } }
        if (graded.isEmpty()) return null
        val tries = graded.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val total = tries.values.sum().toDouble()
        for (c in listOf(ClimbIntensity.LIMIT, ClimbIntensity.HARD, ClimbIntensity.VOLUME)) {
            val n = tries[c] ?: 0
            if (n >= MEANINGFUL_TRIES && n / total >= MEANINGFUL_SHARE) return c
        }
        // No class carried the day: the median attempt decides (short sessions, scattered tries).
        val ordered = graded.flatMap { (c, n) -> List(n) { c } }.sortedBy { it.ordinal }
        return ordered[ordered.size / 2]
    }

    private fun comparable(row: ClimbRow, summary: LogbookSummary): Boolean =
        row.difficulty != null && (summary.primaryBrand == null || row.brand == summary.primaryBrand)

    /**
     * Load units of one day per structure.
     *
     * - Board rows: attempts × [intensityFactor] for fingers, × skin factor for skin,
     *   × intensity × angle weight (40° = 1) for the shoulder. Rows that cannot be graded
     *   (another board, missing grade) count at flash level.
     * - [boardMinutes] only matter when a session recorded time but no logbook rows:
     *   then one attempt per three minutes at flash level.
     * - [manual] days (gym, rock, another board): minutes ÷ minutes-per-attempt of the
     *   discipline × the entered intensity; rope climbing loads fingers and skin less.
     * - [fingerSets] are the day's completed sets: finger exercises add hang seconds ÷ 10 ×
     *   load relative to body weight (per hand for one-arm work), pulling and pushing
     *   exercises add shoulder units (≈ 1 per set of five body-weight reps).
     */
    fun dayUnits(
        rows: List<ClimbRow>,
        summary: LogbookSummary,
        boardMinutes: Int,
        manual: List<ClimbingDayEntry>,
        fingerSets: List<ExerciseSet>,
        catalog: ExerciseCatalog,
    ): Map<LoadStructure, Double> {
        var finger = 0.0
        var skin = 0.0
        var shoulder = 0.0

        rows.forEach { r ->
            val intensity = (if (comparable(r, summary)) classifyAttempt(r.difficulty, summary) else null) ?: ClimbIntensity.VOLUME
            val tries = r.tries.coerceAtLeast(1).toDouble()
            finger += tries * intensityFactor(intensity)
            skin += tries * skinFactor(intensity)
            shoulder += tries * intensityFactor(intensity) * angleWeight(r.angle)
        }
        if (rows.isEmpty() && boardMinutes > 0) {
            val tries = boardMinutes / 3.0
            finger += tries
            skin += tries
            shoulder += tries
        }

        manual.forEach { m ->
            val (minutesPerAttempt, fingerShare, skinShare) = when (m.kind) {
                ClimbingDayKind.GYM_BOULDER -> Triple(3.0, 1.0, 1.0)
                ClimbingDayKind.OTHER_BOARD -> Triple(3.0, 1.0, 1.0)
                ClimbingDayKind.OUTDOOR -> Triple(4.0, 1.0, 1.2)
                ClimbingDayKind.GYM_ROPE -> Triple(6.0, 0.8, 0.6)
                ClimbingDayKind.OTHER -> Triple(5.0, 0.7, 0.7)
            }
            val tries = m.minutes.coerceAtLeast(0) / minutesPerAttempt
            finger += tries * intensityFactor(m.intensity) * fingerShare
            skin += tries * skinFactor(m.intensity) * skinShare
            shoulder += tries * intensityFactor(m.intensity)
        }

        fingerSets.filter { it.isCompleted && it.setType != SetType.WARMUP }.forEach { s ->
            val def = catalog.fallbackFor(s.exerciseSlug)
            val bw = s.bodyweightKg?.takeIf { it > 0 }
            if (LoadDomain.FINGER in def.domains && def.kind != ExerciseKind.CLIMB) {
                val seconds = when (def.kind) {
                    ExerciseKind.INTERVAL -> (s.workS ?: 7.0) * (s.repsPerSet ?: 6)
                    ExerciseKind.HANG, ExerciseKind.TIME -> s.durationS ?: s.targetDurationS ?: 10.0
                    else -> (s.reps ?: 5) * 2.0
                }
                finger += seconds / 10.0 * relativeLoad(def.load, s.loadKg, bw, def.unilateral)
            }
            if (def.category == ExerciseCategoryV2.PULL || def.category == ExerciseCategoryV2.PUSH ||
                (LoadDomain.SHOULDER in def.domains && def.category != ExerciseCategoryV2.FINGER)
            ) {
                val reps = s.reps?.toDouble() ?: ((s.durationS ?: 0.0) / 5.0)
                shoulder += reps / 5.0 * relativeLoad(def.load, s.loadKg, bw, def.unilateral)
            }
        }
        return mapOf(LoadStructure.FINGER to finger, LoadStructure.SKIN to skin, LoadStructure.SHOULDER to shoulder)
    }

    /** Steeper boards load the shoulder girdle more; 40° is the reference. */
    private fun angleWeight(angle: Int): Double = (angle / 40.0).coerceIn(0.5, 1.5)

    /** Load relative to body weight (1 = body weight); per hand for one-arm external loads. */
    private fun relativeLoad(mode: LoadMode, loadKg: Double?, bodyweightKg: Double?, unilateral: Boolean): Double {
        val bw = bodyweightKg ?: return 1.0
        return when (mode) {
            LoadMode.BODYWEIGHT_PLUS -> (bw + (loadKg ?: 0.0)) / bw
            LoadMode.BODYWEIGHT -> 1.0
            LoadMode.EXTERNAL -> (loadKg ?: 0.0) / (if (unilateral) bw / 2.0 else bw)
            LoadMode.NONE -> 0.5
        }.coerceIn(0.2, 2.5)
    }

    /**
     * Acute (7 days) against chronic (28 days) per structure, plus the daily
     * series of the last [SERIES_DAYS] days for the chart. Days without an
     * entry count as zero — rest days are part of the average.
     */
    fun status(daily: Map<LocalDate, Map<LoadStructure, Double>>, today: LocalDate): LoadStatus {
        val days = (SERIES_DAYS - 1 downTo 0).map { today.minus(DatePeriod(days = it)) }
        val series = LoadStructure.entries.associateWith { s -> days.map { d -> d to (daily[d]?.get(s) ?: 0.0) } }
        val structures = series.mapValues { (_, points) ->
            val values = points.map { it.second }
            val acute = values.takeLast(ACUTE_DAYS).average()
            val chronic = values.takeLast(CHRONIC_DAYS).average()
            val ratio = if (chronic < MIN_CHRONIC) null else acute / chronic
            val trend = when {
                ratio == null -> if (acute >= 1.0) LoadTrend.RISING else LoadTrend.LOW
                ratio >= SPIKE_RATIO -> LoadTrend.SPIKE
                ratio >= RISING_RATIO -> LoadTrend.RISING
                ratio <= LOW_RATIO -> LoadTrend.LOW
                else -> LoadTrend.NORMAL
            }
            StructureLoad(acute, chronic, ratio, trend)
        }
        return LoadStatus(structures, series)
    }

    /**
     * Hours before the next maximal finger load after a day of [intensity].
     * Older athletes recover more slowly: +12 h from 40, +24 h from 55.
     */
    fun recoveryHours(intensity: ClimbIntensity, ageBand: AgeBand?): Int {
        val base = when (intensity) {
            ClimbIntensity.LIMIT -> 60
            ClimbIntensity.HARD -> 48
            ClimbIntensity.VOLUME -> 24
            ClimbIntensity.LIGHT -> 12
        }
        val extra = when (ageBand) {
            AgeBand.Y40_54 -> 12
            AgeBand.Y55_PLUS -> 24
            else -> 0
        }
        return base + extra
    }
}
