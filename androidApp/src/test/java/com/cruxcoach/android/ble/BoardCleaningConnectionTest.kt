package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real connection/encoders with acknowledged fake GATT writes. No radio or
 * controller is claimed by these tests. Reflection only replaces link setup. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardCleaningConnectionTest {
    private class Link(brand: BoardBrand = BoardBrand.KILTER, apiLevel: Int = 3, pacingMs: Long = 0L) {
        val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
        val board = DiscoveredBoard("Test board", "123", apiLevel, "00:11:22:33:44:55", -40, brand)
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
            set("writePacingMs", pacingMs)
            set("encoder", BoardPacketEncoder(apiLevel, BoardPacketEncoder.ledsPerHoldFor(brand)))
            // As connect() does: older controllers light their sections.
            get<BoardCleaningSession>("cleaning").attach(
                BoardCleaningStorage.key(board),
                if (brand.usesAuroraProtocol) {
                    BoardPacketEncoder.cleaningHoldLimit(apiLevel, BoardPacketEncoder.ledsPerHoldFor(brand))
                } else Int.MAX_VALUE,
            )
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

        /** Today's logbook as the cleaning view model hands it over. */
        fun log(vararg positions: Set<Int>) = connection.updateCleaningLog(
            connection.cleaningTicket(),
            LocalDate.now().toString(),
            positions.map { LoggedHolds(LocalDateTime.now().minusMinutes(1), it) },
        )
    }

    /** API-2 packets of [bytes]: type and positions per packet. */
    private fun api2Packets(bytes: ByteArray): List<Pair<Byte, List<Int>>> {
        val packets = mutableListOf<Pair<Byte, List<Int>>>()
        var offset = 0
        while (offset < bytes.size) {
            val length = bytes[offset + 1].toInt() and 0xff
            val positions = (offset + 5 until offset + 4 + length step 2).map { index ->
                assertTrue("every lit position stays visible", bytes[index + 1].toInt() and 0xfc != 0)
                (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0x03) shl 8)
            }
            packets += bytes[offset + 4] to positions
            offset += length + 5
        }
        return packets
    }

    @Test fun `cleaning fences every normal write and restores exact previous packet`() = runTest {
        val link = Link()
        val connection = link.connection
        val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))
        assertTrue(connection.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        val previous = link.bytes()
        link.log(setOf(5, 8))
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

    @Test fun `sent, resent and previewed climbs never enter the collection`() = runTest {
        val link = Link()
        val holds = listOf(BoardHold(1, 12), BoardHold(2, 13))
        assertTrue(link.connection.sendClimb(holds, mapOf(1 to 5, 2 to 8)))
        assertTrue(link.connection.resendWithColors(mapOf(12 to 0x1c)))
        link.log()
        assertEquals(0, link.connection.cleaningState.value.holdCount)
        assertTrue(link.connection.sendClimb(holds, mapOf(1 to 5)))
        assertEquals(0, link.connection.cleaningState.value.holdCount)
        assertFalse(link.connection.startCleaning(link.board.address))
        link.log(setOf(5))
        assertEquals(1, link.connection.cleaningState.value.holdCount)
    }

    @Test fun `a logbook read for the previous connection is dropped`() = runTest {
        val link = Link()
        val ticket = link.connection.cleaningTicket()
        link.connection.disconnect()
        link.connection.updateCleaningLog(ticket, LocalDate.now().toString(), listOf(LoggedHolds(LocalDateTime.now(), setOf(1))))
        assertEquals(0, link.connection.cleaningState.value.holdCount)
        assertFalse(link.connection.cleaningState.value.available)
    }

    @Test fun `API-2 sections stay single packets at the link's pacing and the last one restores the climb`() = runTest {
        val link = Link(apiLevel = 2, pacingMs = 100L)
        val connection = link.connection
        assertTrue(connection.sendClimb(listOf(BoardHold(1, 12)), mapOf(1 to 7)))
        val previous = link.bytes()
        link.log((0 until 120).toSet(), (100 until 200).toSet())
        assertEquals(200, connection.cleaningState.value.holdCount)
        assertEquals(127, connection.cleaningState.value.holdLimit)

        link.writes.clear()
        val started = testScheduler.currentTime
        assertTrue(connection.startCleaning(link.board.address))
        assertEquals((link.writes.size - 1) * 100L, testScheduler.currentTime - started)
        assertEquals(listOf(BoardPacketEncoder.API2_ONLY to (0 until 127).toList()), api2Packets(link.bytes()))
        assertEquals(127, connection.cleaningState.value.displayedHoldCount)

        link.writes.clear()
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = true))
        assertEquals(listOf(BoardPacketEncoder.API2_ONLY to (127 until 200).toList()), api2Packets(link.bytes()))
        assertTrue(connection.cleaningState.value.active)
        assertEquals(73, connection.cleaningState.value.holdCount)

        link.writes.clear()
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = true))
        assertArrayEquals(previous, link.bytes())
        assertFalse(connection.cleaningState.value.active)
        assertEquals(0, connection.cleaningState.value.holdCount)
        assertTrue(connection.cleaningState.value.allCleaned)
    }

    @Test fun `MoonBoard dual LEDs count holds once, keep UART pacing and restore original roles and LED mode`() = runTest {
        val link = Link(BoardBrand.MOONBOARD)
        val connection = link.connection
        assertTrue(connection.sendMoonBoardClimb("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016, MoonBoardLedMode.BOTH))
        val original = link.bytes()
        link.log(MoonBoardFrameEncoder.cleaningPositions("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016))
        assertEquals(2, connection.cleaningState.value.holdCount)
        link.writes.clear()
        assertTrue(connection.startCleaning(link.board.address))
        assertEquals("l#P1,P33#", link.bytes().decodeToString())
        link.writes.clear()
        assertTrue(connection.finishCleaning(link.board.address, markCleaned = true))
        assertArrayEquals(original, link.bytes())
        assertEquals(0, connection.cleaningState.value.holdCount)

        link.log(MoonBoardFrameEncoder.cleaningPositions("p12r42p24r44", MoonBoardVariant.MOONBOARD_2016), (40 until 60).toSet())
        link.writes.clear()
        val started = testScheduler.currentTime
        assertTrue(connection.startCleaning(link.board.address))
        assertTrue(link.writes.size > 1)
        assertEquals((link.writes.size - 1) * 100L, testScheduler.currentTime - started)
        assertEquals(MoonBoardFrameEncoder.encodeCleaning((40 until 60).toSet()).decodeToString(), link.bytes().decodeToString())
    }
}
