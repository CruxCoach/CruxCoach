package com.cruxcoach.engine

import com.cruxcoach.domain.engine.PhaseSelector
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PhaseSelectorTest {

    private val selector = PhaseSelector()

    private fun makeProfile(
        level: ClimbingLevel = ClimbingLevel.INTERMEDIATE,
        fingerStrength: Float = 5.0f,
        power: Float = 5.0f,
        pull: Float = 5.0f
    ) = ClimberProfile(
        fingerStrength = fingerStrength,
        upperBodyPull = pull,
        upperBodyPush = 5.0f,
        coreStrength = 5.0f,
        power = power,
        powerEndurance = 5.0f,
        flexibility = 5.0f,
        technique = 5.0f,
        overallLevel = level
    )

    private fun makeLogs(
        count: Int,
        rpe: Double = 7.0,
        painAreas: List<String> = emptyList()
    ): List<WorkoutLog> {
        return (1..count).map { i ->
            WorkoutLog(
                id = i.toLong(), date = "2026-01-0${i.coerceAtMost(9)}",
                perceivedRpe = rpe, painAreas = painAreas
            )
        }
    }

    // === DELOAD WEEK TESTS ===

    @Test
    fun week5_isDeload() {
        // 0-indexed: week 4 in a 5-week cycle → DELOAD
        val result = selector.selectPhase(
            weeksSinceStart = 4, // weekInCycle = 4 % 5 = 4
            recentLogs = makeLogs(3),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.DELOAD, result)
    }

    @Test
    fun week10_isDeload() {
        val result = selector.selectPhase(
            weeksSinceStart = 9, // weekInCycle = 9 % 5 = 4
            recentLogs = makeLogs(3),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.DELOAD, result)
    }

    @Test
    fun week15_isDeload() {
        val result = selector.selectPhase(
            weeksSinceStart = 14, // 14 % 5 = 4
            recentLogs = makeLogs(3),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.DELOAD, result)
    }

    // === WEAKNESS-BASED PHASE TESTS ===

    @Test
    fun weakFingerStrength_selectsStrength() {
        val profile = makeProfile(fingerStrength = 2.0f)
        val result = selector.selectPhase(
            weeksSinceStart = 2, // weekInCycle = 2 % 5 = 2
            recentLogs = makeLogs(3),
            profile = profile
        )
        // Finger weakness + week 2 of block → POWER (in the strength-focus rotation)
        assertEquals(TrainingPhase.POWER, result)
    }

    @Test
    fun weakFingerStrength_week0_selectsStrength() {
        val profile = makeProfile(fingerStrength = 2.0f)
        val result = selector.selectPhase(
            weeksSinceStart = 0, // weekInCycle = 0
            recentLogs = makeLogs(3),
            profile = profile
        )
        assertEquals(TrainingPhase.STRENGTH, result)
    }

    @Test
    fun weakPower_selectsPower() {
        val profile = makeProfile(power = 2.0f)
        val result = selector.selectPhase(
            weeksSinceStart = 0,
            recentLogs = makeLogs(3),
            profile = profile
        )
        assertEquals(TrainingPhase.POWER, result)
    }

    // === BEGINNER OVERRIDE ===

    @Test
    fun beginnerClimber_alwaysBase() {
        val profile = makeProfile(level = ClimbingLevel.BEGINNER)
        val result = selector.selectPhase(
            weeksSinceStart = 2,
            recentLogs = makeLogs(3),
            profile = profile
        )
        assertEquals(TrainingPhase.BASE, result)
    }

    // === RPE OVERRIDE ===

    @Test
    fun highRpe_forcesDeload() {
        val result = selector.selectPhase(
            weeksSinceStart = 2,
            recentLogs = makeLogs(4, rpe = 9.0),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.DELOAD, result)
    }

    @Test
    fun normalRpe_noDeloadOverride() {
        val result = selector.selectPhase(
            weeksSinceStart = 0,
            recentLogs = makeLogs(4, rpe = 7.5),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.STRENGTH, result)
    }

    // === FINGER PAIN OVERRIDE ===

    @Test
    fun fingerPain_forcesDeload() {
        val result = selector.selectPhase(
            weeksSinceStart = 1,
            recentLogs = makeLogs(3, painAreas = listOf("left_ring_finger")),
            profile = makeProfile()
        )
        assertEquals(TrainingPhase.DELOAD, result)
    }

    @Test
    fun noFingerPain_noOverride() {
        val hasFingerPain = selector.hasFingerPain(makeLogs(3))
        assertEquals(false, hasFingerPain)
    }

    @Test
    fun newestPainAndRpeAreSelectedRegardlessOfInputOrder() {
        val logs = listOf(
            WorkoutLog(id = 3, date = "2026-01-03", perceivedRpe = 9.5, painAreas = listOf("finger")),
            WorkoutLog(id = 1, date = "2026-01-01", perceivedRpe = 4.0),
            WorkoutLog(id = 2, date = "2026-01-02", perceivedRpe = 4.0),
            WorkoutLog(id = 0, date = "2025-12-31", perceivedRpe = 4.0),
            WorkoutLog(id = 4, date = "2026-01-04", perceivedRpe = 9.5, painAreas = listOf("finger")),
        )

        assertEquals(true, selector.hasFingerPain(logs.shuffledForTest()))
        assertEquals(false, selector.isOverreaching(logs.shuffledForTest()))
    }

    // === HELPER TESTS ===

    @Test
    fun isOverreaching_highRpe_true() {
        val logs = makeLogs(4, rpe = 9.0)
        assertEquals(true, selector.isOverreaching(logs))
    }

    @Test
    fun isOverreaching_lowRpe_false() {
        val logs = makeLogs(4, rpe = 7.0)
        assertEquals(false, selector.isOverreaching(logs))
    }

    @Test
    fun isOverreaching_tooFewLogs_false() {
        val logs = makeLogs(1, rpe = 9.5)
        assertEquals(false, selector.isOverreaching(logs))
    }

    private fun <T> List<T>.shuffledForTest(): List<T> = listOf(last(), first()) + drop(1).dropLast(1)
}
