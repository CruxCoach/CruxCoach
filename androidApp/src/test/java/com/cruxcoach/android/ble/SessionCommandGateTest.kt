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
        assertFalse(gate.isContextCapable("friend"))
        gate.markContextCapable("friend")
        assertTrue(gate.isContextCapable("friend"))
        assertFalse(gate.join("friend"))
        assertTrue(gate.isContextCapable("friend"))

        gate.remove("friend")
        assertFalse(gate.hasJoined("friend"))
        assertFalse(gate.isContextCapable("friend"))
        assertTrue(gate.join("friend"))
        assertFalse(gate.isContextCapable("friend"))
        gate.markContextCapable("friend")
        gate.clear()
        assertFalse(gate.hasJoined("friend"))
        assertFalse(gate.isContextCapable("friend"))
    }
    @Test
    fun `legacy peers retain bursts but cannot flood indefinitely`() {
        var now = 0L
        val gate = SessionCommandGate { now }
        assertFalse(gate.allowMutation("legacy"))
        gate.join("legacy")
        repeat(120) { assertTrue(gate.allowMutation("legacy")) }
        assertFalse(gate.allowMutation("legacy"))
        gate.join("legacy")
        assertFalse(gate.allowMutation("legacy"))
        now += 1_000_000_000
        repeat(30) { assertTrue(gate.allowMutation("legacy")) }
        assertFalse(gate.allowMutation("legacy"))
        gate.join("other")
        assertTrue(gate.allowMutation("other"))
    }
}
