package com.cruxcoach.android.data

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.android.ble.RelayInboundClimb
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The host's own send must not end sharing. While the host writes a climb the board link reads SENDING; taken for a
 * lost board, every climb the host lit stopped the relay and dropped its guests, and with no guest left the host's
 * send mode fell back to the single-connection one, so swiping lit the wall again (two phones, 2026-11-21: 9 sends
 * by the host, 9 relay stops).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CruxRelayHostSendTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val guest = "AA:BB:CC:00:00:02"

    @Before
    fun clearRecoveryRecord() {
        context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class Sharing(
        val manager: CruxRelayManager,
        val server: RelayGattServer,
        val connectionState: MutableStateFlow<ConnectionState>,
        val clientEvents: MutableSharedFlow<GattConnectionEvent>,
        val setGuests: (Int) -> Unit,
    )

    /** A manager on a connected single-connection board that follows the connection. */
    private fun TestScope.sharing(): Sharing {
        Shadows.shadowOf(context as Application)
            .grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val board = DiscoveredBoard(
            displayName = "Kilter Board",
            serial = "123",
            apiLevel = 3,
            address = "00:11:22:33:44:55",
            rssi = -40,
            boardBrand = BoardBrand.KILTER,
            advertisesWhileConnected = false,
        )
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns MutableStateFlow(board)
        every { connection.connectedBoard } returns board
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(false)
        every { preferences.relayDisclosureSeen } returns flowOf(true)
        val server = mockk<RelayGattServer>(relaxed = true)
        val clientEvents = MutableSharedFlow<GattConnectionEvent>()
        var guests = 0
        coEvery { server.start() } returns true
        every { server.climbs } returns MutableSharedFlow<RelayInboundClimb>()
        every { server.connectionEvents } returns clientEvents
        every { server.getConnectedCount() } answers { guests }
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        every { advertiser.startRelayAdvertising() } returns "started"
        coEvery { advertiser.awaitRelayAdvertisingStart() } returns
            AdvertisingSetCallback.ADVERTISE_SUCCESS
        var name: String? = "Test phone"
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } answers { name }
        every { adapter.setName(any()) } answers {
            name = firstArg()
            true
        }
        val manager = CruxRelayManager(
            context = context,
            relayServer = server,
            advertiser = advertiser,
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
            adapterProvider = { adapter },
        )
        runCurrent()
        return Sharing(manager, server, connectionState, clientEvents) { guests = it }
    }

    /** Sharing runs and one guest is connected through it. */
    private suspend fun TestScope.sharingWithGuest(): Sharing {
        val sharing = sharing()
        val started = sharing.manager.state.first { it.advertising || it.error != null }
        assertTrue("Relay startup failed: $started", started.advertising)
        sharing.setGuests(1)
        sharing.clientEvents.emit(GattConnectionEvent.Connected(guest))
        runCurrent()
        // A new guest restarts advertising; wait until the relay has settled again.
        sharing.manager.state.first { it.advertising && it.clientCount == 1 }
        return sharing
    }

    @Test
    fun `the host's own send keeps the relay and its guest`() = runTest {
        val sharing = sharingWithGuest()

        sharing.connectionState.value = ConnectionState.SENDING
        runCurrent()
        assertTrue("sharing ended while the host was sending", sharing.manager.state.value.enabled)
        sharing.connectionState.value = ConnectionState.CONNECTED
        runCurrent()

        val after = sharing.manager.state.value
        assertTrue("sharing did not survive the send: $after", after.enabled && after.advertising)
        assertEquals(1, after.clientCount)
        coVerify(exactly = 0) { sharing.server.stop() }
        coVerify(exactly = 1) { sharing.server.start() }
    }

    @Test
    fun `a board lost in the middle of a send still ends sharing`() = runTest {
        val sharing = sharingWithGuest()

        sharing.connectionState.value = ConnectionState.SENDING
        runCurrent()
        sharing.connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()
        val stopped = sharing.manager.state.first { !it.enabled && !it.advertising }

        assertEquals(0, stopped.clientCount)
        coVerify(exactly = 1) { sharing.server.stop() }
    }
}
