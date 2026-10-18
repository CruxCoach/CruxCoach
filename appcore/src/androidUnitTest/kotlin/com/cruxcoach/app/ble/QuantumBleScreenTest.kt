package com.cruxcoach.app.ble

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.ui.BleScreenModel
import com.cruxcoach.app.ui.BleScreenState
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The BLE screen on a Quantum controller: model, read-only wall roster, no plain clear. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumBleScreenTest {
    private val foreign = QuantumActivePlayer(
        "00112233-4455-6677-8899-aabbccddeeff", "11111111-2222-4333-8444-555555555555", 120, 0xff0000,
    )

    private fun metadata(type: Int, columns: Int, rows: Int) = ByteArray(41).apply {
        this[34] = type.toByte()
        this[35] = (columns ushr 8).toByte(); this[36] = columns.toByte()
        this[37] = (rows ushr 8).toByte(); this[38] = rows.toByte()
    }

    private fun routeList(players: List<QuantumActivePlayer>): ByteArray {
        var out = byteArrayOf(1, 0x47, players.size.toByte(), 0)
        players.forEach { p ->
            out += QuantumBoardPacketEncoder.uuidBytes(p.routeId) + QuantumBoardPacketEncoder.uuidBytes(p.userId) +
                byteArrayOf((p.remainingSeconds ushr 8).toByte(), p.remainingSeconds.toByte()) +
                byteArrayOf((p.color ushr 16).toByte(), (p.color ushr 8).toByte(), p.color.toByte())
        }
        return out
    }

    @Test
    fun `a connected quantum wall shows its model and live roster but no plain clear`() = runTest {
        val central = FakeBleCentral { testScheduler.currentTime }
        central.services = listOf(
            BleServiceInfo(
                "FFE0",
                listOf(
                    FakeBleCentral.characteristic("FFF1", notify = true),
                    FakeBleCentral.characteristic("FFF2", write = true, writeNoResponse = true),
                    FakeBleCentral.characteristic("FFF4", read = true),
                    FakeBleCentral.characteristic("FFF5", read = true),
                ),
            ),
        )
        central.maxWriteLength = 509
        central.reads["FFF5"] = metadata(0, 15, 15)
        central.reads["FFF4"] = routeList(listOf(foreign))
        val presenter = BoardConnectionPresenter(central, MemoryKeyValueStore(), FixedClock(1_000), StandardTestDispatcher(testScheduler))
        val screen = BleScreenModel(presenter)
        var last: BleScreenState = screen.currentState
        val watch = screen.watch { last = it }

        assertFalse(last.connectedQuantum)
        assertEquals("unsynced", last.quantumWall)

        presenter.startScan()
        central.listener!!.onAdvertisement("id-q", "QB_020000000001", null, -40)
        runCurrent()
        screen.connect("id-q")
        advanceTimeBy(1_000); runCurrent()

        assertTrue(last.connected)
        assertTrue(last.connectedQuantum)
        assertEquals("XL", last.quantumModelName)
        assertEquals("live", last.quantumWall)
        assertEquals(1, last.quantumWallActive)
        assertEquals(4, last.quantumWallCapacity)

        screen.disconnect(); runCurrent()
        assertFalse(last.connectedQuantum)
        assertEquals("stale", last.quantumWall, "the roster is last-known after a disconnect")
        watch.cancel()
    }
}
