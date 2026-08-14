package com.cruxcoach.android.boardcell

sealed interface BoardCellApplyResult {
    data class Applied(val snapshot: BoardCellSnapshot) : BoardCellApplyResult
    data object IgnoredStale : BoardCellApplyResult
    data class Rejected(val reason: String) : BoardCellApplyResult
    data class NeedSnapshot(val expectedSequence: Long, val receivedSequence: Long) : BoardCellApplyResult
}

/** Transport-agnostic reducer. FIPS and BLE/GATT must feed this exact boundary. */
class BoardCellReplica(
    val localMemberId: String,
    initial: BoardCellSnapshot? = null,
) {
    var snapshot: BoardCellSnapshot? = initial
        private set

    fun applySnapshot(incoming: BoardCellSnapshot): BoardCellApplyResult {
        if (!incoming.hasValidHash()) return BoardCellApplyResult.Rejected("invalid state hash")
        val current = snapshot
        if (current != null) {
            if (incoming.physicalBoardId != current.physicalBoardId) {
                return BoardCellApplyResult.Rejected("physical board mismatch")
            }
            if (incoming.cellId != current.cellId) {
                return BoardCellApplyResult.Rejected("cell id mismatch")
            }
            if (incoming.epoch == current.epoch && incoming.sequence == current.sequence &&
                incoming.stateHash != current.stateHash &&
                current.availability == BoardCellAvailability.ACTIVE) {
                return BoardCellApplyResult.Rejected("conflicting snapshot at same sequence")
            }
            if (incoming.epoch < current.epoch ||
                (incoming.epoch == current.epoch && incoming.sequence < current.sequence)
            ) return BoardCellApplyResult.IgnoredStale
        }
        if (localMemberId !in incoming.members) {
            return BoardCellApplyResult.Rejected("local node is not a cell member")
        }
        snapshot = incoming
        return BoardCellApplyResult.Applied(incoming)
    }

    fun applyEvent(envelope: BoardCellEnvelope): BoardCellApplyResult {
        val current = snapshot ?: return BoardCellApplyResult.NeedSnapshot(0, envelope.sequence)
        if (envelope.physicalBoardId != current.physicalBoardId || envelope.cellId != current.cellId) {
            return BoardCellApplyResult.Rejected("board/cell mismatch")
        }
        if (localMemberId !in current.members) return BoardCellApplyResult.Rejected("not a member")
        if (envelope.epoch != current.epoch) {
            return if (envelope.epoch > current.epoch) {
                freezeForSnapshot(current)
                BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
            } else BoardCellApplyResult.IgnoredStale
        }
        if (envelope.sequence <= current.sequence) return BoardCellApplyResult.IgnoredStale
        if (envelope.sequence != current.sequence + 1) {
            freezeForSnapshot(current)
            return BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
        }
        if (envelope.previousHash != current.stateHash) {
            freezeForSnapshot(current)
            return BoardCellApplyResult.NeedSnapshot(current.sequence + 1, envelope.sequence)
        }
        val reduced = reduce(current, envelope.event, envelope.sequence)
        if (reduced.stateHash != envelope.resultingHash) {
            freezeForSnapshot(current)
            return BoardCellApplyResult.Rejected("resulting hash mismatch")
        }
        snapshot = reduced
        return BoardCellApplyResult.Applied(reduced)
    }

    internal fun freeze(availability: BoardCellAvailability) {
        require(availability == BoardCellAvailability.FROZEN_NEEDS_CONTROLLER ||
            availability == BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
        snapshot = snapshot?.copy(availability = availability)?.withComputedHash()
    }

    private fun freezeForSnapshot(current: BoardCellSnapshot) {
        snapshot = current
        freeze(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT)
    }

    companion object {
        fun reduce(current: BoardCellSnapshot, event: BoardCellEvent, sequence: Long): BoardCellSnapshot {
            val next = when (event) {
                is BoardCellEvent.ProjectCommitted -> current.copy(
                    projection = event.projection, projectionKnown = true,
                )
                is BoardCellEvent.ProjectUnknown -> current.copy(
                    projection = null, projectionKnown = false,
                )
                is BoardCellEvent.PlaylistReplaced -> current.copy(playlist = event.playlist)
                is BoardCellEvent.MemberJoined -> current.copy(members = current.members + event.memberId)
                is BoardCellEvent.LeaseRenewed -> current.copy(leaseUntilMs = event.leaseUntilMs)
                is BoardCellEvent.ControllerTransferred -> current.copy(
                    controllerId = event.controllerId,
                    leaseUntilMs = event.leaseUntilMs,
                )
            }.copy(sequence = sequence, availability = BoardCellAvailability.ACTIVE, stateHash = "")
            return next.withComputedHash()
        }
    }
}
