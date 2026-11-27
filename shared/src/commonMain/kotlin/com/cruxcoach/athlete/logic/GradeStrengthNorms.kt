package com.cruxcoach.athlete.logic

/**
 * Rough orientation for "how strong are climbers at a grade", on the unified
 * difficulty scale (16 = 6a, 22 = 7a; see [LogbookSummaries.difficultyOf]).
 *
 * Built by us as a smooth line through the ranges that published climbing
 * research and coaching surveys report for boulderers: finger strength as
 * the total load held for ~10 s on a 20 mm edge with both hands (body weight
 * plus added weight, in % of body weight), and weighted pull-up 1RM as total
 * load in % of body weight. Individual spread is large; the band is ± one
 * typical spread, and the app shows it only as orientation, never as a goal
 * or a ranking. It also feeds the plausibility hint for self-estimates.
 */
object GradeStrengthNorms {

    enum class Metric { FINGER_MAX_HANG_20MM, PULL_UP_1RM }

    enum class Position { BELOW, WITHIN, ABOVE }

    enum class Plausibility { PLAUSIBLE, UNUSUALLY_LOW, UNUSUALLY_HIGH }

    data class Band(val low: Double, val center: Double, val high: Double)

    private const val REFERENCE = 18.5 // 6b/6b+ (≈ V4)

    /** Band at a difficulty, or null outside the range the line is meant for (5a … 8c). */
    fun band(metric: Metric, difficulty: Double): Band? {
        if (difficulty < 13.0 || difficulty > 32.0) return null
        val (center, spread) = when (metric) {
            Metric.FINGER_MAX_HANG_20MM -> 120.0 + 5.3 * (difficulty - REFERENCE) to 12.0
            Metric.PULL_UP_1RM -> 140.0 + 3.5 * (difficulty - REFERENCE) to 15.0
        }
        return Band(center - spread, center, center + spread)
    }

    fun position(metric: Metric, difficulty: Double, valuePctBw: Double): Position? {
        val b = band(metric, difficulty) ?: return null
        return when {
            valuePctBw < b.low -> Position.BELOW
            valuePctBw > b.high -> Position.ABOVE
            else -> Position.WITHIN
        }
    }

    /**
     * Difficulty at which [valuePctBw] would sit in the middle of the band:
     * "your fingers are typical for about 7a". Clamped to the band's range.
     */
    fun typicalDifficulty(metric: Metric, valuePctBw: Double): Double {
        val d = when (metric) {
            Metric.FINGER_MAX_HANG_20MM -> REFERENCE + (valuePctBw - 120.0) / 5.3
            Metric.PULL_UP_1RM -> REFERENCE + (valuePctBw - 140.0) / 3.5
        }
        return d.coerceIn(13.0, 32.0)
    }

    /** A self-estimate far outside two spreads is worth a second look — a hint, not a block. */
    fun plausibility(metric: Metric, difficulty: Double, valuePctBw: Double): Plausibility {
        val b = band(metric, difficulty) ?: return Plausibility.PLAUSIBLE
        val spread = b.high - b.center
        return when {
            valuePctBw > b.center + 2.5 * spread -> Plausibility.UNUSUALLY_HIGH
            valuePctBw < b.center - 2.5 * spread -> Plausibility.UNUSUALLY_LOW
            else -> Plausibility.PLAUSIBLE
        }
    }
}
