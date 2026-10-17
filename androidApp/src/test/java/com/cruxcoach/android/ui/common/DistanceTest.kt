package com.cruxcoach.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DistanceTest {
    private fun de(m: Double) = distanceNumber(m, Locale.GERMANY)
    private fun en(m: Double) = distanceNumber(m, Locale.US)

    @Test
    fun `metres to the ten below a kilometre`() {
        assertEquals("350" to false, de(347.0))
        assertEquals("0" to false, de(3.0))
        assertEquals("990" to false, de(994.0))
    }

    @Test
    fun `one decimal below ten kilometres, in the locale's format`() {
        assertEquals("1,0" to true, de(995.0))
        assertEquals("1,2" to true, de(1_234.0))
        assertEquals("1.2" to true, en(1_234.0))
        assertEquals("9,9" to true, de(9_900.0))
    }

    @Test
    fun `whole kilometres beyond`() {
        assertEquals("10" to true, de(9_960.0))
        assertEquals("87" to true, de(86_600.0))
        assertEquals("1.234" to true, de(1_234_000.0))
    }
}
