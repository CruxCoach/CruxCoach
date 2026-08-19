package com.cruxcoach.android.boardcell

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Fenced controller recovery, reproducing the 2026-08-17 Nokia capture.
 *
 * What happened there: the controller vanished without a handover, a
 * `MemberLeft` or a successor snapshot. The Nokia froze on schedule at
 * 19:51:33, but recovery was gated on the controller still appearing in the
 * radio's direct-peer set — and that set kept the dead controller for another
 * minute after its L2CAP channel closed at 19:51:45. Recovery only began at
 * 19:52:45, and when it finally reconnected the board, the ordinary
 * board-selection path rebuilt the coordinator and dropped the very snapshot
 * it was about to recover from.
 */
class BoardCellRecoveryFenceTest {

    @After fun reset() = BoardCellScopeRegistry.resetForTest()

    private val board = PhysicalBoardId("board-recovery")
    private val cell = BoardCellId.forPhysical(board)
    private val leaseTimeoutMs = 6_000L

    private fun snapshot(
        availability: BoardCellAvailability = BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
        controller: String = "old-controller",
        members: Set<String> = setOf("old-controller", "nokia", "pixel"),
        term: Long = 1,
        playlist: BoardPlaylistState = BoardPlaylistState(),
    ) = BoardCellSnapshot(
        cellId = cell, physicalBoardId = board, epoch = 1, sequence = 12,
        controllerId = controller, controllerTerm = term, lineageId = "lineage",
        members = members, availability = availability, playlist = playlist,
    ).withComputedHash()

    // ===== The stale direct-peer entry must not gate the election =====

    @Test fun `a frozen cell recovers on canonical silence, whatever the radio still lists`() {
        // The controller is *still* a direct authenticated peer as far as the
        // native layer is concerned. That is precisely the stale cache from the
        // capture, and it must not decide anything.
        assertTrue(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(), "nokia", controllerSilentMs = leaseTimeoutMs, leaseTimeoutMs = leaseTimeoutMs))
        assertTrue(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(), "nokia", controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
    }

    @Test fun `a controller still inside its lease is never taken over`() {
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(), "nokia", controllerSilentMs = leaseTimeoutMs - 1,
            leaseTimeoutMs = leaseTimeoutMs))
    }

    @Test fun `an active cell is never recovered from`() {
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(availability = BoardCellAvailability.ACTIVE), "nokia",
            controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
        // A cell frozen for some other reason is not a controller problem.
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(availability = BoardCellAvailability.FROZEN_FORK), "nokia",
            controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(availability = BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT), "nokia",
            controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
    }

    @Test fun `only a member, and never the controller itself, may recover`() {
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(), "outsider", controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(controller = "nokia"), "nokia",
            controllerSilentMs = 60_000, leaseTimeoutMs = leaseTimeoutMs))
    }

    @Test fun `a replica with no observation yet waits rather than seizing the board`() {
        // Freezing only happens after an observation window, so a missing
        // value means this replica was just adopted.
        assertFalse(BoardCellRecoveryFence.mayAttemptRecovery(
            snapshot(), "nokia", controllerSilentMs = null, leaseTimeoutMs = leaseTimeoutMs))
    }

    // ===== Revalidation before connect and before commit =====

    @Test fun `recovery is abandoned when the term or the hash moved on`() {
        val base = snapshot()

        assertTrue(BoardCellRecoveryFence.stillRecoverable(base, "nokia", base.controllerTerm,
            base.stateHash, 60_000, leaseTimeoutMs))
        // Somebody else recovered: new term.
        assertFalse(BoardCellRecoveryFence.stillRecoverable(base, "nokia", base.controllerTerm + 1,
            base.stateHash, 60_000, leaseTimeoutMs))
        // A repair snapshot landed: same term, different hash.
        assertFalse(BoardCellRecoveryFence.stillRecoverable(base, "nokia", base.controllerTerm,
            "some-other-hash", 60_000, leaseTimeoutMs))
        assertFalse(BoardCellRecoveryFence.stillRecoverable(null, "nokia", base.controllerTerm,
            base.stateHash, 60_000, leaseTimeoutMs))
    }

    @Test fun `a controller that came back during the connect stops the commit`() {
        val base = snapshot()
        // The board connect can take seconds. If the controller started
        // talking again in that window, seizing the board would be a split
        // brain with a live controller.
        assertFalse(BoardCellRecoveryFence.stillRecoverable(base, "nokia", base.controllerTerm,
            base.stateHash, controllerSilentMs = 0, leaseTimeoutMs = leaseTimeoutMs))
        assertFalse(BoardCellRecoveryFence.stillRecoverable(
            snapshot(availability = BoardCellAvailability.ACTIVE), "nokia", base.controllerTerm,
            base.stateHash, 60_000, leaseTimeoutMs))
    }

    // ===== An authorized reconnect must preserve the canonical replica =====

    @Test fun `the reconnect recovery asked for preserves the exact frozen base`() {
        val frozen = snapshot()

        val decision = BoardCellReconnectPolicy.decide(board, board, frozen, "nokia")

        // The decision carries the snapshot it was made about. The caller must
        // not re-read the live flow afterwards: a concurrent update or
        // teardown between deciding and acting would otherwise bind a
        // different cell, or dereference a value that had become null.
        val preserve = decision as BoardCellReconnectPolicy.Decision.PreserveReplica
        assertSame(frozen, preserve.retained)
        assertEquals(cell, preserve.retained.cellId)
    }

    @Test fun `the handover target board connect preserves source released state`() {
        val transfer = BoardCellHandover(
            transferId = "pixel-to-nokia",
            sourceControllerId = "pixel",
            targetControllerId = "nokia",
            sourceTerm = 1,
            targetTerm = 2,
            baseSequence = 12,
            baseHash = "base-hash",
            phase = HandoverPhase.SOURCE_RELEASED,
        )
        val released = snapshot(
            availability = BoardCellAvailability.ACTIVE,
            controller = "pixel",
            members = setOf("pixel", "nokia"),
        ).copy(handover = transfer).withComputedHash()

        val decision = BoardCellReconnectPolicy.decide(board, board, released, "nokia")

        val preserve = decision as BoardCellReconnectPolicy.Decision.PreserveReplica
        assertSame(released, preserve.retained)
        assertEquals(HandoverPhase.SOURCE_RELEASED, preserve.retained.handover?.phase)
        assertEquals("pixel-to-nokia", preserve.retained.handover?.transferId)
    }

    @Test fun `an ordinary board selection still initializes from scratch`() {
        val frozen = snapshot()
        val initialize = BoardCellReconnectPolicy.Decision.Initialize
        // No recovery in flight at all.
        assertEquals(initialize, BoardCellReconnectPolicy.decide(board, null, frozen, "nokia"))
        // Recovery is running, but the user plugged into a different wall.
        val otherBoard = PhysicalBoardId("board-elsewhere")
        assertEquals(initialize,
            BoardCellReconnectPolicy.decide(otherBoard, board, frozen, "nokia"))
        // Nothing retained to preserve.
        assertEquals(initialize, BoardCellReconnectPolicy.decide(board, board, null, "nokia"))
        // Retained state for a different board is not this board's base.
        assertEquals(initialize, BoardCellReconnectPolicy.decide(board, board,
            snapshot().copy(physicalBoardId = otherBoard), "nokia"))
        // Not a member: no standing to revive anything.
        assertEquals(initialize, BoardCellReconnectPolicy.decide(board, board, frozen, "outsider"))
    }

    // ===== End to end on a real coordinator =====

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
        override fun markPhysicalWriteSucceeded(intent: BoardWriteIntent) = Unit
        override fun commit(snapshot: BoardCellSnapshot, intent: BoardWriteIntent, ack: BoardCommandAck) {
            snapshots[snapshot.physicalBoardId] = snapshot; acks[ack.commandId] = ack
        }
        override fun recordAck(ack: BoardCommandAck) { acks[ack.commandId] = ack }
        override fun discardIntent(boardId: PhysicalBoardId, commandId: String) = Unit
        override fun pendingIntent(boardId: PhysicalBoardId) = intents[boardId]
        override fun commandAck(commandId: String) = acks[commandId]
    }

    /**
     * The whole point of preserving the base: the playlist, its host and its
     * membership have to be there on the other side of the recovery, because
     * the recovered controller is the device that now has to project it.
     */
    @Test fun `recovery keeps the canonical playlist, term and membership`() = runTest {
        val playlist = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 1,
            items = listOf("climb-a" to 40, "climb-b" to 45), restAfterSeconds = listOf(120, 0),
            hostId = "pixel", members = listOf("pixel", "nokia")))
        val frozen = snapshot(playlist = playlist)
        val coordinator = BoardCellCoordinator("nokia", NoOpBoardCellTransport, MemoryStore(),
            settleMs = 0, heartbeatTimeoutMs = leaseTimeoutMs)
        assertTrue(coordinator.restoreTrustedSnapshot(frozen, 1_000) is BoardCellApplyResult.Applied)

        val recovery = coordinator.recoverController(board, "exclusive-board-connection:proof", 2_000)

        assertNotNull("the frozen base must still be there to recover from", recovery)
        val recovered = coordinator.snapshot(board)!!
        assertEquals("nokia", recovered.controllerId)
        assertEquals(frozen.controllerTerm + 1, recovered.controllerTerm)
        assertEquals(BoardCellAvailability.ACTIVE, recovered.availability)
        // The old controller left the cell, and with it the playlist; the
        // playlist host is untouched because it was somebody else.
        assertFalse("old-controller" in recovered.members)
        assertEquals("pixel", recovered.playlist.hostId)
        assertEquals(listOf("pixel", "nokia"), recovered.playlist.members)
        assertEquals(listOf("climb-a" to 40, "climb-b" to 45), recovered.playlist.items)
        assertEquals(1, recovered.playlist.currentIndex)
    }

    @Test fun `losing the old controller hands the playlist on when it was the host`() = runTest {
        val playlist = BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, currentIndex = 0, items = listOf("climb-a" to 40),
            hostId = "old-controller", members = listOf("old-controller", "pixel", "nokia")))
        val frozen = snapshot(playlist = playlist)
        val coordinator = BoardCellCoordinator("nokia", NoOpBoardCellTransport, MemoryStore(),
            settleMs = 0, heartbeatTimeoutMs = leaseTimeoutMs)
        coordinator.restoreTrustedSnapshot(frozen, 1_000)

        coordinator.recoverController(board, "exclusive-board-connection:proof", 2_000)

        val recovered = coordinator.snapshot(board)!!
        // The technical role went to this device; the playlist host went to the
        // longest-active remaining playlist member, which is a different
        // decision made by a different rule.
        assertEquals("nokia", recovered.controllerId)
        assertEquals("pixel", recovered.playlist.hostId)
        assertEquals(listOf("pixel", "nokia"), recovered.playlist.members)
    }

    @Test fun `a recovery whose base has already moved on is refused by the coordinator`() = runTest {
        val frozen = snapshot()
        val coordinator = BoardCellCoordinator("nokia", NoOpBoardCellTransport, MemoryStore(),
            settleMs = 0, heartbeatTimeoutMs = leaseTimeoutMs)
        coordinator.restoreTrustedSnapshot(frozen, 1_000)

        // A repair snapshot from a controller that came back makes the cell
        // active again; the commit must not go through on top of it.
        val repaired = frozen.copy(availability = BoardCellAvailability.ACTIVE,
            sequence = frozen.sequence + 1).withComputedHash()
        assertTrue(coordinator.acceptSnapshot("old-controller", repaired, 1_500)
            is BoardCellApplyResult.Applied)

        assertNull(coordinator.recoverController(board, "exclusive-board-connection:proof", 2_000))
        assertEquals("old-controller", coordinator.snapshot(board)!!.controllerId)
    }
}
