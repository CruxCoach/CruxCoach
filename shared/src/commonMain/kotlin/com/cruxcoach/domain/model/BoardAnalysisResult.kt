package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BoardAnalysisResult(
    val boardType: String,
    val maxGrade: String,
    val comfortGrade: String,
    val totalSends: Int,
    val strongestAngle: Int? = null,
    val weakestAngle: Int? = null,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val powerScore: Float,
    val enduranceScore: Float,
    val gradePyramid: Map<String, Int> = emptyMap(),
    val monthlyProgression: Map<String, String> = emptyMap()
)
