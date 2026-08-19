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
        items: List<BoardPlaylistEntry> = listOf(
            BoardPlaylistEntry("owner-a", "climb-a", 40),
            BoardPlaylistEntry("owner-b", "climb-b", 45)),
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
        assertEquals(listOf("nokia"), playlist.members)
        assertEquals(listOf(
            BoardPlaylistEntry("nokia", "climb-a", 40),
            BoardPlaylistEntry("nokia", "climb-b", 45)), playlist.items)
        assertEquals(0, playlist.currentIndex)
        assertEquals(1L, coordinator.snapshot(board)!!.playlistRevision)
        // The controller is the serializer and nothing more: it is not the
        // playlist host and did not join the playlist.
        assertNotEquals("controller", playlist.hostId)
        assertFalse("controller" in playlist.members)
        assertTrue(store.acks.containsKey("start-command-01"))
    }

    @Test fun `a controller that is not a playlist member never becomes one by serializing`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))

        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(listOf("nokia", "pixel"), playlist.members)
        assertTrue("controller" in coordinator.snapshot(board)!!.members)
        assertFalse("controller" in playlist.members)
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

    // ===== Concurrent requests and the 30 s window =====

    @Test fun `a second start becomes a request and a third is told it is busy`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel", "moto"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val proposalAck = coordinator.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1,
                listOf(BoardPlaylistEntry("pixel", "x", 40)), listOf(0)))
        val busyAck = coordinator.applyPlaylistControl(board, 103, "moto",
            startControl("start-command-03", "start-request-03", 2,
                listOf(BoardPlaylistEntry("moto", "y", 40)), listOf(0)))

        assertEquals(BoardCommandStatus.COMMITTED, proposalAck!!.status)
        assertEquals(BoardCommandStatus.REJECTED_CONFLICT, busyAck!!.status)
        assertTrue(busyAck.detail!!, busyAck.detail!!.contains("already open"))
        assertEquals("start-request-02", coordinator.snapshot(board)!!.playlist.proposal!!.requestId)
    }

    @Test fun `an unanswered request expires after thirty seconds as a rejection`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1,
                listOf(BoardPlaylistEntry("pixel", "x", 40)), listOf(0)))
        assertNotNull(coordinator.snapshot(board)!!.playlist.proposal)

        assertEquals(wallClock + BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS,
            coordinator.snapshot(board)!!.playlist.proposal!!.expiresAtEpochMs)

        clockNow = wallClock + BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS - 1
        coordinator.expireLocalDeadlines(103)
        assertNotNull("must not expire early", coordinator.snapshot(board)!!.playlist.proposal)

        clockNow = wallClock + BoardPlaylistPolicy.PROPOSAL_TIMEOUT_MS
        coordinator.expireLocalDeadlines(104)

        val playlist = coordinator.snapshot(board)!!.playlist
        assertNull(playlist.proposal)
        // A timeout is a refusal: nothing about the running playlist moved and
        // the requester did not quietly become a member.
        assertEquals(listOf(
            BoardPlaylistEntry("nokia", "climb-a", 40),
            BoardPlaylistEntry("nokia", "climb-b", 45)), playlist.items)
        assertEquals(listOf("nokia"), playlist.members)
    }

    @Test fun `a controller handover does not grant the request another full window`() = runTest {
        val (source, transport) = cell(members = arrayOf("nokia", "pixel"))
        source.applyPlaylistControl(board, 101, "nokia", startControl())
        source.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1,
                listOf(BoardPlaylistEntry("pixel", "x", 40)), listOf(0)))
        val published = source.snapshot(board)!!
        val deadline = published.playlist.proposal!!.expiresAtEpochMs

        // A coordinator that adopts the state 25 s in — a restart, or a
        // technical controller handover — inherits the original deadline. A
        // controller-local monotonic timer restarted the promised 30 s here,
        // so the open state survived but the timeout it promised did not.
        var inheritorNow = wallClock + 25_000
        val inheritor = BoardCellCoordinator("controller", transport, MemoryStore(),
            settleMs = 0, heartbeatTimeoutMs = 100_000, wallClockEpochMs = { inheritorNow })
        assertTrue(inheritor.restoreTrustedSnapshot(published, 9_000_000)
            is BoardCellApplyResult.Applied)
        assertEquals(deadline, inheritor.snapshot(board)!!.playlist.proposal!!.expiresAtEpochMs)

        inheritor.expireLocalDeadlines(9_000_000)
        assertNotNull("25 s in, the request is still open",
            inheritor.snapshot(board)!!.playlist.proposal)

        // Five more seconds is the whole remaining window, not thirty more.
        inheritorNow = deadline
        inheritor.expireLocalDeadlines(9_000_100)
        assertNull(inheritor.snapshot(board)!!.playlist.proposal)
    }

    @Test fun `a controller adopting an already expired request declines it at once`() = runTest {
        val (source, transport) = cell(members = arrayOf("nokia", "pixel"))
        source.applyPlaylistControl(board, 101, "nokia", startControl())
        source.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1,
                listOf(BoardPlaylistEntry("pixel", "x", 40)), listOf(0)))
        val published = source.snapshot(board)!!

        val inheritorNow = published.playlist.proposal!!.expiresAtEpochMs + 60_000
        val inheritor = BoardCellCoordinator("controller", transport, MemoryStore(),
            settleMs = 0, heartbeatTimeoutMs = 100_000, wallClockEpochMs = { inheritorNow })
        assertTrue(inheritor.restoreTrustedSnapshot(published, 9_000_000)
            is BoardCellApplyResult.Applied)

        inheritor.expireLocalDeadlines(9_000_000)

        val playlist = inheritor.snapshot(board)!!.playlist
        assertNull(playlist.proposal)
        assertEquals(listOf("nokia"), playlist.members)
        assertEquals(listOf(
            BoardPlaylistEntry("nokia", "climb-a", 40),
            BoardPlaylistEntry("nokia", "climb-b", 45)), playlist.items)
    }

    @Test fun `the host replacing a playlist installs the requested queue`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            startControl("start-command-02", "start-request-02", 1,
                listOf(BoardPlaylistEntry("pixel", "x", 40)), listOf(30)))

        val ack = coordinator.applyPlaylistControl(board, 103, "nokia", BoardPlaylistControl.Decide(
            "decide-command-01", 2, "start-request-02", BoardPlaylistProposalDecision.REPLACE))

        assertEquals(BoardCommandStatus.COMMITTED, ack!!.status)
        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(listOf(BoardPlaylistEntry("pixel", "x", 40)), playlist.items)
        assertEquals(listOf(30), playlist.restAfterSeconds)
        assertEquals("nokia", playlist.hostId)
        assertEquals(listOf("nokia", "pixel"), playlist.members)
    }

    // ===== Rights =====

    @Test fun `mesh membership alone does not grant playlist edit rights`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia", "outsider"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        val before = coordinator.snapshot(board)!!.playlist

        val committed = coordinator.applyPlaylistCommand(board, 102, "edit-command-01",
            coordinator.snapshot(board)!!.playlistRevision, "outsider") { current, _ ->
            BoardPlaylistOps.add(current, "outsider", "smuggled", 40)
        }

        assertNull(committed)
        assertEquals(before, coordinator.snapshot(board)!!.playlist)
        assertEquals(BoardCommandStatus.REJECTED_CONFLICT, store.acks["edit-command-01"]!!.status)
        assertEquals("not a playlist member", store.acks["edit-command-01"]!!.detail)
    }

    @Test fun `every playlist member may edit, advance and step back`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))

        fun revision() = coordinator.snapshot(board)!!.playlistRevision
        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "guest-add", revision(), "pixel") {
                current, _ -> BoardPlaylistOps.add(current, "pixel", "climb-c", 40) })
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
        // "controller" is the technical controller and an authenticated cell
        // member, but it never joined the playlist. Its API-28 leaf did join,
        // over GATT, and that is what this bounded authority represents.
        assertFalse("controller" in coordinator.snapshot(board)!!.playlist.members)

        val committed = coordinator.applyPlaylistCommand(board, 102, "leaf-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "controller",
            BoardPlaylistAuthority.GATEWAY_PROXY) { current, _ ->
            BoardPlaylistOps.add(current, "controller", "climb-from-leaf", 40)
        }

        assertNotNull(committed)
        val playlist = coordinator.snapshot(board)!!.playlist
        assertEquals(3, playlist.items.size)
        assertEquals(BoardPlaylistEntry("controller", "climb-from-leaf", 40), playlist.items[2])
        // The leaf moved the queue and nothing else.
        assertEquals("nokia", playlist.hostId)
        assertEquals(listOf("nokia"), playlist.members)
    }

    @Test fun `the same gateway without proxy authority is still refused`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val committed = coordinator.applyPlaylistCommand(board, 102, "plain-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "controller",
            BoardPlaylistAuthority.MEMBER) { current, _ ->
            BoardPlaylistOps.add(current, "controller", "smuggled", 40)
        }

        assertNull(committed)
        assertEquals(BoardCommandStatus.REJECTED_CONFLICT, store.acks["plain-add-01"]!!.status)
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
        assertEquals(listOf("nokia"), playlist.members)
    }

    @Test fun `a proxy claim from a node outside the cell is refused`() = runTest {
        val (coordinator, _, store) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        val committed = coordinator.applyPlaylistCommand(board, 102, "stranger-add-01",
            coordinator.snapshot(board)!!.playlistRevision, "not-in-this-cell",
            BoardPlaylistAuthority.GATEWAY_PROXY) { current, _ ->
            BoardPlaylistOps.add(current, "not-in-this-cell", "smuggled", 40)
        }

        assertNull(committed)
        assertEquals("gateway is not a cell member", store.acks["stranger-add-01"]!!.detail)
    }

    @Test fun `a legacy non-joinable playlist keeps working without playlist membership`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        assertNotNull(coordinator.replacePlaylist(board,
            BoardPlaylistState(7, 0, listOf(BoardPlaylistEntry("", "legacy", 40))),
            101, "legacy-command-01"))

        val committed = coordinator.applyPlaylistCommand(board, 102, "legacy-command-02",
            coordinator.snapshot(board)!!.playlistRevision, "nokia") { current, _ ->
            current.copy(items = current.items + BoardPlaylistEntry("", "legacy-b", 40))
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
        assertEquals("pixel", playlist.hostId)
        assertEquals(listOf("pixel", "moto"), playlist.members)
        assertEquals(listOf(
            BoardPlaylistEntry("nokia", "climb-a", 40),
            BoardPlaylistEntry("nokia", "climb-b", 45)), playlist.items)
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

    @Test fun `the last playlist member leaving ends the playlist without ending the cell`() = runTest {
        val (coordinator) = cell(members = arrayOf("nokia"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())

        coordinator.applyPlaylistControl(board, 102, "nokia",
            BoardPlaylistControl.Leave("leave-command-01", 1))

        assertEquals(BoardPlaylistState(), coordinator.snapshot(board)!!.playlist)
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
        val (coordinator, transport) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))
        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "add-pixel",
            coordinator.snapshot(board)!!.playlistRevision, "pixel") { current, _ ->
                BoardPlaylistOps.add(current, "pixel", "climb-b", 45) })
        coordinator.applyPlaylistCommand(board, 104, "advance-command-01",
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
        assertTrue(replica.restoreTrustedSnapshot(published, 105) is BoardCellApplyResult.Applied)
        // The replica inherits the instant, so it counts down the remainder
        // rather than restarting the full two minutes.
        val restored = replica.snapshot(board)!!.playlist.activeRest!!
        assertEquals(rest, restored)
        assertEquals(80, restored.remainingSeconds(wallClock + 40_000L))
    }

    @Test fun `a technical controller handover keeps the same canonical rest end`() = runTest {
        val (coordinator, transport) = cell(members = arrayOf("nokia", "pixel"))
        coordinator.applyPlaylistControl(board, 101, "nokia", startControl())
        coordinator.applyPlaylistControl(board, 102, "pixel",
            BoardPlaylistControl.Join("join-command-01", 1))
        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "add-pixel",
            coordinator.snapshot(board)!!.playlistRevision, "pixel") { current, _ ->
                BoardPlaylistOps.add(current, "pixel", "climb-b", 45) })
        coordinator.applyPlaylistCommand(board, 104, "advance-command-01",
            coordinator.snapshot(board)!!.playlistRevision, "nokia") { current, _ ->
                BoardPlaylistOps.next(current, wallClock)
            }
        val restBefore = coordinator.snapshot(board)!!.playlist.activeRest!!

        coordinator.prepareHandover(board, "pixel", 105, "transfer-rest-01")
        coordinator.sourceReleased(board, "transfer-rest-01", 106)
        val afterRelease = coordinator.snapshot(board)!!

        // The technical role is moving; the pause everybody is standing in
        // front of is not restarting because of it.
        assertEquals(restBefore, afterRelease.playlist.activeRest)
        val inheritor = BoardCellCoordinator("pixel", transport, MemoryStore(), settleMs = 0)
        assertTrue(inheritor.restoreTrustedSnapshot(afterRelease, 107) is BoardCellApplyResult.Applied)
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
            assertTrue(exact); BoardPlaylistOps.add(s, "nokia", "climb-c", 40)
        })
        assertNotNull(coordinator.applyPlaylistCommand(board, 104, "add-d", shared, "pixel") { s, exact ->
            assertFalse(exact); BoardPlaylistOps.add(s, "pixel", "climb-d", 40)
        })

        assertEquals(listOf(
            BoardPlaylistEntry("nokia", "climb-a", 40),
            BoardPlaylistEntry("nokia", "climb-b", 45),
            BoardPlaylistEntry("nokia", "climb-c", 40),
            BoardPlaylistEntry("pixel", "climb-d", 40)),
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
