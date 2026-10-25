package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardPacketEncoder
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LED-animation frames on a real connection with acknowledged fake GATT writes:
 * they give way to everything that owns the wall and never repaint a climb the
 * wall no longer shows. No radio or controller is claimed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardBleAnimationFrameTest {

    private class Link(brand: BoardBrand = BoardBrand.KILTER, apiLevel: Int = 3) {
        val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
        val board = DiscoveredBoard("Test board", "123", apiLevel, "00:11:22:33:44:55", -40, brand)
        val writes = mutableListOf<ByteArray>()
        var status = BluetoothGatt.GATT_SUCCESS
        private val gatt = mockk<BluetoothGatt>(relaxed = true)
        private val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 8, 16)

        init {
            get<CoroutineScope>("scope").cancel()
            set("currentBoard", board)
            set("gatt", gatt)
            set("writeCharacteristic", characteristic)
            get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
            get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = brand
            set("encoder", BoardPacketEncoder(apiLevel, BoardPacketEncoder.ledsPerHoldFor(brand)))
            get<BoardCleaningSession>("cleaning").attach(BoardCleaningStorage.key(board))
            val callback = get<BluetoothGattCallback>("gattCallback")
            every { gatt.writeCharacteristic(any()) } answers {
                writes += characteristic.value.copyOf()
                callback.onCharacteristicWrite(gatt, characteristic, status)
                true
            }
        }

        private fun set(name: String, value: Any) {
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> get(name: String): T =
            BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection) as T

        fun bytes(): ByteArray = writes.flatMap { it.toList() }.toByteArray()

        fun log(vararg positions: Set<Int>) = connection.updateCleaningLog(
            connection.cleaningTicket(),
            LocalDate.now().toString(),
            positions.map { LoggedHolds(LocalDateTime.now().minusMinutes(1), it) },
        )
    }

    private val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))
    private val ledMap = mapOf(1 to 5, 2 to 8)

    @Test fun `a frame of the lit climb goes out`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        val lit = assertNotNullFrame(connection)
        link.writes.clear()
        val frame = listOf(5 to 0x03, 8 to 0x03)
        assertEquals(FrameSendResult.SENT, connection.sendAnimationLeds(lit, frame, BoardBrand.KILTER))
        assertArrayEquals(BoardPacketEncoder(3).encodeClimb(frame).flatMap { it.toList() }.toByteArray(), link.bytes())
    }

    @Test fun `a frame made from a climb the wall no longer shows is dropped`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        val old = assertNotNullFrame(connection)
        assertTrue(connection.sendClimb(listOf(BoardHold(1, 12)), ledMap))
        link.writes.clear()
        assertEquals(FrameSendResult.DEFERRED, connection.sendAnimationLeds(old, listOf(5 to 0x03), BoardBrand.KILTER))
        assertTrue("the newer climb stays on the wall", link.writes.isEmpty())
        assertTrue(connection.clearBoard())
        assertNull(connection.litFrame.value)
    }

    @Test fun `hold cleaning owns the wall until it is finished`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        val lit = assertNotNullFrame(connection)
        link.log(setOf(5, 8))
        assertTrue(connection.startCleaning(link.board.address))
        link.writes.clear()
        assertEquals(FrameSendResult.DEFERRED, connection.sendAnimationLeds(lit, listOf(5 to 0x03), BoardBrand.KILTER))
        assertTrue("the holds to clean stay lit", link.writes.isEmpty())
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = false))
        assertEquals("cleaning restores the climb the effect was moving", lit, connection.litFrame.value)
        assertEquals(FrameSendResult.SENT, connection.sendAnimationLeds(lit, listOf(5 to 0x03), BoardBrand.KILTER))
    }

    @Test fun `relayed and failed sends leave nothing for an effect to repaint`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        assertTrue(connection.sendRawChunks(BoardPacketEncoder(3).encodeClimb(listOf(9 to 0x1c))))
        assertNull("relayed bytes are not a climb the effect knows", connection.litFrame.value)

        assertTrue(connection.sendClimb(holds, ledMap))
        link.status = BluetoothGatt.GATT_FAILURE
        assertTrue(!connection.sendClimb(listOf(BoardHold(1, 12)), ledMap))
        assertNull("after a failed write the wall's state is unknown", connection.litFrame.value)
    }

    @Test fun `another board or an API-2 frame that would show dark gets no frame`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        val lit = assertNotNullFrame(connection)
        link.writes.clear()
        assertEquals(FrameSendResult.DEFERRED, connection.sendAnimationLeds(lit, listOf(5 to 0x03), BoardBrand.TENSION))

        val api2 = Link(apiLevel = 2)
        val many = (1..140).map { BoardHold(it, 12) }
        val map = (1..140).associateWith { it }
        assertTrue(api2.connection.sendClimb(many, map))
        val api2Lit = assertNotNullFrame(api2.connection)
        api2.writes.clear()
        val white = (1..140).map { it to 0xFF }
        assertEquals(FrameSendResult.DEFERRED, api2.connection.sendAnimationLeds(api2Lit, white, BoardBrand.KILTER))
        assertTrue(link.writes.isEmpty() && api2.writes.isEmpty())
    }

    @Test fun `an idle release puts the climb's own colours back only after an effect frame`() = runTest {
        val link = Link()
        val connection = link.connection
        assertTrue(connection.sendClimb(holds, ledMap))
        val staticBytes = link.bytes()
        val lit = assertNotNullFrame(connection)
        link.writes.clear()
        connection.restoreStaticBeforeIdleRelease()
        assertTrue("a static climb is left as it is", link.writes.isEmpty())

        assertEquals(FrameSendResult.SENT, connection.sendAnimationLeds(lit, listOf(5 to 0x01, 8 to 0x01), BoardBrand.KILTER))
        link.writes.clear()
        connection.restoreStaticBeforeIdleRelease()
        assertArrayEquals(staticBytes, link.bytes())
        link.writes.clear()
        connection.restoreStaticBeforeIdleRelease()
        assertTrue("once restored, nothing more to do", link.writes.isEmpty())
    }

    private fun assertNotNullFrame(connection: BoardBleConnection): LitFrame {
        val frame = connection.litFrame.value
        assertNotNull(frame)
        return frame!!
    }
}
