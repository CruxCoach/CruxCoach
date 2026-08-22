package com.cruxcoach.android.boardcell

import java.util.UUID
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
    suspend fun publishRecovery(recovery: BoardCellControllerRecovery) = Unit
}

object NoOpBoardCellTransport : BoardCellTransport {
    override suspend fun publishClaim(claim: BoardCellClaim) = Unit
    override suspend fun publishEvent(envelope: BoardCellEnvelope) = Unit
    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) = Unit
    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
}

/** Local fencing knowledge used only to resolve simultaneous first-connect histories. */
enum class PhysicalBoardAuthority { UNKNOWN, HELD, NOT_HELD }

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
    private val handoverTimeoutMs: Long = 45_000L,
    private val initialJoinMode: BoardJoinMode = BoardJoinMode.OPEN,
    /** Whether this process currently owns the exclusive physical board connection. */
    private val physicalBoardAuthority: () -> PhysicalBoardAuthority = {
        PhysicalBoardAuthority.UNKNOWN
    },
    /**
     * UTC wall clock, injectable for tests.
     *
     * Only the node that serializes a commit ever reads it, and only to stamp
     * a canonical instant (a rest's end, a request's expiry) that every other
     * replica then reads rather than re-derives. Monotonic time stays local
     * and never crosses the wire.
     */
    private val wallClockEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val claims = mutableMapOf<PhysicalBoardId, MutableList<BoardCellClaim>>()
    private val replicas = mutableMapOf<PhysicalBoardId, BoardCellReplica>()
    private val settleDeadlines = mutableMapOf<PhysicalBoardId, Long>()
    private val controllerObservedAt = mutableMapOf<PhysicalBoardId, Long>()
    /** Local monotonic observations only; never hashed or trusted from peers. */
    private val memberObservedAt = mutableMapOf<PhysicalBoardId, MutableMap<String, Long>>()
    private val memberDepartedAt = mutableMapOf<PhysicalBoardId, MutableMap<String, Long>>()
    private val handoverDeadlines = mutableMapOf<String, Long>()
    private val observedForkLineages = mutableMapOf<PhysicalBoardId, MutableSet<String>>()
    private val observedForkMembers = mutableMapOf<PhysicalBoardId, MutableSet<String>>()

    suspend fun beginClaim(boardId: PhysicalBoardId, cellId: BoardCellId, nowMonotonicMs: Long): BoardCellClaim {
        val claim = BoardCellClaim(
            boardId,
            cellId,
            nodeId,
            proposedTerm = 1,
            proposedJoinMode = initialJoinMode,
        )
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
            joinMode = winner.proposedJoinMode,
            // The one shared playlist is created with the cell rather than
            // started by somebody. Its id is derived from state every replica
            // already agrees on, so there is no start command to lose, no
            // proposal to answer and nothing for two devices to race over.
            playlist = BoardPlaylistState(
                sessionId = BoardPlaylistSession.idFor(winner.cellId, 1)),
        ).withComputedHash()
        replicas[boardId] = BoardCellReplica(nodeId, snapshot)
        controllerObservedAt[boardId] = nowMonotonicMs
        memberObservedAt[boardId] = snapshot.members.associateWithTo(mutableMapOf()) { nowMonotonicMs }
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
                if (incoming.controllerId == nodeId) seedMemberLiveness(incoming, nowMonotonicMs)
                incoming.handover?.takeIf {
                    it.phase in setOf(HandoverPhase.PREPARED, HandoverPhase.SOURCE_RELEASED,
                        HandoverPhase.TARGET_READY)
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
        var current = replicas[incoming.physicalBoardId]?.snapshot
        if (current != null && incoming.cellId != current.cellId)
            return@withLock BoardCellApplyResult.Rejected("conflicting cell id")
        val resolvesCurrentFork = current != null && current.lineageId in incoming.resolvedLineages &&
            incoming.resolvedLineages.size >= 2 &&
            incoming.lineageId == BoardCellLineage.resolvedId(incoming.cellId, incoming.resolvedLineages) &&
            incoming.availability == BoardCellAvailability.FROZEN_WRITE_RECOVERY
        if (current != null && incoming.lineageId != current.lineageId && !resolvesCurrentFork) {
            // Simultaneous GATT attempts can create two histories before the radios meet. The
            // exclusive physical connection is the fencing token: its owner must never freeze or
            // surrender to a competing mesh-only history.
            if (current.controllerId == nodeId &&
                physicalBoardAuthority() == PhysicalBoardAuthority.HELD)
                return@withLock BoardCellApplyResult.Rejected("physical board owner keeps canonical lineage")

            // Conversely, a self-elected replica that no longer owns the board must yield once the
            // already-known physical controller sends a snapshot that includes this node. This is
            // the normal convergence path after two simultaneous first-connect attempts, not an
            // operator-recoverable fork.
            val yieldsToPhysicalController = current.controllerId == nodeId &&
                physicalBoardAuthority() == PhysicalBoardAuthority.NOT_HELD &&
                senderId == incoming.controllerId && nodeId in incoming.members &&
                incoming.availability == BoardCellAvailability.ACTIVE && incoming.hasValidHash()
            if (yieldsToPhysicalController) {
                replicas.remove(incoming.physicalBoardId)
                observedForkLineages.remove(incoming.physicalBoardId)
                observedForkMembers.remove(incoming.physicalBoardId)
                current = null
            }
        }
        val recovery = incoming.lastControllerRecovery
        val repairsMissedRecovery = current != null &&
            current.availability == BoardCellAvailability.FROZEN_NEEDS_CONTROLLER &&
            senderId == incoming.controllerId && senderId in current.members &&
            incoming.lineageId == current.lineageId && incoming.epoch == current.epoch &&
            incoming.controllerTerm == current.controllerTerm + 1 &&
            incoming.sequence > current.sequence && recovery != null &&
            recovery.claimantId == senderId && recovery.baseControllerId == current.controllerId &&
            recovery.baseControllerTerm == current.controllerTerm &&
            recovery.baseSequence == current.sequence && recovery.baseHash == current.stateHash &&
            recovery.connectionProof.isNotBlank()
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
            if (!authorizedTransfer && !resolvesCurrentFork && !repairsMissedRecovery)
                return@withLock BoardCellApplyResult.Rejected("snapshot not ordered by canonical controller")
        }
        val replica = replicas.getOrPut(incoming.physicalBoardId) { BoardCellReplica(nodeId) }
        replica.applySnapshot(incoming).also {
            if (it is BoardCellApplyResult.Applied) {
                controllerObservedAt[incoming.physicalBoardId] = nowMonotonicMs
                if (it.snapshot.controllerId == nodeId) seedMemberLiveness(it.snapshot, nowMonotonicMs)
                durableStore.persistSnapshot(it.snapshot)
                it.snapshot.recentCommandIds.forEach { commandId ->
                    durableStore.recordAck(ack(commandId, BoardCommandStatus.COMMITTED, it.snapshot))
                }
                BoardCellScopeRegistry.joinCell(incoming.physicalBoardId, incoming.cellId)
            } else if (it is BoardCellApplyResult.IgnoredStale &&
                senderId == current?.controllerId) {
                // An authenticated duplicate still proves that the canonical
                // controller is alive during reordering/reconnect.
                controllerObservedAt[incoming.physicalBoardId] = nowMonotonicMs
            }
        }
    }

    suspend fun acceptEvent(senderId: String, envelope: BoardCellEnvelope, nowMonotonicMs: Long = 0): BoardCellApplyResult = mutex.withLock {
        val replica = replicas[envelope.physicalBoardId]
            ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        val current = replica.snapshot ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        if (senderId != current.controllerId || senderId !in current.members)
            return@withLock BoardCellApplyResult.Rejected("event sender is not canonical controller")
        // A correctly scoped packet proves controller liveness even when its
        // event is duplicate or reveals a gap that needs snapshot repair.
        controllerObservedAt[envelope.physicalBoardId] = nowMonotonicMs
        val result = replica.applyEvent(envelope)
        if (result is BoardCellApplyResult.Applied) {
            controllerObservedAt[envelope.physicalBoardId] = nowMonotonicMs
            if (result.snapshot.controllerId == nodeId) seedMemberLiveness(result.snapshot, nowMonotonicMs)
            durableStore.persistSnapshot(result.snapshot)
            eventCommandId(envelope.event)?.let { commandId ->
                durableStore.recordAck(ack(commandId, BoardCommandStatus.COMMITTED, result.snapshot))
            }
        } else if (result is BoardCellApplyResult.NeedSnapshot) {
            transport.requestSnapshot(envelope.cellId, result.expectedSequence - 1)
        }
        result
    }

    /** Records authenticated control-plane traffic without mutating history. */
    suspend fun observeControllerActivity(
        boardId: PhysicalBoardId,
        senderId: String,
        nowMonotonicMs: Long,
    ): Boolean = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock false
        if (senderId != current.controllerId || senderId !in current.members) return@withLock false
        controllerObservedAt[boardId] = nowMonotonicMs
        true
    }

    /** A last direct transport closing is stronger than an absent heartbeat,
     * but not proof that the controller is permanently gone. Shorten the
     * remaining lease to [failureGraceMs]; any subsequently authenticated
     * controller frame renews the ordinary lease through the methods above. */
    suspend fun suspectControllerTransportLoss(
        boardId: PhysicalBoardId,
        controllerId: String,
        nowMonotonicMs: Long,
        failureGraceMs: Long,
    ): Boolean = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock false
        if (current.availability != BoardCellAvailability.ACTIVE ||
            current.controllerId == nodeId || current.controllerId != controllerId ||
            controllerId !in current.members) return@withLock false
        // elapsedRealtime starts near zero on a freshly booted phone. A
        // negative synthetic observation is valid local arithmetic and keeps
        // the grace exact even during that first lease window.
        val acceleratedObservation = nowMonotonicMs - heartbeatTimeoutMs + failureGraceMs
        val previous = controllerObservedAt[boardId] ?: nowMonotonicMs
        controllerObservedAt[boardId] = minOf(previous, acceleratedObservation)
        true
    }

    private fun eventCommandId(event: BoardCellEvent): String? = when (event) {
        is BoardCellEvent.ProjectCommitted -> event.commandId
        is BoardCellEvent.ProjectUnknown -> event.commandId
        is BoardCellEvent.PlaylistOpsCommitted -> event.commandId
        is BoardCellEvent.ProjectionRecoveryRequired -> event.commandId
        else -> null
    }

    /**
     * Serializes one member's playlist command.
     *
     * The decision itself is [BoardPlaylistPolicy], which knows nothing about
     * transport or roles — this only supplies the canonical base state, the
     * staleness rules every other command already obeys, and the durable ack
     * that makes a replayed command idempotent across a retry, a reconnect and
     * a controller handover.
     *
     * Note what is *not* here: no membership check beyond the transport's, no
     * host, no lifecycle. Every authenticated cell member may edit the shared
     * playlist arbitrarily; the controller decides the order, not the right.
     *
     * The commit is canonical and replicated the moment it returns. Putting
     * the climb on the physical board is a separate, later step, so a slow or
     * failing board write can never hold up the group's playlist.
     */
    suspend fun applyPlaylistCommand(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        senderId: String,
        command: BoardPlaylistCommand,
    ): BoardCommandAck? = mutex.withLock {
        val replicaSnapshot = replicas[boardId]?.snapshot
        durableStore.commandAck(command.commandId)
            ?.takeIf { ack -> replicaSnapshot != null && ack.matches(replicaSnapshot) }
            ?.let { return@withLock it }
        // Deliberately not recorded in the durable ack window: "ask somebody
        // else" and "you are ahead of me" are statements about this device at
        // this moment, not decisions about the command. Remembering them would
        // answer the sender's next retry — after the handover completed, after
        // this replica caught up — with the same refusal for ever, and the
        // edit would be lost with no error anybody could act on.
        val current = writable(boardId, nowMonotonicMs) ?: run {
            val snapshot = replicas[boardId]?.snapshot ?: return@withLock null
            return@withLock ack(command.commandId, BoardCommandStatus.NOT_CONTROLLER, snapshot,
                detail = "not writable")
        }
        if (command.basePlaylistRevision > current.playlistRevision) {
            return@withLock ack(command.commandId, BoardCommandStatus.REJECTED_STALE, current,
                detail = "playlist revision is ahead of controller")
        }
        if (senderId != nodeId && senderId !in current.members) {
            return@withLock ack(command.commandId, BoardCommandStatus.REJECTED_CONFLICT, current,
                detail = "sender is not a cell member").also(durableStore::recordAck)
        }
        val outcome = BoardPlaylistPolicy.resolve(current.playlist, senderId, command,
            wallClockEpochMs(), senderIsController = senderId == nodeId)
        when (outcome) {
            is BoardPlaylistPolicy.Outcome.Reject ->
                ack(command.commandId, BoardCommandStatus.REJECTED_CONFLICT, current,
                    detail = outcome.reason).also(durableStore::recordAck)
            is BoardPlaylistPolicy.Outcome.Accepted ->
                ack(command.commandId, BoardCommandStatus.COMMITTED, current,
                    detail = BoardCommandAck.DETAIL_ALREADY_IN_REQUESTED_STATE)
                    .also(durableStore::recordAck)
            is BoardPlaylistPolicy.Outcome.Commit -> {
                val envelope = commitCommandEvent(boardId,
                    BoardCellEvent.PlaylistOpsCommitted(outcome.ops, command.commandId),
                    command.commandId)
                if (envelope == null) null else durableStore.commandAck(command.commandId)
            }
        }
    }


    suspend fun joinMember(
        boardId: PhysicalBoardId,
        memberId: String,
        nowMonotonicMs: Long = 0,
    ): BoardCellEnvelope? = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock null
        val departedAt = memberDepartedAt[boardId]?.get(memberId)
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.ACTIVE ||
            memberId.isBlank() || memberId in current.members ||
            (nowMonotonicMs > 0 && departedAt != null &&
                nowMonotonicMs - departedAt < heartbeatTimeoutMs) ||
            current.members.size >= MAX_MEMBERS) return@withLock null
        commitCanonical(boardId, BoardCellEvent.MemberJoined(memberId)).also {
            memberObservedAt.getOrPut(boardId) { mutableMapOf() }[memberId] = nowMonotonicMs
            memberDepartedAt[boardId]?.remove(memberId)
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    /** Every current member may choose the rule; the controller serializes it. */
    suspend fun setJoinMode(
        boardId: PhysicalBoardId,
        requestingMember: String,
        mode: BoardJoinMode,
        nowMonotonicMs: Long = 0,
    ): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs) ?: return@withLock null
        if (requestingMember !in current.members || current.joinMode == mode) return@withLock null
        commitCanonical(boardId, BoardCellEvent.JoinModeChanged(mode)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    /** Any correctly realm-scoped FIPS frame proves its authenticated end
     * source is alive, independent of how many mesh hops carried it.
     *
     * Transport peer caches are deliberately not evidence here: Android/FIPS
     * can retain a disconnected BLE peer for about a minute after the channel
     * closed. Only the wire receive path may renew this observation.
     */
    suspend fun observeAuthenticatedMemberFrame(
        boardId: PhysicalBoardId,
        memberId: String,
        nowMonotonicMs: Long,
    ): Boolean = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock false
        if (current.controllerId != nodeId || memberId == nodeId || memberId !in current.members)
            return@withLock false
        memberObservedAt.getOrPut(boardId) { mutableMapOf() }[memberId] = nowMonotonicMs
        true
    }

    suspend fun leaveMember(
        boardId: PhysicalBoardId,
        memberId: String,
        reason: BoardCellMemberLeaveReason,
        nowMonotonicMs: Long = 0,
    ): BoardCellEnvelope? = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.ACTIVE ||
            memberId == current.controllerId || memberId !in current.members) return@withLock null
        commitCanonical(boardId, BoardCellEvent.MemberLeft(memberId, reason)).also {
            memberObservedAt[boardId]?.remove(memberId)
            memberDepartedAt.getOrPut(boardId) { mutableMapOf() }[memberId] = nowMonotonicMs
        }
    }

    /** Remove peers only after three missed 2 s liveness windows. Missing
     * observations start with a complete grace period. */
    suspend fun evictExpiredMembers(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        timeoutMs: Long,
    ): List<BoardCellEnvelope> = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock emptyList()
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.ACTIVE)
            return@withLock emptyList()
        val observations = memberObservedAt.getOrPut(boardId) { mutableMapOf() }
        val protectedTarget = current.handover?.takeIf { it.phase in setOf(
            HandoverPhase.PREPARED, HandoverPhase.SOURCE_RELEASED, HandoverPhase.TARGET_READY,
        ) }?.targetControllerId
        val expired = current.members.asSequence().filter { it != nodeId && it != protectedTarget }
            .filter { member ->
                val last = observations[member]
                if (last == null) { observations[member] = nowMonotonicMs; false }
                else nowMonotonicMs - last >= timeoutMs
            }.sorted().toList()
        expired.mapNotNull { member ->
            commitCanonical(boardId, BoardCellEvent.MemberLeft(
                member, BoardCellMemberLeaveReason.LIVENESS_TIMEOUT)).also {
                observations.remove(member)
                memberDepartedAt.getOrPut(boardId) { mutableMapOf() }[member] = nowMonotonicMs
            }
        }
    }

    suspend fun liveSuccessors(
        boardId: PhysicalBoardId,
        nowMonotonicMs: Long,
        timeoutMs: Long,
    ): List<String> = mutex.withLock {
        val current = replicas[boardId]?.snapshot ?: return@withLock emptyList()
        if (current.controllerId != nodeId) return@withLock emptyList()
        val observations = memberObservedAt.getOrPut(boardId) { mutableMapOf() }
        current.members.asSequence().filter { it != nodeId }
            .filter { member -> observations[member]?.let { nowMonotonicMs - it < timeoutMs } == true }
            .sorted().toList()
    }

    suspend fun forgetLocalReplica(
        boardId: PhysicalBoardId,
        clearDurableSnapshot: Boolean = true,
    ) = mutex.withLock {
        replicas.remove(boardId)
        controllerObservedAt.remove(boardId)
        memberObservedAt.remove(boardId)
        memberDepartedAt.remove(boardId)
        if (clearDurableSnapshot) durableStore.clearSnapshot(boardId)
    }

    private fun seedMemberLiveness(snapshot: BoardCellSnapshot, nowMonotonicMs: Long) {
        val observations = memberObservedAt.getOrPut(snapshot.physicalBoardId) { mutableMapOf() }
        observations.keys.retainAll(snapshot.members)
        snapshot.members.filter { it != nodeId }.forEach { observations.putIfAbsent(it, nowMonotonicMs) }
    }

    companion object {
        /** Must stay aligned with the bounded wire snapshot decoder. */
        const val MAX_MEMBERS = 128
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

    /**
     * Projects an explicit lamp request against the user-visible state it was
     * composed from. Heartbeats can be rebased, but a concurrent selection or
     * physical projection change makes the old request stale. The comparison
     * happens under [mutex], so another commit cannot slip between the check
     * and the write.
     */
    suspend fun projectSemantically(
        boardId: PhysicalBoardId,
        request: BoardProjectionRequest,
        nowMonotonicMs: Long,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult = projectInternal(
        boardId = boardId,
        requested = request.projection,
        nowMonotonicMs = nowMonotonicMs,
        commandId = request.commandId,
        baseSequence = request.baseSequence,
        boardWrite = boardWrite,
        semanticRequest = request,
    ) { BoardCellEvent.ProjectCommitted(request.projection, request.commandId) }

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

    /** Same recovery action, retaining the participant lamp request's semantic stale check. */
    suspend fun reprojectSemanticallyAfterRecovery(
        boardId: PhysicalBoardId,
        request: BoardProjectionRequest,
        nowMonotonicMs: Long,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult = projectInternal(
        boardId = boardId,
        requested = request.projection,
        nowMonotonicMs = nowMonotonicMs,
        commandId = request.commandId,
        baseSequence = request.baseSequence,
        boardWrite = boardWrite,
        allowRecovery = true,
        semanticRequest = request,
    ) {
        BoardCellEvent.ProjectCommitted(
            request.projection,
            request.commandId,
            recoversUnknownProjection = true,
        )
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
        semanticRequest: BoardProjectionRequest? = null,
        eventAfterWrite: suspend () -> BoardCellEvent,
    ): ProjectionResult = mutex.withLock {
        val replicaSnapshot = replicas[boardId]?.snapshot
        durableStore.commandAck(commandId)
            ?.takeIf { old -> replicaSnapshot != null && old.matches(replicaSnapshot) }
            ?.let { old ->
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
        val effectiveBaseSequence = semanticRequest?.semanticBaseSequence(current) ?: baseSequence
        if (effectiveBaseSequence != null && effectiveBaseSequence != current.sequence) {
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

    /**
     * Stamps this controller's own relay state into canonical state.
     *
     * Only the writable controller may, and only for the lease it is on — the
     * claim carries `(epoch, term)` and the reducer drops it otherwise, so a
     * superseded owner cannot describe a cell it no longer serves. Unchanged
     * claims commit nothing: relay capacity moves with every guest and peer,
     * and a heartbeat-rate event stream would replicate noise, not state.
     */
    suspend fun publishRelayState(
        boardId: PhysicalBoardId,
        relay: BoardCellRelayState,
        nowMonotonicMs: Long,
    ): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        val claim = relay.copy(epoch = current.epoch, controllerTerm = current.controllerTerm)
            .sanitized(BoardCellReplica.RELAY_SLOT_CEILING)
        if (claim == current.relay) return@withLock null
        commitCanonical(boardId, BoardCellEvent.RelayStateChanged(claim))
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
        if (current.handover?.phase in setOf(HandoverPhase.PREPARED, HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY, HandoverPhase.COMMITTED)) return@withLock null
        val handover = BoardCellHandover(transferId, nodeId, targetId, current.controllerTerm,
            current.controllerTerm + 1, current.sequence, current.stateHash, HandoverPhase.PREPARED)
        handoverDeadlines[transferId] = nowMonotonicMs + handoverTimeoutMs
        commitCanonical(boardId, BoardCellEvent.HandoverPrepared(handover)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    /** The source records this only after its exclusive physical board connection is closed. */
    suspend fun sourceReleased(
        boardId: PhysicalBoardId,
        transferId: String,
        nowMonotonicMs: Long,
    ): BoardCellEnvelope? = mutex.withLock {
        val current = writable(boardId, nowMonotonicMs, allowHandover = true) ?: return@withLock null
        val h = current.handover ?: return@withLock null
        if (current.controllerId != nodeId || h.sourceControllerId != nodeId ||
            h.transferId != transferId || h.phase != HandoverPhase.PREPARED ||
            nowMonotonicMs > (handoverDeadlines[transferId] ?: Long.MIN_VALUE)) return@withLock null
        commitCanonical(boardId, BoardCellEvent.HandoverSourceReleased(transferId)).also {
            replicas[boardId]?.snapshot?.let { snapshot -> transport.publishSnapshot(snapshot) }
        }
    }

    /** Called by the target only after the source release is canonical and its board is connected. */
    suspend fun targetReady(boardId: PhysicalBoardId, readinessProof: String) {
        mutex.withLock {
            val current = replicas[boardId]?.snapshot ?: return@withLock
            val handover = current.handover ?: return@withLock
            if (handover.targetControllerId != nodeId || handover.phase != HandoverPhase.SOURCE_RELEASED || readinessProof.isBlank()) return@withLock
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
        if (h.phase != HandoverPhase.SOURCE_RELEASED) return@withLock false
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
        if (h.transferId != transferId || h.phase !in setOf(HandoverPhase.PREPARED,
                HandoverPhase.SOURCE_RELEASED, HandoverPhase.TARGET_READY)) return@withLock null
        commitCanonical(boardId, BoardCellEvent.HandoverAborted(transferId, reason))
    }

    suspend fun expireLocalDeadlines(nowMonotonicMs: Long) = mutex.withLock {
        replicas.forEach { (board, replica) ->
            val current = replica.snapshot ?: return@forEach
            val last = controllerObservedAt[board] ?: nowMonotonicMs
            val h = current.handover
            val transferInProgress = h?.phase in setOf(HandoverPhase.PREPARED,
                HandoverPhase.SOURCE_RELEASED, HandoverPhase.TARGET_READY)
            // A released source intentionally has no physical board heartbeat
            // while it waits for the target. The handover deadline, not the
            // shorter controller heartbeat, governs this bounded interval.
            // A member waiting for a missing snapshot must still be able to
            // recover when the controller subsequently disappears. Leaving it
            // in FROZEN_NEEDS_SNAPSHOT forever would strand the whole cell.
            if (!transferInProgress && current.availability in setOf(
                    BoardCellAvailability.ACTIVE,
                    BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT,
                ) &&
                nowMonotonicMs - last >= heartbeatTimeoutMs) {
                replica.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
                replica.snapshot?.let(durableStore::persistSnapshot)
            }
            if (current.controllerId == nodeId && h?.phase in setOf(HandoverPhase.PREPARED,
                    HandoverPhase.SOURCE_RELEASED, HandoverPhase.TARGET_READY) &&
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
            physicalBoardAuthority() == PhysicalBoardAuthority.HELD ||
            senderId !in current.members ||
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

    /** Recover only after the claimant has acquired the exclusive physical
     * board connection. The connection is the fencing token for real boards. */
    suspend fun recoverController(
        boardId: PhysicalBoardId,
        connectionProof: String,
        nowMonotonicMs: Long,
    ): BoardCellControllerRecovery? = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock null
        val current = replica.snapshot ?: return@withLock null
        if (current.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER ||
            nodeId !in current.members || connectionProof.isBlank()) {
            return@withLock null
        }
        val event = BoardCellEvent.ControllerRecovered(nodeId, current.controllerTerm + 1, connectionProof)
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = envelope(current, next, event)
        val recovery = BoardCellControllerRecovery(
            claimantId = nodeId,
            baseControllerId = current.controllerId,
            baseControllerTerm = current.controllerTerm,
            baseSequence = current.sequence,
            baseHash = current.stateHash,
            connectionProof = connectionProof,
            envelope = envelope,
        )
        durableStore.persistSnapshot(next)
        check(replica.applyEvent(envelope) is BoardCellApplyResult.Applied)
        seedMemberLiveness(next, nowMonotonicMs)
        controllerObservedAt[boardId] = nowMonotonicMs
        transport.publishRecovery(recovery)
        recovery
    }

    suspend fun acceptControllerRecovery(
        senderId: String,
        recovery: BoardCellControllerRecovery,
        nowMonotonicMs: Long,
    ): BoardCellApplyResult = mutex.withLock {
        val envelope = recovery.envelope
        val replica = replicas[envelope.physicalBoardId]
            ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        val current = replica.snapshot
            ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        val event = envelope.event as? BoardCellEvent.ControllerRecovered
            ?: return@withLock BoardCellApplyResult.Rejected("recovery event required")
        if (senderId != recovery.claimantId || senderId != event.controllerId ||
            senderId !in current.members || recovery.connectionProof.isBlank() ||
            event.connectionProof != recovery.connectionProof ||
            recovery.baseControllerId != current.controllerId ||
            recovery.baseControllerTerm != current.controllerTerm ||
            recovery.baseSequence != current.sequence || recovery.baseHash != current.stateHash ||
            event.controllerTerm != current.controllerTerm + 1 ||
            current.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER) {
            return@withLock BoardCellApplyResult.Rejected("invalid controller recovery")
        }
        replica.applyEvent(envelope).also { result ->
            if (result is BoardCellApplyResult.Applied) {
                controllerObservedAt[envelope.physicalBoardId] = nowMonotonicMs
                durableStore.persistSnapshot(result.snapshot)
            }
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
                HandoverPhase.PREPARED, HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY)) return null
        val last = controllerObservedAt[boardId] ?: return null
        val transferInProgress = allowHandover && current.handover?.phase in setOf(
            HandoverPhase.PREPARED,
            HandoverPhase.SOURCE_RELEASED,
            HandoverPhase.TARGET_READY,
        )
        // Releasing the physical board deliberately stops normal controller
        // heartbeats. The transfer deadline fences this interval; applying the
        // shorter heartbeat lease here used to freeze the source just as the
        // target reported its (slower, OEM-dependent) GATT readiness. That
        // local freeze then leaked through anti-entropy as a same-sequence,
        // different-hash snapshot and stranded both replicas as a false fork.
        if (!transferInProgress && nowMonotonicMs - last > heartbeatTimeoutMs) {
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
    fun commandAck(commandId: String, snapshot: BoardCellSnapshot): BoardCommandAck? =
        durableStore.commandAck(commandId)?.takeIf { it.matches(snapshot) }

    /**
     * A remembered decision belongs to the cell, epoch and controller term it
     * was made under. A command id that collides across any of those is a
     * different command, and replaying the old answer to it would silently
     * swallow an edit nobody ever decided.
     */
    private fun BoardCommandAck.matches(snapshot: BoardCellSnapshot): Boolean =
        cellId == snapshot.cellId && epoch == snapshot.epoch &&
            controllerTerm == snapshot.controllerTerm

    /**
     * How long since any authenticated traffic from the canonical controller,
     * or null when nothing has ever been observed for this board.
     *
     * This is the canonical liveness fact — the same observation that freezes
     * the cell after three missed heartbeat windows. Fenced recovery reads it
     * instead of asking the native radio whether the controller is still a
     * direct peer: that set is a transport cache and kept a dead controller in
     * it for a further minute after its L2CAP channel had already closed,
     * which stalled the election for exactly that long.
     */
    fun controllerSilentForMs(boardId: PhysicalBoardId, nowMonotonicMs: Long): Long? =
        controllerObservedAt[boardId]?.let { nowMonotonicMs - it }
}
