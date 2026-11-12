package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LED lab's Quantum switch at PaintBox's projector: what it hands to the connection, off and on. */
class LedLabProjectionTest {
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val layers = mockk<BoardLayerManager>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val sessions = mockk<SessionQueueManager> { every { state } returns MutableStateFlow(SessionQueueState()) }
    private val relay = mockk<CruxRelayManager> { every { state } returns MutableStateFlow(CruxRelayState()) }
    private val projector = PaintProjector(ble, layers, prefs, sessions, relay)

    /** 93 holds in one of the four Quantum colors: one more than a message takes. */
    private val picture = PaintDocument(board = PaintBoard("quantum", 9101, 9201), colors = (1..93).associateWith { 28 })
    private val ledMap = (1..93).associateWith { it }

    @After fun reset() = LedLab.reset()

    private fun quantum(state: QuantumControllerState = QuantumControllerState(), sent: Boolean = true) {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
        every { ble.connectedBoard } returns DiscoveredBoard("Quantum", "", 3, "AA:BB", -40, BoardBrand.QUANTUM)
        every { ble.quantumControllerState } returns MutableStateFlow(state)
        every { layers.paintIdentity(any()) } answers { "00000000-0000-4000-8000-00000000000${firstArg<Int>() + 1}" }
        coEvery { ble.refreshQuantumState() } returns true
        coEvery { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns sent
    }

    @Test fun `93 LEDs of a Quantum color are refused as in the release build`() = runTest {
        quantum()
        val refused = runCatching { projector.send(picture, ledMap) { true } }.exceptionOrNull() as? PaintProjectionException
        assertEquals(R.string.paintbox_quantum_limit, refused?.messageRes)
        coVerify(exactly = 0) { ble.refreshQuantumState() }
        coVerify(exactly = 0) { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(LedLab.report.value!!.nothingWritten)
        assertNull(LedLab.report.value!!.boardAnswer)
    }

    @Test fun `with the switch 93 LEDs of a color go to the connection as one layer`() = runTest {
        quantum()
        LedLab.change { it.copy(quantumSeveralMessages = true) }
        assertTrue(projector.send(picture, ledMap) { true })
        coVerify(exactly = 1) {
            ble.sendClimb(match { holds -> holds.map { it.placementId } == (1..93).toList() }, ledMap,
                any(), any(), any(), any(), any(), any(), BoardBrand.QUANTUM)
        }
        // 92 of a color go out the same way with the switch as without.
        val small = picture.copy(colors = (1..92).associateWith { 28 })
        assertTrue(projector.send(small, ledMap) { true })
        coVerify(exactly = 1) {
            ble.sendClimb(match { it.size == 92 }, ledMap, any(), any(), any(), any(), any(), any(), BoardBrand.QUANTUM)
        }
    }

    @Test fun `what the controller refused a send with stands in the report`() = runTest {
        quantum(QuantumControllerState(lastFailure = QuantumCommandFailure.ROUTE_IN_USE), sent = false)
        LedLab.change { it.copy(quantumSeveralMessages = true) }
        assertFalse(projector.send(picture, ledMap) { true })
        assertEquals("ROUTE_IN_USE", LedLab.report.value!!.boardAnswer)
    }
}
