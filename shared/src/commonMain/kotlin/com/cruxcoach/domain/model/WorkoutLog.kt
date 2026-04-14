package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkoutLog(
    val id: Long = 0,
    val sessionId: Long? = null,
    val date: String,
    val actualDurationMin: Int? = null,
    val perceivedRpe: Double? = null,
    val energyLevel: Int? = null,
    val moodPre: Int? = null,
    val moodPost: Int? = null,
    val fingerSkinStatus: String = "GOOD",
    val painAreas: List<String> = emptyList(),
    val sleepHoursPrevNight: Double? = null,
    val completedExercises: List<ExerciseBlock> = emptyList(),
    val freeNotes: String? = null
)
