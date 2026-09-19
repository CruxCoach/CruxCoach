package com.cruxcoach.app.logbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime

/** Port of Android HistoryCutoffTest: calendar arithmetic of the retention cutoff. */
class HistoryCutoffTest {
    @Test
    fun lands_on_the_leap_day_in_a_leap_year() =
        assertEquals("2024-02-29T10:30", computeHistoryCutoffIso(LocalDateTime(2024, 3, 1, 10, 30), 1))

    @Test
    fun lands_on_feb_28_in_a_non_leap_year() =
        assertEquals("2023-02-28T10:30", computeHistoryCutoffIso(LocalDateTime(2023, 3, 1, 10, 30), 1))

    @Test
    fun crosses_the_year_boundary() =
        assertEquals("2023-12-31T00:00", computeHistoryCutoffIso(LocalDateTime(2024, 1, 1, 0, 0), 1))

    @Test
    fun subtracts_a_multi_day_window_across_a_month_boundary() =
        assertEquals("2024-02-14T12:00", computeHistoryCutoffIso(LocalDateTime(2024, 3, 15, 12, 0), 30))
}
