package com.cruxcoach.android.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionJoinSecurityTest {
    @Test
    fun `generator emits zero-padded six digit capability`() {
        assertEquals("000007", SessionJoinCode.generate { bound ->
            assertEquals(1_000_000, bound)
            7
        })
        assertEquals("999999", SessionJoinCode.generate { 999_999 })
    }

    @Test
    fun `gate admits only exact well-formed capability and clears lifecycle state`() {
        val gate = SessionCommandGate()

        assertFalse(gate.admit("attacker", "123456", "123457"))
        assertFalse(gate.admit("legacy", "123456", ""))
        assertFalse(gate.isAdmitted("attacker"))
        assertTrue(gate.admit("friend", "123456", "123456"))
        assertTrue(gate.isAdmitted("friend"))

        gate.remove("friend")
        assertFalse(gate.isAdmitted("friend"))
        assertTrue(gate.admit("friend", "123456", "123456"))
        gate.clear()
        assertFalse(gate.isAdmitted("friend"))
    }
}
