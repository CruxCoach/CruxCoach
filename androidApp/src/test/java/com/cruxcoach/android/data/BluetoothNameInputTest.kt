package com.cruxcoach.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothNameInputTest {

    @Test
    fun `an ordinary phone name is fine and trimmed`() {
        assertNull(BluetoothNameInput.problem("Galaxy S21 Ultra"))
        assertNull(BluetoothNameInput.problem("  My phone  "))
        assertNull(BluetoothNameInput.problem("CruxCoach test phone"))
    }

    @Test
    fun `an empty name is refused`() {
        assertEquals(BluetoothNameInput.Problem.BLANK, BluetoothNameInput.problem(""))
        assertEquals(BluetoothNameInput.Problem.BLANK, BluetoothNameInput.problem("   "))
    }

    @Test
    fun `a sharing name is refused`() {
        for (name in listOf("CruxCoach·Kilter Board@3", "CruxCoach@3", "CruxRelay", " CruxRelay·MoonBoard ")) {
            assertEquals(name, BluetoothNameInput.Problem.SHARING_NAME, BluetoothNameInput.problem(name))
        }
    }

    @Test
    fun `the limit counts UTF-8 bytes, not letters`() {
        assertNull(BluetoothNameInput.problem("a".repeat(BluetoothNameInput.MAX_BYTES)))
        assertEquals(
            BluetoothNameInput.Problem.TOO_LONG,
            BluetoothNameInput.problem("a".repeat(BluetoothNameInput.MAX_BYTES + 1)),
        )
        // "ä" takes two bytes: 124 fit, 125 do not.
        assertNull(BluetoothNameInput.problem("ä".repeat(124)))
        assertEquals(BluetoothNameInput.Problem.TOO_LONG, BluetoothNameInput.problem("ä".repeat(125)))
    }

    @Test
    fun `the suggestion is the device name unless it cannot be used`() {
        assertEquals("My phone", BluetoothNameInput.suggestion(" My phone "))
        assertEquals("", BluetoothNameInput.suggestion(null))
        assertEquals("", BluetoothNameInput.suggestion("   "))
        assertEquals("", BluetoothNameInput.suggestion("CruxCoach·Kilter Board@3"))
    }
}
