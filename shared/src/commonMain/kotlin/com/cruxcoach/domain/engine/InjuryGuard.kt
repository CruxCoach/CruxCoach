package com.cruxcoach.domain.engine

import com.cruxcoach.domain.model.*

class InjuryGuard {

    fun getActiveRestrictions(
        userProfile: UserProfile,
        recentLogs: List<WorkoutLog>
    ): List<TrainingRestriction> {
        val restrictions = mutableListOf<TrainingRestriction>()

        // Check chronic injuries from profile
        for (injury in userProfile.injuryHistory) {
            addChronicRestrictions(injury, restrictions)
        }

        // Check acute pain from most recent workout log
        val lastLog = recentLogs.maxWithOrNull(compareBy<WorkoutLog> { it.date }.thenBy { it.id })
        if (lastLog != null) {
            addAcuteRestrictions(lastLog, restrictions)
        }

        return restrictions
    }

    fun validateSession(
        session: PlannedSession,
        restrictions: List<TrainingRestriction>
    ): PlannedSession {
        if (restrictions.isEmpty()) return session

        val stopCategories = restrictions
            .filter { it.severity == Severity.STOP }
            .flatMap { it.restrictedCategories }
            .toSet()

        val cautionCategories = restrictions
            .filter { it.severity == Severity.CAUTION }
            .flatMap { it.restrictedCategories }
            .toSet()

        val cautionReasons = restrictions
            .filter { it.severity == Severity.CAUTION }
            .flatMap { restriction ->
                restriction.restrictedCategories.map { category -> category to restriction.reason }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, reasons) -> reasons.distinct().joinToString(" · ") }

        val filteredExercises = session.exercises
            .filter { exercise ->
                // Remove exercises whose category is in STOP restrictions
                exercise.category.uppercase() !in stopCategories
            }
            .map { exercise ->
                // Add warning notes to CAUTION exercises
                if (exercise.category.uppercase() in cautionCategories) {
                    val reason = cautionReasons[exercise.category.uppercase()] ?: "Vorsicht bei Vorbelastung"
                    exercise.copy(
                        notes = if (exercise.notes.isBlank()) "⚠️ $reason"
                        else "${exercise.notes} | ⚠️ $reason"
                    )
                } else {
                    exercise
                }
            }

        return session.copy(exercises = filteredExercises)
    }

    internal fun addChronicRestrictions(
        injury: String,
        restrictions: MutableList<TrainingRestriction>
    ) {
        val injuryLower = injury.lowercase()
        when {
            "pulley" in injuryLower || "ringband" in injuryLower -> {
                restrictions.add(
                    TrainingRestriction(
                        restrictedCategories = setOf("HANGBOARD", "CAMPUS", "POWER"),
                        reason = "Pulley-Verletzung in Historie – konservatives Fingertraining",
                        severity = Severity.CAUTION
                    )
                )
            }
            "shoulder" in injuryLower || "schulter" in injuryLower -> {
                restrictions.add(
                    TrainingRestriction(
                        restrictedCategories = setOf("CAMPUS"),
                        reason = "Schulter-Vorbelastung – kein dynamisches Zugtraining über Kopf",
                        severity = Severity.CAUTION
                    )
                )
            }
            "elbow" in injuryLower || "ellenbogen" in injuryLower -> {
                restrictions.add(
                    TrainingRestriction(
                        restrictedCategories = setOf("HANGBOARD"),
                        reason = "Ellenbogen-Vorbelastung – Hangboard-Volumen limitiert",
                        severity = Severity.CAUTION
                    )
                )
            }
        }
    }

    internal fun addAcuteRestrictions(
        lastLog: WorkoutLog,
        restrictions: MutableList<TrainingRestriction>
    ) {
        if (lastLog.painAreas.any { it.contains("finger", ignoreCase = true) }) {
            restrictions.add(
                TrainingRestriction(
                    restrictedCategories = setOf("HANGBOARD", "CAMPUS", "POWER", "BOARD_CLIMBING"),
                    reason = "Finger-Schmerz beim letzten Training – KEIN Fingertraining bis schmerzfrei!",
                    severity = Severity.STOP
                )
            )
        }
    }
}
