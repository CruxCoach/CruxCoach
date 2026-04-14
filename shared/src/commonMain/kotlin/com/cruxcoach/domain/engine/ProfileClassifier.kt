package com.cruxcoach.domain.engine

import com.cruxcoach.domain.model.*
import com.cruxcoach.util.GradeConverter

class ProfileClassifier {

    companion object {
        // Benchmark: max_hang as % bodyweight per V-grade (Lattice/Power Company Research)
        val FINGER_STRENGTH_BENCHMARKS = mapOf(
            "V0" to 70f,  "V1" to 80f,  "V2" to 90f,
            "V3" to 100f, "V4" to 110f, "V5" to 120f,
            "V6" to 130f, "V7" to 140f, "V8" to 150f,
            "V9" to 160f, "V10" to 170f, "V11" to 180f,
            "V12" to 190f, "V13" to 200f, "V14" to 210f
        )

        // Benchmark: weighted pullup added weight as % bodyweight per V-grade
        val PULL_STRENGTH_BENCHMARKS = mapOf(
            "V0" to 0f,   "V1" to 0f,   "V2" to 0f,
            "V3" to 5f,   "V4" to 10f,  "V5" to 18f,
            "V6" to 25f,  "V7" to 33f,  "V8" to 40f,
            "V9" to 48f,  "V10" to 55f, "V11" to 63f,
            "V12" to 70f, "V13" to 78f, "V14" to 85f
        )
    }

    fun classify(
        assessment: Assessment,
        userProfile: UserProfile,
        boardAnalysis: BoardAnalysisResult? = null
    ): ClimberProfile {
        val grade = userProfile.maxBoulderGrade
        val bw = userProfile.weightKg

        val fingerScore = calculateFingerScore(assessment, grade, bw)
        val pullScore = calculatePullScore(assessment, grade, bw)
        val pushScore = calculatePushScore(assessment)
        val coreScore = calculateCoreScore(assessment)
        val flexScore = calculateFlexibilityScore(assessment)
        val powerScore = boardAnalysis?.powerScore ?: estimatePowerFromGrade(grade)
        val enduranceScore = boardAnalysis?.enduranceScore ?: 5.0f
        val techniqueScore = calculateTechniqueScore(fingerScore, pullScore, grade)
        val level = determineLevel(grade)

        return ClimberProfile(
            fingerStrength = fingerScore,
            upperBodyPull = pullScore,
            upperBodyPush = pushScore,
            coreStrength = coreScore,
            power = powerScore,
            powerEndurance = enduranceScore,
            flexibility = flexScore,
            technique = techniqueScore,
            overallLevel = level
        )
    }

    internal fun calculateFingerScore(assessment: Assessment, grade: String, bodyweight: Double): Float {
        val hangKg = assessment.maxHang20mmKg ?: return 5.0f
        val expectedPct = FINGER_STRENGTH_BENCHMARKS[grade] ?: 130f
        val actualPct = (hangKg / bodyweight * 100.0).toFloat()
        return (actualPct / expectedPct * 5.0f).coerceIn(1.0f, 10.0f)
    }

    internal fun calculatePullScore(assessment: Assessment, grade: String, bodyweight: Double): Float {
        val pullKg = assessment.weightedPullupKg ?: return 5.0f
        val expectedPct = PULL_STRENGTH_BENCHMARKS[grade] ?: 25f
        if (expectedPct <= 0f) {
            // For grades where 0% is expected, any added weight is above benchmark
            return if (pullKg > 0) 8.0f else 5.0f
        }
        val actualPct = (pullKg / bodyweight * 100.0).toFloat()
        return (actualPct / expectedPct * 5.0f).coerceIn(1.0f, 10.0f)
    }

    internal fun calculatePushScore(assessment: Assessment): Float {
        val reps = assessment.pushUpMaxReps ?: return 5.0f
        return when {
            reps >= 40 -> 9.0f
            reps >= 30 -> 7.0f
            reps >= 20 -> 5.0f
            reps >= 10 -> 3.0f
            else -> 1.0f
        }
    }

    internal fun calculateCoreScore(assessment: Assessment): Float {
        val holdSec = assessment.coreHoldSec ?: return 5.0f
        return when {
            holdSec >= 120 -> 9.0f
            holdSec >= 90 -> 7.0f
            holdSec >= 60 -> 5.0f
            holdSec >= 30 -> 3.0f
            else -> 1.0f
        }
    }

    internal fun calculateFlexibilityScore(assessment: Assessment): Float {
        // flexibilityScore is 1-5 self-assessment, scale to 1-10
        return (assessment.flexibilityScore * 2.0f).coerceIn(1.0f, 10.0f)
    }

    internal fun calculateTechniqueScore(fingerScore: Float, pullScore: Float, actualGrade: String): Float {
        val predictedNumeric = predictGradeFromPhysical(fingerScore, pullScore)
        val actualNumeric = GradeConverter.vScaleToNumeric(actualGrade)
        if (actualNumeric < 0) return 5.0f

        val delta = actualNumeric - predictedNumeric
        return when {
            delta > 2 -> 9.0f   // Climbs way above physical prediction = great technique
            delta > 0 -> 7.0f   // Climbs above prediction
            delta == 0 -> 5.0f  // Average technique
            delta > -2 -> 4.0f  // Slightly below prediction
            else -> 2.0f        // Strong but can't apply it = poor technique
        }
    }

    internal fun predictGradeFromPhysical(fingerScore: Float, pullScore: Float): Int {
        // Average of finger and pull strength scores maps to approximate grade
        val avgScore = (fingerScore + pullScore) / 2.0f
        return when {
            avgScore >= 9.0f -> 12  // V12
            avgScore >= 8.0f -> 10  // V10
            avgScore >= 7.0f -> 8   // V8
            avgScore >= 6.0f -> 6   // V6
            avgScore >= 5.0f -> 4   // V4
            avgScore >= 4.0f -> 3   // V3
            avgScore >= 3.0f -> 2   // V2
            else -> 0               // V0
        }
    }

    internal fun estimatePowerFromGrade(grade: String): Float {
        val numeric = GradeConverter.vScaleToNumeric(grade)
        if (numeric < 0) return 5.0f
        return (numeric.toFloat() / 14.0f * 8.0f + 1.0f).coerceIn(1.0f, 10.0f)
    }

    internal fun determineLevel(grade: String): ClimbingLevel {
        val numeric = GradeConverter.vScaleToNumeric(grade)
        return when {
            numeric < 0 -> ClimbingLevel.BEGINNER
            numeric <= 3 -> ClimbingLevel.BEGINNER
            numeric <= 6 -> ClimbingLevel.INTERMEDIATE
            numeric <= 10 -> ClimbingLevel.ADVANCED
            else -> ClimbingLevel.ELITE
        }
    }
}
