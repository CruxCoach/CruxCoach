package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.testing.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The board screens' status chip, derived by [BleScreenModel] from the real presenter and a scripted central. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleScreenStatusTest {

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun keys(): Set<String> = values.keys
    }

    private class Rig(scope: TestScope) {
        val central = FakeBleCentral { scope.testScheduler.currentTime }
        val presenter = BoardConnectionPresenter(central, MemoryStore(), FixedClock(1), StandardTestDispatcher(scope.testScheduler))
        val model = BleScreenModel(presenter)
        val seen = mutableListOf<BleScreenState>()
        val subscription = model.watch { seen += it }

        fun advertiseKilter() {
            central.listener!!.onAdvertisement(KILTER_ID, "Kilter Board#A1@3", null, -60)
        }

        fun close() {
            subscription.cancel()
            presenter.dispose()
        }
    }

    private fun TestScope.connectKilter(rig: Rig) {
        rig.model.startScan()
        rig.advertiseKilter()
        runCurrent()
        rig.model.connect(KILTER_ID)
        runCurrent()
        assertEquals("connecting", rig.seen.last().status)
        assertEquals("Kilter Board", rig.seen.last().statusBoardName)
        advanceTimeBy(1_000); runCurrent()
    }

    @Test
    fun `scan connect drop and reconnect walk the chip through its states`() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertEquals("hidden", rig.model.currentState.status)

        rig.model.startScan()
        runCurrent()
        assertEquals("scanning", rig.model.currentState.status)

        connectKilter(rig)
        val connected = rig.model.currentState
        assertEquals("connected", connected.status)
        assertEquals("Kilter Board", connected.statusBoardName)
        assertFalse(connected.canReconnect, "nothing to reconnect while the link is up")

        // The board drops the link (out of range, switched off): the user did nothing.
        rig.central.listener!!.onDisconnected(KILTER_ID)
        runCurrent()
        val lost = rig.model.currentState
        assertEquals("lost", lost.status)
        assertEquals("Kilter Board", lost.statusBoardName)
        assertTrue(lost.canReconnect, "the connected board was remembered")
        assertEquals("lost", rig.seen.last().status)

        rig.model.reconnect()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, rig.central.connects.size, "reconnect goes straight to the remembered board")
        assertEquals("connected", rig.model.currentState.status)

        // A disconnect the user asked for is not a lost link.
        rig.model.disconnect()
        runCurrent()
        assertEquals("hidden", rig.model.currentState.status)
        assertEquals("hidden", rig.seen.last().status)
        rig.close()
    }

    @Test
    fun `dismissing a lost link hides it until the next link drops`() = runTest {
        val rig = Rig(this)
        connectKilter(rig)
        rig.central.listener!!.onDisconnected(KILTER_ID)
        runCurrent()
        assertEquals("lost", rig.model.currentState.status)

        val before = rig.seen.size
        rig.model.dismissStatus()
        assertEquals("hidden", rig.model.currentState.status)
        assertTrue(rig.seen.size > before, "the dismissal reaches watchers without a link change")
        assertEquals("hidden", rig.seen.last().status)

        rig.model.reconnect()
        advanceTimeBy(1_000); runCurrent()
        assertEquals("connected", rig.model.currentState.status)
        rig.central.listener!!.onDisconnected(KILTER_ID)
        runCurrent()
        assertEquals("lost", rig.model.currentState.status)
        rig.close()
    }

    @Test
    fun `bluetooth off and missing permission show until dismissed and return when they recur`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.central.adapter = BleAdapterState.POWERED_OFF
        rig.central.listener!!.onAdapterState(BleAdapterState.POWERED_OFF)
        runCurrent()
        assertEquals("off", rig.model.currentState.status)
        rig.model.dismissStatus()
        assertEquals("hidden", rig.model.currentState.status)

        rig.central.adapter = BleAdapterState.POWERED_ON
        rig.central.listener!!.onAdapterState(BleAdapterState.POWERED_ON)
        runCurrent()
        assertEquals("hidden", rig.model.currentState.status)
        rig.central.adapter = BleAdapterState.POWERED_OFF
        rig.central.listener!!.onAdapterState(BleAdapterState.POWERED_OFF)
        runCurrent()
        assertEquals("off", rig.model.currentState.status, "a dismissal lasts only until the status changes")

        rig.central.adapter = BleAdapterState.UNAUTHORIZED
        rig.central.listener!!.onAdapterState(BleAdapterState.UNAUTHORIZED)
        runCurrent()
        assertEquals("unauthorized", rig.model.currentState.status)
        rig.close()
    }

    @Test
    fun `a failed connect shows as failed and offers no reconnect without a remembered board`() = runTest {
        val rig = Rig(this)
        rig.central.onConnect = {}
        rig.model.startScan()
        rig.advertiseKilter()
        runCurrent()
        rig.model.connect(KILTER_ID)
        advanceTimeBy(60_000); runCurrent()
        val failed = rig.model.currentState
        assertEquals("failed", failed.status)
        assertEquals("connectFailed", failed.connectFailure)
        assertFalse(failed.canReconnect)
        rig.close()
    }

    private companion object {
        const val KILTER_ID = "11111111-2222-3333-4444-555555555555"
    }
}
