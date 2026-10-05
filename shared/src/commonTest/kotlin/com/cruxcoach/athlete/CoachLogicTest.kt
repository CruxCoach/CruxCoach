package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoachLogicTest {

    private val home = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR,
        EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES, EquipmentV2.DUMBBELL)
    private val logbook = LogbookSummary(primaryBrand = "kilter", sampleSize = 40, workingDifficulty = 21.0,
        workingGrade = "6c+", sessionsPerWeek = 2.5, usualClimbingDays = setOf(2, 4, 6))

    private fun bench(slug: String, source: BenchmarkSource = BenchmarkSource.MANUAL) =
        Benchmark(id = slug + source, exerciseSlug = slug, reps = 5, source = source, measuredAt = 1)

    @Test
    fun emptyProfileIsBasisWithGoalFirst() {
        val c = CoachLogic.completeness(AthleteProfile(), emptyList(), LogbookSummary())
        assertEquals(CoachLevel.BASIS, c.level)
        assertEquals(0, c.score)
        assertEquals(CoachStep.GOAL, c.next)
    }

    @Test
    fun answersAndStartValuesReachPrecise() {
        val profile = AthleteProfile(
            equipmentConfigured = true,
            coach = CoachProfile(goal = CoachGoal.CLIMB_HARDER, climbingDays = setOf(2, 4), experience = ExperienceBand.Y3_5,
                focus = setOf(FocusArea.FINGER_STRENGTH)),
        )
        val partial = CoachLogic.completeness(profile, emptyList(), logbook)
        assertEquals(CoachLevel.PERSONAL, partial.level)
        assertEquals(CoachStep.START_VALUE_PULL, partial.next)
        val full = CoachLogic.completeness(profile, listOf(bench("pull.weighted_pull_up"), bench("finger.max_hang")), logbook)
        assertEquals(CoachLevel.PRECISE, full.level)
        assertEquals(100, full.score)
        assertTrue(full.missing.isEmpty())
    }

    @Test
    fun youngClimbersAreNotAskedForFingerValues() {
        val profile = AthleteProfile(coach = CoachProfile(ageBand = AgeBand.UNDER_16))
        val c = CoachLogic.completeness(profile, emptyList(), LogbookSummary())
        assertFalse(CoachStep.START_VALUE_FINGER in c.missing)
        assertFalse(CoachLogic.fingerMaxAllowed(profile.coach))
    }

    @Test
    fun weekPlanUsesLogbookRhythmAndSpacesStrengthAwayFromClimbing() {
        val coach = CoachProfile(trainingDaysPerWeek = 5, experience = ExperienceBand.OVER_5)
        val plan = WeekPlanSuggester.suggest(coach, logbook, emptyList(), home, emptyList())
        assertEquals(7, plan.size)
        assertEquals(setOf(2, 4, 6), plan.filterValues { it == PLAN_BOARD }.keys)
        val offWall = plan.filterValues { it.startsWith("builtin:") && it != "builtin:" + BuiltinRoutines.MOBILITY_10 }.keys
        assertEquals(2, offWall.size, "$plan")
        // Max finger work never lands the day after a board day.
        plan.filterValues { it == "builtin:" + BuiltinRoutines.FINGER_BASICS }.keys.forEach { day ->
            val previous = if (day == 1) 7 else day - 1
            assertTrue(plan[previous] != PLAN_BOARD, "$plan")
        }
    }

    @Test
    fun weekPlanWithoutBoardWhileClimbingIsPaused() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 4, climbingPaused = true, startedOn = "2026-10-01")
        val plan = WeekPlanSuggester.suggest(CoachProfile(trainingDaysPerWeek = 3), logbook, emptyList(), home, listOf(injury))
        assertTrue(PLAN_BOARD !in plan.values, "$plan")
        assertTrue("builtin:" + BuiltinRoutines.INJURY_ONE_ARM in plan.values, "$plan")
        assertTrue("builtin:" + BuiltinRoutines.FINGER_BASICS !in plan.values)
    }

    @Test
    fun ownRoutineLeadsTheOffWallDays() {
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("pull.pull_up")), updatedAt = 5)
        val plan = WeekPlanSuggester.suggest(CoachProfile(climbingDays = setOf(2, 5), trainingDaysPerWeek = 3), logbook,
            listOf(mine), home, emptyList())
        assertTrue("r1" in plan.values, "$plan")
    }

    @Test
    fun learningStateFollowsSourceAndSessions() {
        val estimate = listOf(bench("pull.pull_up", BenchmarkSource.ESTIMATE))
        assertEquals(LearningState.State.None, LearningState.of("pull.pull_up", emptyList(), 0))
        assertEquals(LearningState.State.Estimated, LearningState.of("pull.pull_up", estimate, 0))
        assertEquals(LearningState.State.Learning(1, 2), LearningState.of("pull.pull_up", estimate, 1))
        assertEquals(LearningState.State.Known, LearningState.of("pull.pull_up", estimate, 2))
        assertEquals(LearningState.State.Known, LearningState.of("pull.pull_up", listOf(bench("pull.pull_up")), 0))
    }
}
