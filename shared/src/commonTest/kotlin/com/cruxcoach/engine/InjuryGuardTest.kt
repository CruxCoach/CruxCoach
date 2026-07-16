package com.cruxcoach.engine

import com.cruxcoach.domain.engine.InjuryGuard
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InjuryGuardTest {

    private val guard = InjuryGuard()

    private fun makeProfile(
        injuryHistory: List<String> = emptyList()
    ) = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = 70.0,
        heightCm = 175.0, maxBoulderGrade = "V6",
        injuryHistory = injuryHistory,
        createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    private fun makeLog(
        painAreas: List<String> = emptyList(),
        skinStatus: String = "GOOD"
    ) = WorkoutLog(
        id = 1, date = "2026-01-15",
        painAreas = painAreas,
        fingerSkinStatus = skinStatus
    )

    // === getActiveRestrictions ===

    @Test
    fun fingerPainInLastLog_stopForFingerExercises() {
        val profile = makeProfile()
        val logs = listOf(makeLog(painAreas = listOf("left_ring_finger")))

        val restrictions = guard.getActiveRestrictions(profile, logs)

        assertTrue(restrictions.any { it.severity == Severity.STOP })
        val stopRestriction = restrictions.first { it.severity == Severity.STOP }
        assertTrue("HANGBOARD" in stopRestriction.restrictedCategories)
        assertTrue("CAMPUS" in stopRestriction.restrictedCategories)
        assertTrue("POWER" in stopRestriction.restrictedCategories)
    }

    @Test
    fun pulleyInHistory_cautionForHangboardAndCampus() {
        val profile = makeProfile(injuryHistory = listOf("A2 pulley injury 2024"))
        val logs = listOf(makeLog())

        val restrictions = guard.getActiveRestrictions(profile, logs)

        assertTrue(restrictions.any { it.severity == Severity.CAUTION })
        val caution = restrictions.first { it.severity == Severity.CAUTION }
        assertTrue("HANGBOARD" in caution.restrictedCategories)
        assertTrue("CAMPUS" in caution.restrictedCategories)
    }

    @Test
    fun shoulderInjury_cautionForCampus() {
        val profile = makeProfile(injuryHistory = listOf("Schulter-Impingement"))
        val logs = listOf(makeLog())

        val restrictions = guard.getActiveRestrictions(profile, logs)

        assertTrue(restrictions.any { it.severity == Severity.CAUTION })
        val caution = restrictions.first { it.severity == Severity.CAUTION }
        assertTrue("CAMPUS" in caution.restrictedCategories)
    }

    @Test
    fun elbowInjury_cautionForHangboard() {
        val profile = makeProfile(injuryHistory = listOf("Ellenbogen-Tendinitis"))
        val logs = listOf(makeLog())

        val restrictions = guard.getActiveRestrictions(profile, logs)

        assertTrue(restrictions.any { it.severity == Severity.CAUTION })
        val caution = restrictions.first { it.severity == Severity.CAUTION }
        assertTrue("HANGBOARD" in caution.restrictedCategories)
    }

    @Test
    fun noInjuries_emptyRestrictions() {
        val profile = makeProfile()
        val logs = listOf(makeLog())

        val restrictions = guard.getActiveRestrictions(profile, logs)

        assertTrue(restrictions.isEmpty())
    }

    @Test
    fun noLogs_onlyChecksProfil() {
        val profile = makeProfile(injuryHistory = listOf("pulley"))
        val restrictions = guard.getActiveRestrictions(profile, emptyList())

        assertEquals(1, restrictions.size)
        assertEquals(Severity.CAUTION, restrictions[0].severity)
    }

    @Test
    fun newestLogIsSelectedRegardlessOfInputOrder() {
        val oldPain = makeLog(painAreas = listOf("finger")).copy(id = 1, date = "2026-01-01")
        val newestHealthy = makeLog().copy(id = 2, date = "2026-01-20")

        val restrictions = guard.getActiveRestrictions(makeProfile(), listOf(newestHealthy, oldPain))

        assertTrue(restrictions.none { it.severity == Severity.STOP })
    }

    // === validateSession ===

    @Test
    fun validateSession_removesBlockedExercises() {
        val session = PlannedSession(
            dayOfWeek = 1,
            sessionType = SessionType.STRENGTH,
            exercises = listOf(
                ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, restSeconds = 180),
                ExerciseBlock(nameEn = "Pullups", nameDe = "Klimmzüge", category = "PULL", sets = 3, restSeconds = 150),
                ExerciseBlock(nameEn = "Campus Ladders", nameDe = "Campus Leitern", category = "CAMPUS", sets = 4, restSeconds = 180)
            ),
            targetDurationMin = 60,
            targetRpe = 8.0f
        )

        val restrictions = listOf(
            TrainingRestriction(
                restrictedCategories = setOf("HANGBOARD", "CAMPUS", "POWER"),
                reason = "Finger pain",
                severity = Severity.STOP
            )
        )

        val validated = guard.validateSession(session, restrictions)

        assertEquals(1, validated.exercises.size)
        assertEquals("PULL", validated.exercises[0].category)
    }

    @Test
    fun validateSession_addsCautionNotes() {
        val session = PlannedSession(
            dayOfWeek = 1,
            sessionType = SessionType.STRENGTH,
            exercises = listOf(
                ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, restSeconds = 180),
                ExerciseBlock(nameEn = "Pullups", nameDe = "Klimmzüge", category = "PULL", sets = 3, restSeconds = 150)
            ),
            targetDurationMin = 60,
            targetRpe = 8.0f
        )

        val restrictions = listOf(
            TrainingRestriction(
                restrictedCategories = setOf("HANGBOARD"),
                reason = "Ellenbogen-Vorbelastung",
                severity = Severity.CAUTION
            )
        )

        val validated = guard.validateSession(session, restrictions)

        // Both exercises should remain (CAUTION doesn't remove)
        assertEquals(2, validated.exercises.size)
        // Hangboard exercise should have warning note
        assertTrue(validated.exercises[0].notes.contains("⚠️"))
        // Pull exercise should not have warning
        assertTrue(!validated.exercises[1].notes.contains("⚠️"))
    }

    @Test
    fun validateSession_noRestrictions_noChanges() {
        val session = PlannedSession(
            dayOfWeek = 1,
            sessionType = SessionType.STRENGTH,
            exercises = listOf(
                ExerciseBlock(nameEn = "Pullups", nameDe = "Klimmzüge", category = "PULL", sets = 3, restSeconds = 150)
            ),
            targetDurationMin = 60,
            targetRpe = 8.0f
        )

        val validated = guard.validateSession(session, emptyList())
        assertEquals(session, validated)
    }

    @Test
    fun validateSessionMapsEveryCautionCategoryAndPreservesEveryReason() {
        val session = PlannedSession(
            dayOfWeek = 1,
            sessionType = SessionType.STRENGTH,
            exercises = listOf(
                ExerciseBlock(nameEn = "Campus", nameDe = "Campus", category = "CAMPUS", sets = 2),
                ExerciseBlock(nameEn = "Hangs", nameDe = "Hangs", category = "HANGBOARD", sets = 2),
            ),
            targetDurationMin = 45,
            targetRpe = 7.0f,
        )
        val restrictions = listOf(
            TrainingRestriction(setOf("HANGBOARD", "CAMPUS"), "Finger history", Severity.CAUTION),
            TrainingRestriction(setOf("CAMPUS"), "Shoulder history", Severity.CAUTION),
        )

        val validated = guard.validateSession(session, restrictions)

        assertTrue(validated.exercises[0].notes.contains("Finger history"))
        assertTrue(validated.exercises[0].notes.contains("Shoulder history"))
        assertTrue(validated.exercises[1].notes.contains("Finger history"))
    }
}
