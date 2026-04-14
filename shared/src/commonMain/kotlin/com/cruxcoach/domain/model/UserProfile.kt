package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: Long = 0,
    val name: String,
    val age: Int,
    val weightKg: Double,
    val heightCm: Double,
    val apeIndex: Double? = null,
    val maxBoulderGrade: String,
    val maxSportGrade: String? = null,
    val climbingYears: Double = 1.0,
    val sessionsPerWeek: Int = 3,
    val availableEquipment: List<String> = emptyList(),
    val injuryHistory: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)
