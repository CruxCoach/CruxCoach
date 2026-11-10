package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A caller that leaves while its frame is on the way (PaintBox closed during a
 * send) must not cut the frame short: the box would read the next send into the
 * torn one. Fake GATT writes are acknowledged only when the test says so.
 *
 * The same holds for a frame of the board-size check, which goes out without
 * the "sending" state and without touching the idle countdown: its sheet lights
 * again whenever the link turns ready, so its own frame must not make it do so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardBleFrameCancelTest {

    /** @param idleScope where the connection runs its idle countdown; without one it never runs. */
    private class Link(brand: BoardBrand, idleScope: CoroutineScope? = null) {
        val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
        val board = DiscoveredBoard("Test board", "123", 3, "00:11:22:33:44:55", -40, brand)
        val writes = mutableListOf<ByteArray>()
        private var unacknowledged = 0
        private val gatt = mockk<BluetoothGatt>(relaxed = true)
        private val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 8, 16)
        private val callback: BluetoothGattCallback

        init {
            get<CoroutineScope>("scope").cancel()
            idleScope?.let { set("scope", it) }
            set("currentBoard", board)
            set("gatt", gatt)
            set("writeCharacteristic", characteristic)
            get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
            get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = brand
            set("encoder", BoardPacketEncoder(3, BoardPacketEncoder.ledsPerHoldFor(brand)))
            get<BoardCleaningSession>("cleaning").attach(BoardCleaningStorage.key(board))
            callback = get("gattCallback")
            every { gatt.writeCharacteristic(any()) } answers {
                writes += characteristic.value.copyOf()
                unacknowledged++
                true
            }
        }

        /** Acknowledges the write in flight, as the box would a moment later. */
        fun acknowledge() {
            if (unacknowledged == 0) return
            unacknowledged--
            callback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        }

        private fun set(name: String, value: Any) {
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> get(name: String): T =
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection) as T

        fun bytes(): ByteArray = writes.flatMap { it.toList() }.toByteArray()

        /** The idle countdown the connection holds right now. */
        fun idleCountdown(): Job? = get("disconnectJob")
    }

    /** Starts [send], leaves after the first write, then lets the box answer. */
    private fun TestScope.leaveDuring(link: Link, send: suspend () -> Unit) =
        sendThrough(link, leave = true, send = send)

    /**
     * Starts [send]. With its first write on the way it runs [onTheWay] and
     * leaves if [leave]; then it lets the box answer until the send is over.
     */
    private fun TestScope.sendThrough(
        link: Link,
        leave: Boolean,
        onTheWay: () -> Unit = {},
        send: suspend () -> Unit,
    ) {
        val job = launch { send() }
        runCurrent()
        assertEquals("the first write is on the way", 1, link.writes.size)
        onTheWay()
        if (leave) job.cancel()
        repeat(500) {
            if (job.isCompleted) return@repeat
            link.acknowledge()
            advanceTimeBy(150)
            runCurrent()
        }
        assertTrue(job.isCompleted)
    }

    @Test fun `an Aurora picture is finished when its sender leaves`() = runTest {
        val link = Link(BoardBrand.KILTER)
        val leds = (1..30).map { it to 0xff }
        val expected = BoardPacketEncoder(3, 2).encodeClimb(leds)
        assertTrue("a frame of several writes", expected.size > 2)
        leaveDuring(link) { link.connection.sendRawLeds(leds, BoardBrand.KILTER) }
        assertArrayEquals(expected.flatMap { it.toList() }.toByteArray(), link.bytes())
        assertEquals(ConnectionState.CONNECTED, link.connection.connectionState.value)
    }

    @Test fun `a MoonBoard picture is finished when its sender leaves`() = runTest {
        val link = Link(BoardBrand.MOONBOARD)
        val frames = (1..40).joinToString("") { "p${it}r43" }
        val expected = MoonBoardFrameEncoder.encode(frames, MoonBoardVariant.MOONBOARD_2016, MoonBoardLedMode.BELOW)
        assertTrue("a frame of several writes", expected.size > 2 * BoardPacketEncoder.BLE_MTU)
        leaveDuring(link) {
            link.connection.sendMoonBoardClimb(frames, MoonBoardVariant.MOONBOARD_2016, MoonBoardLedMode.BELOW)
        }
        assertArrayEquals(expected, link.bytes())
        assertEquals(ConnectionState.CONNECTED, link.connection.connectionState.value)
    }

    @Test fun `a size-check frame keeps the link ready and is finished when its sender leaves`() = runTest {
        val idle = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val link = Link(BoardBrand.KILTER, idle)
        link.connection.autoDisconnectSeconds = 30
        val countdown = link.idleCountdown()
        assertTrue("a countdown runs", countdown?.isActive == true)
        val leds = (1..30).map { it to 0xff }
        val expected = BoardPacketEncoder(3, 2).encodeClimb(leds)
        assertTrue("a frame of several writes", expected.size > 2)

        var stateOnTheWay: ConnectionState? = null
        sendThrough(link, leave = true, onTheWay = { stateOnTheWay = link.connection.connectionState.value }) {
            link.connection.sendRawFrame(leds, BoardBrand.KILTER)
        }

        assertEquals(ConnectionState.CONNECTED, stateOnTheWay)
        assertArrayEquals(expected.flatMap { it.toList() }.toByteArray(), link.bytes())
        assertEquals(ConnectionState.CONNECTED, link.connection.connectionState.value)
        assertSame("the countdown from before goes on", countdown, link.idleCountdown())
        assertTrue(link.connection.idleDisconnectArmed)
        idle.cancel()
    }

    @Test fun `a picture shows the link as sending and starts the idle countdown again`() = runTest {
        val idle = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val link = Link(BoardBrand.KILTER, idle)
        link.connection.autoDisconnectSeconds = 30
        val countdown = link.idleCountdown()
        assertTrue("a countdown runs", countdown?.isActive == true)
        val leds = (1..30).map { it to 0xff }

        var stateOnTheWay: ConnectionState? = null
        var armedOnTheWay = true
        var sent = false
        sendThrough(
            link,
            leave = false,
            onTheWay = {
                stateOnTheWay = link.connection.connectionState.value
                armedOnTheWay = link.connection.idleDisconnectArmed
            },
        ) { sent = link.connection.sendRawLeds(leds, BoardBrand.KILTER) }

        assertTrue(sent)
        assertEquals(ConnectionState.SENDING, stateOnTheWay)
        assertFalse("no idle disconnect in the middle of a frame", armedOnTheWay)
        assertEquals(ConnectionState.CONNECTED, link.connection.connectionState.value)
        assertNotSame("a new countdown, not the one from before", countdown, link.idleCountdown())
        assertTrue(link.connection.idleDisconnectArmed)
        idle.cancel()
    }
}
