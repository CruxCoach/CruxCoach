package com.cruxcoach.android.boardcell

sealed interface BoardCellApplyResult {
    data class Applied(val snapshot: BoardCellSnapshot) : BoardCellApplyResult
    data object IgnoredStale : BoardCellApplyResult
    data class Rejected(val reason: String) : BoardCellApplyResult
    data class NeedSnapshot(val expectedSequence: Long, val receivedSequence: Long) : BoardCellApplyResult
    data class Fork(val localLineage: String, val remoteLineage: String) : BoardCellApplyResult
}

class BoardCellReplica(val localMemberId: String, initial: BoardCellSnapshot? = null) {
    var snapshot: BoardCellSnapshot? = initial
        private set

    fun applySnapshot(incoming: BoardCellSnapshot): BoardCellApplyResult {
        if (!incoming.hasValidHash()) return BoardCellApplyResult.Rejected("invalid state hash")
        val current = snapshot
        if (current != null) {
            if (incoming.physicalBoardId != current.physicalBoardId || incoming.cellId != current.cellId)
                return BoardCellApplyResult.Rejected("board/cell mismatch")
            if (incoming.lineageId != current.lineageId) {
                val validResolution = current.lineageId in incoming.resolvedLineages &&
                    incoming.lineageId == BoardCellLineage.resolvedId(incoming.cellId, incoming.resolvedLineages) &&
                    incoming.availability == BoardCellAvailability.FROZEN_WRITE_RECOVERY
                if (validResolution) {
                    if (localMemberId !in incoming.members) return BoardCellApplyResult.Rejected("local node is not a cell member")
                    snapshot = incoming
                    return BoardCellApplyResult.Applied(incoming)
                }
                freeze(BoardCellAvailability.FROZEN_FORK)
                return BoardCellApplyResult.Fork(current.lineageId, incoming.lineageId)
            }
            if (incoming.controllerTerm < current.controllerTerm ||
                (incoming.controllerTerm == current.controllerTerm && incoming.epoch < current.epoch) ||
                (incoming.controllerTerm == current.controllerTerm && incoming.epoch == current.epoch && incoming.sequence < current.sequence))
                return BoardCellApplyResult.IgnoredStale
            if (incoming.controllerTerm == current.controllerTerm && incoming.epoch == current.epoch &&
                incoming.sequence == current.sequence && incoming.stateHash != current.stateHash) {
                if (current.availability == BoardCellAvailability.ACTIVE ||
                    current.availability == BoardCellAvailability.FROZEN_FORK) {
                    freeze(BoardCellAvailability.FROZEN_FORK)
                    return BoardCellApplyResult.Fork(current.lineageId, incoming.lineageId)
                }
            }
        }
        if (localMemberId !in incoming.members) return BoardCellApplyResult.Rejected("local node is not a cell member")
        snapshot = incoming
        return BoardCellApplyResult.Applied(incoming)
    }

    fun applyEvent(envelope: BoardCellEnvelope): BoardCellApplyResult {
        val current = snapshot ?: return BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        if (envelope.physicalBoardId != current.physicalBoardId || envelope.cellId != current.cellId)
            return BoardCellApplyResult.Rejected("board/cell mismatch")
        if (localMemberId !in current.members) return BoardCellApplyResult.Rejected("not a member")
        if (envelope.controllerTerm != current.controllerTerm) {
            if (envelope.controllerTerm > current.controllerTerm) freeze(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
            return if (envelope.controllerTerm > current.controllerTerm)
                BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
            else BoardCellApplyResult.IgnoredStale
        }
        if (envelope.epoch != current.epoch) {
            if (envelope.epoch > current.epoch) freeze(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
            return if (envelope.epoch > current.epoch)
                BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
            else BoardCellApplyResult.IgnoredStale
        }
        if (envelope.sequence <= current.sequence) return BoardCellApplyResult.IgnoredStale
        if (envelope.sequence != current.sequence + 1 || envelope.previousHash != current.stateHash) {
            freeze(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
            return BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
        }
        val reduced = reduce(current, envelope.event, envelope.sequence)
        if (reduced.stateHash != envelope.resultingHash) {
            freeze(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
            return BoardCellApplyResult.Rejected("resulting hash mismatch")
        }
        snapshot = reduced
        return BoardCellApplyResult.Applied(reduced)
    }

    internal fun freeze(availability: BoardCellAvailability) {
        require(availability != BoardCellAvailability.ACTIVE && availability != BoardCellAvailability.SETTLING)
        snapshot = snapshot?.copy(availability = availability)?.withComputedHash()
    }

    companion object {
        fun reduce(current: BoardCellSnapshot, event: BoardCellEvent, sequence: Long): BoardCellSnapshot {
            val next = when (event) {
                is BoardCellEvent.ProjectCommitted -> current.copy(
                    projection = event.projection,
                    projectionKnown = true,
                    availability = if (event.recoversUnknownProjection &&
                        current.availability == BoardCellAvailability.FROZEN_WRITE_RECOVERY)
                        BoardCellAvailability.ACTIVE else current.availability,
                )
                is BoardCellEvent.ProjectUnknown -> current.copy(projection = null, projectionKnown = false)
                is BoardCellEvent.PlaylistReplaced -> current.copy(
                    playlist = event.playlist,
                    playlistRevision = current.playlistRevision + 1,
                )
                is BoardCellEvent.MemberJoined -> current.copy(members = current.members + event.memberId)
                is BoardCellEvent.ControllerHeartbeat -> current.copy(controllerHeartbeat = event.heartbeat)
                is BoardCellEvent.HandoverPrepared -> current.copy(handover = event.value)
                is BoardCellEvent.HandoverSourceReleased -> current.copy(
                    handover = current.handover?.takeIf { it.transferId == event.transferId }?.copy(
                        phase = HandoverPhase.SOURCE_RELEASED))
                is BoardCellEvent.HandoverTargetReady -> current.copy(
                    handover = current.handover?.takeIf { it.transferId == event.transferId }?.copy(
                        phase = HandoverPhase.TARGET_READY, readinessProof = event.readinessProof))
                is BoardCellEvent.HandoverCommitted -> current.copy(
                    controllerId = event.targetControllerId,
                    controllerTerm = event.targetTerm,
                    controllerHeartbeat = 0,
                    handover = current.handover?.copy(phase = HandoverPhase.COMMITTED),
                )
                is BoardCellEvent.HandoverCompleted -> current.copy(
                    handover = current.handover?.copy(phase = HandoverPhase.COMPLETED))
                is BoardCellEvent.HandoverAborted -> current.copy(
                    handover = current.handover?.copy(phase = HandoverPhase.ABORTED))
                is BoardCellEvent.ControllerRecovered -> current.copy(
                    controllerId = event.controllerId,
                    controllerTerm = event.controllerTerm,
                    controllerHeartbeat = 0,
                    availability = BoardCellAvailability.ACTIVE,
                    handover = null,
                )
                is BoardCellEvent.ProjectionRecoveryRequired -> current.copy(
                    projection = null, projectionKnown = false,
                    availability = BoardCellAvailability.FROZEN_WRITE_RECOVERY)
                is BoardCellEvent.ForkDetected -> current.copy(availability = BoardCellAvailability.FROZEN_FORK)
                is BoardCellEvent.OperatorRecovered -> current.copy(
                    lineageId = event.newLineageId, epoch = event.newEpoch,
                    resolvedLineages = event.resolvedLineages,
                    members = current.members + event.resolvedMembers,
                    projection = null, projectionKnown = false,
                    availability = BoardCellAvailability.FROZEN_WRITE_RECOVERY, handover = null)
            }
            val commandId = when (event) {
                is BoardCellEvent.ProjectCommitted -> event.commandId
                is BoardCellEvent.ProjectUnknown -> event.commandId
                is BoardCellEvent.PlaylistReplaced -> event.commandId
                is BoardCellEvent.ProjectionRecoveryRequired -> event.commandId
                else -> null
            }
            val withDedup = if (commandId == null) next else next.copy(
                recentCommandIds = (next.recentCommandIds.filterNot { it == commandId } + commandId)
                    .takeLast(256),
            )
            val ordered = withDedup.copy(sequence = sequence, stateHash = "")
            return ordered.withComputedHash()
        }
    }
}
