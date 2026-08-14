package com.cruxcoach.android.boardcell

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BoardCellTransport {
    suspend fun publishClaim(claim: BoardCellClaim)
    suspend fun publishEvent(envelope: BoardCellEnvelope)
    suspend fun publishSnapshot(snapshot: BoardCellSnapshot)
    suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long)
}

object NoOpBoardCellTransport : BoardCellTransport {
    override suspend fun publishClaim(claim: BoardCellClaim) = Unit
    override suspend fun publishEvent(envelope: BoardCellEnvelope) = Unit
    override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) = Unit
    override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
}

sealed interface ProjectionResult {
    data class Committed(val envelope: BoardCellEnvelope) : ProjectionResult
    data class Refused(val reason: String) : ProjectionResult
    data object BoardWriteFailed : ProjectionResult
}

/** Controller-side serializer. A board write becomes canonical only after PROJECT_COMMITTED. */
class BoardCellCoordinator(
    private val nodeId: String,
    private val transport: BoardCellTransport = NoOpBoardCellTransport,
    private val settleMs: Long = 1_000L,
    private val leaseMs: Long = 15_000L,
) {
    private val mutex = Mutex()
    private val claims = mutableMapOf<PhysicalBoardId, MutableList<BoardCellClaim>>()
    private val replicas = mutableMapOf<PhysicalBoardId, BoardCellReplica>()
    private val settleDeadlines = mutableMapOf<PhysicalBoardId, Long>()

    suspend fun beginClaim(boardId: PhysicalBoardId, cellId: BoardCellId, nowMs: Long): BoardCellClaim {
        val claim = BoardCellClaim(boardId, cellId, nodeId, epoch = nowMs, observedAtMs = nowMs)
        mutex.withLock {
            claims.getOrPut(boardId) { mutableListOf() }.add(claim)
            settleDeadlines[boardId] = maxOf(settleDeadlines[boardId] ?: 0L, nowMs + settleMs)
        }
        transport.publishClaim(claim)
        return claim
    }

    suspend fun observeClaim(claim: BoardCellClaim, receivedAtMs: Long = claim.observedAtMs) = mutex.withLock {
        claims.getOrPut(claim.physicalBoardId) { mutableListOf() }.add(claim)
        settleDeadlines[claim.physicalBoardId] = maxOf(
            settleDeadlines[claim.physicalBoardId] ?: 0L,
            receivedAtMs + settleMs,
        )
    }

    suspend fun settle(boardId: PhysicalBoardId, nowMs: Long): BoardCellSnapshot? = mutex.withLock {
        if (nowMs < (settleDeadlines[boardId] ?: Long.MAX_VALUE)) return@withLock null
        val winner = claims[boardId].orEmpty().minByOrNull(BoardCellClaim::rank) ?: return@withLock null
        val existing = replicas[boardId]?.snapshot
        if (existing != null && existing.cellId == winner.cellId) return@withLock existing
        val snapshot = BoardCellSnapshot(
            cellId = winner.cellId,
            physicalBoardId = boardId,
            epoch = winner.epoch,
            sequence = 0,
            controllerId = winner.claimantId,
            leaseUntilMs = nowMs + leaseMs,
            members = claims[boardId].orEmpty().mapTo(sortedSetOf()) { it.claimantId },
        ).withComputedHash()
        replicas[boardId] = BoardCellReplica(nodeId, snapshot)
        BoardCellScopeRegistry.bindCell(boardId, snapshot.cellId)
        transport.publishSnapshot(snapshot)
        snapshot
    }

    /** Authenticated transport sender is checked against canonical membership/controller. */
    suspend fun acceptSnapshot(senderId: String, incoming: BoardCellSnapshot): BoardCellApplyResult =
        mutex.withLock {
            val current = replicas[incoming.physicalBoardId]?.snapshot
            if (senderId !in incoming.members || senderId != incoming.controllerId) {
                return@withLock BoardCellApplyResult.Rejected("snapshot sender is not controller/member")
            }
            if (current != null && incoming.cellId != current.cellId) {
                return@withLock BoardCellApplyResult.Rejected("conflicting cell id")
            }
            if (current != null && senderId != current.controllerId) {
                return@withLock BoardCellApplyResult.Rejected("snapshot not ordered by current controller")
            }
            val replica = replicas.getOrPut(incoming.physicalBoardId) { BoardCellReplica(nodeId) }
            replica.applySnapshot(incoming).also {
                if (it is BoardCellApplyResult.Applied) {
                    BoardCellScopeRegistry.joinCell(incoming.physicalBoardId, incoming.cellId)
                }
            }
        }

    /** Restore from app-private durable storage before transport is available. */
    suspend fun restoreTrustedSnapshot(incoming: BoardCellSnapshot): BoardCellApplyResult = mutex.withLock {
        if (!incoming.hasValidHash() || nodeId !in incoming.members) {
            return@withLock BoardCellApplyResult.Rejected("invalid durable snapshot")
        }
        val replica = replicas.getOrPut(incoming.physicalBoardId) { BoardCellReplica(nodeId) }
        replica.applySnapshot(incoming).also {
            if (it is BoardCellApplyResult.Applied) {
                BoardCellScopeRegistry.bindCell(incoming.physicalBoardId, incoming.cellId)
            }
        }
    }

    suspend fun replacePlaylist(
        boardId: PhysicalBoardId,
        playlist: BoardPlaylistState,
        nowMs: Long,
    ): BoardCellEnvelope? = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock null
        val current = replica.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || current.availability != BoardCellAvailability.ACTIVE ||
            nowMs > current.leaseUntilMs) return@withLock null
        val event = BoardCellEvent.PlaylistReplaced(playlist)
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = BoardCellEnvelope(current.cellId, boardId, current.epoch, next.sequence,
            current.stateHash, event, next.stateHash)
        replica.applyEvent(envelope)
        transport.publishEvent(envelope)
        envelope
    }

    suspend fun joinMember(boardId: PhysicalBoardId, memberId: String): BoardCellEnvelope? =
        mutex.withLock {
            val replica = replicas[boardId] ?: return@withLock null
            val current = replica.snapshot ?: return@withLock null
            if (current.controllerId != nodeId || memberId in current.members || memberId.isBlank()) {
                return@withLock null
            }
            val event = BoardCellEvent.MemberJoined(memberId)
            val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
            val envelope = BoardCellEnvelope(current.cellId, boardId, current.epoch, next.sequence,
                current.stateHash, event, next.stateHash)
            replica.applyEvent(envelope)
            transport.publishEvent(envelope)
            transport.publishSnapshot(next)
            envelope
        }

    suspend fun acceptEvent(senderId: String, envelope: BoardCellEnvelope): BoardCellApplyResult =
        mutex.withLock {
            val replica = replicas[envelope.physicalBoardId]
                ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
            val current = replica.snapshot
                ?: return@withLock BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
            if (senderId != current.controllerId || senderId !in current.members) {
                return@withLock BoardCellApplyResult.Rejected("event sender is not canonical controller")
            }
            val result = replica.applyEvent(envelope)
            if (result is BoardCellApplyResult.NeedSnapshot) {
                transport.requestSnapshot(envelope.cellId, result.expectedSequence - 1)
            }
            result
        }

    suspend fun project(
        boardId: PhysicalBoardId,
        projection: BoardProjection,
        nowMs: Long,
        boardWrite: suspend () -> Boolean,
    ): ProjectionResult = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock ProjectionResult.Refused("cell not settled")
        val current = replica.snapshot ?: return@withLock ProjectionResult.Refused("snapshot missing")
        if (current.availability != BoardCellAvailability.ACTIVE) {
            return@withLock ProjectionResult.Refused("cell frozen")
        }
        if (current.controllerId != nodeId) return@withLock ProjectionResult.Refused("not controller")
        if (nowMs > current.leaseUntilMs) {
            replica.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
            return@withLock ProjectionResult.Refused("controller lease expired")
        }
        if (!boardWrite()) return@withLock ProjectionResult.BoardWriteFailed
        val event = BoardCellEvent.ProjectCommitted(projection)
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = BoardCellEnvelope(
            cellId = current.cellId,
            physicalBoardId = boardId,
            epoch = current.epoch,
            sequence = next.sequence,
            previousHash = current.stateHash,
            event = event,
            resultingHash = next.stateHash,
        )
        val applied = replica.applyEvent(envelope)
        check(applied is BoardCellApplyResult.Applied)
        transport.publishEvent(envelope)
        ProjectionResult.Committed(envelope)
    }

    /**
     * External-app ingress uses the same lease and serialization boundary as native sends.
     * The physical write is followed by exactly one ordered canonical event while the mutex
     * remains held: identified writes commit a projection, otherwise PROJECT_UNKNOWN.
     */
    suspend fun projectExternal(
        boardId: PhysicalBoardId,
        nowMs: Long,
        boardWrite: suspend () -> Boolean,
        identify: suspend () -> BoardProjection?,
    ): ProjectionResult = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock ProjectionResult.Refused("cell not settled")
        val current = replica.snapshot ?: return@withLock ProjectionResult.Refused("snapshot missing")
        if (current.availability != BoardCellAvailability.ACTIVE) {
            return@withLock ProjectionResult.Refused("cell frozen")
        }
        if (current.controllerId != nodeId) return@withLock ProjectionResult.Refused("not controller")
        if (nowMs > current.leaseUntilMs) {
            replica.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
            return@withLock ProjectionResult.Refused("controller lease expired")
        }
        if (!boardWrite()) return@withLock ProjectionResult.BoardWriteFailed
        val event = identify()?.let(BoardCellEvent::ProjectCommitted)
            ?: BoardCellEvent.ProjectUnknown()
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = BoardCellEnvelope(current.cellId, boardId, current.epoch, next.sequence,
            current.stateHash, event, next.stateHash)
        check(replica.applyEvent(envelope) is BoardCellApplyResult.Applied)
        transport.publishEvent(envelope)
        ProjectionResult.Committed(envelope)
    }

    suspend fun renewLease(boardId: PhysicalBoardId, nowMs: Long): BoardCellEnvelope? = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock null
        val current = replica.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || nowMs > current.leaseUntilMs) return@withLock null
        val event = BoardCellEvent.LeaseRenewed(nowMs + leaseMs)
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = BoardCellEnvelope(current.cellId, boardId, current.epoch, next.sequence,
            current.stateHash, event, next.stateHash)
        replica.applyEvent(envelope)
        transport.publishEvent(envelope)
        envelope
    }

    /** Only the reachable old controller can order a transfer; partitions freeze. */
    suspend fun transferController(
        boardId: PhysicalBoardId,
        newControllerId: String,
        nowMs: Long,
    ): BoardCellEnvelope? = mutex.withLock {
        val replica = replicas[boardId] ?: return@withLock null
        val current = replica.snapshot ?: return@withLock null
        if (current.controllerId != nodeId || nowMs > current.leaseUntilMs ||
            newControllerId !in current.members) return@withLock null
        val event = BoardCellEvent.ControllerTransferred(newControllerId, nowMs + leaseMs)
        val next = BoardCellReplica.reduce(current, event, current.sequence + 1)
        val envelope = BoardCellEnvelope(current.cellId, boardId, current.epoch, next.sequence,
            current.stateHash, event, next.stateHash)
        replica.applyEvent(envelope)
        transport.publishEvent(envelope)
        envelope
    }

    suspend fun freezeExpiredControllers(nowMs: Long) = mutex.withLock {
        replicas.values.forEach { replica ->
            val current = replica.snapshot ?: return@forEach
            if (nowMs > current.leaseUntilMs && current.availability == BoardCellAvailability.ACTIVE) {
                replica.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
            }
        }
    }

    suspend fun freezeForTransportRealmSwitch(boardId: PhysicalBoardId) = mutex.withLock {
        replicas[boardId]?.freeze(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER)
    }

    /** Persistent controller identity returning after its lease expired. */
    suspend fun resumeOwnController(boardId: PhysicalBoardId, nowMs: Long): BoardCellSnapshot? =
        mutex.withLock {
            val replica = replicas[boardId] ?: return@withLock null
            val current = replica.snapshot ?: return@withLock null
            if (current.controllerId != nodeId) return@withLock null
            if (nowMs <= current.leaseUntilMs) return@withLock current
            val resumed = current.copy(
                epoch = maxOf(current.epoch + 1, nowMs),
                sequence = 0,
                leaseUntilMs = nowMs + leaseMs,
                availability = BoardCellAvailability.ACTIVE,
                stateHash = "",
            ).withComputedHash()
            replica.applySnapshot(resumed)
            transport.publishSnapshot(resumed)
            resumed
        }

    fun snapshot(boardId: PhysicalBoardId): BoardCellSnapshot? = replicas[boardId]?.snapshot
}
