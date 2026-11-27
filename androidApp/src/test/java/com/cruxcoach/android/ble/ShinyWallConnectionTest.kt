package com.cruxcoach.android.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Shiny Wall link rules: the write characteristic is found in whichever
 * service carries it, writes use the ack when offered, and no Aurora packet
 * reaches the wall's text parser. Property bits: WRITE = 8, WRITE_NO_RESPONSE = 4.
 */
class ShinyWallConnectionTest {

    private fun service(vararg chars: Pair<UUID, BluetoothGattCharacteristic>): BluetoothGattService {
        val service = mockk<BluetoothGattService>()
        every { service.getCharacteristic(any()) } returns null
        for ((uuid, characteristic) in chars) {
            every { service.getCharacteristic(uuid) } returns characteristic
        }
        return service
    }

    @Test
    fun `the write characteristic is found in any service`() {
        val shiny = mockk<BluetoothGattCharacteristic>()
        val other = mockk<BluetoothGattCharacteristic>()
        val services = listOf(
            service(UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb") to other),
            service(BoardBleUuids.SHINY_WALL_WRITE_CHAR to shiny),
        )
        assertSame(shiny, findCharacteristicInServices(services, BoardBleUuids.SHINY_WALL_WRITE_CHAR))
        assertNull(findCharacteristicInServices(services.take(1), BoardBleUuids.SHINY_WALL_WRITE_CHAR))
        assertNull(findCharacteristicInServices(emptyList(), BoardBleUuids.SHINY_WALL_WRITE_CHAR))
    }

    @Test
    fun `writes are paced like the controller's own app`() {
        assertEquals(100L, shinyWallGapBeforeMs("-".encodeToByteArray()))
        assertEquals(100L, shinyWallGapBeforeMs("+0002".encodeToByteArray()))
        assertEquals(50L, shinyWallGapBeforeMs("0040.".encodeToByteArray()))
        assertEquals(50L, shinyWallGapBeforeMs(ByteArray(0)))
    }

    @Test
    fun `Aurora packets never go to a Shiny Wall or a Quantum board`() {
        assertFalse(auroraPacketsAllowed(BoardBrand.SHINYWALL))
        assertFalse(auroraPacketsAllowed(BoardBrand.QUANTUM))
        assertTrue(auroraPacketsAllowed(BoardBrand.KILTER))
        assertTrue(auroraPacketsAllowed(BoardBrand.MOONBOARD))
        assertTrue(shinyWallCommandAllowed(BoardBrand.SHINYWALL))
        assertFalse(shinyWallCommandAllowed(BoardBrand.KILTER))
        assertFalse(shinyWallCommandAllowed(null))
    }

    @Test
    fun `Shiny Wall links keep their own pacing, not the Aurora gap`() {
        assertEquals(0L, auroraWritePacingMs(BoardBrand.SHINYWALL, 8))
        assertEquals(0L, auroraWritePacingMs(BoardBrand.SHINYWALL, 12, advertisesSerial = false))
    }

    @Test
    fun `Shiny Wall controllers are not offered a relay and keep their projection`() {
        val board = DiscoveredBoard("ShinyWall_STP_1", "1", 0, "AA:BB", -50, boardBrand = BoardBrand.SHINYWALL)
        assertEquals(
            com.cruxcoach.android.data.BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
            com.cruxcoach.android.data.BoardRelayPolicy.availability(board),
        )
        assertEquals(
            BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
            BoardControllerProfiles.forBoard(board).projectionLifetime,
        )
        assertFalse(BoardControllerProfiles.forBoard(board).relaySupported)
    }
}
