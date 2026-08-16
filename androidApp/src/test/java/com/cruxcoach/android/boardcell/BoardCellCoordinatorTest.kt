package com.cruxcoach.android.boardcell

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCellCoordinatorTest {
    @Test fun `projection request rebases over heartbeat but not user-visible state changes`() {
        val base = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 4,
            "controller", lineageId = "lineage", members = setOf("controller", "member"),
            projection = BoardProjection("old", 40), playlistRevision = 7).withComputedHash()
        val request = BoardProjectionRequest("projection-command", BoardProjection("next", 40),
            base.sequence, base.projection, base.playlistRevision)
        val heartbeat = BoardCellReplica.reduce(base, BoardCellEvent.ControllerHeartbeat(1), 5)

        assertEquals(heartbeat.sequence, request.semanticBaseSequence(heartbeat))
        assertEquals(base.sequence, request.semanticBaseSequence(
            heartbeat.copy(projection = BoardProjection("other", 40))))
        assertEquals(base.sequence, request.semanticBaseSequence(
            heartbeat.copy(playlistRevision = 8)))
    }

    private class RecordingTransport : BoardCellTransport {
        val events = mutableListOf<BoardCellEnvelope>()
        val snapshots = mutableListOf<BoardCellSnapshot>()
        val ready = mutableListOf<Pair<String, HandoverReady>>()
        val recoveries = mutableListOf<BoardCellControllerRecovery>()
        override suspend fun publishClaim(claim: BoardCellClaim) = Unit
        override suspend fun publishEvent(envelope: BoardCellEnvelope) { events += envelope }
        override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) { snapshots += snapshot }
        override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
        override suspend fun sendHandoverReady(target: String, ready: HandoverReady) { this.ready += target to ready }
        override suspend fun publishRecovery(recovery: BoardCellControllerRecovery) { recoveries += recovery }
    }

    private class MemoryStore(var crashAfterPhysicalSuccess: Boolean = false) : BoardCellDurableStore {
        val snapshots = mutableMapOf<PhysicalBoardId, BoardCellSnapshot>()
        val intents = mutableMapOf<PhysicalBoardId, BoardWriteIntent>()
        val acks = mutableMapOf<String, BoardCommandAck>()
        override fun persistSnapshot(snapshot: BoardCellSnapshot) { snapshots[snapshot.physicalBoardId] = snapshot }
        override fun persistSnapshotWithAck(snapshot: BoardCellSnapshot, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot; acks[ack.commandId] = ack
        }
        override fun persistIntent(intent: BoardWriteIntent) { intents[intent.physicalBoardId] = intent }
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) {
            intents[intent.physicalBoardId] = intent.copy(state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED)
            if (crashAfterPhysicalSuccess) throw SimulatedCrash()
        }
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot; intents.remove(intent.physicalBoardId); acks[ack.commandId] = ack
        }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) { if (intents[boardId]?.commandId == commandId) intents.remove(boardId) }
        override fun pendingIntent(boardId: PhysicalBoardId) = intents[boardId]
        override fun commandAck(commandId: String) = acks[commandId]
    }
    private class SimulatedCrash : RuntimeException()

    private suspend fun settled(node: String, board: PhysicalBoardId = PhysicalBoardId("board"),
        transport: RecordingTransport = RecordingTransport(), store: MemoryStore = MemoryStore(),
        now: Long = 100): Triple<BoardCellCoordinator, RecordingTransport, MemoryStore> {
        val coordinator = BoardCellCoordinator(node, transport, store, settleMs = 0,
            heartbeatTimeoutMs = 100, handoverTimeoutMs = 50)
        coordinator.beginClaim(board, BoardCellId.forPhysical(board), now)
        coordinator.settle(board, now)
        return Triple(coordinator, transport, store)
    }

    @Test fun `simultaneous claims settle deterministically before first write`() = runTest {
        val transport = RecordingTransport()
        val c = BoardCellCoordinator("node-b", transport, settleMs = 10)
        val board = PhysicalBoardId("kilter:serial:1")
        c.beginClaim(board, BoardCellId.forPhysical(board), 1_000)
        c.observeClaim(BoardCellClaim(board, BoardCellId.forPhysical(board), "node-a", 1, "lineage-a"), 1_000)
        assertNull(c.settle(board, 1_009))
        assertEquals("node-a", c.settle(board, 1_010)!!.controllerId)
        assertEquals(setOf("node-a", "node-b"), c.snapshot(board)!!.members)
    }

    @Test fun `two adjacent boards and concurrent commands retain exact physical commit order`() = runTest {
        val transport = RecordingTransport()
        val c = BoardCellCoordinator("n", transport, settleMs = 0)
        val a = PhysicalBoardId("kilter:serial:a"); val b = PhysicalBoardId("kilter:serial:b")
        c.beginClaim(a, BoardCellId.forPhysical(a), 10); c.settle(a, 10)
        c.beginClaim(b, BoardCellId.forPhysical(b), 10); c.settle(b, 10)
        val writes = mutableListOf<String>()
        val one = async { c.project(a, BoardProjection("one", 40), 11, "one", 0) { delay(5); writes += "one"; true } }
        val two = async { c.project(a, BoardProjection("two", 45), 11, "two", 0) { writes += "two"; true } }
        val results = listOf(one.await(), two.await())
        assertEquals(1, results.count { it is ProjectionResult.Committed })
        assertEquals(1, results.count { (it as? ProjectionResult.Refused)?.ack?.status == BoardCommandStatus.REJECTED_STALE })
        assertEquals(1, writes.size)
        assertNull(c.snapshot(b)!!.projection)
        assertEquals(writes.single(), c.snapshot(a)!!.projection!!.climbUuid)
    }

    @Test fun `monotonic heartbeat deadlines ignore remote wall clock skew and never elect`() = runTest {
        val board = PhysicalBoardId("moon:serial:clock")
        val (source) = settled("source", board, now = 5)
        source.joinMember(board, "replica")
        val snapshot = source.snapshot(board)!!
        val replica = BoardCellCoordinator("replica", settleMs = 0, heartbeatTimeoutMs = 100)
        assertTrue(replica.restoreTrustedSnapshot(snapshot, 1_000_000) is BoardCellApplyResult.Applied)
        replica.expireLocalDeadlines(1_000_099)
        assertEquals(BoardCellAvailability.ACTIVE, replica.snapshot(board)!!.availability)
        replica.expireLocalDeadlines(1_000_101)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER, replica.snapshot(board)!!.availability)
        assertEquals("source", replica.snapshot(board)!!.controllerId)
    }

    @Test fun `authenticated controller control traffic renews local lease`() = runTest {
        val board = PhysicalBoardId("moon:serial:control-liveness")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "replica")
        val replica = BoardCellCoordinator("replica", settleMs = 0, heartbeatTimeoutMs = 100)
        replica.restoreTrustedSnapshot(source.snapshot(board)!!, 1_000)

        assertTrue(replica.observeControllerActivity(board, "source", 1_099))
        replica.expireLocalDeadlines(1_100)
        assertEquals(BoardCellAvailability.ACTIVE, replica.snapshot(board)!!.availability)
        assertFalse(replica.observeControllerActivity(board, "replica", 1_150))
        replica.expireLocalDeadlines(1_199)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
            replica.snapshot(board)!!.availability)
    }

    @Test fun `snapshot gap does not prevent controller recovery after controller disappears`() = runTest {
        val board = PhysicalBoardId("moon:serial:gap-then-failure")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "replica")
        val initial = source.snapshot(board)!!
        val replica = BoardCellCoordinator("replica", settleMs = 0, heartbeatTimeoutMs = 100)
        replica.restoreTrustedSnapshot(initial, 1_000)
        val event = BoardCellEvent.ControllerHeartbeat(initial.controllerHeartbeat + 2)
        val skipped = BoardCellReplica.reduce(initial, event, initial.sequence + 2)
        val gap = BoardCellEnvelope(initial.cellId, board, initial.epoch, initial.controllerTerm,
            skipped.sequence, initial.stateHash, event, skipped.stateHash)

        assertTrue(replica.acceptEvent("source", gap, 1_050) is BoardCellApplyResult.NeedSnapshot)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT,
            replica.snapshot(board)!!.availability)
        replica.expireLocalDeadlines(1_150)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
            replica.snapshot(board)!!.availability)
        assertNotNull(replica.recoverController(board, "exclusive-gatt-proof", 1_151))
    }

    @Test fun `recovery priority is canonical despite divergent direct peer views`() {
        val members = (1..24).mapTo(sortedSetOf()) { "member-$it" } + "controller"
        val snapshot = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"), 1, 7,
            "controller", controllerTerm = 3, lineageId = "lineage", members = members)
            .withComputedHash()
        // These nodes may each see only themselves/directly adjacent peers;
        // delay calculation deliberately receives no topology view at all.
        val delays = members.filter { it != "controller" }.associateWith {
            BoardCellRecoveryElection.delayMs(snapshot, it, retry = 0)!!
        }
        assertEquals(delays, members.filter { it != "controller" }.associateWith {
            BoardCellRecoveryElection.delayMs(snapshot, it, retry = 0)!!
        })
        assertEquals(250L, delays.values.minOrNull())
        assertTrue(delays.values.maxOrNull()!! <= 2_750L)
        assertEquals(delays.getValue("member-1") + 1_500L,
            BoardCellRecoveryElection.delayMs(snapshot, "member-1", retry = 1))
    }

    @Test fun `controller recovery requires frozen state and advances one canonical term`() = runTest {
        val board = PhysicalBoardId("kilter:serial:recovery")
        val sourceTransport = RecordingTransport()
        val (source) = settled("source", board, sourceTransport, now = 100)
        source.joinMember(board, "candidate")
        source.joinMember(board, "observer")
        val base = source.snapshot(board)!!
        val candidateTransport = RecordingTransport()
        val candidate = BoardCellCoordinator("candidate", candidateTransport,
            settleMs = 0, heartbeatTimeoutMs = 10)
        val observer = BoardCellCoordinator("observer", settleMs = 0, heartbeatTimeoutMs = 10)
        candidate.restoreTrustedSnapshot(base, 100)
        observer.restoreTrustedSnapshot(base, 100)
        candidate.expireLocalDeadlines(111)
        observer.expireLocalDeadlines(111)

        val recovery = candidate.recoverController(board, "exclusive-gatt-proof", 112)!!
        assertEquals("candidate", candidate.snapshot(board)!!.controllerId)
        assertEquals(base.controllerTerm + 1, candidate.snapshot(board)!!.controllerTerm)
        assertEquals(BoardCellAvailability.ACTIVE, candidate.snapshot(board)!!.availability)
        assertTrue(observer.acceptControllerRecovery("candidate", recovery, 112) is BoardCellApplyResult.Applied)
        assertEquals("candidate", observer.snapshot(board)!!.controllerId)
        assertNull(observer.recoverController(board, "second-proof", 113))
    }

    @Test fun `same controller reclaims board after bluetooth restart with next term`() = runTest {
        val board = PhysicalBoardId("kilter:serial:bluetooth-restart")
        val (controller) = settled("controller", board, now = 100)
        controller.expireLocalDeadlines(200)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
            controller.snapshot(board)!!.availability)
        val oldTerm = controller.snapshot(board)!!.controllerTerm

        assertNotNull(controller.recoverController(board, "reconnected-board-proof", 201))
        assertEquals("controller", controller.snapshot(board)!!.controllerId)
        assertEquals(oldTerm + 1, controller.snapshot(board)!!.controllerTerm)
        assertEquals(BoardCellAvailability.ACTIVE, controller.snapshot(board)!!.availability)
        assertEquals(0L, BoardCellRecoveryElection.delayMs(
            controller.snapshot(board)!!.copy(controllerTerm = oldTerm), "controller", 0))
    }

    @Test fun `snapshot repairs a missed controller recovery event after reconnect`() = runTest {
        val board = PhysicalBoardId("kilter:serial:recovery-snapshot")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "candidate")
        source.joinMember(board, "observer")
        val base = source.snapshot(board)!!
        val candidate = BoardCellCoordinator("candidate", settleMs = 0, heartbeatTimeoutMs = 10)
        val observer = BoardCellCoordinator("observer", settleMs = 0, heartbeatTimeoutMs = 10)
        candidate.restoreTrustedSnapshot(base, 100)
        observer.restoreTrustedSnapshot(base, 100)
        candidate.expireLocalDeadlines(111)
        observer.expireLocalDeadlines(111)

        // The recovery event is lost while the observer is partitioned.
        candidate.recoverController(board, "exclusive-gatt-proof", 112)!!
        candidate.heartbeat(board, 113)
        val recoveredSnapshot = candidate.snapshot(board)!!

        assertTrue(observer.acceptSnapshot("candidate", recoveredSnapshot, 114) is BoardCellApplyResult.Applied)
        assertEquals("candidate", observer.snapshot(board)!!.controllerId)
        assertEquals(recoveredSnapshot.stateHash, observer.snapshot(board)!!.stateHash)
        assertEquals(BoardCellAvailability.ACTIVE, observer.snapshot(board)!!.availability)
    }

    @Test fun `member cannot disguise recovery metadata behind a legacy hash`() = runTest {
        val board = PhysicalBoardId("kilter:serial:legacy-recovery")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "member")
        val current = source.snapshot(board)!!
        val forged = current.copy(
            controllerId = "member",
            controllerTerm = current.controllerTerm + 1,
            sequence = current.sequence + 1,
            lastControllerRecovery = BoardCellControllerRecoveryProof(
                "member", current.controllerId, current.controllerTerm,
                current.sequence, current.stateHash, "made-up-proof",
            ),
            stateHash = "",
        )
        val legacyHash = BoardCellHash.computeLegacyV3(forged)
        assertFalse(forged.copy(stateHash = legacyHash).hasValidHash())
    }

    @Test fun `handover requires prepared target readiness commit and completion across two coordinators`() = runTest {
        val board = PhysicalBoardId("kilter:serial:handover")
        val sourceTransport = RecordingTransport(); val targetTransport = RecordingTransport()
        val (source) = settled("source", board, sourceTransport, now = 100)
        source.joinMember(board, "target")
        val target = BoardCellCoordinator("target", targetTransport, settleMs = 0, heartbeatTimeoutMs = 100)
        target.restoreTrustedSnapshot(source.snapshot(board)!!, 100)

        val prepared = source.prepareHandover(board, "target", 101, "tx")!!
        assertTrue(target.acceptEvent("source", prepared, 101) is BoardCellApplyResult.Applied)
        target.targetReady(board, "too-early")
        assertTrue(targetTransport.ready.isEmpty())
        val released = source.sourceReleased(board, "tx", 102)!!
        assertTrue(target.acceptEvent("source", released, 102) is BoardCellApplyResult.Applied)
        target.targetReady(board, "board-and-host-ready")
        val ready = targetTransport.ready.single().second
        assertTrue(source.acceptTargetReady("target", ready, 103))
        val readyEvent = sourceTransport.events.last()
        assertTrue(target.acceptEvent("source", readyEvent, 103) is BoardCellApplyResult.Applied)
        val committed = source.commitHandover(board, "tx", 104)!!
        assertTrue(target.acceptEvent("source", committed, 104) is BoardCellApplyResult.Applied)
        assertEquals(2, target.snapshot(board)!!.controllerTerm)
        assertTrue(source.project(board, BoardProjection("old", 40), 105) { true } is ProjectionResult.Refused)
        val completed = target.completeHandover(board, "tx", 105)!!
        assertTrue(source.acceptEvent("target", completed, 105) is BoardCellApplyResult.Applied)
        assertEquals(HandoverPhase.COMPLETED, source.snapshot(board)!!.handover!!.phase)
        assertTrue(target.project(board, BoardProjection("new", 40), 106) { true } is ProjectionResult.Committed)
    }

    @Test fun `unready target times out and aborts only before commit`() = runTest {
        val board = PhysicalBoardId("board-timeout")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "target")
        source.prepareHandover(board, "target", 101, "timeout")
        source.sourceReleased(board, "timeout", 102)
        source.expireLocalDeadlines(152)
        assertEquals(HandoverPhase.ABORTED, source.snapshot(board)!!.handover!!.phase)
        assertEquals("source", source.snapshot(board)!!.controllerId)
    }

    @Test fun `source and target restart safely at every persisted handover boundary`() = runTest {
        val board = PhysicalBoardId("board-phase-crash")
        val sourceStore = MemoryStore(); val targetStore = MemoryStore()
        val sourceTransport = RecordingTransport(); val targetTransport = RecordingTransport()
        val (source) = settled("source", board, sourceTransport, sourceStore, 100)
        source.joinMember(board, "target")
        val target = BoardCellCoordinator("target", targetTransport, targetStore, heartbeatTimeoutMs = 100)
        target.restoreTrustedSnapshot(source.snapshot(board)!!, 100)

        val prepared = source.prepareHandover(board, "target", 101, "phase-crash")!!
        target.acceptEvent("source", prepared, 101)

        // Source crash in PREPARED: the local monotonic timeout is rebuilt,
        // never inferred from a remote wall clock.
        val sourceAfterPrepare = BoardCellCoordinator("source", sourceTransport, sourceStore,
            heartbeatTimeoutMs = 100, handoverTimeoutMs = 50)
        sourceAfterPrepare.restoreTrustedSnapshot(sourceStore.snapshots.getValue(board), 1_000)
        sourceAfterPrepare.expireLocalDeadlines(1_049)
        assertEquals(HandoverPhase.PREPARED, sourceAfterPrepare.snapshot(board)!!.handover!!.phase)

        // Target crash in PREPARED still cannot become ready before the source
        // has explicitly released the single-connection board.
        val targetAfterPrepare = BoardCellCoordinator("target", targetTransport, targetStore,
            heartbeatTimeoutMs = 100)
        targetAfterPrepare.restoreTrustedSnapshot(targetStore.snapshots.getValue(board), 2_000)
        targetTransport.ready.clear()
        targetAfterPrepare.targetReady(board, "too-early-after-restart")
        assertTrue(targetTransport.ready.isEmpty())
        val released = sourceAfterPrepare.sourceReleased(board, "phase-crash", 1_049)!!
        assertTrue(targetAfterPrepare.acceptEvent("source", released, 2_001) is BoardCellApplyResult.Applied)
        targetAfterPrepare.targetReady(board, "ready-after-release")
        val ready = targetTransport.ready.single().second
        assertTrue(sourceAfterPrepare.acceptTargetReady("target", ready, 1_050))
        targetAfterPrepare.acceptEvent("source", sourceTransport.events.last(), 2_002)

        // Source crash in TARGET_READY accepts an idempotent READY retry and commits once.
        val sourceAfterReady = BoardCellCoordinator("source", sourceTransport, sourceStore,
            heartbeatTimeoutMs = 100, handoverTimeoutMs = 50)
        sourceAfterReady.restoreTrustedSnapshot(sourceStore.snapshots.getValue(board), 3_000)
        assertTrue(sourceAfterReady.acceptTargetReady("target", ready, 3_001))
        val commit = sourceAfterReady.commitHandover(board, "phase-crash", 3_002)!!
        targetAfterPrepare.acceptEvent("source", commit, 2_003)

        // Target crash in COMMITTED resumes as the persisted new term/controller and completes.
        val targetAfterCommit = BoardCellCoordinator("target", targetTransport, targetStore,
            heartbeatTimeoutMs = 100)
        targetAfterCommit.restoreTrustedSnapshot(targetStore.snapshots.getValue(board), 4_000)
        val complete = targetAfterCommit.completeHandover(board, "phase-crash", 4_001)!!

        // Source crash after COMMIT cannot regain authority; it only observes completion.
        val sourceAfterCommit = BoardCellCoordinator("source", sourceTransport, sourceStore,
            heartbeatTimeoutMs = 100)
        sourceAfterCommit.restoreTrustedSnapshot(sourceStore.snapshots.getValue(board), 5_000)
        assertTrue(sourceAfterCommit.project(board, BoardProjection("forbidden", 40), 5_001) { true }
            is ProjectionResult.Refused)
        assertTrue(sourceAfterCommit.acceptEvent("target", complete, 5_001) is BoardCellApplyResult.Applied)
        assertEquals(HandoverPhase.COMPLETED, sourceAfterCommit.snapshot(board)!!.handover!!.phase)
    }

    @Test fun `write success then crash before commit restores unknown frozen and requires reproject`() = runTest {
        val board = PhysicalBoardId("board-crash")
        val store = MemoryStore(crashAfterPhysicalSuccess = true)
        val (first) = settled("controller", board, store = store, now = 100)
        var writes = 0
        assertThrows(SimulatedCrash::class.java) {
            kotlinx.coroutines.test.runTest { first.project(board, BoardProjection("maybe", 40), 101, "cmd") { writes++; true } }
        }
        assertEquals(1, writes)
        store.crashAfterPhysicalSuccess = false
        val recovered = BoardCellCoordinator("controller", durableStore = store, settleMs = 0)
        recovered.restoreTrustedSnapshot(store.snapshots.getValue(board), 200)
        recovered.recoverPendingWrite(board)
        assertEquals(BoardCellAvailability.FROZEN_WRITE_RECOVERY, recovered.snapshot(board)!!.availability)
        assertFalse(recovered.snapshot(board)!!.projectionKnown)
        assertTrue(recovered.project(board, BoardProjection("unsafe", 40), 201) { true } is ProjectionResult.Refused)
        assertTrue(recovered.reprojectAfterRecovery(board, BoardProjection("operator", 40), 201) { true }
            is ProjectionResult.Committed)
        assertEquals(BoardCellAvailability.ACTIVE, recovered.snapshot(board)!!.availability)
    }

    @Test fun `failed and duplicate commands are durable and never repeat physical write`() = runTest {
        val board = PhysicalBoardId("board-idempotent"); val store = MemoryStore()
        val (c) = settled("controller", board, store = store, now = 100)
        var writes = 0
        val failed = c.project(board, BoardProjection("x", 40), 101, "failed") { writes++; false }
        assertEquals(BoardCommandStatus.BOARD_WRITE_FAILED, (failed as ProjectionResult.BoardWriteFailed).ack.status)
        val retryFailed = c.project(board, BoardProjection("x", 40), 102, "failed") { writes++; true }
        assertTrue(retryFailed is ProjectionResult.Refused)
        val committed = c.project(board, BoardProjection("y", 40), 103, "ok") { writes++; true }
        assertTrue(committed is ProjectionResult.Committed)
        assertTrue(c.project(board, BoardProjection("y", 40), 104, "ok") { writes++; true } is ProjectionResult.Duplicate)
        assertEquals(2, writes)
    }

    @Test fun `playlist command and terminal rejections remain idempotent across restart`() = runTest {
        val board = PhysicalBoardId("board-playlist-acks"); val store = MemoryStore()
        val (first) = settled("controller", board, store = store, now = 100)
        val playlist = BoardPlaylistState(42, 0, listOf("climb" to 40))

        assertNotNull(first.replacePlaylist(board, playlist, 101, "playlist-ok", 0))
        assertEquals(BoardCommandStatus.COMMITTED, store.acks.getValue("playlist-ok").status)
        assertNull(first.replacePlaylist(board, playlist, 102, "playlist-ok", 0))
        assertEquals(1, first.snapshot(board)!!.sequence)

        val restarted = BoardCellCoordinator("controller", durableStore = store, heartbeatTimeoutMs = 100)
        restarted.restoreTrustedSnapshot(store.snapshots.getValue(board), 1_000)
        assertNull(restarted.replacePlaylist(board, playlist, 1_001, "playlist-ok", 0))
        assertNull(restarted.replacePlaylist(board, playlist, 1_001, "playlist-stale", 0))
        assertEquals(BoardCommandStatus.REJECTED_STALE, store.acks.getValue("playlist-stale").status)
        assertNull(restarted.replacePlaylist(board, playlist, 1_002, "playlist-stale", 1))
        assertEquals(1, restarted.snapshot(board)!!.sequence)

        val participantStore = MemoryStore()
        val participant = BoardCellCoordinator("participant", durableStore = participantStore,
            heartbeatTimeoutMs = 100)
        val participantSnapshot = restarted.snapshot(board)!!.copy(
            members = setOf("controller", "participant"),
        ).withComputedHash()
        participant.restoreTrustedSnapshot(participantSnapshot, 2_000)
        var writes = 0
        val refused = participant.project(board, BoardProjection("forbidden", 40), 2_001,
            "participant-command", participantSnapshot.sequence) { writes++; true }
        assertEquals(BoardCommandStatus.NOT_CONTROLLER, (refused as ProjectionResult.Refused).ack!!.status)
        assertEquals(BoardCommandStatus.NOT_CONTROLLER,
            participantStore.acks.getValue("participant-command").status)
        assertEquals(0, writes)
    }

    @Test fun `concurrent playlist commands validate before mutating local session state`() = runTest {
        val board = PhysicalBoardId("board-command-order"); val store = MemoryStore()
        val (coordinator) = settled("controller", board, store = store, now = 100)
        val mutations = mutableListOf<String>()

        assertNotNull(coordinator.replacePlaylistAfterValidation(
            board, 101, "command-first", 0) {
            mutations += "first"
            BoardPlaylistState(7, 0, listOf("first" to 40))
        })
        assertNull(coordinator.replacePlaylistAfterValidation(
            board, 101, "command-second", 0) {
            mutations += "second"
            BoardPlaylistState(7, 0, listOf("second" to 40))
        })

        assertEquals(listOf("first"), mutations)
        assertEquals(listOf("first" to 40), coordinator.snapshot(board)!!.playlist.items)
        assertEquals(BoardCommandStatus.REJECTED_STALE, store.acks.getValue("command-second").status)
    }

    @Test fun `playlist revision ignores heartbeat and permits semantic rebase`() = runTest {
        val board = PhysicalBoardId("board-playlist-revision"); val store = MemoryStore()
        val (coordinator) = settled("controller", board, store = store, now = 100)
        val initial = BoardPlaylistState(7, 0, listOf("a" to 40))
        assertNotNull(coordinator.replacePlaylist(board, initial, 101, "initial"))
        val baseRevision = coordinator.snapshot(board)!!.playlistRevision
        coordinator.heartbeat(board, 102)

        val rebased = coordinator.applyPlaylistCommand(board, 103, "semantic-add",
            baseRevision) { current, exact ->
            assertTrue(exact) // sequence changed, playlist revision did not
            current.copy(items = current.items + ("b" to 40))
        }

        assertNotNull(rebased)
        assertEquals(listOf("a" to 40, "b" to 40), coordinator.snapshot(board)!!.playlist.items)
        assertEquals(baseRevision + 1, coordinator.snapshot(board)!!.playlistRevision)
    }

    @Test fun `two commands from one playlist revision may both commit after safe rebase`() = runTest {
        val board = PhysicalBoardId("board-safe-rebase"); val store = MemoryStore()
        val (coordinator) = settled("controller", board, store = store, now = 100)
        assertNotNull(coordinator.replacePlaylist(board,
            BoardPlaylistState(7, 0, listOf("a" to 40)), 101, "initial-safe-rebase"))
        val base = coordinator.snapshot(board)!!.playlistRevision

        assertNotNull(coordinator.applyPlaylistCommand(board, 102, "add-b", base) { state, _ ->
            state.copy(items = state.items + ("b" to 40))
        })
        assertNotNull(coordinator.applyPlaylistCommand(board, 103, "add-c", base) { state, exact ->
            assertFalse(exact)
            state.copy(items = state.items + ("c" to 40))
        })

        assertEquals(listOf("a" to 40, "b" to 40, "c" to 40),
            coordinator.snapshot(board)!!.playlist.items)
        assertEquals(BoardCommandStatus.COMMITTED, store.acks.getValue("add-b").status)
        assertEquals(BoardCommandStatus.COMMITTED, store.acks.getValue("add-c").status)
        assertTrue(coordinator.snapshot(board)!!.recentCommandIds.containsAll(listOf("add-b", "add-c")))
    }

    @Test fun `participant can never write physical board directly`() = runTest {
        val board = PhysicalBoardId("board-participant")
        val (source) = settled("source", board, now = 100)
        source.joinMember(board, "participant")
        val participant = BoardCellCoordinator("participant", heartbeatTimeoutMs = 100)
        participant.restoreTrustedSnapshot(source.snapshot(board)!!, 100)
        var writes = 0
        assertTrue(participant.project(board, BoardProjection("x", 40), 101) { writes++; true } is ProjectionResult.Refused)
        assertEquals(0, writes)
    }

    @Test fun `late meeting of independently settled controllers freezes both histories`() = runTest {
        val board = PhysicalBoardId("board-fork")
        val at = RecordingTransport(); val bt = RecordingTransport()
        val (a) = settled("a", board, transport = at, now = 100)
        val (b) = settled("b", board, transport = bt, now = 100)
        a.joinMember(board, "b"); b.joinMember(board, "a")
        a.project(board, BoardProjection("a-climb", 40), 101) { true }
        b.project(board, BoardProjection("b-climb", 40), 101) { true }
        assertTrue(a.acceptSnapshot("b", b.snapshot(board)!!, 102) is BoardCellApplyResult.Fork)
        assertTrue(b.acceptSnapshot("a", a.snapshot(board)!!, 102) is BoardCellApplyResult.Fork)
        assertEquals(BoardCellAvailability.FROZEN_FORK, a.snapshot(board)!!.availability)
        assertEquals(BoardCellAvailability.FROZEN_FORK, b.snapshot(board)!!.availability)
        val (winner, loser, winnerTransport) = if (a.snapshot(board)!!.lineageId < b.snapshot(board)!!.lineageId)
            Triple(a, b, at) else Triple(b, a, bt)
        assertNotNull(winner.operatorRecoverFork(board, 103))
        val resolution = winnerTransport.snapshots.last()
        assertTrue(loser.acceptSnapshot(resolution.controllerId, resolution, 104) is BoardCellApplyResult.Applied)
        assertEquals(BoardCellAvailability.FROZEN_WRITE_RECOVERY, loser.snapshot(board)!!.availability)
        assertFalse(loser.snapshot(board)!!.projectionKnown)
    }
}
