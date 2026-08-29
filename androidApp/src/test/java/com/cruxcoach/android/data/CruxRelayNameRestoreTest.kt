package com.cruxcoach.android.data

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.RelayGattServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun TestScope.manager(adapter: () -> BluetoothAdapter?): CruxRelayManager {
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
    fun `missing original adapter name refuses rename without persisting dirty state`() = runTest {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.name } returns null
        every { adapter.setName(any()) } returns true
        val manager = manager { adapter }
        runCurrent()

        assertFalse(manager.snapshotAndSetAdapterName("CruxRelay"))

        val prefs = context.getSharedPreferences(CruxRelayManager.PREFS, Context.MODE_PRIVATE)
        assertFalse(prefs.all.toString(), prefs.getBoolean(CruxRelayManager.KEY_NAME_DIRTY, false))
        assertNull(prefs.getString(CruxRelayManager.KEY_ORIGINAL_NAME, null))
        verify(exactly = 0) { adapter.setName(any()) }
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
}
