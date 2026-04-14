package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WeekPlan(
    val phase: TrainingPhase,
    val sessions: List<PlannedSession>,
    val focusAreas: List<String>,
    val weekNumber: Int,
    val adaptationNotes: List<String> = emptyList()
)
