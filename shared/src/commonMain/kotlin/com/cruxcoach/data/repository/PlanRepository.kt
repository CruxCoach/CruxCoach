package com.cruxcoach.data.repository

import com.cruxcoach.domain.model.PlannedSession
import com.cruxcoach.domain.model.TrainingPlan

interface PlanRepository {
    fun getActivePlan(userId: Long): TrainingPlan?
    fun getPlanById(id: Long): TrainingPlan?
    fun getAllPlans(userId: Long): List<TrainingPlan>
    fun insertPlan(plan: TrainingPlan): Long
    fun deletePlan(id: Long)

    fun getSessionsForPlan(planId: Long): List<PlannedSession>
    fun getSessionForDay(planId: Long, dayOfWeek: Int): PlannedSession?
    fun getSessionForToday(userId: Long): PlannedSession?
    fun insertSession(session: PlannedSession): Long
    fun deleteSessionsForPlan(planId: Long)

    fun savePlan(plan: TrainingPlan, sessions: List<PlannedSession>): Long
    fun replaceSessionsForPlan(planId: Long, sessions: List<PlannedSession>)
}
