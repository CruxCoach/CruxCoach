package com.cruxcoach.android.data

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import io.mockk.verify
import io.mockk.verifyOrder
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

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CruxRelayNameRestoreTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearRecoveryRecord() {
        context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun seedDirty(original: String = "My phone") {
        context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CruxRelayManager.KEY_NAME_DIRTY, true)
            .putString(CruxRelayManager.KEY_ORIGINAL_NAME, original)
            .commit()
    }

    private fun TestScope.manager(
        nameGuard: RelayAdapterNameGuard? = null,
        adapter: () -> BluetoothAdapter?,
    ): CruxRelayManager {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        every { connection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { connection.connectedBoardDescriptor } returns MutableStateFlow(null)
        val preferences = mockk<UserPreferences>(relaxed = true)
        every { preferences.relayManualStart } returns flowOf(true)
        every { preferences.relayDisclosureSeen } returns flowOf(true)
        val server = mockk<RelayGattServer>(relaxed = true)
        every { server.getConnectedCount() } returns 0
        return CruxRelayManager(
            context = context,
            relayServer = server,
            advertiser = mockk<ClimbBleAdvertiser>(relaxed = true),
            bleConnection = connection,
            projectionCoordinator = mockk<BoardProjectionCoordinator>(relaxed = true),
            userPreferences = preferences,
            scope = backgroundScope,
            adapterProvider = adapter,
            nameGuard = nameGuard ?: RelayAdapterNameGuard(context, adapter, backgroundScope),
        )
    }

    private fun mutableAdapter(initialName: String?): Pair<BluetoothAdapter, () -> String?> {
        var name = initialName
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } answers { name }
        every { adapter.setName(any()) } answers {
            name = firstArg()
            true
        }
        return adapter to { name }
    }

    @Test
    fun `adapter unavailable keeps crash recovery record`() = runTest {
        seedDirty()

        manager { null }
        runCurrent()

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
        assertEquals("My phone", prefs.getString(CruxRelayManager.KEY_ORIGINAL_NAME, null))
    }

    @Test
    fun `restore permission failure keeps crash recovery record`() = runTest {
        seedDirty()
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } returns "CruxRelay"
        every { adapter.setName(any()) } throws SecurityException("denied")

        manager { adapter }
        runCurrent()

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
        assertEquals("My phone", prefs.getString(CruxRelayManager.KEY_ORIGINAL_NAME, null))
    }

    @Test
    fun `verified restore clears crash recovery record`() = runTest {
        seedDirty()
        val (adapter, currentName) = mutableAdapter("CruxRelay")

        manager { adapter }
        runCurrent()

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertEquals("My phone", currentName())
        assertFalse(prefs.all.toString(), prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
        assertNull(prefs.getString(CruxRelayManager.KEY_ORIGINAL_NAME, null))
    }

    @Test
    fun `a name an interrupted stop never gave back returns while no board is connected`() = runTest {
        val (adapter, currentName) = mutableAdapter("My phone")
        val guard = RelayAdapterNameGuard(context, { adapter }, backgroundScope)
        // Lent, and then the stop that should have returned it never ran.
        assertEquals(RelayNameLend.LENT, guard.lend("CruxCoach·Kilter Board@3"))

        manager(guard) { adapter }
        runCurrent()

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertEquals("My phone", currentName())
        assertFalse(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
    }

    @Test
    fun `Bluetooth on broadcast retries a retained restore`() = runTest {
        seedDirty()
        var available: BluetoothAdapter? = null
        manager { available }
        runCurrent()
        val (adapter, currentName) = mutableAdapter("CruxRelay")
        available = adapter

        context.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertEquals("My phone", currentName())
        assertFalse(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
    }

    private class Sharing(
        val manager: CruxRelayManager,
        val server: RelayGattServer,
        val advertiser: ClimbBleAdvertiser,
        val adapter: BluetoothAdapter,
        val adapterName: () -> String?,
    )

    /** A manager on a connected single-connection board, sharing switched on. */
    private fun TestScope.sharing(phoneName: String): Sharing {
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
        every { connection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { connection.connectedBoardDescriptor } returns MutableStateFlow(board)
        every { connection.connectedBoard } returns board
        val preferences = mockk<UserPreferences>(relaxed = true)
        val manualStart = MutableStateFlow(true)
        every { preferences.relayManualStart } returns manualStart
        every { preferences.relayDisclosureSeen } returns flowOf(true)
        coEvery { preferences.setRelayManualStart(any()) } answers { manualStart.value = firstArg() }
        val server = mockk<RelayGattServer>(relaxed = true)
        coEvery { server.start() } returns true
        every { server.climbs } returns MutableSharedFlow<RelayInboundClimb>()
        every { server.connectionEvents } returns MutableSharedFlow<GattConnectionEvent>()
        every { server.getConnectedCount() } returns 0
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        every { advertiser.startRelayAdvertising() } returns "started"
        coEvery { advertiser.awaitRelayAdvertisingStart() } returns
            AdvertisingSetCallback.ADVERTISE_SUCCESS
        val (adapter, currentName) = mutableAdapter(phoneName)
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
        manager.setSharingEnabled(true)
        runCurrent()
        return Sharing(manager, server, advertiser, adapter, currentName)
    }

    @Test
    fun `stopping gives the name back before the server goes down`() = runTest {
        val sharing = sharing("Test phone")
        val started = sharing.manager.state.first { it.advertising || it.error != null }
        assertTrue("Relay startup failed: $started", started.advertising)
        assertEquals("CruxCoach·Kilter Board@3", sharing.adapterName())

        sharing.manager.setSharingEnabled(false)
        runCurrent()
        sharing.manager.state.first { !it.enabled && !it.advertising }

        assertEquals("Test phone", sharing.adapterName())
        verifyStopOrder(sharing)
    }

    private fun verifyStopOrder(sharing: Sharing) {
        io.mockk.coVerifyOrder {
            sharing.advertiser.stopRelayAdvertising()
            sharing.adapter.setName("Test phone")
            sharing.server.stop()
        }
    }

    @Test
    fun `a phone already carrying a sharing name is not shared and says why`() = runTest {
        val sharing = sharing("CruxCoach·MoonBoard")
        val result = sharing.manager.state.first { it.advertising || it.error != null }

        assertEquals(RelayError.NAME_IN_USE, result.error)
        assertFalse(result.advertising)
        coVerify(exactly = 0) { sharing.server.start() }
        verify(exactly = 0) { sharing.adapter.setName(any()) }
        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
    }

    @Test
    fun `swiping the app away sends the name back at once`() = runTest {
        val sharing = sharing("Test phone")
        sharing.manager.state.first { it.advertising || it.error != null }

        sharing.manager.stopBeforeProcessMayEnd()

        // Before any coroutine runs: the process may not live long enough for one.
        verifyOrder {
            sharing.advertiser.stopRelayAdvertising()
            sharing.adapter.setName("Test phone")
        }
        assertEquals("Test phone", sharing.adapterName())
        runCurrent()
        sharing.manager.state.first { !it.enabled && !it.advertising }
        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
    }
}
