package com.cruxcoach.data.repository

import com.cruxcoach.domain.model.WorkoutLog

interface WorkoutRepository {
    fun insertWorkout(log: WorkoutLog): Long
    fun getWorkoutById(id: Long): WorkoutLog?
    fun getRecentWorkouts(limit: Int): List<WorkoutLog>
    fun getWorkoutsForDateRange(startDate: String, endDate: String): List<WorkoutLog>
    fun getAvgRpeLastN(n: Int): Double?
    fun countThisWeek(): Long
    fun getAll(): List<WorkoutLog>
    fun deleteWorkout(id: Long)
}
