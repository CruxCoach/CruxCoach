package com.cruxcoach.app.playback

import com.cruxcoach.app.ble.BoardConnectionController
import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.logbook.MapKeyValueStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.data.repository.LedGridPoint
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Port of Android `BoardEasterAnimations` and the settings' easter play/stop. */
@OptIn(ExperimentalCoroutinesApi::class)
class EasterAnimationTest {
    /** An 11 × 11 LED grid, x and y 0..100 in steps of 10, LED position = row-major index. */
    private val grid: List<LedGridPoint> = (0..10).flatMap { row ->
        (0..10).map { col -> LedGridPoint(placementId = (row * 11 + col).toLong(), x = col * 10L, y = row * 10L, ledPosition = (row * 11 + col).toLong()) }
    }

    private fun led(col: Int, row: Int) = row * 11 + col

    @Test
    fun `the egg is eight frames of a pastel egg wider at the bottom than at the top`() {
        val frames = EasterEggFrames.easterEgg(grid)
        assertEquals(8, frames.size)
        val first = frames.first().toMap()
        assertTrue(led(5, 5) in first, "the centre is inside the egg")
        assertFalse(led(0, 0) in first, "corners stay dark")
        assertFalse(led(5, 0) in first, "the bottom row lies below the egg (80 % of the height)")
        val bottomWidth = (0..10).count { led(it, 2) in first }
        val topWidth = (0..10).count { led(it, 8) in first }
        assertTrue(bottomWidth > topWidth, "bottom $bottomWidth vs top $topWidth")
        // Stripes run bottom to top through the frame's palette.
        assertEquals(BoardPacketEncoder.encodeColor(255, 150, 180), first[led(5, 1)], "first stripe of frame 1: pink")
        assertEquals(BoardPacketEncoder.encodeColor(130, 255, 180), first[led(5, 9)], "last stripe of frame 1: mint")
        assertTrue(frames.all { it.map { p -> p.first }.toSet() == first.keys }, "the shape stays, the colours shift")
        assertTrue(EasterEggFrames.easterEgg(emptyList()).isEmpty())
    }

    private class Rig(scope: kotlinx.coroutines.test.TestScope, kilter: Boolean = true, val linked: Boolean = true) {
        val keyValues = MapKeyValueStore()
        val sent = mutableListOf<List<Pair<Int, Int>>>()
        var clears = 0
        var sendOk = true
        val model = EasterAnimationModel(
            keyValues = keyValues,
            boardLinked = MutableStateFlow(linked),
            isBoardLinked = { linked },
            connectedIsKilter = { kilter },
            loadGrid = { (0..10).flatMap { r -> (0..10).map { c -> LedGridPoint((r * 11 + c).toLong(), c * 10L, r * 10L, (r * 11 + c).toLong()) } } },
            sendFrame = { leds -> sent += leds; sendOk },
            clearBoard = { clears++ },
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            pause = { delay(it) },
        )
    }

    @Test
    fun `seven taps unlock it once and for good`() = runTest {
        val rig = Rig(this)
        assertEquals(listOf(-1, -1, -1, 3, 2, 1, 0), (1..7).map { rig.model.tapAppInfo() })
        assertTrue(rig.model.currentState.unlocked)
        assertEquals("true", rig.keyValues.getString("easter_animations_unlocked"))
        assertEquals(-1, rig.model.tapAppInfo())
        assertTrue(Rig(this).also { it.keyValues.putString("easter_animations_unlocked", "true") }.let {
            EasterAnimationModel(it.keyValues, MutableStateFlow(true), { true }, { true }, { emptyList() }, { true }, {},
                backgroundScope, StandardTestDispatcher(testScheduler)).currentState.unlocked
        }, "the unlock is read back from the store")
    }

    @Test
    fun `it plays three rounds a frame every 250 ms then clears the board`() = runTest {
        val rig = Rig(this)
        rig.model.play()
        runCurrent()
        assertTrue(rig.sent.isEmpty(), "locked: nothing plays")
        repeat(7) { rig.model.tapAppInfo() }
        rig.model.play()
        runCurrent()
        assertTrue(rig.model.currentState.isAnimating)
        assertEquals(1, rig.sent.size)
        advanceTimeBy(250 * 8 + 10)
        assertEquals(9, rig.sent.size)
        advanceTimeBy(250L * 24)
        runCurrent()
        assertEquals(24, rig.sent.size)
        assertEquals(1, rig.clears)
        assertFalse(rig.model.currentState.isAnimating)
    }

    @Test
    fun `a failed frame ends it and stop clears the board`() = runTest {
        val failing = Rig(this)
        repeat(7) { failing.model.tapAppInfo() }
        failing.sendOk = false
        failing.model.play()
        advanceTimeBy(5_000)
        assertEquals(1, failing.sent.size)
        assertEquals(0, failing.clears, "Android stops without clearing when a frame cannot be written")
        assertFalse(failing.model.currentState.isAnimating)

        val stopped = Rig(this)
        repeat(7) { stopped.model.tapAppInfo() }
        stopped.model.play()
        advanceTimeBy(600)
        stopped.model.stop()
        runCurrent()
        val sentAtStop = stopped.sent.size
        advanceTimeBy(5_000)
        assertEquals(sentAtStop, stopped.sent.size)
        assertEquals(1, stopped.clears)
        assertFalse(stopped.model.currentState.isAnimating)
    }

    @Test
    fun `only a connected Kilter board gets the egg`() = runTest {
        val tension = Rig(this, kilter = false)
        repeat(7) { tension.model.tapAppInfo() }
        tension.model.play()
        advanceTimeBy(5_000)
        assertTrue(tension.sent.isEmpty())
        val offline = Rig(this, linked = false)
        repeat(7) { offline.model.tapAppInfo() }
        offline.model.play()
        advanceTimeBy(5_000)
        assertTrue(offline.sent.isEmpty())
        assertFalse(offline.model.currentState.boardConnected)
    }

    @Test
    fun `raw frames are encoded for the connected board and refused elsewhere`() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        val controller = BoardConnectionController(central, backgroundScope, FixedClock(1_000))
        assertEquals(BoardSendResult.NOT_CONNECTED, controller.sendRawLeds(listOf(5 to 0x11)))
        controller.connect(DiscoveredBoard("Kilter Board", "A1", 3, "id-kilter", -50, BoardBrand.KILTER))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, controller.state.value.connection)
        assertEquals(BoardSendResult.BOARD_MISMATCH, controller.sendRawLeds(listOf(5 to 0x11), BoardBrand.TENSION))
        assertTrue(central.writes.isEmpty())
        assertEquals(BoardSendResult.OK, controller.sendRawLeds(listOf(5 to 0x11, 300 to 0x22), BoardBrand.KILTER))
        val expected = BoardPacketEncoder(3, 2).encodeClimb(listOf(5 to 0x11, 300 to 0x22))
        assertEquals(expected.map { it.toList() }, central.writes.map { it.bytes })
    }
}
