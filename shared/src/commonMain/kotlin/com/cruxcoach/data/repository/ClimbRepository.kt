package com.cruxcoach.data.repository

import com.cruxcoach.domain.model.ClimbLog

interface ClimbRepository {
    fun insertClimb(log: ClimbLog): Long
    fun getClimbById(id: Long): ClimbLog?
    fun getClimbsForWorkout(workoutLogId: Long): List<ClimbLog>
    fun getSendsForDateRange(startDate: String, endDate: String): List<ClimbLog>
    fun getGradePyramid(): Map<String, Long>
    fun getFlashRate(): Map<String, Double>
    fun getStyleDistribution(): Map<String, Long>
    fun getRecentHighestGrade(): String?
    fun getAll(): List<ClimbLog>
    fun deleteClimb(id: Long)
}
