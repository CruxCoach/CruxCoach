package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.CruxRelayState
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.data.SessionRole
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
    private val sessionState = MutableStateFlow(SessionQueueState())
    private val sessions = mockk<SessionQueueManager> { every { state } returns sessionState }
    private val relayState = MutableStateFlow(CruxRelayState())
    private val relay = mockk<CruxRelayManager> { every { state } returns relayState }
    private val projector = PaintProjector(ble, layers, prefs, sessions, relay)

    private fun kilter(apiLevel: Int = 3) {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { ble.connectedBoard } returns DiscoveredBoard("Kilter", "1", apiLevel, "AA:BB", -40, BoardBrand.KILTER)
        coEvery { ble.sendRawLeds(any(), any(), any()) } returns true
    }

    @Test fun `Aurora sends arbitrary colors at mapped addresses including an empty frame`() = runTest {
        kilter()
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = mapOf(10 to 224, 20 to 28))
        assertTrue(projector.send(doc, mapOf(10 to 3, 20 to 5)) { true })
        coVerify { ble.sendRawLeds(listOf(3 to 224, 5 to 28), BoardBrand.KILTER, BoardPacketEncoder.V2_PICTURE_MAX_POWER) }
        assertTrue(projector.send(doc.copy(colors = emptyMap()), emptyMap()) { true })
        coVerify { ble.sendRawLeds(emptyList(), BoardBrand.KILTER, any()) }
    }
    @Test fun `an API-3 picture never draws more than the box's start-up test`() = runTest {
        kilter()
        val ledMap = (1..90).associateWith { it }
        val third = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = (1..30).associateWith { 0xff })
        assertTrue(projector.send(third, ledMap) { true })
        assertFalse(projector.lastSendDimmed)
        coVerify { ble.sendRawLeds(match { leds -> leds.size == 30 && leds.all { it.second == 0xff } }, BoardBrand.KILTER, any()) }
        val wall = third.copy(colors = (1..90).associateWith { 0xff })
        assertTrue(projector.send(wall, ledMap) { true })
        assertTrue(projector.lastSendDimmed)
        coVerify { ble.sendRawLeds(match { leds -> leds.size == 90 && leds.all { it.second != 0xff } &&
            leds.sumOf { BoardPacketEncoder.v3ChannelLoad(it.second) } <= 90.0 }, BoardBrand.KILTER, any()) }
    }
    @Test fun `an API-2 picture that its power limit would show dark is refused`() = runTest {
        kilter(apiLevel = 2)
        val ledMap = (1..127).associateWith { it }
        val white = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = (1..127).associateWith { 0xff })
        val refused = runCatching { projector.send(white, ledMap) { true } }.exceptionOrNull() as? PaintProjectionException
        assertEquals(R.string.paintbox_too_bright, refused?.messageRes)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any(), any()) }
        assertTrue(projector.send(white.copy(colors = (1..20).associateWith { 0xff }), ledMap) { true })
        assertFalse("20 white holds fit the picture budget", projector.lastSendDimmed)
        // 30 white holds draw 18 W: the board budget would take them, a picture is dimmed.
        assertTrue(projector.send(white.copy(colors = (1..30).associateWith { 0xff }), ledMap) { true })
        assertTrue(projector.lastSendDimmed)
    }
    @Test fun `a session or board sharing refuses every frame`() = runTest {
        kilter()
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = mapOf(10 to 224))
        suspend fun refusal() = (runCatching { projector.send(doc, mapOf(10 to 3)) { true } }
            .exceptionOrNull() as? PaintProjectionException)?.messageRes
        sessionState.value = SessionQueueState(role = SessionRole.HOST)
        assertEquals(R.string.paintbox_session_active, refusal())
        assertEquals(R.string.paintbox_session_active, projector.boardBusy())
        sessionState.value = SessionQueueState(isConnecting = true)
        assertEquals(R.string.paintbox_session_active, refusal())
        sessionState.value = SessionQueueState()
        relayState.value = CruxRelayState(enabled = true)
        assertEquals(R.string.paintbox_relay_active, refusal())
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any(), any()) }
        relayState.value = CruxRelayState()
        assertNull(projector.boardBusy())
        assertTrue(projector.send(doc, mapOf(10 to 3)) { true })
    }
    @Test fun `an API-2 box gets at most one packet of holds`() = runTest {
        kilter(apiLevel = 2)
        val ledMap = (1..128).associateWith { it }
        val full = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = (1..128).associateWith { 28 })
        val refused = runCatching { projector.send(full, ledMap) { true } }.exceptionOrNull() as? PaintProjectionException
        assertEquals(R.string.paintbox_api2_limit, refused?.messageRes)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any(), any()) }
        assertTrue(projector.send(full.copy(colors = (1..127).associateWith { 28 }), ledMap) { true })
        coVerify(exactly = 1) { ble.sendRawLeds(match { it.size == 127 }, BoardBrand.KILTER, BoardPacketEncoder.V2_PICTURE_MAX_POWER) }
        // An API-3 box takes the whole wall.
        kilter(apiLevel = 3)
        assertTrue(projector.send(full, ledMap) { true })
    }
    @Test fun `missing LEDs or a different board never emit partial Aurora data`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10), colors = mapOf(1 to 28))
        assertTrue(runCatching { projector.send(doc, emptyMap()) { true } }.isFailure)
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.TENSION)
        assertTrue(runCatching { projector.send(doc, mapOf(1 to 1)) { true } }.isFailure)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any(), any()) }
    }
    @Test fun `paused generation never reaches the transport`() = runTest {
        val doc = PaintDocument(board = PaintBoard("kilter", 1, 10))
        assertTrue(runCatching { projector.send(doc, emptyMap()) { false } }.isFailure)
        coVerify(exactly = 0) { ble.sendRawLeds(any(), any(), any()) }
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
    @Test fun `a session that starts during a Quantum picture stops it before the next layer`() = runTest {
        every { ble.connectedBoardBrand } returns MutableStateFlow(BoardBrand.QUANTUM)
        every { ble.connectedBoard } returns DiscoveredBoard("Quantum", "", 3, "AA:BB", -40, BoardBrand.QUANTUM)
        every { ble.quantumControllerState } returns MutableStateFlow(QuantumControllerState())
        every { layers.paintIdentity(any()) } answers { "00000000-0000-4000-8000-00000000000${firstArg<Int>() + 1}" }
        coEvery { ble.refreshQuantumState() } returns true
        coEvery { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            sessionState.value = SessionQueueState(role = SessionRole.HOST)
            true
        }
        val doc = PaintDocument(board = PaintBoard("quantum", 9101, 9201), colors = mapOf(1 to 28, 2 to 31))
        val refused = runCatching { projector.send(doc, mapOf(1 to 1, 2 to 2)) { true } }.exceptionOrNull() as? PaintProjectionException
        assertEquals(R.string.paintbox_session_active, refused?.messageRes)
        coVerify(exactly = 1) { ble.sendClimb(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
