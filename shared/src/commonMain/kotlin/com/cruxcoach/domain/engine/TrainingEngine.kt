package com.cruxcoach.domain.engine

import com.cruxcoach.domain.model.*

class TrainingEngine(
    private val exerciseSelector: ExerciseSelector,
    private val phaseSelector: PhaseSelector,
    private val injuryGuard: InjuryGuard
) {

    fun generateWeekPlan(
        profile: ClimberProfile,
        userProfile: UserProfile,
        currentAssessment: Assessment,
        recentLogs: List<WorkoutLog>,
        weeksSinceStart: Int
    ): WeekPlan {
        // 1. Identify top-2 weaknesses
        val weaknesses = findTopWeaknesses(profile, 2)

        // 2. Determine training phase
        val phase = phaseSelector.selectPhase(
            weeksSinceStart = weeksSinceStart,
            recentLogs = recentLogs,
            profile = profile
        )

        // 3. Create week template based on sessions/week + phase
        val template = createWeekTemplate(userProfile.sessionsPerWeek, phase)

        // 4. Get active injury restrictions
        val restrictions = injuryGuard.getActiveRestrictions(userProfile, recentLogs)

        // 5. Fill sessions with exercises
        val sessions = template.map { (dayOfWeek, sessionType) ->
            val exercises = exerciseSelector.selectExercises(
                sessionType = sessionType,
                weaknesses = weaknesses,
                equipment = userProfile.availableEquipment,
                restrictions = restrictions,
                level = profile.overallLevel
            )
            PlannedSession(
                dayOfWeek = dayOfWeek,
                sessionType = sessionType,
                exercises = exercises,
                targetDurationMin = estimateDuration(exercises),
                targetRpe = targetRpeForPhase(phase, sessionType)
            )
        }

        // 6. Validate sessions through InjuryGuard
        val validatedSessions = sessions.map { session ->
            injuryGuard.validateSession(session, restrictions)
        }

        return WeekPlan(
            phase = phase,
            sessions = validatedSessions,
            focusAreas = weaknesses,
            weekNumber = weeksSinceStart + 1,
            adaptationNotes = emptyList()
        )
    }

    internal fun findTopWeaknesses(profile: ClimberProfile, count: Int): List<String> {
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
        return dimensions.entries
            .sortedBy { it.value }
            .take(count)
            .map { it.key }
    }

    internal fun createWeekTemplate(
        sessionsPerWeek: Int,
        phase: TrainingPhase
    ): List<Pair<Int, SessionType>> {
        return when (sessionsPerWeek.coerceIn(MIN_SESSIONS_PER_WEEK, MAX_SESSIONS_PER_WEEK)) {
            2 -> when (phase) {
                TrainingPhase.STRENGTH -> listOf(2 to SessionType.STRENGTH, 5 to SessionType.VOLUME)
                TrainingPhase.POWER -> listOf(2 to SessionType.POWER, 5 to SessionType.VOLUME)
                TrainingPhase.DELOAD -> listOf(3 to SessionType.DELOAD, 6 to SessionType.TECHNIQUE)
                else -> listOf(2 to SessionType.STRENGTH, 5 to SessionType.VOLUME)
            }
            3 -> when (phase) {
                TrainingPhase.BASE -> listOf(
                    1 to SessionType.STRENGTH, 3 to SessionType.VOLUME, 5 to SessionType.TECHNIQUE
                )
                TrainingPhase.STRENGTH -> listOf(
                    1 to SessionType.STRENGTH, 3 to SessionType.POWER, 5 to SessionType.VOLUME
                )
                TrainingPhase.POWER -> listOf(
                    1 to SessionType.POWER, 3 to SessionType.STRENGTH, 6 to SessionType.VOLUME
                )
                TrainingPhase.PERFORMANCE -> listOf(
                    2 to SessionType.POWER, 4 to SessionType.TECHNIQUE, 6 to SessionType.VOLUME
                )
                TrainingPhase.DELOAD -> listOf(
                    2 to SessionType.DELOAD, 4 to SessionType.TECHNIQUE, 6 to SessionType.DELOAD
                )
            }
            4 -> when (phase) {
                TrainingPhase.STRENGTH -> listOf(
                    1 to SessionType.STRENGTH, 2 to SessionType.VOLUME,
                    4 to SessionType.POWER, 6 to SessionType.TECHNIQUE
                )
                TrainingPhase.POWER -> listOf(
                    1 to SessionType.POWER, 2 to SessionType.STRENGTH,
                    4 to SessionType.VOLUME, 6 to SessionType.TECHNIQUE
                )
                TrainingPhase.DELOAD -> listOf(
                    1 to SessionType.DELOAD, 3 to SessionType.TECHNIQUE,
                    5 to SessionType.DELOAD, 6 to SessionType.TECHNIQUE
                )
                else -> listOf(
                    1 to SessionType.STRENGTH, 3 to SessionType.POWER,
                    5 to SessionType.VOLUME, 6 to SessionType.TECHNIQUE
                )
            }
            else -> error("sessions/week bounds are exhaustive")
        }
    }

    private companion object {
        const val MIN_SESSIONS_PER_WEEK = 2
        const val MAX_SESSIONS_PER_WEEK = 4
    }

    internal fun targetRpeForPhase(phase: TrainingPhase, sessionType: SessionType): Float {
        return when (phase) {
            TrainingPhase.BASE -> 6.5f
            TrainingPhase.STRENGTH -> if (sessionType == SessionType.STRENGTH) 8.5f else 7.0f
            TrainingPhase.POWER -> if (sessionType == SessionType.POWER) 9.0f else 7.0f
            TrainingPhase.PERFORMANCE -> 8.0f
            TrainingPhase.DELOAD -> 5.5f
        }
    }

    internal fun estimateDuration(exercises: List<ExerciseBlock>): Int {
        if (exercises.isEmpty()) return 0
        var totalMin = 0
        for (exercise in exercises) {
            val setTime = when {
                exercise.duration.isNotBlank() && exercise.duration.contains("min") -> {
                    // Parse "5-10 min" → take average
                    val parts = exercise.duration.replace(" min", "").split("-")
                    parts.mapNotNull { it.trim().toIntOrNull() }.average().toInt()
                }
                exercise.duration.isNotBlank() -> {
                    // "7 sec" type → ~2 min per set (including rest)
                    2
                }
                else -> 3 // Default: ~3 min per set including rest
            }
            totalMin += exercise.sets * setTime + (exercise.sets - 1) * exercise.restSeconds / 60
        }
        return totalMin.coerceIn(30, 120)
    }
}
