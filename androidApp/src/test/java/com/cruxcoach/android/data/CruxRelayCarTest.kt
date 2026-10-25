package com.cruxcoach.android.data

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass.Device
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Sharing ends when the phone connects to a car (owner decision 2026-10-25). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CruxRelayCarTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val car = "AA:BB:CC:00:00:01"

    @Before
    fun clearRecoveryRecord() {
        context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class Sharing(
        val manager: CruxRelayManager,
        val server: RelayGattServer,
        val connectionState: MutableStateFlow<ConnectionState>,
        val adapterName: () -> String?,
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
        coEvery { server.start() } returns true
        every { server.climbs } returns MutableSharedFlow<RelayInboundClimb>()
        every { server.connectionEvents } returns MutableSharedFlow<GattConnectionEvent>()
        every { server.getConnectedCount() } returns 0
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
        return Sharing(manager, server, connectionState) { name }
    }

    private suspend fun Sharing.awaitSettled() =
        manager.state.first { it.advertising || it.error != null || !it.enabled }

    @Test
    fun `a car ends sharing and the phone gets its name back`() = runTest {
        val sharing = sharing()
        val started = sharing.manager.state.first { it.advertising || it.error != null }
        assertTrue("Relay startup failed: $started", started.advertising)
        assertEquals("CruxCoach·Kilter Board@3", sharing.adapterName())

        sharing.manager.onBluetoothDevice(car, Device.AUDIO_VIDEO_CAR_AUDIO, connected = true)
        runCurrent()
        val stopped = sharing.manager.state.first { !it.enabled && !it.advertising }

        assertEquals(RelayError.STOPPED_FOR_CAR, stopped.error)
        assertEquals("Test phone", sharing.adapterName())
        coVerify(exactly = 1) { sharing.server.stop() }
    }

    @Test
    fun `a hands-free car kit ends sharing too`() = runTest {
        val sharing = sharing()
        sharing.manager.state.first { it.advertising || it.error != null }

        sharing.manager.onBluetoothDevice(car, Device.AUDIO_VIDEO_HANDSFREE, connected = true)
        runCurrent()

        val stopped = sharing.manager.state.first { !it.enabled && !it.advertising }
        assertEquals(RelayError.STOPPED_FOR_CAR, stopped.error)
    }

    @Test
    fun `headphones and devices of unknown class change nothing`() = runTest {
        val sharing = sharing()
        sharing.manager.state.first { it.advertising || it.error != null }

        sharing.manager.onBluetoothDevice("AA:BB:CC:00:00:02", Device.AUDIO_VIDEO_HEADPHONES, true)
        sharing.manager.onBluetoothDevice("AA:BB:CC:00:00:03", Device.AUDIO_VIDEO_WEARABLE_HEADSET, true)
        sharing.manager.onBluetoothDevice("AA:BB:CC:00:00:04", Device.AUDIO_VIDEO_LOUDSPEAKER, true)
        sharing.manager.onBluetoothDevice("AA:BB:CC:00:00:05", null, true)
        runCurrent()

        assertTrue(sharing.manager.state.value.enabled)
        assertTrue(sharing.manager.state.value.advertising)
        assertNull(sharing.manager.state.value.error)
        assertEquals("CruxCoach·Kilter Board@3", sharing.adapterName())
    }

    @Test
    fun `no restart on its own while the car is connected, as usual once it is gone`() = runTest {
        val sharing = sharing()
        sharing.manager.state.first { it.advertising || it.error != null }
        sharing.manager.onBluetoothDevice(car, Device.AUDIO_VIDEO_CAR_AUDIO, connected = true)
        runCurrent()
        sharing.manager.state.first { !it.enabled && !it.advertising }

        // Board link drops and comes back while the car is still connected.
        sharing.connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()
        sharing.connectionState.value = ConnectionState.CONNECTED
        runCurrent()
        assertFalse(sharing.manager.state.value.enabled)
        coVerify(exactly = 1) { sharing.server.start() }
        assertEquals("Test phone", sharing.adapterName())

        // The car is gone: the next board connection shares as usual.
        sharing.manager.onBluetoothDevice(car, null, connected = false)
        sharing.connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()
        sharing.connectionState.value = ConnectionState.CONNECTED
        runCurrent()
        val again = sharing.manager.state.first { it.advertising || it.error != null }
        assertTrue("Relay restart failed: $again", again.advertising)
        coVerify(exactly = 2) { sharing.server.start() }
    }

    @Test
    fun `the user can share again on purpose while the car is connected`() = runTest {
        val sharing = sharing()
        sharing.manager.state.first { it.advertising || it.error != null }
        sharing.manager.onBluetoothDevice(car, Device.AUDIO_VIDEO_CAR_AUDIO, connected = true)
        runCurrent()
        sharing.manager.state.first { !it.enabled && !it.advertising }

        sharing.manager.requestEnable()
        runCurrent()

        val again = sharing.manager.state.first { it.advertising || it.error != null }
        assertTrue("Relay restart failed: $again", again.advertising)
        assertNull(again.error)
    }

    @Test
    fun `a car while not sharing only remembers it`() = runTest {
        val sharing = sharing()
        sharing.manager.state.first { it.advertising || it.error != null }
        sharing.manager.disable()
        runCurrent()
        sharing.awaitSettled()

        sharing.manager.onBluetoothDevice(car, Device.AUDIO_VIDEO_CAR_AUDIO, connected = true)
        runCurrent()

        assertNull(sharing.manager.state.value.error)
    }
}
