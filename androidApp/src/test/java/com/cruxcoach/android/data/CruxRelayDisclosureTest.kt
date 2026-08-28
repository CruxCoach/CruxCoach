package com.cruxcoach.android.data

import android.Manifest
import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSetCallback
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.RelayInboundClimb
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CruxRelayDisclosureTest {
    private val context: Context
        get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun board(
        address: String,
        advertisesWhileConnected: Boolean? = null,
    ) = DiscoveredBoard(
        displayName = "Kilter Board",
        serial = "123",
        apiLevel = 3,
        address = address,
        rssi = -40,
        boardBrand = BoardBrand.KILTER,
        advertisesWhileConnected = advertisesWhileConnected,
    )

    @Test
    fun `no relay transport starts before disclosure is answered`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        val connectedBoard = MutableStateFlow<DiscoveredBoard?>(board("00:11:22:33:44:55"))
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns connectedBoard
        every { connection.connectedBoard } answers { connectedBoard.value }
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(false)
        val server = mockk<RelayGattServer>(relaxed = true)
        every { server.getConnectedCount() } returns 0
        val manager = CruxRelayManager(
            context = context,
            relayServer = server,
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
        )
        runCurrent()

        manager.requestEnable()
        runCurrent()

        assertTrue(manager.state.value.pendingDisclosure)
        assertFalse(manager.state.value.enabled)
        coVerify(exactly = 0) { preferences.setRelayDisclosureSeen() }
        coVerify(exactly = 0) { server.start() }

    }

    @Test
    fun `cancelled automatic disclosure is not repeated after a board send`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        val connectedBoard = MutableStateFlow<DiscoveredBoard?>(board("00:11:22:33:44:55"))
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns connectedBoard
        every { connection.connectedBoard } answers { connectedBoard.value }
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(false)
        every { preferences.relayDisclosureSeen } returns flowOf(false)
        val manager = CruxRelayManager(
            context = context,
            relayServer = mockk<RelayGattServer>(relaxed = true),
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
        )
        runCurrent()
        assertTrue(manager.state.value.pendingDisclosure)

        manager.dismissDisclosure()
        connectionState.value = ConnectionState.SENDING
        runCurrent()
        connectionState.value = ConnectionState.CONNECTED
        runCurrent()

        assertFalse(manager.state.value.pendingDisclosure)

        // A deliberate action remains able to ask again without reconnecting.
        manager.requestEnable()
        runCurrent()
        assertTrue(manager.state.value.pendingDisclosure)

        // Cancelling again is scoped only to this connection. A real
        // disconnect/reconnect may offer automatic sharing once more.
        manager.dismissDisclosure()
        connectionState.value = ConnectionState.DISCONNECTED
        runCurrent()
        connectionState.value = ConnectionState.CONNECTED
        runCurrent()
        assertTrue(manager.state.value.pendingDisclosure)
    }

    @Test
    fun `answer persists but cannot authorize a replacement board`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        var connected = board("00:11:22:33:44:55")
        val connectedBoard = MutableStateFlow<DiscoveredBoard?>(connected)
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns connectedBoard
        every { connection.connectedBoard } answers { connected }
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(false)
        val server = mockk<RelayGattServer>(relaxed = true)
        every { server.getConnectedCount() } returns 0
        val manager = CruxRelayManager(
            context = context,
            relayServer = server,
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
        )
        runCurrent()
        manager.requestEnable()
        runCurrent()
        assertTrue(manager.state.value.pendingDisclosure)

        connected = board("AA:BB:CC:DD:EE:FF")
        manager.confirmDisclosureAndEnable()
        runCurrent()

        coVerify(exactly = 1) { preferences.setRelayDisclosureSeen() }
        assertFalse(manager.state.value.pendingDisclosure)
        assertFalse(manager.state.value.enabled)
        coVerify(exactly = 0) { server.start() }
        verify(exactly = 0) { connection.acquireKeepAlive(any()) }
    }

    @Test
    fun `multi-connect observation suppresses relay without an error`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        val connectedBoard = MutableStateFlow<DiscoveredBoard?>(
            board(
                address = "00:11:22:33:44:55",
                advertisesWhileConnected = true,
            ),
        )
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns connectedBoard
        every { connection.connectedBoard } answers { connectedBoard.value }
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(true)
        val server = mockk<RelayGattServer>(relaxed = true)
        val manager = CruxRelayManager(
            context = context,
            relayServer = server,
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
        )
        runCurrent()

        manager.requestEnable()
        runCurrent()

        assertFalse(manager.state.value.enabled)
        assertFalse(manager.state.value.pendingDisclosure)
        assertTrue(manager.state.value.error == null)
        coVerify(exactly = 0) { server.start() }
    }

    @Test
    fun `physical board disconnect always stops relay advertising`() = runTest {
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        val connectedBoard = MutableStateFlow<DiscoveredBoard?>(board("00:11:22:33:44:55"))
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoardDescriptor } returns connectedBoard
        every { connection.connectedBoard } answers { connectedBoard.value }

        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(true)
        val server = mockk<RelayGattServer>(relaxed = true)
        coEvery { server.start() } returns true
        every { server.climbs } returns MutableSharedFlow<RelayInboundClimb>()
        every { server.connectionEvents } returns MutableSharedFlow<GattConnectionEvent>()
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        every { advertiser.startRelayAdvertising() } returns "started"
        coEvery { advertiser.awaitRelayAdvertisingStart() } returns
            AdvertisingSetCallback.ADVERTISE_SUCCESS

        var adapterName = "Test phone"
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } answers { adapterName }
        every { adapter.setName(any()) } answers {
            adapterName = firstArg()
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

        manager.requestEnable()
        manager.state.first { it.advertising }
        verify(exactly = 1) { advertiser.startRelayAdvertising() }

        connectedBoard.value = null
        connectionState.value = ConnectionState.DISCONNECTED
        manager.state.first { !it.enabled && !it.advertising }

        verify(exactly = 1) { advertiser.stopRelayAdvertising() }
        coVerify(exactly = 1) { server.stop() }
        assertFalse(manager.state.value.enabled)
        assertFalse(manager.state.value.advertising)
        assertTrue(manager.state.value.advertisedName == null)
        assertTrue(adapterName == "Test phone")
    }

    @Test
    fun `adapter name restore waits for Android propagation before clearing recovery`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        every { connection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { connection.connectedBoardDescriptor } returns MutableStateFlow(null)
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(true)

        var adapterName = "CruxCoach·Kilter Board@3"
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } answers { adapterName }
        every { adapter.setName("Test phone") } answers {
            backgroundScope.launch {
                delay(200)
                adapterName = "Test phone"
            }
            true
        }
        val manager = CruxRelayManager(
            context = context,
            relayServer = mockk<RelayGattServer>(relaxed = true),
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
            adapterProvider = { adapter },
        )
        context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CruxRelayManager.KEY_NAME_DIRTY, true)
            .putString(CruxRelayManager.KEY_ORIGINAL_NAME, "Test phone")
            .commit()

        assertTrue(manager.restoreAdapterName())
        assertTrue(adapterName == "Test phone")
        assertFalse(
            context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
                .getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false),
        )
    }
}
