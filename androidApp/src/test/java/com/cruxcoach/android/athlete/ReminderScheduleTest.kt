package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When reminders come (2026-10-18: the Nokia has notifications denied, so they could not be checked on the device and
 * had no tests at all): the next occurrence, the week plan's say, weigh-in weekdays, the monthly measuring day – also
 * across the end of summer time in Germany (2026-10-25).
 */
class ReminderScheduleTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, berlin)

    @Test
    fun `the training reminder comes today while the time is ahead, else tomorrow`() {
        assertEquals(at(2026, 10, 18, 17, 0), TrainingReminders.nextOccurrence(at(2026, 10, 18, 9, 0), 17 * 60))
        assertEquals(at(2026, 10, 19, 17, 0), TrainingReminders.nextOccurrence(at(2026, 10, 18, 17, 0), 17 * 60))
        // Within the last 30 s counts as passed: no double reminder right after it fired.
        assertEquals(at(2026, 10, 19, 17, 0), TrainingReminders.nextOccurrence(at(2026, 10, 18, 16, 59).plusSeconds(45), 17 * 60))
        // Out-of-range minutes are clamped, not wrapped into another day.
        assertEquals(at(2026, 10, 18, 23, 59), TrainingReminders.nextOccurrence(at(2026, 10, 18, 9, 0), 5000))
    }

    @Test
    fun `summer time ends between two reminders - the next one keeps its wall-clock time`() {
        // Sunday 2026-10-25 03:00 CEST → 02:00 CET.
        val next = TrainingReminders.nextOccurrence(at(2026, 10, 24, 18, 0), 17 * 60)
        assertEquals(at(2026, 10, 25, 17, 0), next)
        assertEquals(17, next.hour)
    }

    @Test
    fun `only planned training days remind, board days only when wanted`() {
        val plan = mapOf(1 to "builtin:pull_push", 2 to PLAN_REST, 3 to PLAN_BOARD, 4 to "routine-42")
        val p = AthleteProfile(weekPlan = plan, trainingReminderOnClimbingDays = false)
        assertEquals("builtin:pull_push", TrainingReminders.plannedEntry(p, 1))
        assertNull(TrainingReminders.plannedEntry(p, 2))
        assertNull(TrainingReminders.plannedEntry(p, 3))
        assertEquals("routine-42", TrainingReminders.plannedEntry(p, 4))
        assertNull(TrainingReminders.plannedEntry(p, 5))
        assertEquals(PLAN_BOARD, TrainingReminders.plannedEntry(p.copy(trainingReminderOnClimbingDays = true), 3))
    }

    @Test
    fun `a date planned over the standard week reminds as planned that day`() {
        // Monday 2026-10-19 "no time – rest", Tuesday a limit boulder day (owner 2026-10-19).
        val limit = com.cruxcoach.athlete.logic.DayPlans.Climb(com.cruxcoach.athlete.model.ClimbingDayKind.GYM_BOULDER,
            com.cruxcoach.athlete.model.ClimbIntensity.LIMIT).entry
        val p = AthleteProfile(weekPlan = mapOf(1 to "builtin:pull_push"), trainingReminderOnClimbingDays = false,
            datePlan = mapOf("2026-10-19" to PLAN_REST, "2026-10-20" to limit))
        assertNull(TrainingReminders.plannedEntry(p, java.time.LocalDate.of(2026, 10, 19)))
        assertEquals("builtin:pull_push", TrainingReminders.plannedEntry(p, java.time.LocalDate.of(2026, 10, 26)))
        assertNull(TrainingReminders.plannedEntry(p, java.time.LocalDate.of(2026, 10, 20)))
        assertEquals(limit, TrainingReminders.plannedEntry(p.copy(trainingReminderOnClimbingDays = true), java.time.LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `weigh-in comes on the next chosen weekday, a week on when today's time passed`() {
        val sunday = at(2026, 10, 18, 8, 0)
        // Mondays and Thursdays at 07:30.
        assertEquals(at(2026, 10, 19, 7, 30), BodyReminders.nextWeigh(sunday, setOf(1, 4), 7 * 60 + 30))
        // Sundays only, 07:30 already passed → next Sunday.
        assertEquals(at(2026, 10, 25, 7, 30), BodyReminders.nextWeigh(sunday, setOf(7), 7 * 60 + 30))
        // Sundays at 09:00 → still today.
        assertEquals(at(2026, 10, 18, 9, 0), BodyReminders.nextWeigh(sunday, setOf(7), 9 * 60))
        // No (valid) weekday: no reminder.
        assertNull(BodyReminders.nextWeigh(sunday, emptySet(), 7 * 60))
        assertNull(BodyReminders.nextWeigh(sunday, setOf(0, 8), 7 * 60))
    }

    @Test
    fun `the measuring round comes on its day of the month, day 29 to 31 falls back to the 28th`() {
        assertEquals(at(2026, 11, 1, 9, 0), BodyReminders.nextMeasure(at(2026, 10, 18, 8, 0), 1, 9 * 60))
        assertEquals(at(2026, 10, 20, 9, 0), BodyReminders.nextMeasure(at(2026, 10, 18, 8, 0), 20, 9 * 60))
        // February has no 31st: the 28th, every month the same.
        assertEquals(at(2027, 2, 28, 9, 0), BodyReminders.nextMeasure(at(2027, 2, 10, 8, 0), 31, 9 * 60))
        assertEquals(at(2026, 11, 28, 9, 0), BodyReminders.nextMeasure(at(2026, 10, 28, 10, 0), 31, 9 * 60))
    }
}
