package com.cruxcoach.domain.board

/**
 * Analyzes climbing performance on board climbs.
 * Computes sandbag scores, weakness profiles, angle progression, and hold heatmaps.
 */
object BoardAnalyzer {

    data class BoardAnalysisResult(
        val totalAscents: Int,
        val totalAttempts: Int,
        val maxGradeByAngle: Map<Int, String>,
        val sandbagScore: Float,
        val weaknesses: List<String>,
        val angleProgression: Map<Int, String>,
        val holdHeatmap: Map<Int, Int>
    )

    data class AscentData(
        val climbUuid: String,
        val angle: Int,
        val difficulty: Double,
        val quality: Int,
        val bidCount: Int,
        val frames: String
    )

    /**
     * Analyze user's board performance from their ascent history.
     */
    fun analyzeFromAscents(ascents: List<AscentData>): BoardAnalysisResult {
        if (ascents.isEmpty()) return emptyResult()

        val totalAscents = ascents.size
        val totalAttempts = ascents.sumOf { it.bidCount.coerceAtLeast(1) }

        val maxGradeByAngle = computeMaxGradeByAngle(ascents)
        val sandbagScore = calculateSandbagScore(ascents)
        val weaknesses = getWeaknessProfile(ascents)
        val angleProgression = maxGradeByAngle.mapValues { (_, grade) -> grade }
        val holdHeatmap = computeHoldHeatmap(ascents)

        return BoardAnalysisResult(
            totalAscents = totalAscents,
            totalAttempts = totalAttempts,
            maxGradeByAngle = maxGradeByAngle,
            sandbagScore = sandbagScore,
            weaknesses = weaknesses,
            angleProgression = angleProgression,
            holdHeatmap = holdHeatmap
        )
    }

    /**
     * Sandbag score: how much harder/easier the user grades compared to community.
     * > 0 = user grades harder (sandbagger), < 0 = user grades softer (spray-waller)
     * Range: roughly -2.0 to +2.0
     */
    fun calculateSandbagScore(ascents: List<AscentData>): Float {
        if (ascents.isEmpty()) return 0f

        // Average quality rating relative to community norm (3.0 = neutral)
        // High quality ratings on hard climbs = user finds them appropriate
        // Low quality ratings = user finds them too easy/hard
        val avgQuality = ascents.map { it.quality }.average()
        val avgBidCount = ascents.map { it.bidCount.coerceAtLeast(1) }.average()

        // More attempts needed → climbs are harder than expected → positive sandbag score
        // Fewer attempts → climbs are easier → negative
        val attemptFactor = (avgBidCount - 3.0) / 3.0 // Normalized around 3 attempts average
        val qualityFactor = (avgQuality - 3.0) / 2.0 // Normalized around neutral quality

        return (attemptFactor + qualityFactor).toFloat().coerceIn(-2f, 2f)
    }

    /**
     * Compute max V-Scale grade achieved at each angle.
     */
    fun computeMaxGradeByAngle(ascents: List<AscentData>): Map<Int, String> {
        return ascents.groupBy { it.angle }
            .mapValues { (_, climbsAtAngle) ->
                val maxDiff = climbsAtAngle.maxOf { it.difficulty }
                KilterGradeMapper.difficultyToVScale(maxDiff.toInt())
            }
    }

    /**
     * Which hold positions appear most frequently in sent climbs.
     * Key = placementId, Value = count.
     */
    fun computeHoldHeatmap(ascents: List<AscentData>): Map<Int, Int> {
        val heatmap = mutableMapOf<Int, Int>()
        for (ascent in ascents) {
            val holds = BoardClimbParser.parseFrames(ascent.frames)
            for (hold in holds) {
                heatmap[hold.placementId] = (heatmap[hold.placementId] ?: 0) + 1
            }
        }
        return heatmap
    }

    /**
     * Identify weaknesses based on angle performance spread.
     * If max grade at steep angles is much lower than at low angles → overhang weakness.
     */
    fun getWeaknessProfile(ascents: List<AscentData>): List<String> {
        val weaknesses = mutableListOf<String>()
        val gradeByAngle = ascents.groupBy { it.angle }
            .mapValues { (_, climbsAtAngle) -> climbsAtAngle.maxOf { it.difficulty } }

        if (gradeByAngle.size < 2) return weaknesses

        val lowAngleGrades = gradeByAngle.filter { it.key <= 25 }.values
        val steepAngleGrades = gradeByAngle.filter { it.key >= 40 }.values

        if (lowAngleGrades.isNotEmpty() && steepAngleGrades.isNotEmpty()) {
            val lowAvg = lowAngleGrades.average()
            val steepAvg = steepAngleGrades.average()

            if (lowAvg - steepAvg > 3.0) {
                weaknesses.add("Überhang-Schwäche: Stark bei Platte, schwach bei Steilwand")
            }
            if (steepAvg - lowAvg > 3.0) {
                weaknesses.add("Platten-Schwäche: Stark bei Überhang, schwach bei Platte")
            }
        }

        // Analyze hold type distribution across sent climbs
        val allHolds = ascents.flatMap { BoardClimbParser.parseFrames(it.frames) }
        val roleCounts = BoardClimbParser.countByRole(allHolds)
        val footCount = roleCounts[HoldRole.FOOT] ?: 0
        val handCount = roleCounts[HoldRole.HAND] ?: 0

        if (handCount > 0 && footCount.toFloat() / handCount < 0.3f) {
            weaknesses.add("Wenig Fußarbeit: Meiste geschaffte Climbs haben wenige Fußtritte")
        }

        // Check bid count distribution for flash rate
        val flashRate = ascents.count { it.bidCount <= 1 }.toFloat() / ascents.size
        if (flashRate < 0.15f) {
            weaknesses.add("Niedrige Flash-Rate: Mehr Onsight-Training empfohlen")
        }

        return weaknesses
    }

    private fun emptyResult() = BoardAnalysisResult(
        totalAscents = 0,
        totalAttempts = 0,
        maxGradeByAngle = emptyMap(),
        sandbagScore = 0f,
        weaknesses = emptyList(),
        angleProgression = emptyMap(),
        holdHeatmap = emptyMap()
    )
}
