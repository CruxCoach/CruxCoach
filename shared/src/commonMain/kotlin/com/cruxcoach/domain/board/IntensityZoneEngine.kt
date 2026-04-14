package com.cruxcoach.domain.board

enum class IntensityZone { WARMUP, OPTIMAL, LIMIT }

data class IntensityZones(
    val warmUpCeiling: Double,
    val optimalCeiling: Double,
    val isPersonalized: Boolean
) {
    fun classify(difficultyAverage: Double): IntensityZone = when {
        difficultyAverage <= warmUpCeiling -> IntensityZone.WARMUP
        difficultyAverage <= optimalCeiling -> IntensityZone.OPTIMAL
        else -> IntensityZone.LIMIT
    }
}

enum class SessionType { WARMUP_SESSION, VOLUME_SESSION, LIMIT_SESSION, PYRAMID_SESSION }

object IntensityZoneEngine {

    private const val MIN_DATA_POINTS = 5

    /**
     * Compute personalized intensity zones from all user interaction difficulties
     * (both sends and attempts) using 25th/75th percentile boundaries.
     */
    fun computeZones(difficulties: List<Double>, fallbackMaxGrade: String? = null): IntensityZones {
        if (difficulties.size < MIN_DATA_POINTS) {
            return computeFallbackZones(fallbackMaxGrade)
        }
        val sorted = difficulties.sorted()
        val p25 = percentile(sorted, 25)
        val p75 = percentile(sorted, 75)
        return IntensityZones(
            warmUpCeiling = p25,
            optimalCeiling = p75,
            isPersonalized = true
        )
    }

    /**
     * Fallback zones when insufficient data: derive from user's max grade string.
     * warmUpCeiling = maxDifficulty - 6 (~3 V-grades below max)
     * optimalCeiling = maxDifficulty - 2 (~1 V-grade below max)
     */
    fun computeFallbackZones(maxGrade: String?): IntensityZones {
        val maxDiff = if (maxGrade != null) {
            KilterGradeMapper.vScaleToDifficulty(maxGrade).toDouble()
        } else {
            20.0 // default ~V5
        }
        return IntensityZones(
            warmUpCeiling = maxDiff - 6.0,
            optimalCeiling = maxDiff - 2.0,
            isPersonalized = false
        )
    }

    /**
     * Classify a session's overall type based on zone distribution.
     */
    fun classifySession(difficulties: List<Double>, zones: IntensityZones): SessionType {
        if (difficulties.isEmpty()) return SessionType.WARMUP_SESSION
        val counts = difficulties.groupBy { zones.classify(it) }
        val total = difficulties.size.toFloat()
        val warmupPct = (counts[IntensityZone.WARMUP]?.size ?: 0) / total
        val optimalPct = (counts[IntensityZone.OPTIMAL]?.size ?: 0) / total
        val limitPct = (counts[IntensityZone.LIMIT]?.size ?: 0) / total
        return when {
            warmupPct > 0.6f -> SessionType.WARMUP_SESSION
            optimalPct > 0.5f -> SessionType.VOLUME_SESSION
            limitPct > 0.4f -> SessionType.LIMIT_SESSION
            else -> SessionType.PYRAMID_SESSION
        }
    }

    /**
     * Compute percentile using linear interpolation (same as numpy default).
     * @param sorted pre-sorted list of values
     * @param p percentile (0-100)
     */
    private fun percentile(sorted: List<Double>, p: Int): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val fraction = rank - lower
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower])
    }
}
