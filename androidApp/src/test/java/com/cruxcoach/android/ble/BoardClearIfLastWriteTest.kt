package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A clear for a write count, on a real connection with acknowledged fake GATT writes: what was
 * sent since stays on the board. No radio or controller is claimed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardClearIfLastWriteTest {

    private class Link {
        val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
        val board = DiscoveredBoard("Test board", "123", 3, "00:11:22:33:44:55", -40, BoardBrand.KILTER)
        val writes = mutableListOf<ByteArray>()
        private val gatt = mockk<BluetoothGatt>(relaxed = true)
        private val characteristic = BluetoothGattCharacteristic(UUID.randomUUID(), 8, 16)

        init {
            get<CoroutineScope>("scope").cancel()
            set("currentBoard", board)
            set("gatt", gatt)
            set("writeCharacteristic", characteristic)
            get<MutableStateFlow<ConnectionState>>("_connectionState").value = ConnectionState.CONNECTED
            get<MutableStateFlow<BoardBrand?>>("_connectedBoardBrand").value = BoardBrand.KILTER
            set("encoder", BoardPacketEncoder(3, BoardPacketEncoder.ledsPerHoldFor(BoardBrand.KILTER)))
            get<BoardCleaningSession>("cleaning").attach(BoardCleaningStorage.key(board))
            val callback = get<BluetoothGattCallback>("gattCallback")
            every { gatt.writeCharacteristic(any()) } answers {
                writes += characteristic.value.copyOf()
                callback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
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

    @Test fun `a clear for an earlier write leaves the frame sent since`() = runTest {
        val link = Link()
        val connection = link.connection
        val own = connection.sendRawFrame(listOf(5 to 0xFF), BoardBrand.KILTER)
        val other = connection.sendRawFrame(listOf(8 to 0xFF), BoardBrand.KILTER) // a session's climb
        assertTrue(own.done && other.done)
        link.writes.clear()

        assertEquals(BoardWrite.NONE, connection.clearBoardWrite(BoardBrand.KILTER, ifLastWrite = own.write))
        assertTrue("nothing was written", link.writes.isEmpty())
        assertEquals(other.write, connection.boardWrites.value)
    }

    @Test fun `a clear for the last write clears`() = runTest {
        val link = Link()
        val connection = link.connection
        val own = connection.sendRawFrame(listOf(5 to 0xFF), BoardBrand.KILTER)
        link.writes.clear()

        val cleared = connection.clearBoardWrite(BoardBrand.KILTER, ifLastWrite = own.write)
        assertTrue(cleared.done)
        assertEquals(connection.boardWrites.value, cleared.write)
        assertArrayEquals(BoardPacketEncoder(3).encodeClear().flatMap { it.toList() }.toByteArray(), link.bytes())
    }
}
