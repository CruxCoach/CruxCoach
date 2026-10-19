package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Planned climbing days and single dates over the standard week (owner 2026-10-19). */
class DayPlansTest {
    private val monday = LocalDate.parse("2026-10-19")
    private fun day(n: Int) = monday.plus(DatePeriod(days = n))
    private val fingers = "builtin:finger_basics"
    private val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
    private val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
    private val standard = AthleteProfile(weekPlan = mapOf(1 to fingers))

    @Test
    fun aClimbEntryRoundTripsAndUnknownPartsFallBack() {
        assertEquals("climb:gym_boulder:limit", limit)
        assertEquals(DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT), DayPlans.climbOf(limit))
        assertEquals(DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME), DayPlans.climbOf("climb:"))
        assertNull(DayPlans.climbOf(PLAN_BOARD))
        assertNull(DayPlans.climbOf(null))
    }

    @Test
    fun aDateEntryWinsOverTheStandardWeekForThatDateOnly() {
        val p = DayPlans.withDay(standard, monday, PLAN_REST, monday)
        assertEquals(PLAN_REST, DayPlans.entry(p, monday))
        assertEquals(fingers, DayPlans.entry(p, day(7)), "next Monday follows the standard week again")
        val free = DayPlans.withDay(standard, monday, PLAN_FREE, monday)
        assertNull(DayPlans.entry(free, monday))
        assertTrue(DayPlans.isOverridden(free, monday))
        // The standard week's own entry, or "back to the standard week", needs no override.
        assertTrue(DayPlans.withDay(p, monday, fingers, monday).datePlan.isEmpty())
        assertTrue(DayPlans.withDay(p, monday, null, monday).datePlan.isEmpty())
        assertTrue(DayPlans.withDay(AthleteProfile(), day(2), PLAN_FREE, monday).datePlan.isEmpty())
    }

    @Test
    fun oldDateEntriesAreDroppedWhenThePlanIsWritten() {
        val old = AthleteProfile(datePlan = mapOf("2026-09-01" to PLAN_REST, day(-3).toString() to PLAN_REST))
        val p = DayPlans.withDay(old, day(1), volume, monday)
        assertEquals(setOf(day(-3).toString(), day(1).toString()), p.datePlan.keys)
    }

    @Test
    fun theOwnersExampleShapesTheDaysAround() {
        // "No time today: rest; tomorrow a volume boulder day, two days later a limit boulder day."
        var p = DayPlans.withDay(standard, monday, PLAN_REST, monday)
        p = DayPlans.withDay(p, day(1), volume, monday)
        p = DayPlans.withDay(p, day(3), limit, monday)
        val days = DayPlans.preview(p, monday, 7, monday)
        assertEquals(
            listOf(DayPlans.Hint.REST, DayPlans.Hint.CLIMB, DayPlans.Hint.BEFORE_LIMIT, DayPlans.Hint.CLIMB,
                DayPlans.Hint.AFTER_HARD, DayPlans.Hint.OPEN, DayPlans.Hint.OPEN),
            days.map { it.hint },
        )
        assertEquals(ClimbIntensity.LIMIT, days[3].hardness)
        assertTrue(days[0].overridden && days[1].overridden && !days[2].overridden)
        assertTrue(DayPlans.warnings(days, monday) { false }.isEmpty())
    }

    @Test
    fun loggedClimbingCountsOverThePlan() {
        val days = DayPlans.preview(AthleteProfile(), monday, 2, monday, logged = mapOf(day(-1) to ClimbIntensity.LIMIT))
        assertEquals(DayPlans.Hint.AFTER_HARD, days[0].hint)
        assertEquals(DayPlans.Hint.OPEN, days[1].hint)
        // Logged without an intensity: treated as hard, as the coach's recovery rules do.
        val unknown = DayPlans.preview(AthleteProfile(), monday, 1, monday, logged = mapOf(day(-1) to null))
        assertEquals(DayPlans.Hint.AFTER_HARD, unknown[0].hint)
    }

    @Test
    fun usualClimbingDaysCountUnlessTheDateSaysOtherwise() {
        val p = AthleteProfile(coach = CoachProfile(climbingDays = setOf(2)))
        assertTrue(DayPlans.climbingPlanned(p, day(1)))
        assertEquals(DayPlans.Hint.CLIMB, DayPlans.preview(p, day(1), 1, monday)[0].hint)
        val free = DayPlans.withDay(p, day(1), PLAN_FREE, monday)
        assertEquals(PLAN_FREE, free.datePlan[day(1).toString()], "a free date over a usual climbing day is kept")
        assertFalse(DayPlans.climbingPlanned(free, day(1)))
    }

    @Test
    fun swappingMovesBothDays() {
        val p = DayPlans.swap(standard, monday, day(1), monday)
        assertNull(DayPlans.entry(p, monday))
        assertEquals(fingers, DayPlans.entry(p, day(1)))
        val back = DayPlans.swap(p, monday, day(1), monday)
        assertTrue(back.datePlan.isEmpty(), "${back.datePlan}")
    }

    @Test
    fun hardDaysBackToBackAndFingersBeforeAHardDayAreWarnedAbout() {
        var p = DayPlans.withDay(AthleteProfile(), day(1), limit, monday)
        p = DayPlans.withDay(p, day(2), DayPlans.Climb(ClimbingDayKind.OUTDOOR, ClimbIntensity.HARD).entry, monday)
        p = DayPlans.withDay(p, day(4), fingers, monday)
        p = DayPlans.withDay(p, day(5), PLAN_BOARD, monday)
        val warnings = DayPlans.warnings(DayPlans.preview(p, monday, 7, monday), monday) { it == fingers }
        assertEquals(
            listOf(DayPlans.Warning(DayPlans.WarningKind.HARD_BACK_TO_BACK, day(2), day(1)),
                DayPlans.Warning(DayPlans.WarningKind.FINGERS_BEFORE_HARD, day(5), day(4))),
            warnings,
        )
    }

    @Test
    fun aTrainingThatGaveWayFindsAFreeDayThatFits() {
        var p = DayPlans.withDay(standard, monday, PLAN_REST, monday)
        p = DayPlans.withDay(p, day(1), volume, monday)
        p = DayPlans.withDay(p, day(3), limit, monday)
        // Finger work: not before or after the limit day, not on a climbing day.
        assertEquals(day(5), DayPlans.freeDayFor(p, monday, monday, fingerWork = true))
        // Anything else: only not right before the limit day.
        assertEquals(day(4), DayPlans.freeDayFor(p, monday, monday, fingerWork = false))
        // A pause over the week leaves nothing to offer.
        val pause = PausePeriod("p", PauseReason.HOLIDAY, day(1).toString(), day(9).toString())
        assertNull(DayPlans.freeDayFor(p, monday, monday, fingerWork = false, pauses = listOf(pause)))
    }

    @Test
    fun plannedClimbingDaysDriveTheNutritionTargets() {
        val p = DayPlans.withDay(DayPlans.withDay(AthleteProfile(), monday, limit, monday), day(1), volume, monday)
        val todayPlan = PlannedTraining.fromWeekPlan(p, monday, emptyList(), ExerciseCatalog(1, emptyList()))
        assertEquals(PlannedTraining.Kind.CLIMB, todayPlan.kind)
        assertEquals(90, todayPlan.minutes)
        val planned = PlannedTraining.planned(todayPlan)!!
        assertEquals(ClimbIntensity.LIMIT, planned.activity.climbIntensity)
        assertEquals(90, planned.activity.climbingMinutes)
        assertEquals(120, PlannedTraining.fromWeekPlan(p, day(1), emptyList(), ExerciseCatalog(1, emptyList())).minutes)
        // A climbing day logged covers the plan.
        assertNull(PlannedTraining.forTargets(DayActivity(monday.toString(), climbingMinutes = 60), planned))
        // Tomorrow hard: a volume day is one, an easy day is none; the date entry counts.
        assertTrue(PlannedTraining.tomorrowHard(p, monday))
        val easy = DayPlans.withDay(p, day(1), DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIGHT).entry, monday)
        assertFalse(PlannedTraining.tomorrowHard(easy, monday))
        val rest = DayPlans.withDay(AthleteProfile(weekPlan = mapOf(2 to PLAN_BOARD)), day(1), PLAN_REST, monday)
        assertFalse(PlannedTraining.tomorrowHard(rest, monday), "a board day moved away is no hard day")
    }
}
