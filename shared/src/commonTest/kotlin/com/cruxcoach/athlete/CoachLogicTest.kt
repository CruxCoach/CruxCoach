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
        // His own pulling day is not proposed a second time, not even where the finger day is used up (five days a week).
        val five = WeekPlanSuggester.around(CoachProfile(trainingDaysPerWeek = 5), LogbookSummary(), emptyList(), home, emptyList(),
            WeekPlanSuggester.Fixed(entries = mapOf(2 to pullDay))) { it == fingerDay }
        assertEquals(mapOf(1 to fingerDay, 3 to legsDay, 4 to PLAN_REST, 5 to coreDay, 6 to PLAN_REST, 7 to legsDay), five)
        // A mobility day of his own costs no training: three trainings, and no second mobility day.
        assertEquals(mapOf(1 to fingerDay, 3 to PLAN_REST, 4 to pullDay, 5 to PLAN_REST, 6 to legsDay, 7 to PLAN_REST),
            around(WeekPlanSuggester.Fixed(entries = mapOf(2 to mobilityDay))))
        // Nothing is proposed within a pause, and the days after it stay what they would be: no three trainings in four days.
        assertEquals(mapOf(4 to pullDay, 5 to PLAN_REST, 6 to legsDay, 7 to mobilityDay),
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
        // A limit day on Wednesday, five days a week: the day before it is for legs, as the planner advises – legs and
        // pulling change places, the week keeps all its trainings.
        val five = threeDays.copy(coach = CoachProfile(trainingDaysPerWeek = 5))
        val limit = DayPlans.withDay(five, date(2), limitDay, monday)
        assertEquals(
            mapOf(date(0) to fingerDay, date(1) to legsDay, date(3) to pullDay, date(4) to PLAN_REST, date(5) to coreDay, date(6) to PLAN_REST),
            proposals(limit, DayPlans.preview(limit, monday, 7, monday)),
        )
        // A limit day logged yesterday: no finger work today – it changes places with Thursday's pulling.
        assertEquals(
            mapOf(date(0) to pullDay, date(1) to PLAN_REST, date(2) to PLAN_REST, date(3) to fingerDay, date(4) to PLAN_REST,
                date(5) to legsDay, date(6) to mobilityDay),
            proposals(threeDays, DayPlans.preview(threeDays, monday, 7, monday, logged = mapOf(date(-1) to ClimbIntensity.LIMIT))),
        )
        // The week's own rules end on Sunday: its finger day there gives way to a climbing day the athlete planned
        // for the Monday after, also an easy one the hints say nothing about.
        val weekend = threeDays.copy(coach = CoachProfile(climbingDays = setOf(2, 5), trainingDaysPerWeek = 4))
        assertEquals(fingerDay, proposals(weekend, DayPlans.preview(weekend, monday, 8, monday))[date(6)])
        val volumeMonday = DayPlans.withDay(weekend, date(7), DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry, monday)
        assertEquals(pullDay, proposals(volumeMonday, DayPlans.preview(volumeMonday, monday, 8, monday))[date(6)])
        // Only without a training of the week to change places with, the one that does not fit gives way: the
        // same two days looked at alone.
        assertEquals(mapOf(date(1) to legsDay), proposals(limit, DayPlans.preview(limit, date(1), 1, monday)))
        assertEquals(mapOf(date(0) to pullDay),
            proposals(threeDays, DayPlans.preview(threeDays, monday, 1, monday, logged = mapOf(date(-1) to ClimbIntensity.LIMIT))))
    }

    @Test
    fun theWeekFollowsTheAthletesLevel() {
        val climb = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.HARD).entry
        fun week(coach: CoachProfile, tier: DayPlans.Tier) = WeekPlanSuggester.suggest(coach, LogbookSummary(), emptyList(), home, emptyList(), tier)
        // Three climbing days and two trainings, each next to a climbing day: no finger day at the standard level ...
        val three = CoachProfile(climbingDays = setOf(2, 4, 6), trainingDaysPerWeek = 5)
        assertEquals(mapOf(1 to pullDay, 2 to climb, 3 to pullDay, 4 to climb, 5 to PLAN_REST, 6 to climb, 7 to PLAN_REST),
            week(three, DayPlans.Tier.STANDARD))
        // ... an advanced climber's fingers are held back the day of a hard day only: Sunday, after Saturday's
        // climbing, takes the finger day, and a training moves there. The day before a climbing day stays free of it,
        // as on today's card.
        val seasoned = mapOf(1 to pullDay, 2 to climb, 3 to PLAN_REST, 4 to climb, 5 to PLAN_REST, 6 to climb, 7 to fingerDay)
        assertEquals(seasoned, week(three, DayPlans.Tier.ADVANCED))
        assertEquals(seasoned, week(three, DayPlans.Tier.ELITE))
        // The proposal per date reads the same level as the hints it is checked against.
        val profile = threeDays.copy(coach = three)
        val advanced = WeekPlanSuggester.forDays(profile, LogbookSummary(), emptyList(), emptyList(),
            DayPlans.preview(profile, monday, 7, monday, tier = DayPlans.Tier.ADVANCED), tier = DayPlans.Tier.ADVANCED) { it == fingerDay }
        assertEquals(fingerDay, advanced[date(6)])
        assertTrue(fingerDay !in proposals(profile, DayPlans.preview(profile, monday, 7, monday)).values)

        // An elite week holds up to three finger days: at most every second training, never two days in a row, and
        // never the day before a climbing day.
        val one = CoachProfile(climbingDays = setOf(4), trainingDaysPerWeek = 6)
        assertEquals(mapOf(1 to fingerDay, 2 to pullDay, 3 to legsDay, 4 to climb, 5 to coreDay, 6 to pullDay, 7 to PLAN_REST),
            week(one, DayPlans.Tier.STANDARD))
        assertEquals(mapOf(1 to fingerDay, 2 to pullDay, 3 to legsDay, 4 to climb, 5 to fingerDay, 6 to coreDay, 7 to PLAN_REST),
            week(one, DayPlans.Tier.ELITE))
        val everyDay = CoachProfile(trainingDaysPerWeek = 7)
        assertEquals(setOf(1, 3, 5), week(everyDay, DayPlans.Tier.ELITE).filterValues { it == fingerDay }.keys)
        // One in a comeback, as in the weekly targets (finger strength as a focus keeps the finger day in a comeback's week).
        val fingerFocus = everyDay.copy(focus = setOf(FocusArea.FINGER_STRENGTH))
        assertEquals(setOf(1, 3, 5), week(fingerFocus, DayPlans.Tier.ELITE).filterValues { it == fingerDay }.keys)
        assertEquals(setOf(1), week(fingerFocus.copy(goal = CoachGoal.COMEBACK), DayPlans.Tier.ELITE).filterValues { it == fingerDay }.keys)
        // His own finger day is one of the three, and the coach's keep a day's distance from it.
        val own = WeekPlanSuggester.around(one, LogbookSummary(), emptyList(), home, emptyList(),
            WeekPlanSuggester.Fixed(entries = mapOf(2 to fingerDay)), DayPlans.Tier.ELITE) { it == fingerDay }
        assertEquals(mapOf(1 to pullDay, 3 to legsDay, 4 to climb, 5 to fingerDay, 6 to coreDay, 7 to PLAN_REST), own)
        // His own routine leads the week at every level.
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("pull.pull_up")), updatedAt = 5)
        val withRoutine = WeekPlanSuggester.suggest(CoachProfile(climbingDays = setOf(2, 5), trainingDaysPerWeek = 3), LogbookSummary(),
            listOf(mine), home, emptyList(), DayPlans.Tier.ELITE)
        assertTrue("r1" in withRoutine.values, "$withRoutine")
        // A limit day holds an elite climber back the same day only: a finger day may follow it. The day before it
        // is for legs at every level.
        val limit = DayPlans.withDay(threeDays.copy(coach = CoachProfile(trainingDaysPerWeek = 5)), date(2), limitDay, monday)
        assertEquals(
            mapOf(date(0) to fingerDay, date(1) to legsDay, date(3) to fingerDay, date(4) to PLAN_REST, date(5) to pullDay, date(6) to PLAN_REST),
            WeekPlanSuggester.forDays(limit, LogbookSummary(), emptyList(), emptyList(),
                DayPlans.preview(limit, monday, 7, monday, tier = DayPlans.Tier.ELITE), tier = DayPlans.Tier.ELITE) { it == fingerDay },
        )
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
        // A pause until Wednesday: nothing for its days, the days after it as in any week.
        val pause = listOf(PausePeriod("p", PauseReason.HOLIDAY, date(0).toString(), date(2).toString()))
        assertEquals(mapOf(date(3) to pullDay, date(4) to PLAN_REST, date(5) to legsDay, date(6) to mobilityDay),
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
