package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.ClimbLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClimbRepositoryImpl(
    private val database: SecureDatabase
) : ClimbRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val queries = database.climbLogQueries

    override fun insertClimb(log: ClimbLog): Long {
        return queries.transactionWithResult {
            queries.insert(
                workout_log_id = log.workoutLogId,
                date = log.date,
                grade = log.grade,
                style = log.style,
                hold_types = json.encodeToString(log.holdTypes),
                attempts = log.attempts.toLong(),
                sent = if (log.sent) 1L else 0L,
                flash = if (log.flash) 1L else 0L,
                board_type = log.boardType,
                board_angle = log.boardAngle?.toLong(),
                board_climb_external_id = log.boardClimbExternalId,
                notes = log.notes
            )
            queries.lastInsertRowId().executeAsOne()
        }
    }

    override fun getClimbById(id: Long): ClimbLog? {
        return queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun getClimbsForWorkout(workoutLogId: Long): List<ClimbLog> {
        return queries.getForWorkout(workoutLogId).executeAsList().map { it.toDomain() }
    }

    override fun getSendsForDateRange(startDate: String, endDate: String): List<ClimbLog> {
        return queries.getSendsForDateRange(startDate, endDate).executeAsList().map { it.toDomain() }
    }

    override fun getGradePyramid(): Map<String, Long> {
        return queries.getGradePyramid().executeAsList().associate { it.grade to it.count }
    }

    override fun getFlashRate(): Map<String, Double> {
        return queries.getFlashRate().executeAsList().associate { it.grade to it.flash_pct }
    }

    override fun getStyleDistribution(): Map<String, Long> {
        return queries.getStyleDistribution().executeAsList()
            .associate { (it.style ?: "UNKNOWN") to it.count }
    }

    override fun getRecentHighestGrade(): String? {
        return queries.getRecentHighestGrade().executeAsOneOrNull()?.max_grade
    }

    override fun getAll(): List<ClimbLog> {
        return queries.getAll().executeAsList().map { it.toDomain() }
    }

    override fun deleteClimb(id: Long) {
        queries.deleteById(id)
    }

    private fun com.cruxcoach.db.secure.ClimbLog.toDomain(): ClimbLog {
        return ClimbLog(
            id = id,
            workoutLogId = workout_log_id,
            date = date,
            grade = grade,
            style = style,
            holdTypes = json.decodeFromString(hold_types ?: "[]"),
            attempts = attempts.toInt(),
            sent = sent == 1L,
            flash = flash == 1L,
            boardType = board_type,
            boardAngle = board_angle?.toInt(),
            boardClimbExternalId = board_climb_external_id,
            notes = notes
        )
    }
}
