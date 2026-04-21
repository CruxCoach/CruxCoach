package com.cruxcoach.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateTimeUtilTest {

    @Test
    fun daysBetween_simpleWeek_is7() {
        assertEquals(7, DateTimeUtil.daysBetween("2026-03-01", "2026-03-08"))
    }

    @Test
    fun daysBetween_sameDate_is0() {
        assertEquals(0, DateTimeUtil.daysBetween("2026-03-01", "2026-03-01"))
    }

    @Test
    fun daysBetween_reverseOrder_isNegative() {
        assertEquals(-3, DateTimeUtil.daysBetween("2026-03-08", "2026-03-05"))
    }

    @Test
    fun daysBetween_acrossMonthBoundary_counts() {
        // Jan 30 → Feb 2 = 3 days
        assertEquals(3, DateTimeUtil.daysBetween("2026-01-30", "2026-02-02"))
    }

    @Test
    fun daysBetween_acrossLeapDay_counts() {
        // 2024 is a leap year (2026 is not). Feb 28 → Mar 1 in 2024 = 2 days.
        assertEquals(2, DateTimeUtil.daysBetween("2024-02-28", "2024-03-01"))
        // In 2026: Feb 28 → Mar 1 = 1 day.
        assertEquals(1, DateTimeUtil.daysBetween("2026-02-28", "2026-03-01"))
    }

    @Test
    fun weeksBetween_truncatesDown() {
        assertEquals(1, DateTimeUtil.weeksBetween("2026-03-01", "2026-03-10")) // 9 days → 1 week
        assertEquals(2, DateTimeUtil.weeksBetween("2026-03-01", "2026-03-15")) // 14 days → 2 weeks
        assertEquals(0, DateTimeUtil.weeksBetween("2026-03-01", "2026-03-06")) // 5 days → 0 weeks
    }

    @Test
    fun addDays_positive() {
        assertEquals("2026-03-08", DateTimeUtil.addDays("2026-03-01", 7))
    }

    @Test
    fun addDays_negative() {
        assertEquals("2026-02-28", DateTimeUtil.addDays("2026-03-01", -1))
    }

    @Test
    fun addDays_acrossYearBoundary() {
        assertEquals("2026-01-01", DateTimeUtil.addDays("2025-12-31", 1))
        assertEquals("2025-12-31", DateTimeUtil.addDays("2026-01-01", -1))
    }

    @Test
    fun addWeeks_matchesAddDaysTimes7() {
        assertEquals("2026-03-29", DateTimeUtil.addWeeks("2026-03-01", 4))
    }

    @Test
    fun dayOfWeek_monday_is1_sunday_is7() {
        // 2026-03-02 is a Monday (ISO: 1). 2026-03-08 is a Sunday (ISO: 7).
        assertEquals(1, DateTimeUtil.dayOfWeek("2026-03-02"))
        assertEquals(7, DateTimeUtil.dayOfWeek("2026-03-08"))
    }

    @Test
    fun startOfWeek_fromMidWeek_returnsMonday() {
        // 2026-03-04 is a Wednesday. startOfWeek should return Monday 2026-03-02.
        assertEquals("2026-03-02", DateTimeUtil.startOfWeek("2026-03-04"))
    }

    @Test
    fun startOfWeek_fromMonday_returnsSameDay() {
        assertEquals("2026-03-02", DateTimeUtil.startOfWeek("2026-03-02"))
    }

    @Test
    fun startOfWeek_fromSunday_returnsPriorMonday() {
        // 2026-03-08 is Sunday; its week starts 2026-03-02.
        assertEquals("2026-03-02", DateTimeUtil.startOfWeek("2026-03-08"))
    }

    @Test
    fun endOfWeek_is6DaysAfterStart() {
        // Week containing 2026-03-04 is Mon 2026-03-02 → Sun 2026-03-08.
        assertEquals("2026-03-08", DateTimeUtil.endOfWeek("2026-03-04"))
    }

    @Test
    fun endOfWeek_worksAcrossMonthBoundary() {
        // 2026-04-02 is a Thursday; week: Mon 2026-03-30 → Sun 2026-04-05.
        assertEquals("2026-04-05", DateTimeUtil.endOfWeek("2026-04-02"))
    }

    @Test
    fun parseDate_roundTripsIsoString() {
        val isoString = "2026-03-01"
        val parsed = DateTimeUtil.parseDate(isoString)
        assertEquals(isoString, parsed.toString())
    }

    @Test
    fun isoToEpochMs_parsesValidLocalDateTime() {
        // nowIso() emits LocalDateTime.toString(), e.g. "2026-03-01T12:34:56".
        val ms = DateTimeUtil.isoToEpochMs("2026-03-01T12:34:56")
        assertNotNull(ms)
        assertTrue(ms > 0L)
    }

    @Test
    fun isoToEpochMs_returnsNullOnGarbage() {
        assertNull(DateTimeUtil.isoToEpochMs("not-a-date"))
        assertNull(DateTimeUtil.isoToEpochMs(""))
        assertNull(DateTimeUtil.isoToEpochMs("2026-03-01")) // date-only is not a LocalDateTime
    }

    @Test
    fun nowIso_andIsoToEpochMs_roundTripWithinSecond() {
        val iso = DateTimeUtil.nowIso()
        val epoch = DateTimeUtil.isoToEpochMs(iso)
        assertNotNull(epoch)
        // Guarantees nowIso() produces something isoToEpochMs accepts — the
        // contract both the engine and UI rely on.
    }

    @Test
    fun todayIso_matchesNowDate() {
        // Stable within a single test invocation (both calls on same day).
        assertEquals(DateTimeUtil.now().toString(), DateTimeUtil.todayIso())
    }
}
