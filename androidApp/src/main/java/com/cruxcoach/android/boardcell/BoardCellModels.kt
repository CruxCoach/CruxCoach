package com.cruxcoach.android.boardcell

import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PhysicalBoardId(val value: String) { init { require(value.isNotBlank()) } }

@JvmInline
@Serializable
value class BoardCellId(val value: String) {
    init { require(value.isNotBlank()) }
    companion object {
        fun forPhysical(board: PhysicalBoardId): BoardCellId {
            val hex = MessageDigest.getInstance("SHA-256")
                .digest("cruxcoach-board-cell-v1|${board.value}".encodeToByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            return BoardCellId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}")
        }
    }
}

@Serializable
data class BoardProjection(val climbUuid: String, val angle: Int, val projectionSurvivesDisconnect: Boolean = true)

@Serializable
data class BoardPlaylistState(
    val sessionId: Int? = null,
    val currentIndex: Int = -1,
    val items: List<Pair<String, Int>> = emptyList(),
)

@Serializable
enum class BoardCellAvailability {
    SETTLING, ACTIVE, FROZEN_NEEDS_CONTROLLER, FROZEN_NEEDS_SNAPSHOT,
    FROZEN_WRITE_RECOVERY, FROZEN_FORK,
}

@Serializable
enum class HandoverPhase { PREPARED, TARGET_READY, COMMITTED, COMPLETED, ABORTED }

@Serializable
data class BoardCellHandover(
    val transferId: String,
    val sourceControllerId: String,
    val targetControllerId: String,
    val sourceTerm: Long,
    val targetTerm: Long,
    val baseSequence: Long,
    val baseHash: String,
    val phase: HandoverPhase,
    val readinessProof: String? = null,
)

/** Complete state. Deadlines are local monotonic observations and never cross the wire. */
@Serializable
data class BoardCellSnapshot(
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val sequence: Long,
    val controllerId: String,
    val controllerTerm: Long = 1,
    val controllerHeartbeat: Long = 0,
    val lineageId: String,
    val resolvedLineages: Set<String> = emptySet(),
    val members: Set<String>,
    val projection: BoardProjection? = null,
    val projectionKnown: Boolean = true,
    val playlist: BoardPlaylistState = BoardPlaylistState(),
    val availability: BoardCellAvailability = BoardCellAvailability.ACTIVE,
    val handover: BoardCellHandover? = null,
    val stateHash: String = "",
) {
    fun withComputedHash(): BoardCellSnapshot = copy(stateHash = BoardCellHash.compute(this))
    fun hasValidHash(): Boolean = stateHash == BoardCellHash.compute(copy(stateHash = ""))
}

@Serializable
sealed interface BoardCellEvent {
    @Serializable data class ProjectCommitted(
        val projection: BoardProjection,
        val commandId: String,
        val recoversUnknownProjection: Boolean = false,
    ) : BoardCellEvent
    @Serializable data class ProjectUnknown(val commandId: String, val reason: String) : BoardCellEvent
    @Serializable data class PlaylistReplaced(val playlist: BoardPlaylistState, val commandId: String) : BoardCellEvent
    @Serializable data class MemberJoined(val memberId: String) : BoardCellEvent
    @Serializable data class ControllerHeartbeat(val heartbeat: Long) : BoardCellEvent
    @Serializable data class HandoverPrepared(val value: BoardCellHandover) : BoardCellEvent
    @Serializable data class HandoverTargetReady(val transferId: String, val readinessProof: String) : BoardCellEvent
    @Serializable data class HandoverCommitted(val transferId: String, val targetControllerId: String, val targetTerm: Long) : BoardCellEvent
    @Serializable data class HandoverCompleted(val transferId: String) : BoardCellEvent
    @Serializable data class HandoverAborted(val transferId: String, val reason: String) : BoardCellEvent
    @Serializable data class ProjectionRecoveryRequired(val commandId: String) : BoardCellEvent
    @Serializable data class ForkDetected(val competingLineageId: String, val competingHash: String) : BoardCellEvent
    @Serializable data class OperatorRecovered(
        val newLineageId: String,
        val newEpoch: Long,
        val resolvedLineages: Set<String>,
        val resolvedMembers: Set<String>,
    ) : BoardCellEvent
}

@Serializable
data class BoardCellEnvelope(
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val controllerTerm: Long,
    val sequence: Long,
    val previousHash: String,
    val event: BoardCellEvent,
    val resultingHash: String,
)

@Serializable
data class BoardCellClaim(
    val physicalBoardId: PhysicalBoardId,
    val cellId: BoardCellId,
    val claimantId: String,
    val proposedTerm: Long,
    val lineageId: String = UUID.randomUUID().toString(),
) {
    val rank: String get() = "%020d|%s|%s".format(proposedTerm, cellId.value, claimantId)
}

@Serializable
enum class BoardCommandStatus {
    ACCEPTED, COMMITTED, SUPERSEDED, REJECTED_STALE, NOT_CONTROLLER, BOARD_WRITE_FAILED,
}

@Serializable
data class BoardCommandAck(
    val commandId: String,
    val status: BoardCommandStatus,
    val cellId: BoardCellId,
    val epoch: Long,
    val controllerTerm: Long,
    val resultingSequence: Long? = null,
    val resultingHash: String? = null,
    val detail: String? = null,
)

@Serializable
enum class BoardWriteIntentState { PREPARED, PHYSICAL_WRITE_SUCCEEDED, COMMITTED }

@Serializable
data class BoardWriteIntent(
    val commandId: String,
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val controllerTerm: Long,
    val baseSequence: Long,
    val baseHash: String,
    val requestedProjection: BoardProjection?,
    val state: BoardWriteIntentState = BoardWriteIntentState.PREPARED,
)

internal object BoardCellHash {
    fun compute(snapshot: BoardCellSnapshot): String {
        val canonical = buildString {
            append("board-cell-v2\n").append(snapshot.cellId.value).append('\n')
            append(snapshot.physicalBoardId.value).append('\n').append(snapshot.epoch).append('\n')
            append(snapshot.sequence).append('\n').append(snapshot.controllerId).append('\n')
            append(snapshot.controllerTerm).append('\n').append(snapshot.controllerHeartbeat).append('\n')
            append(snapshot.lineageId).append('\n')
            snapshot.resolvedLineages.sorted().forEach { append("r:").append(it).append('\n') }
            snapshot.members.sorted().forEach { append("m:").append(it).append('\n') }
            snapshot.projection?.let { append("p:${it.climbUuid}|${it.angle}|${it.projectionSurvivesDisconnect}\n") }
                ?: append("p:-\n")
            append("pk:${snapshot.projectionKnown}\n")
            append("s:${snapshot.playlist.sessionId ?: "-"}|${snapshot.playlist.currentIndex}\n")
            snapshot.playlist.items.forEach { append("q:${it.first}|${it.second}\n") }
            append("a:${snapshot.availability.name}\n")
            snapshot.handover?.let {
                append("h:${it.transferId}|${it.sourceControllerId}|${it.targetControllerId}|${it.sourceTerm}|${it.targetTerm}|${it.baseSequence}|${it.baseHash}|${it.phase}|${it.readinessProof ?: "-"}\n")
            } ?: append("h:-\n")
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

internal object BoardCellLineage {
    fun resolvedId(cellId: BoardCellId, lineages: Set<String>): String {
        require(lineages.size >= 2)
        val material = "board-cell-lineage-resolution-v1|${cellId.value}|${lineages.sorted().joinToString("|")}"
        return MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
