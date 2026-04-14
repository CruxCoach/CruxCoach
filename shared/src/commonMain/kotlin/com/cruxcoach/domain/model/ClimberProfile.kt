package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ClimberProfile(
    val fingerStrength: Float,
    val upperBodyPull: Float,
    val upperBodyPush: Float,
    val coreStrength: Float,
    val power: Float,
    val powerEndurance: Float,
    val flexibility: Float,
    val technique: Float,
    val overallLevel: ClimbingLevel
)
