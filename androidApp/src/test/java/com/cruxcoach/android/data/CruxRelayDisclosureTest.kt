package com.cruxcoach.android.data

import android.content.Context
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.RelayGattServer
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

    private fun board(address: String) = DiscoveredBoard(
        displayName = "Kilter Board",
        serial = "123",
        apiLevel = 3,
        address = address,
        rssi = -40,
        boardBrand = BoardBrand.KILTER,
    )

    @Test
    fun `no relay transport starts before disclosure is answered`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        every { connection.connectionState } returns connectionState
        every { connection.connectedBoard } returns board("00:11:22:33:44:55")
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
    fun `answer persists but cannot authorize a replacement board`() = runTest {
        val connection = mockk<BoardBleConnection>(relaxed = true)
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        var connected = board("00:11:22:33:44:55")
        every { connection.connectionState } returns connectionState
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
}
