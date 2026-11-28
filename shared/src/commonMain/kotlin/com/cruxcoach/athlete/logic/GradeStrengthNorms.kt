package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.Sex
import kotlin.math.floor

/**
 * How strong boulderers are at a grade: orientation for the climber profile, the plausibility hint of
 * self-estimates and the careful start load of [StartEstimate]. Never a goal or a ranking.
 *
 * **What is measured.** Total load (body weight plus added weight, minus any assistance) in % of body weight:
 * - [Metric.FINGER_MAX_HANG_20MM]: two-arm max hang, 20 mm edge, 10 s, half crimp;
 * - [Metric.PULL_UP_1RM]: weighted pull-up one-rep max.
 *
 * **Sources.** Centre ± one standard deviation (SD) per V grade, fitted to
 * - Power Company Climbing, "Metrics for Climbers" (tables, updated October 2022) and the raw survey data behind
 *   them: about 450 climbers with an outdoor boulder grade, men and women separately;
 * - Lattice Training, "Predictors for performance" (bouldering, 2025, n = 901).
 *
 * Their protocols were converted to the ones above: a 7-s hang × 0.96 for 10 s, a 2RM × 1.04 for the 1RM.
 * Cross-checks: the r/climbharder training survey and Torr et al. 2022 (J Strength Cond Res,
 * doi 10.1519/JSC.0000000000003548).
 *
 * Centre in × body weight, V = V grade ([vOf]):
 *
 * | | men | women | everyone |
 * |---|---|---|---|
 * | hang | 0.998 + 0.0545·V | 0.941 + 0.0599·V (V2–V10, outside: everyone) | 0.976 + 0.0568·V |
 * | hang SD | 0.14 ≤ V5, 0.15 V6–V9, 0.17 ≥ V10 | 0.12 ≤ V3, 0.16 ≥ V4 | 0.15 ≤ V9, 0.17 ≥ V10 |
 * | pull-up | 1.223 + 0.0393·V | 1.034 + 0.0461·V, flat 1.50 above V10 | 1.169 + 0.0437·V |
 * | pull-up SD | 0.15 ≤ V9, 0.17 ≥ V10 | 0.13 | 0.16 ≤ V9, 0.17 ≥ V10 |
 *
 * Between whole V grades the centre follows its line and the SD is interpolated linearly.
 *
 * **Honesty notes.**
 * - Valid V0–V13 ([MIN_DIFFICULTY]..[MAX_DIFFICULTY]). Below V2 the lines are extrapolated, women's hang data end
 *   at V10 (above it everyone's line is used), men's pull-up above V10 is an upper estimate. [Band.thinData] marks
 *   grades below V2, women above V10 and everyone above V11.
 * - Finger strength goes with the boulder grade at r ≈ 0.64–0.70 (it explains about half of the grade
 *   differences), pull-up strength at r ≈ 0.51–0.58 (about a third), so pull-up strength separates grades poorly.
 *   A grade predicted from strength is off by about ± 2 V grades: the app never shows one, only the range of
 *   grades a value is common at ([typicalRange]) or where a value sits at the athlete's own grade ([position]).
 * - Grades are self-reported outdoor grades, and the data come from climbers who test and train; they may
 *   overstate the typical gym climber.
 */
object GradeStrengthNorms {

    enum class Metric { FINGER_MAX_HANG_20MM, PULL_UP_1RM }

    enum class Position { BELOW, WITHIN, ABOVE }

    enum class Plausibility { PLAUSIBLE, UNUSUALLY_LOW, UNUSUALLY_HIGH }

    /** The comparison group: men, women, or everyone (no sex in the profile, or "other"). */
    enum class NormSex {
        MALE, FEMALE, COMBINED;

        companion object {
            fun of(sex: Sex?): NormSex = when (sex) {
                Sex.MALE -> MALE
                Sex.FEMALE -> FEMALE
                Sex.OTHER, null -> COMBINED
            }
        }
    }

    /** Centre ± one SD in % of body weight; [thinData]: only few comparison values at this grade. */
    data class Band(val low: Double, val center: Double, val high: Double, val thinData: Boolean = false)

    /**
     * The easiest and hardest of the app's whole grades ([MIN_DIFFICULTY]..[MAX_DIFFICULTY]) whose band contains
     * a value. [atLowerEnd] / [atUpperEnd]: the range reaches the end of the scale, so the value is common below
     * or above it as well. [thinData]: an end of the range rests on few comparison values.
     */
    data class TypicalRange(
        val lowDifficulty: Double,
        val highDifficulty: Double,
        val atLowerEnd: Boolean,
        val atUpperEnd: Boolean,
        val thinData: Boolean,
    )

    /** 4b = V0, the easiest grade the norms speak for. */
    const val MIN_DIFFICULTY = 11.0

    /** 8b = V13, the hardest grade the norms speak for. */
    const val MAX_DIFFICULTY = 30.0

    /** Difficulty → V grade anchors of the app's scale; piecewise linear in between. */
    private val V_ANCHORS = listOf(
        11.0 to 0.0, 13.0 to 1.0, 15.0 to 2.0, 16.0 to 3.0, 18.0 to 4.0, 20.0 to 5.0, 22.0 to 6.0, 23.0 to 7.0,
        25.0 to 8.0, 26.0 to 9.0, 27.0 to 10.0, 28.0 to 11.0, 29.0 to 12.0, 30.0 to 13.0, 31.0 to 14.0,
    )

    /**
     * V grade of a difficulty on the app's scale: 11 = 4b (V0), 13 = 5a (V1), 15 = 5c (V2), 16 = 6a (V3),
     * 18 = 6b (V4), 20 = 6c (V5), 22 = 7a (V6), 23 = 7a+ (V7), 25 = 7b+ (V8), 26 = 7c (V9), 27 = 7c+ (V10),
     * 28 = 8a (V11), 29 = 8a+ (V12), 30 = 8b (V13), 31 = 8b+ (V14), linear in between (7b = 24 = V7.5);
     * clamped to V0–V14.
     */
    fun vOf(difficulty: Double): Double {
        if (difficulty <= V_ANCHORS.first().first) return V_ANCHORS.first().second
        if (difficulty >= V_ANCHORS.last().first) return V_ANCHORS.last().second
        val upper = V_ANCHORS.indexOfFirst { it.first >= difficulty }
        val (d0, v0) = V_ANCHORS[upper - 1]
        val (d1, v1) = V_ANCHORS[upper]
        return v0 + (v1 - v0) * (difficulty - d0) / (d1 - d0)
    }

    /** Band at a difficulty, or null outside V0–V13. */
    fun band(metric: Metric, difficulty: Double, sex: NormSex): Band? {
        if (difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY) return null
        val v = vOf(difficulty)
        val group = dataGroup(metric, sex, v)
        val mid = center(metric, group, v) * 100.0
        val spread = sd(metric, group, v) * 100.0
        return Band(mid - spread, mid, mid + spread, thinData(sex, v))
    }

    /** Where [valuePctBw] sits in the band at [difficulty]; null outside V0–V13. */
    fun position(metric: Metric, difficulty: Double, valuePctBw: Double, sex: NormSex): Position? {
        val b = band(metric, difficulty, sex) ?: return null
        return when {
            valuePctBw < b.low -> Position.BELOW
            valuePctBw > b.high -> Position.ABOVE
            else -> Position.WITHIN
        }
    }

    /**
     * The grades at which [valuePctBw] is common – "common between 6c and 7c+" – instead of one predicted grade,
     * which would be off by about ± 2 V grades. Null when the value lies outside the band at every grade.
     */
    fun typicalRange(metric: Metric, valuePctBw: Double, sex: NormSex): TypicalRange? {
        val grades = (MIN_DIFFICULTY.toInt()..MAX_DIFFICULTY.toInt()).map { it.toDouble() }.filter { d ->
            val b = band(metric, d, sex) ?: return@filter false
            valuePctBw >= b.low && valuePctBw <= b.high
        }
        if (grades.isEmpty()) return null
        val low = grades.first()
        val high = grades.last()
        return TypicalRange(
            lowDifficulty = low,
            highDifficulty = high,
            atLowerEnd = low <= MIN_DIFFICULTY,
            atUpperEnd = high >= MAX_DIFFICULTY,
            thinData = thinData(sex, vOf(low)) || thinData(sex, vOf(high)),
        )
    }

    /** A self-estimate more than 2.5 SD from the centre at the athlete's grade is worth a second look – a hint, not a block. */
    fun plausibility(metric: Metric, difficulty: Double, valuePctBw: Double, sex: NormSex): Plausibility {
        val b = band(metric, difficulty, sex) ?: return Plausibility.PLAUSIBLE
        val spread = b.high - b.center
        return when {
            valuePctBw > b.center + 2.5 * spread -> Plausibility.UNUSUALLY_HIGH
            valuePctBw < b.center - 2.5 * spread -> Plausibility.UNUSUALLY_LOW
            else -> Plausibility.PLAUSIBLE
        }
    }

    /** Women's hang data cover V2–V10; outside that everyone's line stands in. */
    private fun dataGroup(metric: Metric, sex: NormSex, v: Double): NormSex =
        if (metric == Metric.FINGER_MAX_HANG_20MM && sex == NormSex.FEMALE && (v < 2.0 || v > 10.0)) NormSex.COMBINED else sex

    /** Few comparison values: below V2, women above V10, everyone above V11. */
    private fun thinData(sex: NormSex, v: Double): Boolean =
        v < 2.0 || v > (if (sex == NormSex.FEMALE) 10.0 else 11.0)

    /** Centre in × body weight. */
    private fun center(metric: Metric, group: NormSex, v: Double): Double = when (metric) {
        Metric.FINGER_MAX_HANG_20MM -> when (group) {
            NormSex.MALE -> 0.998 + 0.0545 * v
            NormSex.FEMALE -> 0.941 + 0.0599 * v
            NormSex.COMBINED -> 0.976 + 0.0568 * v
        }
        Metric.PULL_UP_1RM -> when (group) {
            NormSex.MALE -> 1.223 + 0.0393 * v
            NormSex.FEMALE -> if (v > 10.0) 1.50 else 1.034 + 0.0461 * v
            NormSex.COMBINED -> 1.169 + 0.0437 * v
        }
    }

    /** SD in × body weight, linear between whole V grades. */
    private fun sd(metric: Metric, group: NormSex, v: Double): Double {
        val lower = floor(v).toInt()
        val fraction = v - lower
        val atLower = sdAt(metric, group, lower)
        if (fraction < 1e-9) return atLower
        return atLower + (sdAt(metric, group, lower + 1) - atLower) * fraction
    }

    private fun sdAt(metric: Metric, group: NormSex, v: Int): Double = when (metric) {
        Metric.FINGER_MAX_HANG_20MM -> when (group) {
            NormSex.MALE -> when {
                v <= 5 -> 0.14
                v <= 9 -> 0.15
                else -> 0.17
            }
            NormSex.FEMALE -> if (v <= 3) 0.12 else 0.16
            NormSex.COMBINED -> if (v <= 9) 0.15 else 0.17
        }
        Metric.PULL_UP_1RM -> when (group) {
            NormSex.MALE -> if (v <= 9) 0.15 else 0.17
            NormSex.FEMALE -> 0.13
            NormSex.COMBINED -> if (v <= 9) 0.16 else 0.17
        }
    }
}
