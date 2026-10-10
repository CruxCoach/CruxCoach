package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real connection/encoders with acknowledged fake GATT writes. No radio or
 * controller is claimed by these tests. Reflection only replaces link setup. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardCleaningConnectionTest {
    private class Link(brand: BoardBrand = BoardBrand.KILTER) {
        val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
        val board = DiscoveredBoard("Test board", "123", 3, "00:11:22:33:44:55", -40, brand)
        val writes = mutableListOf<ByteArray>()
        var status = BluetoothGatt.GATT_SUCCESS
        private val gatt = mockk<BluetoothGatt>(relaxed = true)
        private val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 8, 16)

        init {
            // A unit-test link has no platform lifecycle/watchdog to run.
            get<CoroutineScope>("scope").cancel()
            set("currentBoard", board)
            set("gatt", gatt)
            set("writeCharacteristic", characteristic)
            get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
            get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = brand
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
    }

    @Test fun `cleaning fences every normal write and restores exact previous packet`() = runTest {
        val link = Link()
        val connection = link.connection
        val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))
        assertTrue(connection.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        val previous = link.bytes()
        assertEquals(2, connection.cleaningState.value.holdCount)
        link.writes.clear()
        assertFalse(connection.startCleaning("another-controller"))
        assertTrue(link.writes.isEmpty())
        assertTrue(connection.startCleaning(link.board.address))
        val count = link.writes.size
        assertFalse(connection.sendClimb(holds, mapOf(1 to 99, 2 to 100)))
        assertFalse(connection.sendRawChunks(listOf(byteArrayOf(99))))
        assertFalse(connection.sendRawLeds(listOf(99 to 255)))
        assertFalse(connection.clearBoard())
        assertEquals(count, link.writes.size)
        assertEquals(2, connection.cleaningState.value.holdCount)
        link.writes.clear()
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = false))
        assertArrayEquals(previous, link.bytes())
        assertEquals(2, connection.cleaningState.value.holdCount)
    }

    @Test fun `failed transmissions and editor previews do not enter the daily collection`() = runTest {
        val link = Link()
        val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))
        link.status = BluetoothGatt.GATT_FAILURE
        assertFalse(link.connection.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        assertEquals(0, link.connection.cleaningState.value.holdCount)
        link.status = BluetoothGatt.GATT_SUCCESS
        assertTrue(link.connection.sendClimb(holds, mapOf(1 to 5, 2 to 8), collectForCleaning = false))
        assertEquals(0, link.connection.cleaningState.value.holdCount)
        assertTrue(link.connection.sendClimb(holds, mapOf(1 to 5)))
        assertEquals(1, link.connection.cleaningState.value.holdCount)
    }

    @Test fun `MoonBoard dual LEDs count holds once and restore original roles and LED mode`() = runTest {
        val link = Link(BoardBrand.MOONBOARD)
        val connection = link.connection
        assertTrue(connection.sendMoonBoardClimb("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016, MoonBoardLedMode.BOTH))
        val original = link.bytes()
        assertEquals(2, connection.cleaningState.value.holdCount)
        link.writes.clear()
        assertTrue(connection.startCleaning(link.board.address))
        assertEquals("l#P1,P33#", link.bytes().decodeToString())
        link.writes.clear()
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = true))
        assertArrayEquals(original, link.bytes())
        assertEquals(0, connection.cleaningState.value.holdCount)
    }
}
