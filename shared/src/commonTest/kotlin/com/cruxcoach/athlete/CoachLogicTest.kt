package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
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

    private val fingerDay = "builtin:" + BuiltinRoutines.FINGER_BASICS
    private val pullDay = "builtin:" + BuiltinRoutines.PULL_ANTAGONIST
    private val legsDay = "builtin:" + BuiltinRoutines.LEGS_BASICS
    private val coreDay = "builtin:" + BuiltinRoutines.CORE_CLIMBER
    private val mobilityDay = "builtin:" + BuiltinRoutines.MOBILITY_10
    private val limitDay = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
    private val monday = LocalDate.parse("2026-10-19")
    private fun date(n: Int) = monday.plus(DatePeriod(days = n))
    private val threeDays = AthleteProfile(equipment = home, coach = CoachProfile(trainingDaysPerWeek = 3))
    private fun proposals(profile: AthleteProfile, days: List<DayPlans.Day>, pauses: List<PausePeriod> = emptyList()) =
        WeekPlanSuggester.forDays(profile, LogbookSummary(), emptyList(), emptyList(), days, pauses) { it == fingerDay }

    @Test
    fun aWeekWithoutClimbingDaysSpreadsItsTrainings() {
        // Three trainings a week and nothing else known: apart from each other, one mobility day, the rest is rest.
        val plan = WeekPlanSuggester.suggest(threeDays.coach, LogbookSummary(), emptyList(), home, emptyList())
        assertEquals(mapOf(1 to fingerDay, 2 to PLAN_REST, 3 to PLAN_REST, 4 to pullDay, 5 to PLAN_REST, 6 to legsDay, 7 to mobilityDay), plan)
    }

    @Test
    fun theWeekIsPlannedAroundWhatTheAthletePlannedHimself() {
        fun around(fixed: WeekPlanSuggester.Fixed) =
            WeekPlanSuggester.around(threeDays.coach, LogbookSummary(), emptyList(), home, emptyList(), fixed) { it == fingerDay }
        // His own finger day on Tuesday is one of the week's three trainings, and the coach adds no second finger day.
        assertEquals(mapOf(1 to mobilityDay, 3 to PLAN_REST, 4 to PLAN_REST, 5 to pullDay, 6 to PLAN_REST, 7 to legsDay),
            around(WeekPlanSuggester.Fixed(entries = mapOf(2 to fingerDay))))
        // A climbing day he planned counts as one too; the trainings keep their distance from it.
        assertEquals(mapOf(1 to fingerDay, 2 to PLAN_REST, 4 to mobilityDay, 5 to PLAN_REST, 6 to pullDay, 7 to PLAN_REST),
            around(WeekPlanSuggester.Fixed(entries = mapOf(3 to limitDay))))
        // A rest day of his own stays his: the three trainings go to the other days.
        assertEquals(mapOf(2 to fingerDay, 3 to mobilityDay, 4 to PLAN_REST, 5 to pullDay, 6 to PLAN_REST, 7 to legsDay),
            around(WeekPlanSuggester.Fixed(entries = mapOf(1 to PLAN_REST))))
        // Nothing is proposed within a pause.
        assertEquals(mapOf(4 to fingerDay, 5 to pullDay, 6 to PLAN_REST, 7 to legsDay),
            around(WeekPlanSuggester.Fixed(paused = setOf(1, 2, 3))))
    }

    @Test
    fun withoutOwnEntriesEveryDayShowsTheSuggestedWeek() {
        // Owner 2026-11-22: fourteen rows of "the coach decides" said nothing. From a Thursday on, two weeks.
        val week = WeekPlanSuggester.suggest(threeDays.coach, LogbookSummary(), emptyList(), home, emptyList())
        val days = DayPlans.preview(threeDays, date(3), 14, date(3))
        assertEquals(days.associate { it.date to week.getValue(it.date.dayOfWeek.isoDayNumber) }, proposals(threeDays, days))
    }

    @Test
    fun theProposalFollowsTheAthletesHardDays() {
        // A limit day on Wednesday, five days a week: pulling the day before becomes legs, as the planner advises.
        val five = threeDays.copy(coach = CoachProfile(trainingDaysPerWeek = 5))
        val limit = DayPlans.withDay(five, date(2), limitDay, monday)
        assertEquals(
            mapOf(date(0) to fingerDay, date(1) to legsDay, date(3) to legsDay, date(4) to PLAN_REST, date(5) to coreDay, date(6) to PLAN_REST),
            proposals(limit, DayPlans.preview(limit, monday, 7, monday)),
        )
        // A limit day logged yesterday: no finger work today, the rest of the week as proposed.
        val after = proposals(threeDays, DayPlans.preview(threeDays, monday, 7, monday, logged = mapOf(date(-1) to ClimbIntensity.LIMIT)))
        assertEquals(pullDay, after[date(0)])
        assertEquals(listOf(pullDay, legsDay, mobilityDay), listOf(after[date(3)], after[date(5)], after[date(6)]))
    }

    @Test
    fun aFreedClimbingDayAndAPauseGetNoClimbingProposed() {
        val usual = threeDays.copy(coach = CoachProfile(climbingDays = setOf(2, 4), trainingDaysPerWeek = 3))
        val climb = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.HARD).entry
        // "Free" over Tuesday's usual climbing: Thursday stays the climbing day, a second training takes Tuesday's place.
        val freed = DayPlans.withDay(usual, date(1), PLAN_FREE, monday)
        assertEquals(
            mapOf(date(0) to fingerDay, date(1) to PLAN_REST, date(2) to PLAN_REST, date(3) to climb, date(4) to mobilityDay,
                date(5) to pullDay, date(6) to PLAN_REST),
            proposals(freed, DayPlans.preview(freed, monday, 7, monday)),
        )
        // A pause until Wednesday: nothing for its days, the week's trainings after it.
        val pause = listOf(PausePeriod("p", PauseReason.HOLIDAY, date(0).toString(), date(2).toString()))
        assertEquals(mapOf(date(3) to fingerDay, date(4) to pullDay, date(5) to PLAN_REST, date(6) to legsDay),
            proposals(threeDays, DayPlans.preview(threeDays, monday, 7, monday, pause), pause))
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

    @Test
    fun coachGradesStandInWithoutLogbookAndFlashIsCapped() {
        val s = LogbookSummaries.withCoachGrades(LogbookSummary(), currentGrade = "6c+", flashGrade = "7a")
        assertTrue(s.gradesFromCoach)
        assertEquals("6c+", s.workingGrade)
        // A flash above the working grade is capped to it.
        assertEquals(s.workingDifficulty, s.flashDifficulty)
        // A graded logbook always wins.
        val logbook = LogbookSummary(workingDifficulty = 22.0, workingGrade = "7a", sampleSize = 20)
        assertEquals(logbook, LogbookSummaries.withCoachGrades(logbook, "5c", null))
    }
}
