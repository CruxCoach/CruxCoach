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
        // A newer controller snapshot that excludes this node is the canonical
        // membership tombstone. Retain it just long enough for the manager to
        // tear down local realm state; initial snapshots still require an
        // admitted local member and therefore cannot be used for passive join.
        if (localMemberId !in incoming.members && current == null)
            return BoardCellApplyResult.Rejected("local node is not a cell member")
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
        /**
         * The largest slot count a claim may describe.
         *
         * One Android BLE adapter, so nothing in a cell can honestly claim
         * more. Clamping rather than rejecting keeps a crafted number from
         * being believed without letting it invalidate an otherwise good
         * snapshot.
         */
        const val RELAY_SLOT_CEILING = 7

        private fun BoardPlaylistState.clearingPendingFor(projection: BoardProjection):
            BoardPlaylistState {
            val pending = pendingProjection ?: return this
            return if (pending.climbUuid == projection.climbUuid && pending.angle == projection.angle)
                copy(pendingProjection = null) else this
        }

        /**
         * Applies a playlist change and advances [BoardCellSnapshot.playlistRevision]
         * exactly when the playlist really moved, so heartbeats and membership
         * churn cannot stale a member's in-flight command.
         */
        private fun BoardCellSnapshot.withPlaylist(
            base: BoardCellSnapshot,
            playlist: BoardPlaylistState,
            forceRevision: Boolean = false,
        ): BoardCellSnapshot = copy(
            playlist = playlist,
            playlistRevision = base.playlistRevision +
                if (forceRevision || playlist != base.playlist) 1 else 0,
        )

        fun reduce(current: BoardCellSnapshot, event: BoardCellEvent, sequence: Long): BoardCellSnapshot {
            val next = when (event) {
                // A successful physical write is the canonical proof that the
                // pending-send state is over; nothing has to remember to clear
                // it, and a retry that lands twice clears it only once.
                // Only the controller may describe the cell's relay, and only
                // for the lease it is on. Anything else is dropped rather than
                // merged: a member that could stamp this would be able to
                // advertise capacity on somebody else's behalf.
                is BoardCellEvent.RelayStateChanged -> {
                    val claim = event.relay
                    if (claim.epoch != current.epoch ||
                        claim.controllerTerm != current.controllerTerm
                    ) current
                    else current.copy(relay = claim.sanitized(RELAY_SLOT_CEILING))
                }
                is BoardCellEvent.ProjectCommitted -> current.copy(
                    projection = event.projection,
                    projectionKnown = true,
                    availability = if (event.recoversUnknownProjection &&
                        current.availability == BoardCellAvailability.FROZEN_WRITE_RECOVERY)
                        BoardCellAvailability.ACTIVE else current.availability,
                ).withPlaylist(current, current.playlist.clearingPendingFor(event.projection))
                is BoardCellEvent.ProjectUnknown -> current.copy(projection = null, projectionKnown = false)
                // The delta is replayed, not trusted: every replica applies the
                // same pure reducer to the same predecessor, and the envelope's
                // resulting hash is what says whether it agreed.
                is BoardCellEvent.PlaylistOpsCommitted -> current.withPlaylist(
                    current, BoardPlaylistPolicy.apply(current.playlist, event.ops),
                    forceRevision = true)
                is BoardCellEvent.JoinModeChanged -> current.copy(joinMode = event.mode)
                // Joining the cell is joining the playlist: there is nothing
                // to add to it, because the playlist has no membership of its
                // own. The snapshot the new member receives already carries it.
                is BoardCellEvent.MemberJoined -> current.copy(
                    members = current.members + event.memberId,
                    membershipRevision = current.membershipRevision +
                        if (event.memberId in current.members) 0 else 1,
                )
                // Leaving takes nothing with it. The playlist belongs to the
                // BoardCell, so it survives every member coming and going and
                // only ends when the cell does.
                is BoardCellEvent.MemberLeft -> current.copy(
                    members = current.members - event.memberId,
                    membershipRevision = current.membershipRevision +
                        if (event.memberId in current.members) 1 else 0,
                )
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
                    lastControllerRecovery = null,
                )
                is BoardCellEvent.HandoverCompleted -> current.copy(
                    handover = current.handover?.copy(phase = HandoverPhase.COMPLETED))
                is BoardCellEvent.HandoverAborted -> current.copy(
                    handover = current.handover?.copy(phase = HandoverPhase.ABORTED))
                // Recovery evicts the unreachable controller from the mesh.
                // The playlist is untouched: the technical role has no product
                // authority over it and no seat in it to vacate.
                is BoardCellEvent.ControllerRecovered -> current.copy(
                    controllerId = event.controllerId,
                    controllerTerm = event.controllerTerm,
                    controllerHeartbeat = 0,
                    availability = BoardCellAvailability.ACTIVE,
                    handover = null,
                    members = if (event.controllerId == current.controllerId) current.members
                        else current.members - current.controllerId,
                    membershipRevision = current.membershipRevision +
                        if (event.controllerId == current.controllerId) 0 else 1,
                    lastControllerRecovery = BoardCellControllerRecoveryProof(
                        claimantId = event.controllerId,
                        baseControllerId = current.controllerId,
                        baseControllerTerm = current.controllerTerm,
                        baseSequence = current.sequence,
                        baseHash = current.stateHash,
                        connectionProof = event.connectionProof,
                    ),
                )
                is BoardCellEvent.ProjectionRecoveryRequired -> current.copy(
                    projection = null, projectionKnown = false,
                    availability = BoardCellAvailability.FROZEN_WRITE_RECOVERY)
                is BoardCellEvent.ForkDetected -> current.copy(availability = BoardCellAvailability.FROZEN_FORK)
                is BoardCellEvent.OperatorRecovered -> current.copy(
                    lineageId = event.newLineageId, epoch = event.newEpoch,
                    resolvedLineages = event.resolvedLineages,
                    members = current.members + event.resolvedMembers,
                    membershipRevision = current.membershipRevision +
                        if (event.resolvedMembers.all { it in current.members }) 0 else 1,
                    projection = null, projectionKnown = false,
                    availability = BoardCellAvailability.FROZEN_WRITE_RECOVERY, handover = null,
                    lastControllerRecovery = null)
            }
            val commandId = when (event) {
                is BoardCellEvent.ProjectCommitted -> event.commandId
                is BoardCellEvent.ProjectUnknown -> event.commandId
                is BoardCellEvent.PlaylistOpsCommitted -> event.commandId
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
