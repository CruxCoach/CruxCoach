package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * The joinable playlist as it actually travels: two real coordinators, a
 * controller that is deliberately *not* the playlist host, and the durable
 * ack window doing the idempotency work.
 */
class JoinablePlaylistMeshTest {

    @After fun reset() = BoardCellScopeRegistry.resetForTest()

    private class MemoryStore : BoardCellDurableStore {
        val snapshots = mutableMapOf<PhysicalBoardId, BoardCellSnapshot>()
        val intents = mutableMapOf<PhysicalBoardId, BoardWriteIntent>()
        val acks = mutableMapOf<String, BoardCommandAck>()
        override fun persistSnapshot(snapshot: BoardCellSnapshot) {
            snapshots[snapshot.physicalBoardId] = snapshot
        }
        override fun clearSnapshot(boardId: PhysicalBoardId) { snapshots.remove(boardId) }
        override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot; acks[ack.commandId] = ack
        }
        override fun persistIntent(intent: BoardWriteIntent) { intents[intent.physicalBoardId] = intent }
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) {
            intents[intent.physicalBoardId] = intent.copy(
                state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED)
        }
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot
            intents.remove(intent.physicalBoardId); acks[ack.commandId] = ack
        }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) {
            if (intents[boardId]?.commandId == commandId) intents.remove(boardId)
        }
        override fun pendingIntent(boardId: PhysicalBoardId) = intents[boardId]
        override fun commandAck(commandId: String) = acks[commandId]
    }

    private class Fanout : BoardCellTransport {
        val snapshots = mutableListOf<BoardCellSnapshot>()
        val events = mutableListOf<BoardCellEnvelope>()
        override suspend fun publishClaim(claim: BoardCellClaim) = Unit
        override suspend fun publishEvent(envelope: BoardCellEnvelope) { events += envelope }
        override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) { snapshots += snapshot }
        override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
    }

    private val board = PhysicalBoardId("board-joinable")

    /**
     * Fixed UTC "now" for the coordinator under test, and a mutable one so a
     * test can move wall-clock time forward without sleeping.
     */
    private val wallClock = 1_786_968_000_000L
    private var clockNow = wallClock

    /**
     * A cell whose technical controller is "controller" and whose other
     * members never write the board. That is the shape the observed defect
     * lived in: the device driving the playlist was a member, not the
     * controller.
     */
    private suspend fun cell(
        store: MemoryStore = MemoryStore(),
        transport: Fanout = Fanout(),
        vararg members: String,
    ): Triple<BoardCellCoordinator, Fanout, MemoryStore> {
        val coordinator = BoardCellCoordinator("controller", transport, store,
            settleMs = 0, heartbeatTimeoutMs = 100_000, wallClockEpochMs = { clockNow })
        coordinator.beginClaim(board, BoardCellId.forPhysical(board), 100)
        coordinator.settle(board, 100)
        members.forEach { coordinator.joinMember(board, it, 100) }
        return Triple(coordinator, transport, store)
    }

    private fun startControl(
        commandId: String = "start-command-01",
        requestId: String = "start-request-01",
        revision: Long = 0,
        items: List<Pair<String, Int>> = listOf("climb-a" to 40, "climb-b" to 45),
        rests: List<Int> = listOf(120, 0),
    ) = BoardPlaylistControl.Start(commandId, revision, requestId, 4_711, items, rests)

    // ===== The regression from the field report =====

    @Test fun `a member that is not the controller starts the playlist canonically`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        assertEquals(0L, coordinator.snapshot(board)!!.playlistRevision)

        val ack = coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        val playlist = coordinator.snapshot(board)!!.playlist
        // Before the fix this device called replacePlaylist(), which only the
        // controller can perform, so the snapshot stayed at revision 0 with an
        // empty queue and index -1 for ever.
        assertEquals("nokia", playlist.hostId)
        assertEquals(listOf("nokia", "controller"), playlist.members)
        assertEquals(listOf("climb-a" to 40, "climb-b" to 45), playlist.items)
        assertEquals(0, playlist.currentIndex)
        assertEquals(1L, coordinator.snapshot(board)!!.playlistRevision)
        // The controller is a regular Board member: it follows the playlist,
        // but serialization still does not make it the playlist host.
        assertNotEquals("controller", playlist.hostId)
        assertTrue("controller" in playlist.members)
        assertTrue(store.acks.containsKey("start-command-01"))
    }

    @Test fun `every board member follows the playlist from its first commit`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))

        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(setOf("controller", "nokia", "pixel"), playlist.members.toSet())
        assertTrue("controller" in coordinator.snapshot(board)!!.members)
        assertTrue("controller" in playlist.members)
    }

    @Test fun `replaying a start command commits exactly one playlist`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        val first = coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        val revision = coordinator.snapshot(board)!!.playlistRevision

        val replay = coordinator.applyPlaylistControl(board, 102, "nokia", startControl())

        assertEquals(first!!.resultingSequence, replay!!.resultingSequence)
        assertEquals(revision, coordinator.snapshot(board)!!.playlistRevision)
        assertEquals(1, store.acks.count { it.key == "start-command-01" })
    }

    // ===== Concurrent additions to the one Board playlist =====

    @Test fun `successive starts append without a host approval queue`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel", "moto"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val secondAck = coordinator.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1, listOf("x" to 40), listOf(0)))
        val thirdAck = coordinator.applyPlaylistControl(board, 103, "moto",
            startControl("start-command-03", "start-request-03", 2, listOf("y" to 40), listOf(0)))

        assertEquals(BoardCommandStatus.COMMITTED, secondAck!!.status)
        assertEquals(BoardCommandStatus.COMMITTED, thirdAck!!.status)
        assertEquals(
            listOf("climb-a" to 40, "climb-b" to 45, "x" to 40, "y" to 40),
            coordinator.snapshot(board)!!.playlist.items,
        )
        assertNull(coordinator.snapshot(board)!!.playlist.proposal)
    }

    @Test fun `adding to a running board playlist keeps its current climb`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        val ack = coordinator.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1, listOf("x" to 40), listOf(0)))
        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        assertNull(playlist.proposal)
        assertEquals(0, playlist.currentIndex)
        assertEquals(listOf("climb-a" to 40, "climb-b" to 45, "x" to 40), playlist.items)
    }

    // ===== Rights =====

    @Test fun `board membership grants equal playlist edit rights`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia", "outsider"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val committed = coordinator.applyPlaylistCommand(board, 102, "edit-command-01",
            coordinator.snapshot(board)!!.playlistRevision, "outsider") { current, _ ->
            BoardPlaylistOps.add(current, "smuggled", 40)
        }

        assertNotNull(committed)
        assertEquals("smuggled", coordinator.snapshot(board)!!.playlist.items.last().first)
        assertEquals(BoardCommandStatus.COMMITTED, store.acks["edit-command-01"]!!.status)
    }

    @Test fun `every playlist member may edit, advance and step back`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))

        fun revision() = coordinator.snapshot(board)!!.playlistRevision
        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "guest-add", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.add(current, "climb-c", 40) })
        assertNotNull(coordinator.applyPlaylistCommand(board, 104, "guest-next", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.next(current, wallClock) })
        assertNotNull(coordinator.applyPlaylistCommand(board, 105, "guest-prev", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.previous(current) })
        assertNotNull(coordinator.applyPlaylistCommand(board, 106, "guest-move", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.move(current, 0, 2) })
        assertNotNull(coordinator.applyPlaylistCommand(board, 107, "guest-remove", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.remove(current, 0) })

        assertEquals(2, coordinator.snapshot(board)!!.playlist.items.size)
    }

    @Test fun `a gateway commits its joined leaf's edit without joining the playlist`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        // The gateway is already a normal Board member. Proxy authority is
        // still bounded to the leaf's queue verb; it grants no lifecycle role.
        assertTrue("controller" in coordinator.snapshot(board)!!.playlist.members)

        val committed = coordinator.applyPlaylistCommand(board, 102, "leaf-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "controller",
            BoardPlaylistAuthority.GATEWAY_PROXY) { current, _ ->
            BoardPlaylistOps.add(current, "climb-from-leaf", 40)
        }

        assertNotNull(committed)
        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(3, playlist.items.size)
        assertEquals("climb-from-leaf" to 40, playlist.items[2])
        // The leaf moved the queue and nothing else.
        assertEquals("nokia", playlist.hostId)
        assertEquals(setOf("nokia", "controller"), playlist.members.toSet())
    }

    @Test fun `the same gateway may also edit as a regular board member`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val committed = coordinator.applyPlaylistCommand(board, 102, "plain-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "controller",
            BoardPlaylistAuthority.MEMBER) { current, _ ->
            BoardPlaylistOps.add(current, "smuggled", 40)
        }

        assertNotNull(committed)
        assertEquals(BoardCommandStatus.COMMITTED, store.acks["plain-add-01"]!!.status)
    }

    @Test fun `a gateway proxy may retry the send but not start, end or host`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        fun revision() = coordinator.snapshot(board)!!.playlistRevision

        assertEquals(BoardCommandStatus.COMMITTED, coordinator.applyPlaylistControl(
            board, 102, "controller",
            BoardPlaylistControl.RetryProjection("leaf-retry-01", revision()),
            BoardPlaylistAuthority.GATEWAY_PROXY)!!.status)

        listOf(
            BoardPlaylistControl.End("leaf-end-01", revision()),
            BoardPlaylistControl.Join("leaf-join-01", revision()),
            BoardPlaylistControl.Leave("leaf-leave-01", revision()),
            startControl("leaf-start-01", "leaf-request-01", revision()),
        ).forEach { control ->
            val ack = coordinator.applyPlaylistControl(board, 103, "controller", control,
                BoardPlaylistAuthority.GATEWAY_PROXY)
            assertEquals(control.javaClass.simpleName,
                BoardCommandStatus.REJECTED_CONFLICT, ack!!.status)
        }
        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals("nokia", playlist.hostId)
        assertEquals(setOf("nokia", "controller"), playlist.members.toSet())
    }

    @Test fun `a proxy claim from a node outside the cell is refused`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val committed = coordinator.applyPlaylistCommand(board, 102, "stranger-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "not-in-this-cell",
            BoardPlaylistAuthority.GATEWAY_PROXY) { current, _ ->
            BoardPlaylistOps.add(current, "smuggled", 40)
        }

        assertNull(committed)
        assertEquals("gateway is not a cell member", store.acks["stranger-add-01"]!!.detail)
    }

    @Test fun `a legacy non-joinable playlist keeps working without playlist membership`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        assertNotNull(coordinator.replacePlaylist(board,
            BoardPlaylistState(7, 0, listOf("legacy" to 40)), 101, "legacy-command-01"))

        val committed = coordinator.applyPlaylistCommand(board, 102, "legacy-command-02",
            coordinator.snapshot(board)!!.playlistRevision, "nokia") { current, _ ->
            current.copy(items = current.items + ("legacy-b" to 40))
        }

        assertNotNull(committed)
        assertEquals(2, coordinator.snapshot(board)!!.playlist.items.size)
        assertFalse(coordinator.snapshot(board)!!.playlist.isJoinable)
    }

    // ===== Host handover, independent of the technical controller =====

    @Test fun `losing a playlist member to mesh eviction hands the playlist on`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel", "moto"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))
        coordinator.applyPlaylistControl(board, 103, "moto",
            BoardPlaylistControl.Join("join-command-02", 2))
        val revisionBefore = coordinator.snapshot(board)!!.playlistRevision

        coordinator.leaveMember(board, "nokia", BoardCellMemberLeaveReason.LIVENESS_TIMEOUT, 104)

        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals("controller", playlist.hostId)
        assertEquals(setOf("controller", "pixel", "moto"), playlist.members.toSet())
        assertEquals(listOf("climb-a" to 40, "climb-b" to 45), playlist.items)
        assertEquals(revisionBefore + 1, coordinator.snapshot(board)!!.playlistRevision)
    }

    @Test fun `a technical controller handover leaves the playlist host untouched`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))
        val before = coordinator.snapshot(board)!!.playlist

        coordinator.prepareHandover(board, "pixel", 103, "transfer-01")
        coordinator.sourceReleased(board, "transfer-01", 104)

        val after = coordinator.snapshot(board)!!
        assertEquals(HandoverPhase.SOURCE_RELEASED, after.handover!!.phase)
        assertEquals("pixel", after.handover.targetControllerId)
        // The technical role is moving; nothing about who owns the playlist is.
        assertEquals(before, after.playlist)
        assertEquals("nokia", after.playlist.hostId)
    }

    @Test fun `a board member cannot leave only the board playlist`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        coordinator.applyPlaylistControl(board, 102, "nokia",
            BoardPlaylistControl.Leave("leave-command-01", 1))

        assertTrue(coordinator.snapshot(board)!!.playlist.isJoinable)
        assertTrue("nokia" in coordinator.snapshot(board)!!.playlist.members)
        assertTrue("nokia" in coordinator.snapshot(board)!!.members)
    }

    // ===== Pending projection and retry =====

    @Test fun `a pending send survives a snapshot round trip and a retry clears it once`() = runTest {
        val (coordinator, transport) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "controller",
            BoardPlaylistControl.ProjectionPending("pending-command-01", 1,
                BoardPlaylistPendingProjection("climb-a", 40,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))

        val published = coordinator.snapshot(board)!!
        val pending = published.playlist.pendingProjection
        assertEquals(BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED, pending!!.reason)

        // A replica that only ever sees the snapshot must reach the same state.
        val replica = BoardCellCoordinator("nokia", transport, MemoryStore(), settleMs = 0)
        assertTrue(replica.restoreTrustedSnapshot(published, 103) is BoardCellApplyResult.Applied)
        assertEquals(pending, replica.snapshot(board)!!.playlist.pendingProjection)

        // The successful write is what clears it, in the reducer, so the
        // queue and the index cannot move a second time on a retry.
        val itemsBefore = coordinator.snapshot(board)!!.playlist.items
        val indexBefore = coordinator.snapshot(board)!!.playlist.currentIndex
        val result = coordinator.project(board, BoardProjection("climb-a", 40), 104,
            "retry-projection-01") { true }

        assertTrue(result is ProjectionResult.Committed)
        val after = coordinator.snapshot(board)!!.playlist
        assertNull(after.pendingProjection)
        assertEquals(itemsBefore, after.items)
        assertEquals(indexBefore, after.currentIndex)
    }

    @Test fun `a member cannot fake the pending-send state`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val ack = coordinator.applyPlaylistControl(board, 102, "nokia",
            BoardPlaylistControl.ProjectionPending("fake-pending-01", 1,
                BoardPlaylistPendingProjection("climb-a", 40,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)))

        assertEquals(BoardCommandStatus.REJECTED_CONFLICT, ack!!.status)
        assertNull(coordinator.snapshot(board)!!.playlist.pendingProjection)
    }

    @Test fun `projecting an entry the playlist does not hold leaves the pending state alone`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "controller",
            BoardPlaylistControl.ProjectionPending("pending-command-01", 1,
                BoardPlaylistPendingProjection("climb-a", 40,
                    BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE)))

        coordinator.project(board, BoardProjection("climb-b", 45), 103, "other-projection-01") { true }

        assertNotNull(coordinator.snapshot(board)!!.playlist.pendingProjection)
    }

    // ===== Rest state across a snapshot =====

    @Test fun `an active rest crosses a snapshot with the same canonical end`() = runTest {
        val (coordinator, transport) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistCommand(board, 102, "advance-command-01",
            coordinator.snapshot(board)!!.playlistRevision, "nokia") { current, _ ->
            BoardPlaylistOps.next(current, wallClock)
        }

        val published = coordinator.snapshot(board)!!
        val rest = published.playlist.activeRest!!
        assertEquals(120, rest.totalSeconds)
        assertEquals(1, rest.nextIndex)
        assertEquals(1L, rest.generation)
        assertEquals(wallClock + 120_000L, rest.endsAtEpochMs)

        val replica = BoardCellCoordinator("nokia", transport, MemoryStore(), settleMs = 0)
        assertTrue(replica.restoreTrustedSnapshot(published, 103) is BoardCellApplyResult.Applied)
        // The replica inherits the instant, so it counts down the remainder
        // rather than restarting the full two minutes.
        val restored = replica.snapshot(board)!!.playlist.activeRest!!
        assertEquals(rest, restored)
        assertEquals(80, restored.remainingSeconds(wallClock + 40_000L))
    }

    @Test fun `a technical controller handover keeps the same canonical rest end`() = runTest {
        val (coordinator, transport) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistCommand(board, 102, "advance-command-01",
            coordinator.snapshot(board)!!.playlistRevision, "nokia") { current, _ ->
            BoardPlaylistOps.next(current, wallClock)
        }
        val restBefore = coordinator.snapshot(board)!!.playlist.activeRest!!

        coordinator.prepareHandover(board, "pixel", 103, "transfer-rest-01")
        coordinator.sourceReleased(board, "transfer-rest-01", 104)
        val afterRelease = coordinator.snapshot(board)!!

        // The technical role is moving; the pause everybody is standing in
        // front of is not restarting because of it.
        assertEquals(restBefore, afterRelease.playlist.activeRest)
        val inheritor = BoardCellCoordinator("pixel", transport, MemoryStore(), settleMs = 0)
        assertTrue(inheritor.restoreTrustedSnapshot(afterRelease, 105) is BoardCellApplyResult.Applied)
        assertEquals(restBefore.endsAtEpochMs,
            inheritor.snapshot(board)!!.playlist.activeRest!!.endsAtEpochMs)
    }

    // ===== Concurrent revisioned commands =====

    @Test fun `two members editing from the same revision both land after a safe rebase`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))
        val shared = coordinator.snapshot(board)!!.playlistRevision

        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "add-c", shared, "nokia") { s, exact ->
            assertTrue(exact); BoardPlaylistOps.add(s, "climb-c", 40)
        })
        assertNotNull(coordinator.applyPlaylistCommand(board, 104, "add-d", shared, "pixel") { s, exact ->
            assertFalse(exact); BoardPlaylistOps.add(s, "climb-d", 40)
        })

        assertEquals(listOf("climb-a" to 40, "climb-b" to 45, "climb-c" to 40, "climb-d" to 40),
            coordinator.snapshot(board)!!.playlist.items)
    }

    @Test fun `a command claiming a revision ahead of the controller is rejected as stale`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val ack = coordinator.applyPlaylistControl(board, 102, "nokia",
            BoardPlaylistControl.Join("join-command-01", 99))

        assertEquals(BoardCommandStatus.REJECTED_STALE, ack!!.status)
        assertEquals(BoardCommandStatus.REJECTED_STALE, store.acks["join-command-01"]!!.status)
    }
}
