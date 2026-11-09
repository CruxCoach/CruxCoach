package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PaintBoxProjectionTest {
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val layers = mockk<BoardLayerManager>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val projector = PaintProjector(ble, layers, prefs)

    @Test fun `Aurora sends arbitrary colors at mapped addresses including an empty frame`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        coEvery { ble.sendRawLeds(any(), any()) } returns true
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = mapOf(10 to 224, 20 to 28))
        assertTrue(projector.send(doc, mapOf(10 to 3, 20 to 5)) { true })
        coVerify { ble.sendRawLeds(listOf(3 to 224, 5 to 28), BoardBrand.KILTER) }
        assertTrue(projector.send(doc.copy(colors = emptyMap()), emptyMap()) { true })
        coVerify { ble.sendRawLeds(emptyList(), BoardBrand.KILTER) }
    }
    @Test fun `missing LEDs or a different board never emit partial Aurora data`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = mapOf(1 to 28))
        assertTrue(runCatching { projector.send(doc, emptyMap()) { true } }.isFailure)
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.TENSION)
        assertTrue(runCatching { projector.send(doc, mapOf(1 to 1)) { true } }.isFailure)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any()) }
    }
    @Test fun `paused generation never reaches the transport`() = runTest {
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10))
        assertTrue(runCatching { projector.send(doc, emptyMap()) { false } }.isFailure)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any()) }
    }
    @Test fun `Quantum refuses oversized images and unknown foreign layers before mutation`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
        every { ble.connectedBoard } returns DiscoveredBoard("Quantum", "", 3, "AA:BB", -40, BoardBrand.QUANTUM)
        coEvery { ble.refreshQuantumState() } returns true
        val roster = MutableStateFlow(QuantumControllerState(players = listOf(QuantumActivePlayer("route", "foreign", 10, 0x00ff00))))
        every { ble.quantumControllerState } returns roster
        val doc = PaintDocument(board = PaintBoard("quantum", 9101, 9201), colors = (1..93).associateWith { 28 })
        assertTrue(runCatching { projector.send(doc, (1..93).associateWith { it }) { true } }.isFailure)
        coVerify(exactly = 0) { ble.refreshQuantumState() }
        assertTrue(runCatching { projector.send(doc.copy(colors = mapOf(1 to 28)), mapOf(1 to 1)) { true } }.isFailure)
        coVerify(exactly = 0) { ble.removeQuantumLayer(any(), any(), any()) }
        coVerify(exactly = 0) { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
    @Test fun `Quantum stops on partial failure and never uses the anonymous identity`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
        every { ble.connectedBoard } returns DiscoveredBoard("Quantum", "", 3, "AA:BB", -40, BoardBrand.QUANTUM)
        every { ble.quantumControllerState } returns MutableStateFlow(QuantumControllerState())
        every { layers.paintIdentity(any()) } answers { "00000000-0000-4000-8000-00000000000${firstArg<Int>() + 1}" }
        coEvery { ble.refreshQuantumState() } returns true
        coEvery { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        val doc = PaintDocument(board = PaintBoard("quantum", 9101, 9201), colors = mapOf(1 to 28, 2 to 31))
        assertFalse(projector.send(doc, mapOf(1 to 1, 2 to 2)) { true })
        coVerify(exactly = 1) { ble.sendClimb(any(), any(), any(), any(), neq(QuantumBoardPacketEncoder.ZERO_UUID), any(), any(), any(), BoardBrand.QUANTUM) }
    }
}
