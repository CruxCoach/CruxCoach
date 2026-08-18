package com.cruxcoach.android.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCommandGateTest {
    @Test
    fun `commands require a join for the current connection lifecycle`() {
        val gate = SessionCommandGate()

        assertFalse(gate.hasJoined("friend"))
        assertTrue(gate.join("friend"))
        assertTrue(gate.hasJoined("friend"))
        assertFalse(gate.join("friend"))

        gate.remove("friend")
        assertFalse(gate.hasJoined("friend"))
        assertTrue(gate.join("friend"))
        gate.clear()
        assertFalse(gate.hasJoined("friend"))
    }
}
