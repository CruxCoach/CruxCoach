package com.cruxcoach.domain.engine

import com.cruxcoach.domain.model.*

class PhaseSelector {

    fun selectPhase(
        weeksSinceStart: Int,
        recentLogs: List<WorkoutLog>,
        profile: ClimberProfile
    ): TrainingPhase {
        // Override 1: Finger pain in recent logs → forced DELOAD
        if (hasFingerPain(recentLogs)) {
            return TrainingPhase.DELOAD
        }

        // Override 2: Average RPE > 8.5 over last 4 sessions → forced DELOAD
        if (isOverreaching(recentLogs)) {
            return TrainingPhase.DELOAD
        }

        // Override 3: Beginner climbers (< 6 months experience) → always BASE
        if (profile.overallLevel == ClimbingLevel.BEGINNER) {
            return TrainingPhase.BASE
        }

        // Macro cycle: every 5th week is DELOAD (weeks 5, 10, 15, 20...)
        val weekInCycle = weeksSinceStart % 5
        if (weekInCycle == 4) { // 0-indexed: week 4 of each 5-week block
            return TrainingPhase.DELOAD
        }

        // Within 4-week work block: select phase based on weaknesses
        return selectPhaseFromWeaknesses(profile, weekInCycle)
    }

    internal fun hasFingerPain(recentLogs: List<WorkoutLog>): Boolean {
        val lastLog = recentLogs.maxWithOrNull(compareBy<WorkoutLog> { it.date }.thenBy { it.id })
            ?: return false
        return lastLog.painAreas.any { it.contains("finger", ignoreCase = true) }
    }

    internal fun isOverreaching(recentLogs: List<WorkoutLog>): Boolean {
        val rpeValues = recentLogs
            .sortedWith(compareByDescending<WorkoutLog> { it.date }.thenByDescending { it.id })
            .filter { it.perceivedRpe != null }
            .take(4)
            .mapNotNull { it.perceivedRpe }

        if (rpeValues.size < 2) return false
        return rpeValues.average() > 8.5
    }

    internal fun selectPhaseFromWeaknesses(
        profile: ClimberProfile,
        weekInBlock: Int
    ): TrainingPhase {
        val weakest = findWeakestDimension(profile)

        // If primary weakness is finger/pull strength → STRENGTH focus
        if (weakest in listOf("finger_strength", "upper_body_pull")) {
            return when (weekInBlock) {
                0 -> TrainingPhase.STRENGTH
                1 -> TrainingPhase.STRENGTH
                2 -> TrainingPhase.POWER
                3 -> TrainingPhase.PERFORMANCE
                else -> TrainingPhase.STRENGTH
            }
        }

        // If primary weakness is power → POWER focus
        if (weakest == "power") {
            return when (weekInBlock) {
                0 -> TrainingPhase.POWER
                1 -> TrainingPhase.STRENGTH
                2 -> TrainingPhase.POWER
                3 -> TrainingPhase.PERFORMANCE
                else -> TrainingPhase.POWER
            }
        }

        // Default rotation: STRENGTH → POWER → STRENGTH → PERFORMANCE
        return when (weekInBlock) {
            0 -> TrainingPhase.STRENGTH
            1 -> TrainingPhase.POWER
            2 -> TrainingPhase.STRENGTH
            3 -> TrainingPhase.PERFORMANCE
            else -> TrainingPhase.STRENGTH
        }
    }

    internal fun findWeakestDimension(profile: ClimberProfile): String {
        val dimensions = mapOf(
            "finger_strength" to profile.fingerStrength,
            "upper_body_pull" to profile.upperBodyPull,
            "upper_body_push" to profile.upperBodyPush,
            "core" to profile.coreStrength,
            "power" to profile.power,
            "power_endurance" to profile.powerEndurance,
            "flexibility" to profile.flexibility,
            "technique" to profile.technique
        )
        return dimensions.minByOrNull { it.value }?.key ?: "finger_strength"
    }
}
