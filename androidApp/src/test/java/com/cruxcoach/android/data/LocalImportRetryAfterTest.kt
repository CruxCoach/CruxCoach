package com.cruxcoach.android.data

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins [parseRetryAfterSeconds] — the local-share receiver's poll cadence
 * while the sender is still building its scrubbed board-DB snapshot (503 +
 * Retry-After). The clamp matters: a bogus header must neither hammer the
 * sender (<2s) nor stall the import for minutes (>15s).
 */
class LocalImportRetryAfterTest {

    @Test
    fun seconds_form_is_parsed() {
        assertEquals(3L, parseRetryAfterSeconds("3"))
        assertEquals(10L, parseRetryAfterSeconds(" 10 "))
    }

    @Test
    fun missing_or_garbage_falls_back_to_5s() {
        assertEquals(5L, parseRetryAfterSeconds(null))
        assertEquals(5L, parseRetryAfterSeconds(""))
        assertEquals(5L, parseRetryAfterSeconds("soon"))
        // HTTP-date form is valid HTTP but not worth parsing here.
        assertEquals(5L, parseRetryAfterSeconds("Fri, 11 Jul 2026 14:30:00 GMT"))
    }

    @Test
    fun values_are_clamped_to_sane_window() {
        assertEquals(2L, parseRetryAfterSeconds("0"))
        assertEquals(2L, parseRetryAfterSeconds("-7"))
        assertEquals(15L, parseRetryAfterSeconds("3600"))
    }
}
