package com.cruxcoach.app.ble

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoardConnectionPresenterTest {

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun keys(): Set<String> = values.keys
    }

    private fun TestScope.presenter(central: FakeBleCentral, store: MemoryStore) =
        BoardConnectionPresenter(central, store, FixedClock(1), StandardTestDispatcher(testScheduler))

    @Test
    fun scanConnectSendAndRememberThroughTheFacade() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        val store = MemoryStore()
        val presenter = presenter(central, store)
        val seen = mutableListOf<ConnectionState>()
        val watch = presenter.watch { seen += it.connection }

        presenter.startScan()
        central.listener!!.onAdvertisement("11111111-2222-3333-4444-555555555555", "Kilter Board#A1@3", null, -80)
        central.listener!!.onAdvertisement("99999999-2222-3333-4444-555555555555", "Tension Board@3", null, -40)
        runCurrent()
        assertEquals(listOf(BoardBrand.TENSION, BoardBrand.KILTER), presenter.state.value.boards.map { it.boardBrand })

        presenter.connect("no-such-board")
        presenter.connect("11111111-2222-3333-4444-555555555555")
        advanceTimeBy(1_000); runCurrent()
        val connected = presenter.state.value
        assertEquals(ConnectionState.CONNECTED, connected.connection)
        assertFalse(connected.scanning)
        assertEquals(BoardConnectionCapacity.SINGLE, connected.connectionCapacity)
        assertEquals("A1", connected.remembered.single().serial)

        presenter.sendClimb(listOf(BoardHold(1, 12)), mapOf(1 to 5), LedHoldColors.kilterStandard().toRoleColorMap(), "tension")
        runCurrent()
        assertEquals(BoardSendResult.BOARD_MISMATCH, presenter.state.value.lastSendResult)
        assertTrue(central.writes.isEmpty())

        presenter.sendClimb(listOf(BoardHold(1, 12)), mapOf(1 to 5), LedHoldColors.kilterStandard().toRoleColorMap(), "kilter")
        runCurrent()
        assertEquals(BoardSendResult.OK, presenter.state.value.lastSendResult)
        assertFalse(presenter.state.value.sending)
        assertEquals(listOf<Byte>(0x01, 0x04, 0x8A.toByte(), 0x02, 0x54, 0x05, 0x00, 0x1C, 0x03), central.writes.single().bytes)

        presenter.disconnect()
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, presenter.state.value.connection)
        assertTrue(ConnectionState.CONNECTING in seen && ConnectionState.SENDING in seen)
        watch.close()
        presenter.dispose()
    }

    @Test
    fun rememberedBoardReconnectsByIdentifierWithASingleAttempt() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        val store = MemoryStore()
        RememberedBoardStore(store).remember(DiscoveredBoard("MoonBoard A", "", 0, "id-moon", -50, BoardBrand.MOONBOARD))
        RememberedBoardStore(store).remember(
            DiscoveredBoard("Kilter Board", "", 3, "id-relay", -50, BoardBrand.KILTER, isCruxRelay = true),
        )
        store.values["ble.remembered_board.tension"] = "{not json"
        val presenter = presenter(central, store)
        assertEquals(listOf("id-moon"), presenter.state.value.remembered.map { it.identifier })

        central.onConnect = {}
        presenter.reconnectRemembered("moonboard")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, central.connects.size)
        assertEquals(BoardConnectFailure.CONNECT_FAILED, presenter.state.value.connectFailure)

        presenter.forgetRemembered("moonboard")
        assertTrue(presenter.state.value.remembered.isEmpty())
        assertNull(RememberedBoardStore(store).get(BoardBrand.MOONBOARD))
        presenter.dispose()
    }

    @Test
    fun firstScanActivatesBluetoothAndStartsOncePoweredOn() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        central.adapter = BleAdapterState.UNKNOWN
        val presenter = presenter(central, MemoryStore())
        presenter.startScan()
        assertEquals(1, central.activations)
        assertFalse(central.scanning)
        central.adapter = BleAdapterState.POWERED_ON
        central.listener!!.onAdapterState(BleAdapterState.POWERED_ON)
        runCurrent()
        assertTrue(central.scanning)
        assertTrue(presenter.state.value.scanning)
        presenter.dispose()
    }
}
