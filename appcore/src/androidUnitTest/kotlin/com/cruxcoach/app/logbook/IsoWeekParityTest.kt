package com.cruxcoach.app.logbook

import java.time.temporal.WeekFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate

/** The hand-written ISO week maths must agree with java.time, which Android uses. */
class IsoWeekParityTest {
    @Test
    fun `iso week, week-based year and monday match java time for 12 years`() {
        var day = java.time.LocalDate.of(2018, 1, 1)
        val end = java.time.LocalDate.of(2030, 1, 1)
        while (day.isBefore(end)) {
            val k = LocalDate(day.year, day.monthValue, day.dayOfMonth)
            assertEquals(day.get(WeekFields.ISO.weekOfWeekBasedYear()), BoardStatsComputer.isoWeek(k), "$day")
            assertEquals(day.get(WeekFields.ISO.weekBasedYear()), BoardStatsComputer.weekBasedYear(k), "$day")
            assertEquals(
                day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString(),
                BoardStatsComputer.mondayOf(k).toString(),
            )
            day = day.plusDays(1)
        }
    }
}
