package com.cruxcoach.usecase

import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanRepositoryRoundtripTest {

    private val repo = FakePlanRepository()

    @Test
    fun savePlan_andReadBack() {
        val plan = TrainingPlan(
            userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
            phase = TrainingPhase.STRENGTH, focusAreas = listOf("finger_strength", "core"),
            sessionsPerWeek = 3
        )

        val sessions = listOf(
            PlannedSession(
                dayOfWeek = 1, sessionType = SessionType.STRENGTH,
                exercises = listOf(
                    ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, duration = "7 sec", restSeconds = 180)
                ),
                targetDurationMin = 60, targetRpe = 8.0f
            ),
            PlannedSession(
                dayOfWeek = 3, sessionType = SessionType.POWER,
                exercises = listOf(
                    ExerciseBlock(nameEn = "Dynos", nameDe = "Dynos", category = "POWER", sets = 4, reps = "3", restSeconds = 180)
                ),
                targetDurationMin = 45, targetRpe = 8.5f
            ),
            PlannedSession(
                dayOfWeek = 5, sessionType = SessionType.VOLUME,
                exercises = listOf(
                    ExerciseBlock(nameEn = "4x4s", nameDe = "4x4s", category = "ENDURANCE", sets = 3, reps = "4", restSeconds = 240)
                ),
                targetDurationMin = 50, targetRpe = 7.0f
            )
        )

        val planId = repo.savePlan(plan, sessions)

        // Read back
        val savedPlan = repo.getPlanById(planId)
        assertNotNull(savedPlan)
        assertEquals(TrainingPhase.STRENGTH, savedPlan.phase)
        assertEquals(3, savedPlan.sessionsPerWeek)
        assertEquals(listOf("finger_strength", "core"), savedPlan.focusAreas)

        val savedSessions = repo.getSessionsForPlan(planId)
        assertEquals(3, savedSessions.size)
        assertEquals(SessionType.STRENGTH, savedSessions[0].sessionType)
        assertEquals(SessionType.POWER, savedSessions[1].sessionType)
        assertEquals(SessionType.VOLUME, savedSessions[2].sessionType)
    }

    @Test
    fun getActivePlan_returnsLatestPlan() {
        repo.insertPlan(
            TrainingPlan(userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
                phase = TrainingPhase.BASE, sessionsPerWeek = 3)
        )

        val active = repo.getActivePlan(1)
        assertNotNull(active)
        assertEquals(TrainingPhase.BASE, active.phase)
    }

    @Test
    fun getActivePlan_returnsNull_forNoPlans() {
        val active = repo.getActivePlan(99)
        assertNull(active)
    }

    @Test
    fun getSessionForDay_returnsCorrectSession() {
        val planId = repo.insertPlan(
            TrainingPlan(userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
                phase = TrainingPhase.STRENGTH, sessionsPerWeek = 2)
        )
        repo.insertSession(
            PlannedSession(planId = planId, dayOfWeek = 2, sessionType = SessionType.STRENGTH,
                exercises = emptyList(), targetDurationMin = 60, targetRpe = 8.0f)
        )
        repo.insertSession(
            PlannedSession(planId = planId, dayOfWeek = 5, sessionType = SessionType.VOLUME,
                exercises = emptyList(), targetDurationMin = 50, targetRpe = 7.0f)
        )

        val tuesdaySession = repo.getSessionForDay(planId, 2)
        assertNotNull(tuesdaySession)
        assertEquals(SessionType.STRENGTH, tuesdaySession.sessionType)

        val fridaySession = repo.getSessionForDay(planId, 5)
        assertNotNull(fridaySession)
        assertEquals(SessionType.VOLUME, fridaySession.sessionType)

        val wednesdaySession = repo.getSessionForDay(planId, 3)
        assertNull(wednesdaySession, "No session on Wednesday")
    }

    @Test
    fun deleteSessionsForPlan_removesAll() {
        val planId = repo.insertPlan(
            TrainingPlan(userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
                phase = TrainingPhase.BASE, sessionsPerWeek = 3)
        )
        repeat(3) {
            repo.insertSession(
                PlannedSession(planId = planId, dayOfWeek = it + 1, sessionType = SessionType.STRENGTH,
                    exercises = emptyList(), targetDurationMin = 60, targetRpe = 7.0f)
            )
        }

        assertEquals(3, repo.getSessionsForPlan(planId).size)

        repo.deleteSessionsForPlan(planId)
        assertEquals(0, repo.getSessionsForPlan(planId).size)
    }

    @Test
    fun savePlan_sessionsHaveCorrectPlanId() {
        val plan = TrainingPlan(
            userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
            phase = TrainingPhase.POWER, sessionsPerWeek = 2
        )
        val sessions = listOf(
            PlannedSession(dayOfWeek = 1, sessionType = SessionType.POWER,
                exercises = emptyList(), targetDurationMin = 45, targetRpe = 9.0f),
            PlannedSession(dayOfWeek = 4, sessionType = SessionType.STRENGTH,
                exercises = emptyList(), targetDurationMin = 60, targetRpe = 8.0f)
        )

        val planId = repo.savePlan(plan, sessions)
        val savedSessions = repo.getSessionsForPlan(planId)

        for (session in savedSessions) {
            assertEquals(planId, session.planId, "Session should reference correct planId")
        }
    }

    @Test
    fun multiplePlans_sameFacingUser() {
        repo.insertPlan(
            TrainingPlan(userId = 1, startDate = "2026-02-22", endDate = "2026-02-28",
                phase = TrainingPhase.BASE, sessionsPerWeek = 3)
        )
        repo.insertPlan(
            TrainingPlan(userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
                phase = TrainingPhase.STRENGTH, sessionsPerWeek = 3)
        )

        val allPlans = repo.getAllPlans(1)
        assertEquals(2, allPlans.size)
    }
}
