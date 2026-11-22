package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A climbing or board day taken back for a date ([DayPlans.withoutClimbing]; device walk 2026-11-22): the date's own
 * entry goes and the standard week applies again; only where the day would still be a climbing day it is planned free.
 */
class ClimbingDayTakenBackTest {
    private val monday = LocalDate.parse("2026-10-19")
    private fun day(n: Int) = monday.plus(DatePeriod(days = n))
    private val fingers = "builtin:finger_basics"
    private fun climb(intensity: ClimbIntensity) = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, intensity).entry

    @Test
    fun theDatesOwnEntryGoesAndTheStandardWeekAppliesAgain() {
        // Nothing in the standard week: the day is free again and no entry is left.
        val free = DayPlans.withoutClimbing(DayPlans.withDay(AthleteProfile(), monday, climb(ClimbIntensity.VOLUME), monday), monday, monday)
        assertNull(DayPlans.entry(free, monday))
        assertTrue(free.datePlan.isEmpty(), "${free.datePlan}")
        // A training in the standard week: it is back, the day is not left free.
        val week = AthleteProfile(weekPlan = mapOf(1 to fingers))
        val training = DayPlans.withoutClimbing(DayPlans.withDay(week, monday, PLAN_BOARD, monday), monday, monday)
        assertEquals(fingers, DayPlans.entry(training, monday))
        assertTrue(training.datePlan.isEmpty(), "${training.datePlan}")
        // A rest day in the standard week is a rest day again.
        val restWeek = AthleteProfile(weekPlan = mapOf(1 to PLAN_REST))
        val rest = DayPlans.withoutClimbing(DayPlans.withDay(restWeek, monday, climb(ClimbIntensity.LIMIT), monday), monday, monday)
        assertEquals(PLAN_REST, DayPlans.entry(rest, monday))
        // Other dates keep their own entries.
        val two = DayPlans.withDay(DayPlans.withDay(week, monday, PLAN_BOARD, monday), day(2), climb(ClimbIntensity.HARD), monday)
        assertEquals(climb(ClimbIntensity.HARD), DayPlans.entry(DayPlans.withoutClimbing(two, monday, monday), day(2)))
    }

    @Test
    fun whereTheDayWouldStillBeAClimbingDayItIsPlannedFree() {
        // The standard week plans the climbing: this date only is free, the week stays.
        val week = AthleteProfile(weekPlan = mapOf(1 to climb(ClimbIntensity.VOLUME)))
        val free = DayPlans.withoutClimbing(week, monday, monday)
        assertEquals(PLAN_FREE, free.datePlan[monday.toString()])
        assertNull(DayPlans.entry(free, monday))
        assertFalse(DayPlans.climbingPlanned(free, monday))
        assertEquals(climb(ClimbIntensity.VOLUME), DayPlans.entry(free, day(7)))
        // A limit day planned over the week's volume day: free – never the volume day behind it.
        val limit = DayPlans.withoutClimbing(DayPlans.withDay(week, monday, climb(ClimbIntensity.LIMIT), monday), monday, monday)
        assertNull(DayPlans.entry(limit, monday))
        assertFalse(DayPlans.climbingPlanned(limit, monday))
        // A usual climbing weekday with a board day set on it: free, or the coach would plan the climbing again.
        val usual = AthleteProfile(coach = CoachProfile(climbingDays = setOf(1)))
        val board = DayPlans.withoutClimbing(DayPlans.withDay(usual, monday, PLAN_BOARD, monday), monday, monday)
        assertEquals(PLAN_FREE, board.datePlan[monday.toString()])
        assertFalse(DayPlans.climbingPlanned(board, monday))
        // The Tuesday after it is a usual climbing day as before.
        assertTrue(DayPlans.climbingPlanned(board.copy(coach = CoachProfile(climbingDays = setOf(1, 2))), day(1)))
    }

    @Test
    fun aDayWithoutClimbingPlannedStaysAsItIs() {
        val week = AthleteProfile(weekPlan = mapOf(1 to fingers), coach = CoachProfile(climbingDays = setOf(2)))
        assertEquals(week, DayPlans.withoutClimbing(week, monday, monday))
        val rest = DayPlans.withDay(week, monday, PLAN_REST, monday)
        assertEquals(rest, DayPlans.withoutClimbing(rest, monday, monday))
        // A usual climbing weekday without an entry is no planned day that could be taken back.
        assertEquals(week, DayPlans.withoutClimbing(week, day(1), monday))
    }
}
