package com.cruxcoach.android.ui.board

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [computeHistoryCutoffIso] — the retention cutoff that
 * pruneClimbHistory compares lexicographically. The risk is calendar
 * arithmetic (leap days, month/year boundaries) under day subtraction.
 */
class HistoryCutoffTest {

    @Test
    fun lands_on_the_leap_day_in_a_leap_year() {
        assertEquals("2024-02-29T10:30", computeHistoryCutoffIso(LocalDateTime(2024, 3, 1, 10, 30), 1))
    }

    @Test
    fun lands_on_feb_28_in_a_non_leap_year() {
        assertEquals("2023-02-28T10:30", computeHistoryCutoffIso(LocalDateTime(2023, 3, 1, 10, 30), 1))
    }

    @Test
    fun crosses_the_year_boundary() {
        assertEquals("2023-12-31T00:00", computeHistoryCutoffIso(LocalDateTime(2024, 1, 1, 0, 0), 1))
    }

    @Test
    fun subtracts_a_multi_day_window_across_a_month_boundary() {
        // March 15 - 30 days = Feb 14 (2024 is a leap year → Feb has 29 days).
        assertEquals("2024-02-14T12:00", computeHistoryCutoffIso(LocalDateTime(2024, 3, 15, 12, 0), 30))
    }
}
