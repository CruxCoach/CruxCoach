package com.cruxcoach.android.ui.board

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The logbook's day headings wrote "17.12.2026" in every language. */
class LogbookDayHeaderDateTest {
    private val day = LocalDate.of(2026, 12, 17)

    @Test fun `German keeps its form`() {
        assertEquals("17.12.2026", dayHeaderDate(day, Locale.GERMANY))
    }

    @Test fun `English gets its own`() {
        assertEquals("Dec 17, 2026", dayHeaderDate(day, Locale.US))
    }
}
