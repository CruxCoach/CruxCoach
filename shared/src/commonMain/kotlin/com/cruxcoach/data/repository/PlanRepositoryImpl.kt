package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.*
import com.cruxcoach.util.DateTimeUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PlanRepositoryImpl(
    private val database: SecureDatabase
) : PlanRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val planQueries = database.trainingPlanQueries
    private val sessionQueries = database.trainingSessionQueries

    override fun getActivePlan(userId: Long): TrainingPlan? {
        return planQueries.getActivePlan(userId).executeAsOneOrNull()?.toDomain()
    }

    override fun getPlanById(id: Long): TrainingPlan? {
        return planQueries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override fun getAllPlans(userId: Long): List<TrainingPlan> {
        return planQueries.getAllForUser(userId).executeAsList().map { it.toDomain() }
    }

    override fun insertPlan(plan: TrainingPlan): Long {
        return planQueries.transactionWithResult {
            planQueries.insert(
                user_id = plan.userId,
                start_date = plan.startDate,
                end_date = plan.endDate,
                phase = plan.phase.name,
                focus_areas = json.encodeToString(plan.focusAreas),
                sessions_per_week = plan.sessionsPerWeek.toLong(),
                plan_version = plan.planVersion.toLong(),
                generated_by = plan.generatedBy.name
            )
            planQueries.lastInsertRowId().executeAsOne()
        }
    }

    override fun deletePlan(id: Long) {
        planQueries.deleteById(id)
    }

    override fun getSessionsForPlan(planId: Long): List<PlannedSession> {
        return sessionQueries.getForPlan(planId).executeAsList().map { it.toDomain() }
    }

    override fun getSessionForDay(planId: Long, dayOfWeek: Int): PlannedSession? {
        return sessionQueries.getForPlanAndDay(planId, dayOfWeek.toLong())
            .executeAsOneOrNull()?.toDomain()
    }

    override fun insertSession(session: PlannedSession): Long {
        return sessionQueries.transactionWithResult {
            sessionQueries.insert(
                plan_id = session.planId,
                day_of_week = session.dayOfWeek.toLong(),
                session_type = session.sessionType.name,
                exercises = json.encodeToString(session.exercises),
                target_duration_min = session.targetDurationMin.toLong(),
                target_rpe = session.targetRpe.toDouble(),
                notes = session.notes
            )
            sessionQueries.lastInsertRowId().executeAsOne()
        }
    }

    override fun getSessionForToday(userId: Long): PlannedSession? {
        val activePlan = getActivePlan(userId) ?: return null
        val todayDow = DateTimeUtil.dayOfWeek(DateTimeUtil.todayIso())
        return getSessionForDay(activePlan.id, todayDow)
    }

    override fun deleteSessionsForPlan(planId: Long) {
        sessionQueries.deleteForPlan(planId)
    }

    override fun replaceSessionsForPlan(planId: Long, sessions: List<PlannedSession>) {
        database.transaction {
            sessionQueries.deleteForPlan(planId)
            for (session in sessions) {
                sessionQueries.insert(
                    plan_id = planId,
                    day_of_week = session.dayOfWeek.toLong(),
                    session_type = session.sessionType.name,
                    exercises = json.encodeToString(session.exercises),
                    target_duration_min = session.targetDurationMin.toLong(),
                    target_rpe = session.targetRpe.toDouble(),
                    notes = session.notes
                )
            }
        }
    }

    override fun savePlan(plan: TrainingPlan, sessions: List<PlannedSession>): Long {
        return database.transactionWithResult {
            planQueries.insert(
                user_id = plan.userId,
                start_date = plan.startDate,
                end_date = plan.endDate,
                phase = plan.phase.name,
                focus_areas = json.encodeToString(plan.focusAreas),
                sessions_per_week = plan.sessionsPerWeek.toLong(),
                plan_version = plan.planVersion.toLong(),
                generated_by = plan.generatedBy.name
            )
            val planId = planQueries.lastInsertRowId().executeAsOne()
            for (session in sessions) {
                sessionQueries.insert(
                    plan_id = planId,
                    day_of_week = session.dayOfWeek.toLong(),
                    session_type = session.sessionType.name,
                    exercises = json.encodeToString(session.exercises),
                    target_duration_min = session.targetDurationMin.toLong(),
                    target_rpe = session.targetRpe.toDouble(),
                    notes = session.notes
                )
            }
            planId
        }
    }

    private fun com.cruxcoach.db.secure.TrainingPlan.toDomain(): TrainingPlan {
        return TrainingPlan(
            id = id,
            userId = user_id,
            startDate = start_date,
            endDate = end_date,
            phase = TrainingPhase.valueOf(phase),
            focusAreas = json.decodeFromString(focus_areas),
            sessionsPerWeek = sessions_per_week.toInt(),
            planVersion = plan_version.toInt(),
            generatedBy = PlanGeneratedBy.valueOf(generated_by)
        )
    }

    private fun com.cruxcoach.db.secure.TrainingSession.toDomain(): PlannedSession {
        return PlannedSession(
            id = id,
            planId = plan_id,
            dayOfWeek = day_of_week.toInt(),
            sessionType = SessionType.valueOf(session_type),
            exercises = json.decodeFromString(exercises),
            targetDurationMin = target_duration_min.toInt(),
            targetRpe = target_rpe.toFloat(),
            notes = notes
        )
    }
}
