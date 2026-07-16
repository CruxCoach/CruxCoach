package com.cruxcoach.android.util

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DateFormattingTest {
    @Test
    fun `ISO storage date uses requested display locale`() {
        assertEquals("Jul 13, 2026", formatIsoDate("2026-07-13", Locale.US))
        assertEquals("13.07.2026", formatIsoDate("2026-07-13", Locale.GERMANY))
    }

    @Test
    fun `invalid storage date remains bounded and visible`() {
        assertEquals("not-a-date", formatIsoDate("not-a-date-and-more", Locale.US))
    }

    @Test
    fun `epoch date time follows locale order`() {
        val epoch = 1_768_435_200_000L // 2026-01-15T00:00:00Z
        assertEquals(
            "1/15/26, 12:00 AM",
            formatEpochDateTime(epoch, Locale.US, ZoneId.of("UTC")),
        )
        assertEquals(
            "15.01.26, 00:00",
            formatEpochDateTime(epoch, Locale.GERMANY, ZoneId.of("UTC")),
        )
    }
}
