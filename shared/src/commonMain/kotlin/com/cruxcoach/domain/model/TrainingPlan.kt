package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TrainingPlan(
    val id: Long = 0,
    val userId: Long,
    val startDate: String,
    val endDate: String,
    val phase: TrainingPhase,
    val focusAreas: List<String> = emptyList(),
    val sessionsPerWeek: Int,
    val planVersion: Int = 1,
    val generatedBy: PlanGeneratedBy = PlanGeneratedBy.INITIAL
)

@Serializable
data class PlannedSession(
    val id: Long = 0,
    val planId: Long = 0,
    val dayOfWeek: Int,
    val sessionType: SessionType,
    val exercises: List<ExerciseBlock>,
    val targetDurationMin: Int,
    val targetRpe: Float,
    val notes: String? = null
)

@Serializable
data class ExerciseBlock(
    val exerciseId: Long = 0,
    val nameEn: String,
    val nameDe: String = "",
    val category: String,
    val sets: Int,
    val reps: String = "",
    val weight: String = "",
    val duration: String = "",
    val restSeconds: Int = 120,
    val notes: String = ""
)
