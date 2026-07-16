package com.cruxcoach.usecase

import com.cruxcoach.domain.engine.AdaptiveAdjuster
import com.cruxcoach.domain.model.*
import com.cruxcoach.domain.usecase.AdaptPlanUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptPlanUseCaseTest {

    private val fakePlanRepo = FakePlanRepository()
    private val fakeWorkoutRepo = FakeWorkoutRepository()
    private val fakeClimbRepo = FakeClimbRepository()

    private val useCase = AdaptPlanUseCase(
        adaptiveAdjuster = AdaptiveAdjuster(),
        planRepository = fakePlanRepo,
        workoutRepository = fakeWorkoutRepo,
        climbRepository = fakeClimbRepo,
        today = { "2026-03-04" },
    )

    private val userProfile = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = 70.0, heightCm = 175.0,
        maxBoulderGrade = "V5", sessionsPerWeek = 3,
        createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    private fun seedPlan(): Long {
        val plan = TrainingPlan(
            userId = 1, startDate = "2026-03-01", endDate = "2026-03-07",
            phase = TrainingPhase.STRENGTH, focusAreas = listOf("finger_strength"),
            sessionsPerWeek = 3
        )
        val planId = fakePlanRepo.insertPlan(plan)

        listOf(
            PlannedSession(
                planId = planId, dayOfWeek = 1, sessionType = SessionType.STRENGTH,
                exercises = listOf(
                    ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, duration = "7 sec", restSeconds = 180),
                    ExerciseBlock(nameEn = "Pullups", nameDe = "Klimmzüge", category = "PULL", sets = 4, reps = "5", restSeconds = 150)
                ),
                targetDurationMin = 60, targetRpe = 8.0f
            ),
            PlannedSession(
                planId = planId, dayOfWeek = 3, sessionType = SessionType.POWER,
                exercises = listOf(
                    ExerciseBlock(nameEn = "Dynos", nameDe = "Dynos", category = "POWER", sets = 4, reps = "3", restSeconds = 180)
                ),
                targetDurationMin = 45, targetRpe = 8.5f
            ),
            PlannedSession(
                planId = planId, dayOfWeek = 5, sessionType = SessionType.VOLUME,
                exercises = listOf(
                    ExerciseBlock(nameEn = "4x4s", nameDe = "4x4s", category = "ENDURANCE", sets = 3, reps = "4", restSeconds = 240, duration = "20-30 min")
                ),
                targetDurationMin = 50, targetRpe = 7.0f
            )
        ).forEach { fakePlanRepo.insertSession(it) }

        return planId
    }

    @Test
    fun execute_returnsNull_whenNoPlanExists() {
        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNull(result, "Should return null when no active plan")
    }

    @Test
    fun execute_returnsResult_whenPlanExists() {
        seedPlan()
        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result, "Should return result when plan exists")
    }

    @Test
    fun execute_noRpeAdaptations_whenLogsAreNormal() {
        seedPlan()
        // Add normal RPE logs
        fakeWorkoutRepo.addLog(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 7.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 2, date = "2026-03-03", perceivedRpe = 7.5))

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)
        // No RPE, injury, or skin adaptations should fire with normal logs
        val rpeOrInjuryTypes = setOf(
            AdaptationType.VOLUME_DECREASE, AdaptationType.INTENSITY_INCREASE,
            AdaptationType.INJURY_ALERT, AdaptationType.SKIN_RECOVERY
        )
        val rpeAdaptations = result.adaptations.filter { it.type in rpeOrInjuryTypes }
        assertTrue(rpeAdaptations.isEmpty(),
            "Should have no RPE/injury adaptations for normal logs, got: ${rpeAdaptations.map { it.type }}")
    }

    @Test
    fun execute_volumeDecrease_whenRpeHigh() {
        seedPlan()
        // Add very high RPE logs
        fakeWorkoutRepo.addLog(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 2, date = "2026-03-02", perceivedRpe = 9.5))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 3, date = "2026-03-03", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 4, date = "2026-03-04", perceivedRpe = 9.5))

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)
        assertTrue(result.adaptations.isNotEmpty(), "Should have adaptations for high RPE")
        assertTrue(result.adaptations.any { it.type == AdaptationType.VOLUME_DECREASE },
            "Should include volume decrease adaptation")
    }

    @Test
    fun execute_injuryAlert_whenFingerPain() {
        seedPlan()
        fakeWorkoutRepo.addLog(
            WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 7.0,
                painAreas = listOf("finger A2 pulley"))
        )

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)
        assertTrue(result.adaptations.any { it.type == AdaptationType.INJURY_ALERT },
            "Should include injury alert for finger pain")
    }

    @Test
    fun execute_skinRecovery_whenSkinSplit() {
        seedPlan()
        fakeWorkoutRepo.addLog(
            WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 7.0,
                fingerSkinStatus = "SPLIT")
        )

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)
        assertTrue(result.adaptations.any { it.type == AdaptationType.SKIN_RECOVERY },
            "Should include skin recovery adaptation")
    }

    @Test
    fun execute_sessionsUpdated_whenAdaptationsApplied() {
        seedPlan()
        // High RPE triggers volume decrease → sessions should be updated in repo
        fakeWorkoutRepo.addLog(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 2, date = "2026-03-02", perceivedRpe = 9.5))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 3, date = "2026-03-03", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 4, date = "2026-03-04", perceivedRpe = 9.5))

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)

        if (result.adaptations.isNotEmpty()) {
            // Sessions in repo should reflect the adaptation
            val updatedSessions = fakePlanRepo.getSessionsForPlan(result.updatedPlan.id)
            assertTrue(updatedSessions.isNotEmpty(), "Should still have sessions after adaptation")
        }
    }

    @Test
    fun execute_planVersionBumped_whenAdapted() {
        seedPlan()
        fakeWorkoutRepo.addLog(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 2, date = "2026-03-02", perceivedRpe = 9.5))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 3, date = "2026-03-03", perceivedRpe = 9.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 4, date = "2026-03-04", perceivedRpe = 9.5))

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)

        if (result.adaptations.isNotEmpty()) {
            assertEquals(2, result.updatedPlan.planVersion, "Version should be bumped to 2")
            assertEquals(PlanGeneratedBy.ADAPTIVE, result.updatedPlan.generatedBy)
        }
    }

    @Test
    fun execute_intensityIncrease_whenRpeLow() {
        seedPlan()
        fakeWorkoutRepo.addLog(WorkoutLog(id = 1, date = "2026-03-01", perceivedRpe = 4.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 2, date = "2026-03-02", perceivedRpe = 5.0))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 3, date = "2026-03-03", perceivedRpe = 4.5))
        fakeWorkoutRepo.addLog(WorkoutLog(id = 4, date = "2026-03-04", perceivedRpe = 5.0))

        val result = useCase.execute(userId = 1, userProfile = userProfile)
        assertNotNull(result)
        assertTrue(result.adaptations.any { it.type == AdaptationType.INTENSITY_INCREASE },
            "Should increase intensity when RPE is low")
    }

    @Test
    fun executeUsesFourCalendarWeeksInsteadOfTenRowsForDeloadEvidence() {
        seedPlan()
        val dates = listOf(
            "2026-02-09", "2026-02-10", "2026-02-11", "2026-02-12", "2026-02-13",
            "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20",
            "2026-02-23", "2026-02-24", "2026-02-25", "2026-02-26", "2026-02-27",
            "2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05", "2026-03-06",
        )
        dates.forEachIndexed { index, date ->
            fakeWorkoutRepo.addLog(WorkoutLog(id = index.toLong(), date = date, perceivedRpe = 7.0))
        }

        val result = useCase.execute(userId = 1, userProfile = userProfile)

        assertNotNull(result)
        assertTrue(result.adaptations.any { it.type == AdaptationType.SUGGEST_DELOAD })
    }
}
