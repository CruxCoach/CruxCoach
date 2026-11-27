package com.cruxcoach.android.ui.training.athlete

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pause rows under the injuries showed the stored day, "2026-11-09", next to a card saying "09.11.2026". */
class PauseSpanTest {
    private val before = Locale.getDefault()
    @After fun restore() = Locale.setDefault(before)

    @Test fun `an open pause shows its first day like the injury card`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("09.11.2026", pauseSpan("2026-11-09", null))
        Locale.setDefault(Locale.US)
        assertEquals("Nov 9, 2026", pauseSpan("2026-11-09", null))
    }

    @Test fun `an ended pause shows both days`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("09.11.2026 – 16.11.2026", pauseSpan("2026-11-09", "2026-11-16"))
    }

    @Test fun `a day that cannot be read stays as it is stored`() {
        assertEquals("soon", pauseSpan("soon", null))
    }
}
