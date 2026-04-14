package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.ExerciseBlock
import com.cruxcoach.domain.model.WorkoutLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkoutRepositoryImpl(
    private val database: SecureDatabase
) : WorkoutRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val queries = database.workoutLogQueries

    override fun insertWorkout(log: WorkoutLog): Long {
        return queries.transactionWithResult {
            queries.insert(
                session_id = log.sessionId,
                date = log.date,
                actual_duration_min = log.actualDurationMin?.toLong(),
                perceived_rpe = log.perceivedRpe,
                energy_level = log.energyLevel?.toLong(),
                mood_pre = log.moodPre?.toLong(),
                mood_post = log.moodPost?.toLong(),
                finger_skin_status = log.fingerSkinStatus,
                pain_areas = json.encodeToString(log.painAreas),
                sleep_hours_prev_night = log.sleepHoursPrevNight,
                completed_exercises = json.encodeToString(log.completedExercises),
                free_notes = log.freeNotes
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    override fun getWorkoutById(id: Long): WorkoutLog? {
        return queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun getRecentWorkouts(limit: Int): List<WorkoutLog> {
        return queries.getRecent(limit.toLong()).executeAsList().map { it.toDomain() }
    }

    override fun getWorkoutsForDateRange(startDate: String, endDate: String): List<WorkoutLog> {
        return queries.getForDateRange(startDate, endDate).executeAsList().map { it.toDomain() }
    }

    override fun getAvgRpeLastN(n: Int): Double? {
        return queries.getAvgRpeLastN(n.toLong()).executeAsOneOrNull()?.avg_rpe
    }

    override fun countThisWeek(): Long {
        return queries.countThisWeek().executeAsOne()
    }

    override fun getAll(): List<WorkoutLog> {
        return queries.getAll().executeAsList().map { it.toDomain() }
    }

    override fun deleteWorkout(id: Long) {
        queries.deleteById(id)
    }

    private fun com.cruxcoach.db.secure.WorkoutLog.toDomain(): WorkoutLog {
        return WorkoutLog(
            id = id,
            sessionId = session_id,
            date = date,
            actualDurationMin = actual_duration_min?.toInt(),
            perceivedRpe = perceived_rpe,
            energyLevel = energy_level?.toInt(),
            moodPre = mood_pre?.toInt(),
            moodPost = mood_post?.toInt(),
            fingerSkinStatus = finger_skin_status ?: "GOOD",
            painAreas = json.decodeFromString(pain_areas),
            sleepHoursPrevNight = sleep_hours_prev_night,
            completedExercises = completed_exercises?.let {
                json.decodeFromString<List<ExerciseBlock>>(it)
            } ?: emptyList(),
            freeNotes = free_notes
        )
    }
}
