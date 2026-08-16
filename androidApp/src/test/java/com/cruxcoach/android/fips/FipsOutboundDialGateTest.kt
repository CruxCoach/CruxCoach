package com.cruxcoach.android.fips

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsOutboundDialGateTest {
    @Test
    fun `only one platform dial may remain in flight`() {
        val gate = FipsOutboundDialGate()

        assertTrue(gate.tryAcquire(1))
        assertTrue(gate.busy())
        assertFalse(gate.tryAcquire(2))
        gate.release(2)
        assertFalse(gate.tryAcquire(3))
        gate.release(1)
        assertTrue(gate.tryAcquire(4))
    }
}
