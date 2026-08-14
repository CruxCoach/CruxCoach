package com.cruxcoach.android.boardcell

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

interface BoardCellTransport {
    suspend fun publishClaim(claim: BoardCellClaim)
    suspend fun publishEvent(envelope: BoardCellEnvelope)
    suspend fun publishSnapshot(snapshot: BoardCellSnapshot)
    suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long)
    suspend fun sendHandoverReady(target: String, ready: HandoverReady) = Unit
    suspend fun sendForkNotice(target: String, notice: BoardCellForkNotice) = Unit
    suspend fun publishCommandAck(target: String, ack: BoardCommandAck) = Unit
}

object NoOpBoardCellTransport : BoardCellTransport {
    override suspend fun publishClaim(claim: BoardCellClaim) = Unit
    override suspend fun publishEvent(envelope: BoardCellEnvelope) = Unit
    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) = Unit
    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
}

@Serializable data class HandoverReady(
    val transferId: String,
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val sourceTerm: Long,
    val baseSequence: Long,
    val baseHash: String,
    val targetId: String,
    val readinessProof: String,
)

@Serializable data class BoardCellForkNotice(
    val conflictingSnapshot: BoardCellSnapshot,
)

sealed interface ProjectionResult {
    data class Committed(val envelope: BoardCellEnvelope, val ack: BoardCommandAck) : ProjectionResult
    data class Duplicate(val ack: BoardCommandAck) : ProjectionResult
    data class Refused(val reason: String, val ack: BoardCommandAck? = null) : ProjectionResult
    data class BoardWriteFailed(val ack: BoardCommandAck) : ProjectionResult
}

/** Serializes all canonical mutations and physical writes for one device. */
class BoardCellCoordinator(
    private val nodeId: String,
    private val transport: BoardCellTransport = NoOpBoardCellTransport,
    private val durableStore: BoardCellDurableStore = NoOpBoardCellDurableStore,
    private val settleMs: Long = 1_000L,
    private val heartbeatTimeoutMs: Long = 15_000L,
    private val handoverTimeoutMs: Long = 15_000L,
) {
    private val mutex = Mutex()
    private val claims = mutableMapOf<PhysicalBoardId, MutableList<BoardCellClaim>>()
    private val replicas = mutableMapOf<PhysicalBoardId, BoardCellReplica>()
    private val settleDeadlines = mutableMapOf<PhysicalBoardId, Long>()
    private val controllerObservedAt = mutableMapOf<PhysicalBoardId, Long>()
    private val handoverDeadlines = mutableMapOf<String, Long>()
    private val observedForkLineages = mutableMapOf<PhysicalBoardId, MutableSet<String>>()
    private val observedForkMembers = mutableMapOf<PhysicalBoardId, MutableSet<String>>()

    suspend fun beginClaim(boardId: PhysicalBoardId, cellId: BoardCellId, nowMonotonicMs: Long): BoardCellClaim {
        val claim = BoardCellClaim(boardId, cellId, nodeId, proposedTerm = 1)
        mutex.withLock {
            claims.getOrPut(boardId) { mutableListOf() }.add(claim)
            settleDeadlines[boardId] = maxOf(settleDeadlines[boardId] ?: 0, nowMonotonicMs + settleMs)
        }
        transport.publishClaim(claim)
        return claim
    }

    suspend fun observeClaim(claim: BoardCellClaim, receivedAtMonotonicMs: Long) = mutex.withLock {
        claims.getOrPut(claim.physicalBoardId) { mutableListOf() }.add(claim)
        settleDeadlines[claim.physicalBoardId] = maxOf(
            settleDeadlines[claim.physicalBoardId] ?: 0, receivedAtMonotonicMs + settleMs)
    }

    suspend fun settle(boardId: PhysicalBoardId, nowMonotonicMs: Long): BoardCellSnapshot? = mutex.withLock {
        if (nowMonotonicMs < (settleDeadlines[boardId] ?: Long.MAX_VALUE)) return@withLock null
        val winner = claims[boardId].orEmpty().minByOrNull(BoardCellClaim::rank) ?: return@withLock null
        replicas[boardId]?.snapshot?.let { return@withLock it }
        val snapshot = BoardCellSnapshot(
            cellId = winner.cellId, physicalBoardId = boardId, epoch = 1, sequence = 0,
            controllerId = winner.claimantId, controllerTerm = 1, lineageId = winner.lineageId,
            members = claims[boardId].orEmpty().mapTo(sortedSetOf()) { it.claimantId },
        ).withComputedHash()
        replicas[boardId] = BoardCellReplica(nodeId, snapshot)
        controllerObservedAt[boardId] = nowMonotonicMs
        durableStore.persistSnapshot(snapshot)
        BoardCellScopeRegistry.bindCell(boardId, snapshot.cellId)
        transport.publishSnapshot(snapshot)
        snapshot
    }

    suspend fun restoreTrustedSnapshot(incoming: BoardCellSnapshot, nowMonotonicMs: Long = 0): BoardCellApplyResult = mutex.withLock {
        if (!incoming.hasValidHash() || nodeId !in incoming.members)
            return@withLock BoardCellApplyResult.Rejected("invalid durable snapshot")
        val replica = replicas.getOrPut(incoming.physicalBoardId) { BoardCellReplica(nodeId) }
        replica.applySnapshot(incoming).also {
            if (it is BoardCellApplyResult.Applied) {
                controllerObservedAt[incoming.physicalBoardId] = nowMonotonicMs
                incoming.handover?.takeIf {
                    it.phase == HandoverPhase.PREPARED || it.phase == HandoverPhase.TARGET_READY
                }?.let { handoverDeadlines[it.transferId] = nowMonotonicMs + handoverTimeoutMs }
                BoardCellScopeRegistry.bindCell(incoming.physicalBoardId, incoming.cellId)
            }
        }
    }

    /** Resolve a crash boundary before transport or writes resume. */
    suspend fun recoverPendingWrite(boardId: PhysicalBoardId): BoardCellEnvelope? = mutex.withLock {
        val pending = durableStore.pendingIntent(boardId) ?: return@withLock null
        if (pending.state == BoardWriteIntentState.PREPARED) {
            durableStore.discardIntent(boardId, pending.commandId)
            return@withLock null
        }
        if (pending.state == BoardWriteIntentState.COMMITTED) return@withLock null
        val current = replicas[boardId]?.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || current.controllerTerm != pending.controllerTerm)
            return@withLock null
        commitCanonical(boardId, BoardCellEvent.ProjectionRecoveryRequired(pending.commandId)).also {
            if (it != null) durableStore.discardIntent(boardId, pending.commandId)
        }
    }

    suspend fun acceptSnapshot(senderId: String, incoming: BoardCellSnapshot, nowMonotonicMs: Long = 0): BoardCellApplyResult = mutex.withLock {
        val committedSource = incoming.handover?.takeIf { it.phase == HandoverPhase.COMMITTED }
            ?.sourceControllerId
        if (senderId !in incoming.members ||
            (senderId != incoming.controllerId && senderId != committedSource))
            return@withLock BoardCellApplyResult.Rejected("snapshot sender is not controller/member")
        val current = replicas[incoming.physicalBoardId]?.snapshot
        if (current != null && incoming.cellId != current.cellId)
            return@withLock BoardCellApplyResult.Rejected("conflicting cell id")
        val resolvesCurrentFork = current != null && current.lineageId in incoming.resolvedLineages &&
            incoming.resolvedLineages.size >= 2 &&
            incoming.lineageId == BoardCellLineage.resolvedId(incoming.cellId, incoming.resolvedLineages) &&
            incoming.availability == BoardCellAvailability.FROZEN_WRITE_RECOVERY
        if (current != null && incoming.lineageId != current.lineageId && !resolvesCurrentFork) {
            observedForkLineages.getOrPut(incoming.physicalBoardId) { mutableSetOf(current.lineageId) }
                .add(incoming.lineageId)
            observedForkMembers.getOrPut(incoming.physicalBoardId) { current.members.toMutableSet() }
                .addAll(incoming.members)
            replicas[incoming.physicalBoardId]?.freeze(BoardCellAvailability.FROZEN_FORK)
            replicas[incoming.physicalBoardId]?.snapshot?.let(durableStore::persistSnapshot)
            transport.sendForkNotice(senderId, BoardCellForkNotice(current))
            return@withLock BoardCellApplyResult.Fork(current.lineageId, incoming.lineageId)
        }
        if (current != null && senderId != current.controllerId) {
            val authorizedTransfer = incoming.controllerTerm > current.controllerTerm &&
                incoming.handover?.phase in setOf(HandoverPhase.COMMITTED, HandoverPhase.COMPLETED) &&
                incoming.handover?.targetControllerId == senderId
            if (!authorizedTransfer && !resolvesCurrentFork)
                return@withLock BoardCellApplyResult.Rejected("snapshot not ordered by canonical controller")
        }
        val replica = replicas.getOrPut(incoming.physicalBoardId) { BoardCellReplica(nodeId) }
        replica.applySnapshot(incoming).also {
            if (it is BoardCellApplyResult.Applied) {
                controllerObservedAt[incoming.physicalBoardId] = nowMonotonicMs
                durableStore.persistSnapshot(it.snapshot)
                BoardCellScopeRegistry.joinCell(incoming.physicalBoardId, incoming.cellId)
            }
        }
    }

    suspend fun acceptEvent(senderId: String, envelope: BoardCellEnvelope, nowMonotonicMs: Long = 0): BoardCellApplyResult = mutex.withLock {
        val replica = replicas[envelope.physicalBoardId]
            ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        val current = replica.snapshot ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        if (senderId != current.controllerId || senderId !in current.members)
            return@withLock BoardCellApplyResult.Rejected("event sender is not canonical controller")
        val result = replica.applyEvent(envelope)
        if (result is BoardCellApplyResult.Applied) {
            controllerObservedAt[envelope.physicalBoardId] = nowMonotonicMs
            durableStore.persistSnapshot(result.snapshot)
        } else if (result is BoardCellApplyResult.NeedSnapshot) {
            transport.requestSnapshot(envelope.cellId, result.expectedSequence - 1)
        }
        result
    }

    suspend fun replacePlaylist(
        boardId: PhysicalBoardId,
        playlist: BoardPlaylistState,
        nowMonotonicMs: Long,
        commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null,
    ): BoardCellEnvelope? = replacePlaylistAfterValidation(
        boardId, nowMonotonicMs, commandId, baseSequence) { playlist }

    /**
     * Validates the command and derives its UI/session projection under the
     * same serializer as every canonical event. A stale concurrent command
     * therefore cannot mutate the local queue before being rejected.
     */
    suspend fun replacePlaylistAfterValidation(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        commandId: String,
        baseSequence: Long?,
        derivePlaylist: () -> BoardPlaylistState?,
    ): BoardCellEnvelope? = mutex.withLock {
        durableStore.commandAck(commandId)?.let { return@withLock null }
        val current = writable(boardId, nowMonotonicMs) ?: return@withLock null
        if (baseSequence != null && baseSequence != current.sequence) {
            durableStore.recordAck(ack(commandId, BoardCommandStatus.REJECTED_STALE, current,
                detail = "expected ${current.sequence}"))
            return@withLock null
        }
        val playlist = try {
            derivePlaylist()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            durableStore.recordAck(ack(commandId, BoardCommandStatus.SUPERSEDED, current,
                detail = failure.message ?: "session command failed"))
            return@withLock null
        }
        if (playlist == null) {
            durableStore.recordAck(ack(commandId, BoardCommandStatus.SUPERSEDED, current,
                detail = "session command produced no state change"))
            return@withLock null
        }
        commitCommandEvent(boardId, BoardCellEvent.PlaylistReplaced(playlist, commandId), commandId)
    }

    suspend fun joinMember(boardId: PhysicalBoardId, memberId: String): BoardCellEnvelope? = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || memberId.isBlank() || memberId in current.members) return@withLock null
        commitCanonical(boardId, BoardCellEvent.MemberJoined(memberId)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    suspend fun project(
        boardId: PhysicalBoardId,
        projection: BoardProjection,
        nowMonotonicMs: Long,
        commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult = projectInternal(boardId, projection, nowMonotonicMs, commandId, baseSequence, boardWrite) {
        BoardCellEvent.ProjectCommitted(projection, commandId)
    }

    /** Explicit operator recovery when the board protocol has no semantic readback. */
    suspend fun reprojectAfterRecovery(
        boardId: PhysicalBoardId,
        projection: BoardProjection,
        nowMonotonicMs: Long,
        commandId: String = UUID.randomUUID().toString(),
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult = projectInternal(boardId, projection, nowMonotonicMs, commandId, null,
        boardWrite, allowRecovery = true) {
        BoardCellEvent.ProjectCommitted(projection, commandId, recoversUnknownProjection = true)
    }

    suspend fun projectExternal(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        commandId: String = UUID.randomUUID().toString(),
        baseSequence: Long? = null,
        boardWrite: suspend () -> Boolean,
        identify: suspend () -> BoardProjection?,
    ): ProjectionResult = projectInternal(boardId, null, nowMonotonicMs, commandId, baseSequence, boardWrite) {
        identify()?.let { BoardCellEvent.ProjectCommitted(it, commandId) }
            ?: BoardCellEvent.ProjectUnknown(commandId, "unidentified_external_write")
    }

    private suspend fun projectInternal(
        boardId: PhysicalBoardId,
        requested: BoardProjection?,
        nowMonotonicMs: Long,
        commandId: String,
        baseSequence: Long?,
        boardWrite: suspend () -> Boolean,
        allowRecovery: Boolean = false,
        eventAfterWrite: suspend () -> BoardCellEvent,
    ): ProjectionResult = mutex.withLock {
        durableStore.commandAck(commandId)?.let { old ->
            if (old.status == BoardCommandStatus.COMMITTED)
                return@withLock ProjectionResult.Duplicate(old)
            return@withLock ProjectionResult.Refused("duplicate command: ${old.status}", old)
        }
        val current = if (allowRecovery) recoveryWritable(boardId, nowMonotonicMs)
            else writable(boardId, nowMonotonicMs)
        if (current == null) {
            val snapshot = replicas[boardId]?.snapshot
            val ack = snapshot?.let { ack(commandId, BoardCommandStatus.NOT_CONTROLLER, it, detail = "not writable") }
            if (ack != null) durableStore.recordAck(ack)
            return@withLock ProjectionResult.Refused("cell is not writable", ack)
        }
        if (baseSequence != null && baseSequence != current.sequence) {
            val rejected = ack(commandId, BoardCommandStatus.REJECTED_STALE, current,
                detail = "expected ${current.sequence}")
            durableStore.recordAck(rejected)
            return@withLock ProjectionResult.Refused("stale base sequence", rejected)
        }
        val intent = BoardWriteIntent(commandId, current.cellId, boardId, current.epoch,
            current.controllerTerm, current.sequence, current.stateHash, requested)
        durableStore.persistIntent(intent)
        transport.publishCommandAck(nodeId, ack(commandId, BoardCommandStatus.ACCEPTED, current))
        if (!boardWrite()) {
            durableStore.discardIntent(boardId, commandId)
            val failed = ack(commandId, BoardCommandStatus.BOARD_WRITE_FAILED, current)
            durableStore.recordAck(failed)
            return@withLock ProjectionResult.BoardWriteFailed(failed)
        }
        val succeeded = intent.copy(state = BoardWriteIntentState.PHYSICAL_WRITE_SUCCEEDED)
        durableStore.markPhysicalWriteSucceeded(succeeded)
        val event = eventAfterWrite()
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = envelope(current, next, event)
        val committed = ack(commandId, BoardCommandStatus.COMMITTED, next)
        durableStore.commit(next, succeeded.copy(state = BoardWriteIntentState.COMMITTED), committed)
        check(replicas.getValue(boardId).applyEvent(envelope) is BoardCellApplyResult.Applied)
        transport.publishEvent(envelope)
        ProjectionResult.Committed(envelope, committed)
    }

    suspend fun heartbeat(boardId: PhysicalBoardId, nowMonotonicMs: Long): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        commitCanonical(boardId, BoardCellEvent.ControllerHeartbeat(current.controllerHeartbeat + 1)).also {
            controllerObservedAt[boardId] = nowMonotonicMs
        }
    }

    suspend fun prepareHandover(
        boardId: PhysicalBoardId,
        targetId: String,
        nowMonotonicMs: Long,
        transferId: String = UUID.randomUUID().toString(),
    ): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs) ?: return@withLock null
        if (targetId == nodeId || targetId !in current.members) return@withLock null
        current.handover?.takeIf { it.transferId == transferId }?.let { return@withLock null }
        if (current.handover?.phase in setOf(HandoverPhase.PREPARED, HandoverPhase.TARGET_READY, HandoverPhase.COMMITTED)) return@withLock null
        val handover = BoardCellHandover(transferId, nodeId, targetId, current.controllerTerm,
            current.controllerTerm + 1, current.sequence, current.stateHash, HandoverPhase.PREPARED)
        handoverDeadlines[transferId] = nowMonotonicMs + handoverTimeoutMs
        commitCanonical(boardId, BoardCellEvent.HandoverPrepared(handover)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    /** Called by the prepared target only after it owns host lifecycle and has a connected board. */
    suspend fun targetReady(boardId: PhysicalBoardId, readinessProof: String) {
        mutex.withLock {
            val current = replicas[boardId]?.snapshot ?: return@withLock
            val handover = current.handover ?: return@withLock
            if (handover.targetControllerId != nodeId || handover.phase != HandoverPhase.PREPARED || readinessProof.isBlank()) return@withLock
            transport.sendHandoverReady(handover.sourceControllerId, HandoverReady(
                handover.transferId, current.cellId, boardId, current.epoch, handover.sourceTerm,
                handover.baseSequence, handover.baseHash, nodeId, readinessProof))
        }
    }

    suspend fun acceptTargetReady(senderId: String, ready: HandoverReady, nowMonotonicMs: Long): Boolean = mutex.withLock {
        val current = writable(ready.physicalBoardId, nowMonotonicMs, allowHandover = true) ?: return@withLock false
        val h = current.handover ?: return@withLock false
        if (senderId != h.targetControllerId || ready.targetId != senderId || ready.transferId != h.transferId ||
            ready.cellId != current.cellId || ready.epoch != current.epoch || ready.sourceTerm != current.controllerTerm ||
            ready.baseSequence != h.baseSequence || ready.baseHash != h.baseHash || ready.readinessProof.isBlank() ||
            nowMonotonicMs > (handoverDeadlines[h.transferId] ?: Long.MIN_VALUE)) return@withLock false
        if (h.phase == HandoverPhase.TARGET_READY) return@withLock true
        if (h.phase != HandoverPhase.PREPARED) return@withLock false
        commitCanonical(ready.physicalBoardId,
            BoardCellEvent.HandoverTargetReady(h.transferId, ready.readinessProof)) != null
    }

    suspend fun commitHandover(boardId: PhysicalBoardId, transferId: String, nowMonotonicMs: Long): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        val h = current.handover ?: return@withLock null
        if (h.transferId != transferId || h.phase != HandoverPhase.TARGET_READY ||
            nowMonotonicMs > (handoverDeadlines[transferId] ?: Long.MIN_VALUE)) return@withLock null
        commitCanonical(boardId, BoardCellEvent.HandoverCommitted(transferId, h.targetControllerId, h.targetTerm)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    suspend fun completeHandover(boardId: PhysicalBoardId, transferId: String, nowMonotonicMs: Long): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        val h = current.handover ?: return@withLock null
        if (current.controllerId != nodeId || h.targetControllerId != nodeId || h.transferId != transferId ||
            h.phase != HandoverPhase.COMMITTED) return@withLock null
        commitCanonical(boardId, BoardCellEvent.HandoverCompleted(transferId))
    }

    suspend fun abortHandover(boardId: PhysicalBoardId, transferId: String, reason: String, nowMonotonicMs: Long): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        val h = current.handover ?: return@withLock null
        if (h.transferId != transferId || h.phase !in setOf(HandoverPhase.PREPARED, HandoverPhase.TARGET_READY)) return@withLock null
        commitCanonical(boardId, BoardCellEvent.HandoverAborted(transferId, reason))
    }

    suspend fun expireLocalDeadlines(nowMonotonicMs: Long) = mutex.withLock {
        replicas.forEach { (board, replica) ->
            val current = replica.snapshot ?: return@forEach
            val last = controllerObservedAt[board] ?: nowMonotonicMs
            if (current.availability == BoardCellAvailability.ACTIVE && nowMonotonicMs - last > heartbeatTimeoutMs) {
                replica.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
                replica.snapshot?.let(durableStore::persistSnapshot)
            }
            val h = current.handover
            if (current.controllerId == nodeId && h?.phase in setOf(HandoverPhase.PREPARED, HandoverPhase.TARGET_READY) &&
                nowMonotonicMs > (handoverDeadlines[h!!.transferId] ?: Long.MAX_VALUE)) {
                commitCanonical(board, BoardCellEvent.HandoverAborted(h.transferId, "target readiness timeout"))
            }
        }
    }

    suspend fun acceptForkNotice(senderId: String, notice: BoardCellForkNotice) = mutex.withLock {
        val remote = notice.conflictingSnapshot
        val replica = replicas[remote.physicalBoardId] ?: return@withLock
        val current = replica.snapshot ?: return@withLock
        if (!remote.hasValidHash() || senderId != remote.controllerId || senderId !in remote.members ||
            remote.cellId != current.cellId || remote.physicalBoardId != current.physicalBoardId ||
            remote.lineageId == current.lineageId) return@withLock
        observedForkLineages.getOrPut(remote.physicalBoardId) { mutableSetOf(current.lineageId) }
            .add(remote.lineageId)
        observedForkMembers.getOrPut(remote.physicalBoardId) { current.members.toMutableSet() }
            .addAll(remote.members)
        replica.freeze(BoardCellAvailability.FROZEN_FORK)
        replica.snapshot?.let(durableStore::persistSnapshot)
    }

    /** Explicit operator action never guesses the physical projection; a reproject remains required. */
    suspend fun operatorRecoverFork(boardId: PhysicalBoardId, nowMonotonicMs: Long): BoardCellEnvelope? = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.FROZEN_FORK) return@withLock null
        val lineages = observedForkLineages[boardId].orEmpty() + current.lineageId
        if (lineages.size < 2 || current.lineageId != lineages.min()) return@withLock null
        val resolved = BoardCellLineage.resolvedId(current.cellId, lineages)
        val members = observedForkMembers[boardId].orEmpty() + current.members
        commitCanonical(boardId, BoardCellEvent.OperatorRecovered(
            resolved, current.epoch + 1, lineages, members)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    suspend fun freezeForTransportRealmSwitch(boardId: PhysicalBoardId) = mutex.withLock {
        replicas[boardId]?.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
        replicas[boardId]?.snapshot?.let(durableStore::persistSnapshot)
    }

    private fun writable(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        allowHandover: Boolean = false,
    ): BoardCellSnapshot? {
        val current = replicas[boardId]?.snapshot ?: return null
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.ACTIVE) return null
        if (!allowHandover && current.handover?.phase in setOf(
                HandoverPhase.PREPARED, HandoverPhase.TARGET_READY)) return null
        val last = controllerObservedAt[boardId] ?: return null
        if (nowMonotonicMs - last > heartbeatTimeoutMs) {
            replicas[boardId]?.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
            replicas[boardId]?.snapshot?.let(durableStore::persistSnapshot)
            return null
        }
        return current
    }

    private fun recoveryWritable(boardId: PhysicalBoardId, nowMonotonicMs: Long): BoardCellSnapshot? {
        val current = replicas[boardId]?.snapshot ?: return null
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.FROZEN_WRITE_RECOVERY) return null
        val last = controllerObservedAt[boardId] ?: return null
        return current.takeIf { nowMonotonicMs - last <= heartbeatTimeoutMs }
    }

    private suspend fun commitCanonical(boardId: PhysicalBoardId, event: BoardCellEvent): BoardCellEnvelope? {
        val replica = replicas[boardId] ?: return null
        val current = replica.snapshot ?: return null
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = envelope(current, next, event)
        durableStore.persistSnapshot(next)
        check(replica.applyEvent(envelope) is BoardCellApplyResult.Applied)
        transport.publishEvent(envelope)
        return envelope
    }

    private suspend fun commitCommandEvent(
        boardId: PhysicalBoardId,
        event: BoardCellEvent,
        commandId: String,
    ): BoardCellEnvelope? {
        val replica = replicas[boardId] ?: return null
        val current = replica.snapshot ?: return null
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = envelope(current, next, event)
        durableStore.persistSnapshotWithAck(next, ack(commandId, BoardCommandStatus.COMMITTED, next))
        check(replica.applyEvent(envelope) is BoardCellApplyResult.Applied)
        transport.publishEvent(envelope)
        return envelope
    }

    private fun envelope(current: BoardCellSnapshot, next: BoardCellSnapshot, event: BoardCellEvent) =
        BoardCellEnvelope(current.cellId, current.physicalBoardId, current.epoch,
            current.controllerTerm, next.sequence, current.stateHash, event, next.stateHash)

    private fun ack(commandId: String, status: BoardCommandStatus, snapshot: BoardCellSnapshot,
        detail: String? = null) = BoardCommandAck(commandId, status, snapshot.cellId, snapshot.epoch,
        snapshot.controllerTerm, snapshot.sequence, snapshot.stateHash, detail)

    fun snapshot(boardId: PhysicalBoardId): BoardCellSnapshot? = replicas[boardId]?.snapshot
    fun commandAck(commandId: String): BoardCommandAck? = durableStore.commandAck(commandId)
}
