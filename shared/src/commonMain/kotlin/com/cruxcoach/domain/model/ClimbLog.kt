package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ClimbLog(
    val id: Long = 0,
    val workoutLogId: Long? = null,
    val date: String,
    val grade: String,
    val style: String? = null,
    val holdTypes: List<String> = emptyList(),
    val attempts: Int = 1,
    val sent: Boolean = false,
    val flash: Boolean = false,
    val boardType: String? = null,
    val boardAngle: Int? = null,
    val boardClimbExternalId: String? = null,
    val notes: String? = null
)
