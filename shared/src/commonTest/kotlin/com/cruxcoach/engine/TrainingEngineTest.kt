package com.cruxcoach.engine

import com.cruxcoach.data.repository.ExerciseEntry
import com.cruxcoach.domain.engine.*
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingEngineTest {

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
        ExerciseEntry(id = 10, nameDe = "Push-Up Plus", nameEn = "Push-Up Plus", category = "ANTAGONIST", difficultyLevel = 1),
        ExerciseEntry(id = 11, nameDe = "Pyramiden", nameEn = "Pyramids", category = "ENDURANCE", difficultyLevel = 1),
        ExerciseEntry(id = 12, nameDe = "Hover Hands", nameEn = "Hover Hands", category = "TECHNIQUE", difficultyLevel = 1)
    )

    private val engine = TrainingEngine(
        exerciseSelector = ExerciseSelector(testLibrary),
        phaseSelector = PhaseSelector(),
        injuryGuard = InjuryGuard()
    )

    private val profile = ClimberProfile(
        fingerStrength = 6.0f, upperBodyPull = 5.0f, upperBodyPush = 5.0f,
        coreStrength = 4.0f, power = 5.0f, powerEndurance = 5.0f,
        flexibility = 3.0f, technique = 7.0f,
        overallLevel = ClimbingLevel.INTERMEDIATE
    )

    private fun makeUserProfile(sessionsPerWeek: Int = 3) = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = 70.0, heightCm = 175.0,
        maxBoulderGrade = "V6", sessionsPerWeek = sessionsPerWeek,
        availableEquipment = listOf("HANGBOARD", "PULL_UP_BAR", "WEIGHTS"),
        createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    private val assessment = Assessment(
        id = 1, userId = 1, date = "2026-01-01",
        maxHang20mmKg = 91.0, weightedPullupKg = 17.5,
        pushUpMaxReps = 25, coreHoldSec = 60
    )

    private fun makeLogs(count: Int, rpe: Double = 7.0): List<WorkoutLog> {
        return (1..count).map { i ->
            WorkoutLog(id = i.toLong(), date = "2026-01-0${i.coerceAtMost(9)}", perceivedRpe = rpe)
        }
    }

    @Test
    fun threeSessionsPerWeek_generatesThreeSessions() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 3),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        assertEquals(3, plan.sessions.size)
    }

    @Test
    fun fourSessionsPerWeek_generatesFourSessions() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 4),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        assertEquals(4, plan.sessions.size)
    }

    @Test
    fun twoSessionsPerWeek_generatesTwoSessions() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 2),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        assertEquals(2, plan.sessions.size)
    }

    @Test
    fun legacySessionCountsAreClampedToSupportedTemplates() {
        assertEquals(2, engine.createWeekTemplate(1, TrainingPhase.POWER).size)
        assertEquals(4, engine.createWeekTemplate(7, TrainingPhase.POWER).size)
    }

    @Test
    fun deloadPhase_lowTargetRpe() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 3),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 4 // Deload week
        )

        assertEquals(TrainingPhase.DELOAD, plan.phase)
        for (session in plan.sessions) {
            assertTrue(session.targetRpe <= 6.0f,
                "Deload session RPE should be <= 6.0, got ${session.targetRpe}")
        }
    }

    @Test
    fun generatedPlan_hasCorrectPhaseAndFocusAreas() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        assertTrue(plan.phase != TrainingPhase.DELOAD, "Week 0 should not be deload")
        assertTrue(plan.focusAreas.isNotEmpty(), "Should have focus areas")
        assertEquals(1, plan.weekNumber) // weeksSinceStart + 1
    }

    @Test
    fun eachSession_hasDayOfWeek() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 3),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        for (session in plan.sessions) {
            assertTrue(session.dayOfWeek in 1..7,
                "Day of week should be 1-7, got ${session.dayOfWeek}")
        }
    }

    @Test
    fun eachSession_hasPositiveDuration() {
        val plan = engine.generateWeekPlan(
            profile = profile,
            userProfile = makeUserProfile(sessionsPerWeek = 3),
            currentAssessment = assessment,
            recentLogs = makeLogs(3),
            weeksSinceStart = 0
        )

        for (session in plan.sessions) {
            assertTrue(session.targetDurationMin > 0,
                "Duration should be positive, got ${session.targetDurationMin}")
        }
    }

    @Test
    fun findTopWeaknesses_returnsCorrectOrder() {
        val weaknesses = engine.findTopWeaknesses(profile, 2)
        // Profile has flexibility=3.0 and core=4.0 as lowest
        assertEquals("flexibility", weaknesses[0])
        assertEquals("core", weaknesses[1])
    }
}
