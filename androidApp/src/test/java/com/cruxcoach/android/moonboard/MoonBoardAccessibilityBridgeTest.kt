package com.cruxcoach.android.moonboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class MoonBoardAccessibilityBridgeTest {
    @After
    fun resetBridge() {
        MoonBoardAccessibilityBridge.service = null
        MoonBoardAccessibilityBridge.update { MoonBoardScanState() }
    }

    @Test
    fun `terminal result survives service self-disable disconnect`() {
        val completed = MoonBoardCsvImportResult(
            foundEntries = 5,
            importedAscents = 4,
            duplicates = 1,
        )
        MoonBoardAccessibilityBridge.update {
            MoonBoardScanState(
                serviceConnected = true,
                running = false,
                captured = 5,
                result = completed,
            )
        }

        MoonBoardAccessibilityBridge.connected(null, "interrupted")

        val state = MoonBoardAccessibilityBridge.state.value
        assertFalse(state.serviceConnected)
        assertFalse(state.running)
        assertEquals(5, state.captured)
        assertSame(completed, state.result)
    }

    @Test
    fun `nonterminal disconnect remains an interrupted failure`() {
        MoonBoardAccessibilityBridge.update {
            MoonBoardScanState(serviceConnected = true, running = true)
        }

        MoonBoardAccessibilityBridge.connected(null, "interrupted")

        val state = MoonBoardAccessibilityBridge.state.value
        assertFalse(state.running)
        assertEquals("interrupted", state.result?.error)
    }
}
