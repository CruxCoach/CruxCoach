package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Adaptation(
    val type: AdaptationType,
    val description: String,
    val emoji: String
)

@Serializable
data class TrainingRestriction(
    val restrictedCategories: Set<String>,
    val reason: String,
    val severity: Severity
)
