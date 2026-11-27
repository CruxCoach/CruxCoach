package com.cruxcoach.android.ui.training.workouts

import android.app.Application
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.model.PLAN_BOARD
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phone check 2026-11-21: a standard week made on a Saturday marked Monday to Friday of that week "missed" – in
 * every new athlete's first week. The plan counts from the day it was set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeekPlanSinceTest : AthleteScreenTest() {

    private val saturday = LocalDate.parse("2026-11-21")
    private val weekdays = (1..5).associateWith { "builtin:core_climber" }

    @Test
    fun `days before the standard week existed are free, not missed`() {
        val before = buildWeek(saturday, weekdays, emptyMap(), emptyList(), emptyList())
        assertEquals(List(5) { WeekDayStatus.MISSED }, before.take(5).map { it.status })
        val since = buildWeek(saturday, weekdays, emptyMap(), emptyList(), emptyList(), planSince = saturday)
        assertEquals(List(5) { WeekDayStatus.FREE }, since.take(5).map { it.status })
        assertTrue(since.take(5).all { it.planned == null })
        // From the day it was set the plan counts as before: a planned day that passes without training is missed.
        val nextFriday = LocalDate.parse("2026-11-27")
        val next = buildWeek(nextFriday, weekdays, emptyMap(), emptyList(), emptyList(), planSince = saturday)
        assertEquals(List(4) { WeekDayStatus.MISSED } + WeekDayStatus.PLANNED, next.take(5).map { it.status })
    }

    @Test
    fun `changing the standard week notes the day, other profile changes do not`() {
        assertEquals(null, repo.profile().weekPlanSince)
        repo.updateProfile { it.copy(sessionMinutes = 30) }
        assertEquals(null, repo.profile().weekPlanSince)
        val iso = service.today().dayOfWeek.isoDayNumber
        repo.updateProfile { it.copy(weekPlan = mapOf(iso to PLAN_BOARD)) }
        val since = repo.profile().weekPlanSince
        assertTrue("$since", since != null && LocalDate.parse(since!!) >= service.today().minus(DatePeriod(days = 1)))
        // The same week saved again keeps its day.
        repo.updateProfile { it.copy(sessionMinutes = 45) }
        assertEquals(since, repo.profile().weekPlanSince)
        // A change to a day still ahead keeps it too; a change to a day already past starts the count anew.
        repo.updateProfile { it.copy(weekPlanSince = "2026-01-01") }
        if (iso < 7) {
            repo.updateProfile { it.copy(weekPlan = it.weekPlan + (7 to "builtin:core_climber")) }
            assertEquals("2026-01-01", repo.profile().weekPlanSince)
        }
        if (iso > 1) {
            repo.updateProfile { it.copy(weekPlan = it.weekPlan + (1 to "builtin:core_climber")) }
            val anew = repo.profile().weekPlanSince
            assertTrue("$anew", anew != null && LocalDate.parse(anew!!) >= service.today().minus(DatePeriod(days = 1)))
        }
    }
}
