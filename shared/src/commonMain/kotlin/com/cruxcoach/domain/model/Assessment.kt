package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Assessment(
    val id: Long = 0,
    val userId: Long,
    val date: String,
    val maxHang20mmKg: Double? = null,
    val maxHangPctBw: Double? = null,
    val weightedPullupKg: Double? = null,
    val pullupMaxReps: Int? = null,
    val pushUpMaxReps: Int? = null,
    val coreHoldSec: Int? = null,
    val flexibilityScore: Int = 3,
    val boardImportSummary: String? = null,
    val notes: String? = null
)
