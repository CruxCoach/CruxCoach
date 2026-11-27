package com.cruxcoach.android.ui.training.athlete

import com.cruxcoach.athlete.logic.TrendWeight
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The weekly review's "Trend weight: … this week" line: only for a week that has a weigh-in to compare. */
class WeekTrendChangeTest {
    private val monday = LocalDate(2026, 11, 16)
    private val sunday = LocalDate(2026, 11, 22)
    private fun point(day: LocalDate, trend: Double) = TrendWeight.Point(day, raw = trend, trend = trend)

    @Test fun `no weigh-in at all gives no line`() {
        assertNull(weekTrendChange(emptyList(), monday, sunday))
    }

    @Test fun `a week without a weigh-in gives no line instead of 0 kg`() {
        val older = listOf(point(LocalDate(2026, 11, 3), 70.4), point(LocalDate(2026, 11, 10), 70.1))
        assertNull(weekTrendChange(older, monday, sunday))
    }

    @Test fun `a weigh-in on the week's first day alone is no change yet`() {
        val trend = listOf(point(LocalDate(2026, 11, 10), 70.1), point(monday, 70.0))
        assertNull(weekTrendChange(trend, monday, sunday))
    }

    @Test fun `a weigh-in in the week is compared with the value the week started at`() {
        val trend = listOf(point(LocalDate(2026, 11, 10), 70.1), point(LocalDate(2026, 11, 19), 69.8))
        assertEquals(-0.3, weekTrendChange(trend, monday, sunday)!!, 1e-9)
    }

    @Test fun `the first weigh-in ever has nothing to compare with`() {
        assertNull(weekTrendChange(listOf(point(LocalDate(2026, 11, 19), 69.8)), monday, sunday))
    }

    @Test fun `a later week does not count for this one`() {
        val trend = listOf(point(LocalDate(2026, 11, 10), 70.1), point(LocalDate(2026, 11, 24), 69.5))
        assertNull(weekTrendChange(trend, monday, sunday))
    }
}
