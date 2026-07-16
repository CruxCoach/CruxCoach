package com.cruxcoach.engine

import com.cruxcoach.domain.engine.AdaptiveAdjuster
import com.cruxcoach.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveAdjusterTest {

    private val adjuster = AdaptiveAdjuster()

    private fun makeWeekPlan(
        phase: TrainingPhase = TrainingPhase.STRENGTH,
        sessionCount: Int = 3
    ) = WeekPlan(
        phase = phase,
        sessions = (1..sessionCount).map { i ->
            PlannedSession(
                dayOfWeek = i * 2,
                sessionType = SessionType.STRENGTH,
                exercises = listOf(
                    ExerciseBlock(nameEn = "Pullups", nameDe = "Klimmzüge", category = "PULL", sets = 4, restSeconds = 150),
                    ExerciseBlock(nameEn = "Max Hangs", nameDe = "Max Hangs", category = "HANGBOARD", sets = 4, restSeconds = 180),
                    ExerciseBlock(nameEn = "Core", nameDe = "Core", category = "CORE", sets = 3, restSeconds = 90)
                ),
                targetDurationMin = 60,
                targetRpe = 8.0f
            )
        },
        focusAreas = listOf("finger_strength"),
        weekNumber = 3
    )

    private fun makeLogs(
        count: Int,
        rpe: Double = 7.0,
        painAreas: List<String> = emptyList(),
        skinStatus: String = "GOOD",
        moodPre: Int = 3
    ): List<WorkoutLog> {
        return (1..count).map { i ->
            WorkoutLog(
                id = i.toLong(),
                date = "2026-02-0${i.coerceAtMost(9)}",
                perceivedRpe = rpe,
                painAreas = painAreas,
                fingerSkinStatus = skinStatus,
                moodPre = moodPre
            )
        }
    }

    private fun makeUserProfile() = UserProfile(
        id = 1, name = "Test", age = 30, weightKg = 70.0, heightCm = 175.0,
        maxBoulderGrade = "V6", sessionsPerWeek = 3,
        createdAt = "2026-01-01", updatedAt = "2026-01-01"
    )

    // === RPE TESTS ===

    @Test
    fun highRpe_volumeDecrease() {
        val plan = makeWeekPlan()
        val logs = makeLogs(4, rpe = 9.0)

        val (adjusted, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.VOLUME_DECREASE },
            "Should have VOLUME_DECREASE adaptation")

        // Check that sets were actually reduced
        val originalSets = plan.sessions[0].exercises.sumOf { it.sets }
        val adjustedSets = adjusted.sessions[0].exercises.sumOf { it.sets }
        assertTrue(adjustedSets < originalSets, "Sets should be reduced: $originalSets -> $adjustedSets")
    }

    @Test
    fun lowRpe_intensityIncrease() {
        val plan = makeWeekPlan()
        val logs = makeLogs(4, rpe = 5.0)

        val (adjusted, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.INTENSITY_INCREASE },
            "Should have INTENSITY_INCREASE adaptation")

        // Target RPE should be increased
        assertTrue(adjusted.sessions[0].targetRpe > plan.sessions[0].targetRpe,
            "Target RPE should increase")
    }

    @Test
    fun normalRpe_noRpeAdaptation() {
        val plan = makeWeekPlan()
        val logs = makeLogs(4, rpe = 7.5)

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.none { it.type == AdaptationType.VOLUME_DECREASE },
            "Should not have volume decrease")
        assertTrue(adaptations.none { it.type == AdaptationType.INTENSITY_INCREASE },
            "Should not have intensity increase")
    }

    @Test
    fun rpeAndMoodUseNewestDatedValuesRegardlessOfListOrder() {
        val oldLow = (1..4).map { i ->
            WorkoutLog(id = i.toLong(), date = "2026-01-0$i", perceivedRpe = 4.0, moodPre = 1)
        }
        val newNormal = (5..8).map { i ->
            WorkoutLog(id = i.toLong(), date = "2026-02-0${i - 4}", perceivedRpe = 7.0, moodPre = 4)
        }

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            makeWeekPlan(),
            (newNormal + oldLow).reversed(),
            emptyList(),
            makeUserProfile(),
        )

        assertTrue(adaptations.none { it.type == AdaptationType.INTENSITY_INCREASE })
        assertTrue(adaptations.none { it.type == AdaptationType.MOTIVATION_BOOST })
    }

    // === INJURY TESTS ===

    @Test
    fun fingerPain_injuryAlert() {
        val plan = makeWeekPlan()
        val logs = makeLogs(3, painAreas = listOf("left_ring_finger"))

        val (adjusted, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.INJURY_ALERT },
            "Should have INJURY_ALERT adaptation")

        // Hangboard exercises should be removed
        for (session in adjusted.sessions) {
            assertTrue(session.exercises.none { it.category == "HANGBOARD" },
                "Hangboard exercises should be removed after finger pain")
        }
    }

    // === SKIN TESTS ===

    @Test
    fun skinSplit_skinRecovery() {
        val plan = makeWeekPlan()
        val logs = makeLogs(3, skinStatus = "SPLIT")

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.SKIN_RECOVERY },
            "Should have SKIN_RECOVERY adaptation")
    }

    // === GRADE PROGRESSION ===

    @Test
    fun newMaxGrade_gradeUpgrade() {
        val plan = makeWeekPlan()
        val logs = makeLogs(3)
        val climbs = listOf(
            ClimbLog(id = 1, date = "2026-02-15", grade = "V7", sent = true),
            ClimbLog(id = 2, date = "2026-02-15", grade = "V5", sent = true)
        )
        val userProfile = makeUserProfile() // maxBoulderGrade = V6

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = climbs,
            userProfile = userProfile
        )

        assertTrue(adaptations.any { it.type == AdaptationType.GRADE_UPGRADE },
            "Should have GRADE_UPGRADE adaptation")
    }

    // === DELOAD SUGGESTION ===

    @Test
    fun fourWeeksWithoutDeload_suggestDeload() {
        // Create logs spanning 4+ weeks with high RPE (not deload)
        val logs = (1..20).map { i ->
            val week = (i - 1) / 5
            val day = (i - 1) % 5 + 1
            WorkoutLog(
                id = i.toLong(),
                date = "2026-01-${(week * 7 + day).coerceAtMost(28).toString().padStart(2, '0')}",
                perceivedRpe = 7.5
            )
        }

        val plan = makeWeekPlan(phase = TrainingPhase.STRENGTH)
        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.SUGGEST_DELOAD },
            "Should suggest deload after 4+ weeks")
    }

    @Test
    fun alreadyInDeload_noDeloadSuggestion() {
        val logs = (1..20).map { i ->
            WorkoutLog(id = i.toLong(), date = "2026-01-${(i).coerceAtMost(28).toString().padStart(2, '0')}", perceivedRpe = 7.5)
        }
        val plan = makeWeekPlan(phase = TrainingPhase.DELOAD)

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = plan,
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.none { it.type == AdaptationType.SUGGEST_DELOAD },
            "Should not suggest deload when already in deload phase")
    }

    @Test
    fun nonAdjacentOrUnratedWeeksDoNotCountAsConsecutiveWorkWeeks() {
        val gapped = listOf("2026-01-05", "2026-01-19", "2026-02-02", "2026-02-16")
            .mapIndexed { index, date -> WorkoutLog(index.toLong(), date = date, perceivedRpe = 7.0) }
        assertEquals(1, adjuster.countWeeksWithoutDeload(gapped))

        val unratedNewest = gapped + WorkoutLog(99, date = "2026-02-23", perceivedRpe = null)
        assertEquals(0, adjuster.countWeeksWithoutDeload(unratedNewest))
    }

    // === MOTIVATION ===

    @Test
    fun lowMood_motivationBoost() {
        val logs = makeLogs(3, moodPre = 2)

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = makeWeekPlan(),
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.any { it.type == AdaptationType.MOTIVATION_BOOST },
            "Should have MOTIVATION_BOOST for low mood")
    }

    @Test
    fun normalMood_noMotivationBoost() {
        val logs = makeLogs(3, moodPre = 4)

        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = makeWeekPlan(),
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.none { it.type == AdaptationType.MOTIVATION_BOOST },
            "Should not have MOTIVATION_BOOST for normal mood")
    }

    // === EMPTY/EDGE CASES ===

    @Test
    fun emptyLogs_noAdaptations() {
        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = makeWeekPlan(),
            recentLogs = emptyList(),
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        assertTrue(adaptations.isEmpty(), "No observations must not be interpreted as missed sessions")
    }

    @Test
    fun adaptationsHaveDescriptionsAndEmojis() {
        val logs = makeLogs(4, rpe = 9.0, moodPre = 2)
        val (_, adaptations) = adjuster.analyzeAndAdapt(
            currentPlan = makeWeekPlan(),
            recentLogs = logs,
            recentClimbs = emptyList(),
            userProfile = makeUserProfile()
        )

        for (adaptation in adaptations) {
            assertTrue(adaptation.description.isNotBlank(), "Description should not be blank")
            assertTrue(adaptation.emoji.isNotBlank(), "Emoji should not be blank")
        }
    }
}
