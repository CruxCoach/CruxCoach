package com.cruxcoach.usecase

import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.domain.engine.*
import com.cruxcoach.domain.model.*
import com.cruxcoach.domain.usecase.GeneratePlanUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratePlanUseCaseTest {

    private val testLibrary = listOf(
        ExerciseEntry(id = 1, nameDe = "Schulter-Stretches", nameEn = "Shoulder Stretches", category = "MOBILITY", difficultyLevel = 1),
        ExerciseEntry(id = 2, nameDe = "Hüftöffner", nameEn = "Hip Openers", category = "MOBILITY", difficultyLevel = 1),
        ExerciseEntry(id = 3, nameDe = "Max Hangs 20mm", nameEn = "Max Hangs 20mm", category = "HANGBOARD",
            equipmentNeeded = listOf("HANGBOARD"), difficultyLevel = 2),
        ExerciseEntry(id = 4, nameDe = "Klimmzüge", nameEn = "Pullups", category = "PULL",
            equipmentNeeded = listOf("PULL_UP_BAR"), difficultyLevel = 1),
        ExerciseEntry(id = 5, nameDe = "Liegestütze", nameEn = "Push-Ups", category = "PUSH", difficultyLevel = 1),
        ExerciseEntry(id = 6, nameDe = "Front Plank", nameEn = "Front Plank", category = "CORE", difficultyLevel = 1),
        ExerciseEntry(id = 7, nameDe = "Dynos", nameEn = "Dynos", category = "POWER", difficultyLevel = 3),
        ExerciseEntry(id = 8, nameDe = "4x4s", nameEn = "4x4s", category = "ENDURANCE", difficultyLevel = 2),
        ExerciseEntry(id = 9, nameDe = "Silent Feet", nameEn = "Silent Feet", category = "TECHNIQUE", difficultyLevel = 1),
        ExerciseEntry(id = 10, nameDe = "Push-Up Plus", nameEn = "Push-Up Plus", category = "ANTAGONIST", difficultyLevel = 1)
    )

    private val fakePlanRepo = FakePlanRepository()
    private val fakeWorkoutRepo = FakeWorkoutRepository()

    private val useCase = GeneratePlanUseCase(
        profileClassifier = ProfileClassifier(),
        trainingEngine = TrainingEngine(
            exerciseSelector = ExerciseSelector(testLibrary),
            phaseSelector = PhaseSelector(),
            injuryGuard = InjuryGuard()
        ),
        planRepository = fakePlanRepo,
        workoutRepository = fakeWorkoutRepo
    )

    private val userProfile = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = 70.0, heightCm = 175.0,
        maxBoulderGrade = "V5", sessionsPerWeek = 3,
        availableEquipment = listOf("HANGBOARD", "PULL_UP_BAR"),
        createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    private val assessment = Assessment(
        id = 1, userId = 1, date = "2026-01-01",
        maxHang20mmKg = 84.0, weightedPullupKg = 12.6,
        pushUpMaxReps = 25, coreHoldSec = 60
    )

    @Test
    fun execute_createsPlanInRepository() {
        val result = useCase.execute(userProfile, assessment)

        assertTrue(result.id > 0, "Plan should have a valid ID")
        assertEquals(1, fakePlanRepo.getPlanCount(), "Should have 1 plan in repo")
    }

    @Test
    fun execute_createsSessionsForPlan() {
        val result = useCase.execute(userProfile, assessment)

        val sessions = fakePlanRepo.getSessionsForPlan(result.id)
        assertEquals(3, sessions.size, "Should have 3 sessions (sessionsPerWeek=3)")
    }

    @Test
    fun execute_sessionsHaveExercises() {
        val result = useCase.execute(userProfile, assessment)
        val sessions = fakePlanRepo.getSessionsForPlan(result.id)

        for (session in sessions) {
            assertTrue(session.exercises.isNotEmpty(),
                "Session on day ${session.dayOfWeek} should have exercises")
        }
    }

    @Test
    fun execute_planHasCorrectUserId() {
        val result = useCase.execute(userProfile, assessment)

        assertEquals(userProfile.id, result.userId)
    }

    @Test
    fun execute_planHasValidDates() {
        val result = useCase.execute(userProfile, assessment)

        assertTrue(result.startDate.isNotBlank(), "Start date should not be blank")
        assertTrue(result.endDate.isNotBlank(), "End date should not be blank")
        assertTrue(result.startDate <= result.endDate, "Start date should be <= end date")
    }

    @Test
    fun execute_planHasInitialGeneratedBy() {
        val result = useCase.execute(userProfile, assessment)

        assertEquals(PlanGeneratedBy.INITIAL, result.generatedBy)
    }

    @Test
    fun execute_planPhaseIsNotNull() {
        val result = useCase.execute(userProfile, assessment)

        assertNotNull(result.phase, "Phase should not be null")
    }

    @Test
    fun execute_withBoardAnalysis_usesPowerScore() {
        val boardAnalysis = BoardAnalysisResult(
            boardType = "KILTER", maxGrade = "V6", comfortGrade = "V4",
            totalSends = 50, powerScore = 8.0f, enduranceScore = 6.0f
        )

        val result = useCase.execute(userProfile, assessment, boardAnalysis)

        assertTrue(result.id > 0, "Plan should be created with board analysis")
    }

    @Test
    fun execute_sessionsHaveValidDayOfWeek() {
        val result = useCase.execute(userProfile, assessment)
        val sessions = fakePlanRepo.getSessionsForPlan(result.id)

        for (session in sessions) {
            assertTrue(session.dayOfWeek in 1..7,
                "Day of week should be 1-7, got ${session.dayOfWeek}")
        }
    }

    @Test
    fun execute_sessionsHavePositiveDuration() {
        val result = useCase.execute(userProfile, assessment)
        val sessions = fakePlanRepo.getSessionsForPlan(result.id)

        for (session in sessions) {
            assertTrue(session.targetDurationMin > 0,
                "Duration should be positive, got ${session.targetDurationMin}")
        }
    }
}
