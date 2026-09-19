package com.cruxcoach.app.ble

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.QuantumActivePlayer
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.QuantumCommand
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

/** Pure cases ported from Android's QuantumControllerEvidenceTest, plus the link driven through a fake central. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumLinkTest {
    private val route = "00112233-4455-6677-8899-aabbccddeeff"
    private val ownUser = "ffeeddcc-bbaa-4988-8766-554433221100"
    private val foreignUser = "11111111-2222-4333-8444-555555555555"
    private val board = DiscoveredBoard("QB_020000000001", "020000000001", 1, "id-q", -40, BoardBrand.QUANTUM)
    private val identity = BoardLayerBoardIdentity("quantum:serial:020000000001", 9201)
    private val holds = listOf(BoardHold(10, 12), BoardHold(20, 13))
    private val ledMap = mapOf(10 to 100, 20 to 200)

    private fun player(userId: String) = QuantumActivePlayer(route, userId, 0, 0x00ffff)

    private fun metadata(type: Int, columns: Int, rows: Int) = ByteArray(41).apply {
        this[34] = type.toByte()
        this[35] = (columns ushr 8).toByte(); this[36] = columns.toByte()
        this[37] = (rows ushr 8).toByte(); this[38] = rows.toByte()
    }

    private fun routeList(players: List<QuantumActivePlayer>, command: Int = 0x47): ByteArray {
        var out = byteArrayOf(1, command.toByte(), players.size.toByte(), 0)
        players.forEach { p ->
            out += QuantumBoardPacketEncoder.uuidBytes(p.routeId) + QuantumBoardPacketEncoder.uuidBytes(p.userId) +
                byteArrayOf((p.remainingSeconds ushr 8).toByte(), p.remainingSeconds.toByte()) +
                byteArrayOf((p.color ushr 16).toByte(), (p.color ushr 8).toByte(), p.color.toByte())
        }
        return out
    }

    private fun quantumServices(service: String = "FFE0") = listOf(
        BleServiceInfo(
            service,
            listOf(
                FakeBleCentral.characteristic("FFF1", notify = true),
                FakeBleCentral.characteristic("FFF2", write = true, writeNoResponse = true),
                FakeBleCentral.characteristic("FFF4", read = true),
                FakeBleCentral.characteristic("FFF5", read = true),
            ),
        ),
    )

    private class Rig(scope: TestScope, owned: String) {
        val central = FakeBleCentral { scope.testScheduler.currentTime }
        val controller = BoardConnectionController(central, scope.backgroundScope, FixedClock(1_000)) {
            it.equals(owned, ignoreCase = true)
        }
    }

    /** A simulated controller: fff4 always answers with the current roster, as deployed XL firmware does. */
    private fun TestScope.rig(configure: FakeBleCentral.() -> Unit = {}): Rig {
        val rig = Rig(this, ownUser)
        val roster = mutableListOf<QuantumActivePlayer>()
        rig.central.services = quantumServices()
        rig.central.maxWriteLength = 509
        rig.central.reads["FFF5"] = metadata(0, 15, 15)
        rig.central.reads["FFF4"] = routeList(roster)
        rig.central.onWrite = { write ->
            val frame = write.bytes.toByteArray()
            when (frame[1].toInt()) {
                0x43 -> roster.removeAll { QuantumBoardPacketEncoder.uuidBytes(it.userId).toList() == write.bytes.subList(2, 18) }
                0x41 -> roster += QuantumActivePlayer(
                    routeId = route, userId = ownUser, remainingSeconds = 0xffff,
                    color = ((frame[34].toInt() and 0xff) shl 16) or ((frame[35].toInt() and 0xff) shl 8) or (frame[36].toInt() and 0xff),
                )
                0x45 -> roster.clear()
            }
            rig.central.reads["FFF4"] = routeList(roster)
        }
        rig.central.configure()
        return rig
    }

    private fun TestScope.connect(rig: Rig) {
        rig.controller.connect(board)
        advanceTimeBy(1_000)
        runCurrent()
    }

    @Test
    fun setupIsOrderedAndOnlyThenConnected() = runTest {
        val rig = rig()
        rig.controller.connect(board)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(
            listOf("connect", "discover", "maxWriteLength", "read:${BoardBleUuids.QUANTUM_METADATA_CHAR}",
                "notify:${BoardBleUuids.QUANTUM_NOTIFY_CHAR}", "write:FFF2", "read:${BoardBleUuids.QUANTUM_STATE_CHAR}"),
            rig.central.operations,
        )
        val state = rig.controller.state.value
        assertEquals(ConnectionState.CONNECTED, state.connection)
        assertEquals(QuantumBoardModel.XL, state.quantumModel)
        // The first command after setup is the route-list request, written WITH response.
        val first = rig.central.writes.single()
        assertEquals(QuantumBoardPacketEncoder.requestRouteList().toList(), first.bytes)
        assertTrue(first.withResponse)
        assertEquals(QuantumControllerSyncStatus.LIVE, rig.controller.quantumState.value.syncStatus)
        // The roster is refreshed every ten seconds while connected.
        advanceTimeBy(10_001); runCurrent()
        assertEquals(2, rig.central.writes.size)
    }

    @Test
    fun oldServiceUuidIsTheFallback() = runTest {
        val rig = rig { services = quantumServices("0000fff0-0000-1000-8000-00805f9b34fb") }
        connect(rig)
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)
    }

    @Test
    fun setupFailsClosedOnBadMetadataSilentReadOrRefusedNotifications() = runTest {
        val cases: List<FakeBleCentral.() -> Unit> = listOf(
            { reads["FFF5"] = metadata(1, 15, 15) },       // type M with XL dimensions
            { reads["FFF5"] = ByteArray(40) },
            { reads["FFF5"] = null },
            { notifySucceeds = false },
            { answerReads = false },                       // 5 s read timeout
        )
        cases.forEach { configure ->
            val rig = rig(configure)
            rig.controller.connect(board)
            advanceTimeBy(8_000); runCurrent()
            val state = rig.controller.state.value
            assertEquals(ConnectionState.DISCONNECTED, state.connection)
            assertEquals(BoardConnectFailure.CONNECT_FAILED, state.failure)
            assertNull(state.quantumModel.takeIf { state.connection == ConnectionState.CONNECTED })
            assertTrue(rig.central.writes.isEmpty(), "no fff2 write before setup completes")
            assertEquals(1, rig.central.cancels.size)
        }
    }

    @Test
    fun projectionWritesTheTransitionWithAndroidsPacingAndConfirmsByReadback() = runTest {
        val rig = rig()
        connect(rig)
        rig.central.writes.clear()
        val start = testScheduler.currentTime
        val result = rig.controller.sendQuantumClimb(holds, ledMap, route, ownUser, 0xff00ffff.toInt(), emptyList(), identity)
        assertEquals(BoardSendResult.OK, result)
        val transition = QuantumBoardPacketEncoder.replaceUserRoute(route, ownUser, listOf(100, 200), 0x00ffff)
        val list = QuantumBoardPacketEncoder.requestRouteList().toList()
        assertEquals(listOf(list, transition[0].toList(), transition[1].toList(), list), rig.central.writes.map { it.bytes })
        // request → 50 ms → TURN_OFF_USER → 50 ms → ACTIVATE_WALL → 250 ms settle → request.
        assertEquals(listOf(0L, 50L, 100L, 350L), rig.central.writes.map { it.atMs - start })
        assertTrue(rig.central.writes.all { it.withResponse })
        assertEquals(ConnectionState.CONNECTED, rig.controller.state.value.connection)

        assertEquals(BoardSendResult.OK, rig.controller.removeQuantumLayer(ownUser, route, identity))
        assertTrue(rig.controller.quantumState.value.players.isEmpty())
    }

    @Test
    fun aFrameLargerThanOneAttWriteIsRefusedNotFragmented() = runTest {
        // 23-byte default MTU: the 5-byte route-list request fits, the activation frame does not.
        val rig = rig { maxWriteLength = 20 }
        connect(rig)
        rig.central.writes.clear()
        val result = rig.controller.sendQuantumClimb(holds, ledMap, route, ownUser, 0x00ffff, emptyList(), identity)
        assertEquals(BoardSendResult.FRAME_TOO_LARGE, result)
        assertTrue(rig.central.writes.all { it.bytes.size <= 20 })
        assertEquals(listOf(QuantumBoardPacketEncoder.requestRouteList().toList()), rig.central.writes.map { it.bytes })
    }

    @Test
    fun quantumFencesRefuseBeforeAnyMutation() = runTest {
        val rig = rig()
        connect(rig)
        rig.central.writes.clear()
        val c = rig.controller
        assertEquals(BoardSendResult.BOARD_MISMATCH,
            c.sendQuantumClimb(holds, ledMap, route, ownUser, 0x00ffff, emptyList(), identity.copy(physicalBoardId = "quantum:serial:other")))
        assertEquals(BoardSendResult.BOARD_MISMATCH,
            c.sendQuantumClimb(holds, ledMap, route, ownUser, 0x00ffff, emptyList(), identity.copy(productSizeId = 9203)))
        assertEquals(BoardSendResult.QUANTUM_PRECONDITION_FAILED,
            c.sendQuantumClimb(holds, ledMap, route, QuantumBoardPacketEncoder.ZERO_UUID, 0x00ffff, emptyList(), identity))
        assertEquals(BoardSendResult.QUANTUM_PRECONDITION_FAILED,
            c.sendQuantumClimb(holds, ledMap, route, foreignUser, 0x00ffff, emptyList(), identity))
        assertTrue(rig.central.writes.isEmpty())
        // Stale roster expectation and a partial LED map: only the read-only request is written.
        assertEquals(BoardSendResult.QUANTUM_PRECONDITION_FAILED,
            c.sendQuantumClimb(holds, ledMap, route, ownUser, 0x00ffff, listOf(player(foreignUser)), identity))
        assertEquals(BoardSendResult.QUANTUM_PRECONDITION_FAILED,
            c.sendQuantumClimb(holds, mapOf(10 to 100), route, ownUser, 0x00ffff, emptyList(), identity))
        assertTrue(rig.central.writes.all { it.bytes[1] == 0x47.toByte() })
        // Generic paths never touch a Quantum wall.
        assertEquals(BoardSendResult.WRONG_BOARD_FAMILY, c.clearBoard())
        assertEquals(BoardSendResult.WRONG_BOARD_FAMILY, c.sendClimb(holds, ledMap))
        assertEquals(BoardSendResult.BOARD_MISMATCH, c.sendClimb(holds, ledMap, expectedBrand = BoardBrand.KILTER))
    }

    @Test
    fun anUnansweredRouteListRetiresTheLink() = runTest {
        val rig = rig()
        connect(rig)
        rig.central.answerReads = false
        val result = rig.controller.sendQuantumClimb(holds, ledMap, route, ownUser, 0x00ffff, emptyList(), identity)
        assertEquals(BoardSendResult.QUANTUM_STATE_UNAVAILABLE, result)
        assertEquals(ConnectionState.DISCONNECTED, rig.controller.state.value.connection)
        assertEquals(QuantumControllerSyncStatus.STALE, rig.controller.quantumState.value.syncStatus)
    }

    @Test
    fun metadataMapsEveryVerifiedControllerType() {
        listOf(0 to QuantumBoardModel.XL, 1 to QuantumBoardModel.M, 2 to QuantumBoardModel.S,
            3 to QuantumBoardModel.BELAY, 4 to QuantumBoardModel.L).forEach { (type, model) ->
            assertEquals(model, parseQuantumControllerMetadata(metadata(type, model.columns, model.rows))?.model)
        }
        assertNull(parseQuantumControllerMetadata(ByteArray(40)))
        assertNull(parseQuantumControllerMetadata(metadata(99, 15, 15)))
        assertNull(parseQuantumControllerMetadata(metadata(1, 15, 15)))
    }

    @Test
    fun framesMustFitOneWrite() {
        assertTrue(quantumFrameFitsWriteLength(5, 20))
        assertFalse(quantumFrameFitsWriteLength(21, 20))
        assertTrue(quantumFrameFitsWriteLength(227, 509))
        assertFalse(quantumFrameFitsWriteLength(510, 509))
        assertFalse(quantumFrameFitsWriteLength(0, 509))
        assertFalse(quantumFrameFitsWriteLength(5, 0))
    }

    @Test
    fun notificationSizingAndRecovery() {
        assertEquals(QUANTUM_NOTIFICATION_NEED_MORE, quantumNotificationFrameSize(byteArrayOf()))
        assertEquals(QUANTUM_NOTIFICATION_NEED_MORE, quantumNotificationFrameSize(byteArrayOf(1)))
        assertEquals(QUANTUM_NOTIFICATION_INVALID, quantumNotificationFrameSize(byteArrayOf(1, 0x7e)))
        assertEquals(QUANTUM_NOTIFICATION_INVALID, quantumNotificationFrameSize(byteArrayOf(1, 0x41, 5, 0)))
        assertEquals(QUANTUM_NOTIFICATION_INVALID, quantumNotificationFrameSize(byteArrayOf(1, 0x47, 0, 1)))
        assertEquals(4 + QuantumBoardBroadcastParser.PLAYER_BYTES, quantumNotificationFrameSize(byteArrayOf(1, 0x41, 1, 0)))
        assertEquals(4 + 4 * QuantumBoardBroadcastParser.PLAYER_BYTES, quantumNotificationFrameSize(byteArrayOf(1, 0x47, 4, 0)))

        val accumulator = QuantumNotificationAccumulator()
        assertTrue(accumulator.consume(byteArrayOf(1, 0x47, 0)).isEmpty())
        assertTrue(accumulator.consume(byteArrayOf(1, 0x47)).isEmpty())
        val recovered = accumulator.consume(byteArrayOf(0, 0)).single()
        assertEquals(listOf<Byte>(1, 0x47, 0, 0), recovered.bytes.toList())
        assertTrue(recovered.crossedCallbackBoundary)
        accumulator.reset()
        val clean = accumulator.consume(byteArrayOf(0x55, 1, 0x7e, 1, 0x64, 0)).single()
        assertEquals(listOf<Byte>(1, 0x64, 0), clean.bytes.toList())
        assertFalse(clean.crossedCallbackBoundary)
    }

    @Test
    fun evidenceAndConfirmationRules() {
        val full = QuantumBroadcast.RouteList(QuantumCommand.REQUEST_USER_ROUTE_LIST, listOf(player(ownUser)))
        assertTrue(quantumFff4PublishesSnapshot(full))
        assertTrue(quantumFff4ConfirmsExplicitRouteList(full))
        assertFalse(quantumFff4ConfirmsExplicitRouteList(QuantumBroadcast.RouteList(QuantumCommand.ACTIVATE_WALL, full.players)))
        assertFalse(quantumFff4PublishesSnapshot(QuantumBroadcast.BoardCleared))
        assertEquals(QuantumControllerEvidence.AUTHORITATIVE, classifyQuantumControllerEvidence(QuantumBroadcast.BoardCleared))
        assertEquals(QuantumControllerEvidence.INFORMATIONAL, classifyQuantumFff4Evidence(QuantumBroadcast.BoardCleared))

        val before = QuantumControllerState(authoritative = true, revision = 10, authoritativeRevision = 10, routeListRevision = 7)
        val cached = before.copy(revision = 11, authoritativeRevision = 11)
        assertFalse(hasFreshExplicitQuantumRouteList(before, cached))
        assertTrue(hasFreshExplicitQuantumRouteList(before, cached.copy(revision = 12, routeListRevision = 12)))

        val confirmed = QuantumControllerState(players = listOf(player(ownUser)), authoritative = true)
        assertTrue(isQuantumProjectionConfirmed(confirmed, emptyList(), route.uppercase(), ownUser, 0xff00ffff.toInt()))
        assertFalse(isQuantumProjectionConfirmed(confirmed, emptyList(), route, foreignUser, 0x00ffff))
        assertFalse(isQuantumProjectionConfirmed(confirmed, emptyList(), route, ownUser, 0x00ff00))
        assertFalse(isQuantumProjectionConfirmed(confirmed.copy(lastFailure = QuantumCommandFailure.COLOR_TAKEN), emptyList(), route, ownUser, 0x00ffff))
        // Losing a pre-existing foreign player is not a confirmation.
        assertFalse(isQuantumProjectionConfirmed(confirmed, listOf(player(foreignUser)), route, ownUser, 0x00ffff))

        val both = QuantumControllerState(players = listOf(player(ownUser), player(foreignUser)), authoritative = true)
        assertFalse(isQuantumScopedRemovalConfirmed(both, both.players, ownUser))
        assertTrue(isQuantumScopedRemovalConfirmed(both.copy(players = listOf(player(foreignUser))), both.players, ownUser))
        assertFalse(isQuantumScopedRemovalConfirmed(both.copy(players = emptyList()), both.players, ownUser))

        val expected = listOf(player(ownUser), player(foreignUser).copy(remainingSeconds = 37, color = 0x123456))
        assertTrue(quantumPlayersMatch(expected, expected.reversed().map {
            it.copy(routeId = it.routeId.uppercase(), userId = it.userId.uppercase(), color = it.color or 0xff000000.toInt(), remainingSeconds = 1)
        }))
        assertFalse(quantumPlayersMatch(expected, expected.dropLast(1)))
        assertTrue(hasConfirmableQuantumDiodeCount(List(92) { BoardHold(it, 12) }))
        assertFalse(hasConfirmableQuantumDiodeCount(List(93) { BoardHold(it, 12) }))

        val live = QuantumControllerState(authoritative = true, syncStatus = QuantumControllerSyncStatus.LIVE, lastAuthoritativeAtMs = 1_000)
        assertFalse(quantumForegroundRefreshRequired(live, 10_999, 10_000))
        assertTrue(quantumForegroundRefreshRequired(live, 11_000, 10_000))
        assertTrue(quantumForegroundRefreshRequired(live.copy(syncStatus = QuantumControllerSyncStatus.STALE), 1_001, 10_000))
    }
}
