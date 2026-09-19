package com.cruxcoach.app.ble

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class BoardConnectionControllerTest {

    private val kilter3 = DiscoveredBoard("Kilter Board", "A1", 3, "id-kilter", -50, BoardBrand.KILTER)
    private val kilter2 = kilter3.copy(apiLevel = 2)
    private val moon = DiscoveredBoard("MoonBoard A", "", 0, "id-moon", -50, BoardBrand.MOONBOARD)
    private val holds = listOf(BoardHold(100, 12), BoardHold(101, 13), BoardHold(102, 14), BoardHold(103, 15))
    private val ledMap = mapOf(100 to 5, 101 to 300, 102 to 17, 103 to 9)

    private class Rig(scope: TestScope) {
        val central = FakeBleCentral { scope.testScheduler.currentTime }
        val controller = BoardConnectionController(central, scope.backgroundScope, FixedClock(1_000))
    }

    private fun TestScope.connected(board: DiscoveredBoard, configure: FakeBleCentral.() -> Unit = {}): Rig {
        val rig = Rig(this)
        rig.central.configure()
        rig.controller.connect(board)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
        return rig
    }

    /** advanceUntilIdle ignores backgroundScope work, so run a fixed minute of virtual time. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun bytes(vararg values: Int): List<Byte> = values.map { it.toByte() }

    @Test
    fun scanListsOnlyNamedBoardsAndPrefersTheAdvertisedName() = runTest {
        val rig = Rig(this)
        rig.controller.startScan()
        assertTrue(rig.central.scanning)
        val events = rig.central.listener!!
        events.onAdvertisement("p1", "Tension Board#77@3", "Kilter Board#OLD@2", -61)
        events.onAdvertisement("p2", null, "MoonBoard A", -70)
        events.onAdvertisement("p3", "JBL Flip", null, -30)
        events.onAdvertisement("p4", null, null, -30)
        events.onAdvertisement("p1", "Tension Board#77@3", null, -55)
        runCurrent()
        val boards = rig.controller.state.value.boards
        assertEquals(listOf("p1", "p2"), boards.map { it.identifier })
        assertEquals(DiscoveredBoard("Tension Board", "77", 3, "p1", -55, BoardBrand.TENSION), boards[0])
        assertEquals(BoardBrand.MOONBOARD, boards[1].boardBrand)
    }

    @Test
    fun adapterStatesAreSurfacedAndBlockScanAndConnect() = runTest {
        val rig = Rig(this)
        for (state in listOf(BleAdapterState.UNSUPPORTED, BleAdapterState.UNAUTHORIZED, BleAdapterState.POWERED_OFF)) {
            rig.central.adapter = state
            rig.central.listener!!.onAdapterState(state)
            runCurrent()
            rig.controller.startScan()
            rig.controller.connect(kilter3)
            settle()
            val snapshot = rig.controller.state.value
            assertEquals(state, snapshot.adapter)
            assertFalse(snapshot.scanning)
            assertEquals(ConnectionState.DISCONNECTED, snapshot.connection)
            assertEquals(BoardConnectFailure.BLUETOOTH_UNAVAILABLE, snapshot.failure)
        }
        assertTrue(rig.central.connects.isEmpty())
        assertFalse(rig.central.scanning)
    }

    @Test
    fun bluetoothTurningOffDropsAnEstablishedLink() = runTest {
        val rig = connected(kilter3)
        rig.central.adapter = BleAdapterState.POWERED_OFF
        rig.central.listener!!.onAdapterState(BleAdapterState.POWERED_OFF)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)
        assertEquals(BoardConnectFailure.BLUETOOTH_UNAVAILABLE, rig.controller.state.value.failure)
        assertEquals(BoardSendResult.NOT_CONNECTED, rig.controller.clearBoard())
    }

    @Test
    fun kilterApi3WritesTheGoldenPacketInOrderWithoutPacing() = runTest {
        val rig = connected(kilter3)
        assertEquals(listOf("connect", "discover"), rig.central.operations)
        val start = testScheduler.currentTime

        // One start hold on LED 5, default colours: 01 len cs 02 'T' pos-lo pos-hi colour 03.
        assertEquals(BoardSendResult.OK, rig.controller.sendClimb(listOf(BoardHold(100, 12)), ledMap))
        assertEquals(bytes(0x01, 0x04, 0x8A, 0x02, 0x54, 0x05, 0x00, 0x1C, 0x03), rig.central.writes.single().bytes)
        rig.central.writes.clear()

        // 40 holds span several 20-byte writes: exact encoder stream, in order, no inter-chunk delay.
        val many = (0 until 40).map { BoardHold(200 + it, 12 + it % 4) }
        val manyMap = many.associate { it.placementId to 10 + it.placementId }
        assertEquals(BoardSendResult.OK, rig.controller.sendClimb(many, manyMap, expectedBrand = BoardBrand.KILTER))
        val expected = BoardPacketEncoder(3, 2).encodeClimbFromHolds(many, manyMap)
        assertTrue(expected.size > 1)
        assertTrue(expected.all { it.size <= 20 })
        assertEquals(expected.map { it.toList() }, rig.central.writes.map { it.bytes })
        assertTrue(rig.central.writes.all { it.atMs == start })
        // Android's default write type: with response whenever WRITE is declared.
        assertTrue(rig.central.writes.all { it.withResponse })
        assertTrue(rig.central.writes.all { it.characteristic == BoardBleUuids.DATA_TRANSFER_CHAR })
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
    }

    @Test
    fun roleColoursResolveByRoleClassAndSkipUnmappedHolds() = runTest {
        val rig = connected(kilter3)
        // Aurora-keyed colour map (1-4) applied to Kilter-coded holds (12-15); hold 999 has no LED.
        val colours = mapOf(1 to 0x11, 2 to 0x22, 3 to 0x33, 4 to 0x44)
        assertEquals(BoardSendResult.OK, rig.controller.sendClimb(holds + BoardHold(999, 12), ledMap, colours))
        val expected = BoardPacketEncoder(3, 2).encodeClimb(listOf(5 to 0x11, 300 to 0x22, 17 to 0x33, 9 to 0x44))
        assertEquals(expected.map { it.toList() }, rig.central.writes.map { it.bytes })
    }

    @Test
    fun kilterApi2UsesTheV2Encoder() = runTest {
        val rig = connected(kilter2)
        assertEquals(BoardSendResult.OK, rig.controller.sendClimb(holds, ledMap))
        val expected = BoardPacketEncoder(2, 2).encodeClimbFromHolds(holds, ledMap)
        assertEquals(expected.map { it.toList() }, rig.central.writes.map { it.bytes })
        assertEquals(0x50.toByte(), rig.central.writes.first().bytes[4])
        assertEquals(4 + 1 + holds.size * 2 + 1, rig.central.writes.sumOf { it.bytes.size })
    }

    @Test
    fun writeTypeFallsBackToWithoutResponseOnlyWhenWriteIsNotDeclared() = runTest {
        val rig = connected(kilter3) {
            services = listOf(
                BleServiceInfo(
                    "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    listOf(FakeBleCentral.characteristic("6e400002-b5a3-f393-e0a9-e50e24dcca9e", writeNoResponse = true)),
                ),
            )
        }
        assertEquals(BoardSendResult.OK, rig.controller.clearBoard())
        assertFalse(rig.central.writes.single().withResponse)
    }

    @Test
    fun moonBoardFragmentsArePaced100msApart() = runTest {
        val rig = connected(moon)
        val frames = "p1r42p13r43p24r43p35r43p46r43p57r43p68r43p79r43p90r43p198r44"
        val start = testScheduler.currentTime
        assertEquals(BoardSendResult.OK, rig.controller.sendMoonBoardClimb(frames, MoonBoardVariant.MOONBOARD_2016))
        val payload = MoonBoardFrameEncoder.encode(frames, MoonBoardVariant.MOONBOARD_2016)
        val expected = payload.toList().chunked(20)
        assertTrue(expected.size >= 3)
        assertEquals(expected, rig.central.writes.map { it.bytes })
        assertEquals(expected.indices.map { start + it * 100L }, rig.central.writes.map { it.atMs })
        // No trailing delay after the last fragment.
        assertEquals(start + (expected.size - 1) * 100L, testScheduler.currentTime)
        assertEquals(BoardSendResult.WRONG_BOARD_FAMILY, rig.controller.sendMoonBoardClimb(frames, MoonBoardVariant.MOONBOARD_2016).let {
            rig.controller.disconnect(); runCurrent()
            val kilter = Rig(this)
            kilter.controller.connect(kilter3); advanceTimeBy(1_000); runCurrent()
            kilter.controller.sendMoonBoardClimb(frames, MoonBoardVariant.MOONBOARD_2016)
        })
    }

    @Test
    fun clearSendsTheEmptyPacketAndMoonBoardGetsTheAuroraV2Quirk() = runTest {
        val kilter = connected(kilter3)
        assertEquals(BoardSendResult.OK, kilter.controller.clearBoard(BoardBrand.KILTER))
        assertEquals(bytes(0x01, 0x01, 0xAB, 0x02, 0x54, 0x03), kilter.central.writes.single().bytes)

        // Documented Android quirk: the MoonBoard encoder is BoardPacketEncoder(apiLevel = 0).
        val moonRig = connected(moon)
        assertEquals(BoardSendResult.OK, moonRig.controller.clearBoard())
        assertEquals(bytes(0x01, 0x01, 0xAF, 0x02, 0x50, 0x03), moonRig.central.writes.single().bytes)
    }

    @Test
    fun brandFenceRefusesBeforeAnyWrite() = runTest {
        val rig = connected(kilter3)
        assertEquals(BoardSendResult.BOARD_MISMATCH, rig.controller.sendClimb(holds, ledMap, expectedBrand = BoardBrand.TENSION))
        assertEquals(BoardSendResult.BOARD_MISMATCH, rig.controller.clearBoard(BoardBrand.MOONBOARD))
        assertTrue(rig.central.writes.isEmpty())
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
    }

    @Test
    fun writeTimeoutDisconnectsAfterFiveSeconds() = runTest {
        val rig = connected(kilter3) { acknowledgeWrites = false }
        val start = testScheduler.currentTime
        assertEquals(BoardSendResult.WRITE_TIMEOUT, rig.controller.sendClimb(holds, ledMap))
        assertEquals(start + 5_000, testScheduler.currentTime)
        assertEquals(1, rig.central.writes.size)
        assertEquals(1, rig.central.cancels.size)
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)
        assertNull(rig.controller.state.value.connectedBoard)
    }

    @Test
    fun failedWriteStopsTheStreamButKeepsTheLink() = runTest {
        val rig = connected(kilter3) { writeSucceeds = false }
        val many = (0 until 40).map { BoardHold(200 + it, 12) }
        assertEquals(BoardSendResult.WRITE_FAILED, rig.controller.sendClimb(many, many.associate { it.placementId to it.placementId }))
        assertEquals(1, rig.central.writes.size)
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
    }

    @Test
    fun silentBoardGetsThreeTenSecondAttemptsWith600msBackoff() = runTest {
        val rig = Rig(this)
        rig.central.onConnect = {}
        rig.controller.connect(kilter3)
        settle()
        assertEquals(listOf(200L, 10_800L, 21_400L), rig.central.connects)
        assertEquals(listOf(10_200L, 20_800L, 31_400L), rig.central.cancels)
        val snapshot = rig.controller.state.value
        assertEquals(ConnectionState.DISCONNECTED, snapshot.connection)
        assertEquals(BoardConnectFailure.CONNECT_FAILED, snapshot.failure)
    }

    @Test
    fun radioFailureRetriesQuietlyAndTheSecondAttemptConnects() = runTest {
        val rig = Rig(this)
        var attempt = 0
        rig.central.onConnect = { id ->
            attempt += 1
            if (attempt == 1) rig.central.listener!!.onDisconnected(id) else rig.central.listener!!.onConnected(id)
        }
        rig.controller.connect(kilter3)
        advanceTimeBy(500)
        assertEquals(ConnectionState.CONNECTING, rig.controller.state.value.connection)
        settle()
        assertEquals(listOf(200L, 800L), rig.central.connects)
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
        assertNull(rig.controller.state.value.failure)
    }

    @Test
    fun aSpeculativeConnectGetsOneAttempt() = runTest {
        val rig = Rig(this)
        rig.central.onConnect = {}
        rig.controller.connect(kilter3, maxAttempts = 1)
        settle()
        assertEquals(1, rig.central.connects.size)
        assertEquals(BoardConnectFailure.CONNECT_FAILED, rig.controller.state.value.failure)
    }

    @Test
    fun failedDiscoveryRetriesAndAMissingCharacteristicDoesNot() = runTest {
        val failing = Rig(this)
        failing.central.services = null
        failing.controller.connect(kilter3)
        settle()
        assertEquals(3, failing.central.connects.size)
        assertEquals(BoardConnectFailure.CONNECT_FAILED, failing.controller.state.value.failure)

        val foreign = Rig(this)
        foreign.central.services = listOf(BleServiceInfo("180A", emptyList()))
        foreign.controller.connect(kilter3)
        settle()
        assertEquals(1, foreign.central.connects.size)
        assertEquals(1, foreign.central.cancels.size)
        assertEquals(BoardConnectFailure.CONNECT_FAILED, foreign.controller.state.value.failure)
        assertEquals(ConnectionState.DISCONNECTED, foreign.controller.state.value.connection)
    }

    @Test
    fun pre2017MoonBoardIsRecognisedAndRefused() = runTest {
        val rig = Rig(this)
        rig.central.services = listOf(BleServiceInfo(BoardBleUuids.REDBEAR_UART_SERVICE.lowercase(), emptyList()))
        rig.controller.connect(moon)
        settle()
        assertEquals(BoardConnectFailure.MOONBOARD_GENERATION_UNSUPPORTED, rig.controller.state.value.failure)
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)
        assertEquals(1, rig.central.cancels.size)

        // The same layout on a Kilter name is only a generic failure.
        val other = Rig(this)
        other.central.services = rig.central.services
        other.controller.connect(kilter3)
        settle()
        assertEquals(BoardConnectFailure.CONNECT_FAILED, other.controller.state.value.failure)
    }

    @Test
    fun remoteDropReturnsToDisconnectedWithoutAFailureCode() = runTest {
        val rig = connected(kilter3)
        rig.central.listener!!.onDisconnected(kilter3.identifier)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)
        assertNull(rig.controller.state.value.failure)
        assertTrue(rig.central.cancels.isEmpty())
    }

    @Test
    fun idleDisconnectArmsOnlyForExclusiveRetainingBoardsAndReArmsAfterASend() = runTest {
        val rig = connected(kilter3)
        rig.controller.autoDisconnectSeconds = 60
        assertTrue(rig.controller.idleDisconnectArmed)
        advanceTimeBy(59_000)
        assertEquals(BoardSendResult.OK, rig.controller.clearBoard())
        advanceTimeBy(59_000)
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
        rig.controller.acquireKeepAlive("session")
        assertFalse(rig.controller.idleDisconnectArmed)
        rig.controller.releaseKeepAlive("session")
        advanceTimeBy(60_001)
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)

        // A MoonBoard clears its LEDs when the last client leaves: never idle-disconnect it.
        val moonRig = connected(moon)
        moonRig.controller.autoDisconnectSeconds = 60
        assertFalse(moonRig.controller.idleDisconnectArmed)
    }
}
