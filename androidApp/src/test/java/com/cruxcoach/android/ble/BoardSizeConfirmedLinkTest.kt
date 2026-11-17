package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.os.Looper
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The size the board-size check confirmed, on a real connection with a fake GATT: it outlives
 * the check, as the board picker saves it later, but not the link. No radio is claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoardSizeConfirmedLinkTest {
    private val connection = BoardBleConnection(RuntimeEnvironment.getApplication())
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val confirmed = ActiveBoardSelection(BoardBrand.KILTER, 1, 27)

    init {
        // A unit-test link has no platform lifecycle/watchdog to run.
        get<CoroutineScope>("scope").cancel()
        BoardBleConnection::class.java.getDeclaredField("gatt").apply { isAccessible = true }.set(connection, gatt)
        state().value = ConnectionState.CONNECTED
    }

    private fun state() = get<MutableStateFlow<ConnectionState>>("_connectionState")

    @Suppress("UNCHECKED_CAST")
    private fun <T> get(name: String): T =
        BoardBleConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection) as T

    @Test fun `a confirmed size stays after the check lets the link go`() {
        connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK)
        connection.noteSizeConfirmed(confirmed)
        connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) // the sheet closed; the picker saves later
        assertEquals(confirmed, connection.sizeConfirmedOnBoard)
    }

    @Test fun `disconnecting takes the confirmed size with the link`() {
        connection.noteSizeConfirmed(confirmed)
        connection.disconnect()
        assertNull(connection.sizeConfirmedOnBoard)
    }

    @Test fun `a link the board dropped takes the confirmed size with it`() {
        connection.noteSizeConfirmed(confirmed)
        val callback = get<BluetoothGattCallback>("gattCallback")
        callback.onConnectionStateChange(gatt, 0x08, BluetoothProfile.STATE_DISCONNECTED) // supervision timeout
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState.value)
        assertNull(connection.sizeConfirmedOnBoard)
    }

    @Test fun `a size confirmed once the link is gone is not kept for the next one`() {
        state().value = ConnectionState.DISCONNECTED
        connection.noteSizeConfirmed(confirmed)
        assertNull(connection.sizeConfirmedOnBoard)
    }
}
