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

/** Identifies one occurrence without relying on an index that may have moved. */
@Serializable
data class BoardPlaylistItemRef(
    val climbUuid: String,
    val angle: Int,
    val occurrence: Int,
    val totalAtBase: Int,
)

@Serializable
enum class BoardPlaylistCommandKind { ADD, REMOVE, SET_CURRENT, NEXT, PREV, MOVE, RESEND }

/** Minimal semantic preconditions needed to safely rebase a playlist command. */
@Serializable
data class BoardPlaylistCommandContext(
    val sessionId: Int?,
    val kind: BoardPlaylistCommandKind,
    val subject: BoardPlaylistItemRef? = null,
    val before: BoardPlaylistItemRef? = null,
    val after: BoardPlaylistItemRef? = null,
    val expectedCurrent: BoardPlaylistItemRef? = null,
    val expectedTarget: BoardPlaylistItemRef? = null,
)

@Serializable
enum class BoardCellAvailability {
    SETTLING, ACTIVE, FROZEN_NEEDS_CONTROLLER, FROZEN_NEEDS_SNAPSHOT,
    FROZEN_WRITE_RECOVERY, FROZEN_FORK,
}

@Serializable
enum class HandoverPhase { PREPARED, SOURCE_RELEASED, TARGET_READY, COMMITTED, COMPLETED, ABORTED }

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

@Serializable
data class BoardCellControllerRequest(
    val requestId: String,
    val requesterId: String,
)

@Serializable
data class BoardCellControllerDecision(
    val requestId: String,
    val accepted: Boolean,
)

/** A current member attests that [candidateId] is its directly authenticated BLE neighbor. */
@Serializable
data class BoardCellJoinRequest(
    val requestId: String,
    val candidateId: String,
    val sponsorId: String,
)

@Serializable
data class BoardCellLeaveRequest(
    val requestId: String,
)

@Serializable
enum class BoardCellMemberLeaveReason { VOLUNTARY, LIVENESS_TIMEOUT }

@Serializable
data class BoardCellControllerRecovery(
    val claimantId: String,
    val baseControllerId: String,
    val baseControllerTerm: Long,
    val baseSequence: Long,
    val baseHash: String,
    val connectionProof: String,
    val envelope: BoardCellEnvelope,
)

/**
 * Durable authorization for snapshots published by a recovered controller.
 *
 * Recovery used to be carried only by the transient event. If that packet was
 * lost, peers correctly rejected the new controller's later snapshot because
 * it was neither the old controller nor a handover target. Keeping the exact
 * recovery base in the hashed snapshot makes reconnect/anti-entropy repair the
 * same transition without weakening normal snapshot authority.
 */
@Serializable
data class BoardCellControllerRecoveryProof(
    val claimantId: String,
    val baseControllerId: String,
    val baseControllerTerm: Long,
    val baseSequence: Long,
    val baseHash: String,
    val connectionProof: String,
)

@Serializable
data class BoardProjectionRequest(
    val commandId: String,
    val projection: BoardProjection,
    val baseSequence: Long,
    val baseProjection: BoardProjection?,
    val basePlaylistRevision: Long,
)

internal fun BoardProjectionRequest.semanticBaseSequence(current: BoardCellSnapshot): Long =
    if (baseSequence <= current.sequence && baseProjection == current.projection &&
        basePlaylistRevision == current.playlistRevision) current.sequence else baseSequence

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
    /** Advances only when the canonical live-membership set changes. */
    val membershipRevision: Long = 0,
    val projection: BoardProjection? = null,
    val projectionKnown: Boolean = true,
    val playlist: BoardPlaylistState = BoardPlaylistState(),
    /** Advances only for playlist mutations; heartbeats cannot stale UI commands. */
    val playlistRevision: Long = 0,
    /** Bounded handover-safe idempotency window for retried commands. */
    val recentCommandIds: List<String> = emptyList(),
    val availability: BoardCellAvailability = BoardCellAvailability.ACTIVE,
    val handover: BoardCellHandover? = null,
    val lastControllerRecovery: BoardCellControllerRecoveryProof? = null,
    val stateHash: String = "",
) {
    fun withComputedHash(): BoardCellSnapshot = copy(stateHash = BoardCellHash.compute(this))
    fun hasValidHash(): Boolean = stateHash == BoardCellHash.compute(copy(stateHash = "")) ||
        (membershipRevision == 0L &&
            stateHash == BoardCellHash.computeLegacyV4(copy(stateHash = ""))) ||
        (membershipRevision == 0L && lastControllerRecovery == null &&
            stateHash == BoardCellHash.computeLegacyV3(copy(stateHash = ""))) ||
        (membershipRevision == 0L && lastControllerRecovery == null && playlistRevision == 0L &&
            stateHash == BoardCellHash.computeLegacyV2(copy(stateHash = "")))
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
    @Serializable data class MemberLeft(
        val memberId: String,
        val reason: BoardCellMemberLeaveReason,
    ) : BoardCellEvent
    @Serializable data class ControllerHeartbeat(val heartbeat: Long) : BoardCellEvent
    @Serializable data class HandoverPrepared(val value: BoardCellHandover) : BoardCellEvent
    @Serializable data class HandoverSourceReleased(val transferId: String) : BoardCellEvent
    @Serializable data class HandoverTargetReady(val transferId: String, val readinessProof: String) : BoardCellEvent
    @Serializable data class HandoverCommitted(val transferId: String, val targetControllerId: String, val targetTerm: Long) : BoardCellEvent
    @Serializable data class HandoverCompleted(val transferId: String) : BoardCellEvent
    @Serializable data class HandoverAborted(val transferId: String, val reason: String) : BoardCellEvent
    @Serializable data class ControllerRecovered(
        val controllerId: String,
        val controllerTerm: Long,
        val connectionProof: String,
    ) : BoardCellEvent
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
    ACCEPTED, COMMITTED, SUPERSEDED, REJECTED_STALE, REJECTED_CONFLICT,
    NOT_CONTROLLER, BOARD_WRITE_FAILED,
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
    fun compute(snapshot: BoardCellSnapshot): String = compute(snapshot, "board-cell-v5", true, true, true)
    fun computeLegacyV4(snapshot: BoardCellSnapshot): String = compute(snapshot, "board-cell-v4", true, true, false)
    fun computeLegacyV3(snapshot: BoardCellSnapshot): String = compute(snapshot, "board-cell-v3", true, false, false)
    fun computeLegacyV2(snapshot: BoardCellSnapshot): String = compute(snapshot, "board-cell-v2", false, false, false)

    private fun compute(snapshot: BoardCellSnapshot, schema: String, includePlaylistRevision: Boolean,
        includeControllerRecovery: Boolean, includeMembershipRevision: Boolean): String {
        val canonical = buildString {
            append(schema).append('\n').append(snapshot.cellId.value).append('\n')
            append(snapshot.physicalBoardId.value).append('\n').append(snapshot.epoch).append('\n')
            append(snapshot.sequence).append('\n').append(snapshot.controllerId).append('\n')
            append(snapshot.controllerTerm).append('\n').append(snapshot.controllerHeartbeat).append('\n')
            append(snapshot.lineageId).append('\n')
            snapshot.resolvedLineages.sorted().forEach { append("r:").append(it).append('\n') }
            snapshot.members.sorted().forEach { append("m:").append(it).append('\n') }
            if (includeMembershipRevision) append("mr:${snapshot.membershipRevision}\n")
            snapshot.projection?.let { append("p:${it.climbUuid}|${it.angle}|${it.projectionSurvivesDisconnect}\n") }
                ?: append("p:-\n")
            append("pk:${snapshot.projectionKnown}\n")
            append("s:${snapshot.playlist.sessionId ?: "-"}|${snapshot.playlist.currentIndex}\n")
            snapshot.playlist.items.forEach { append("q:${it.first}|${it.second}\n") }
            if (includePlaylistRevision) append("pr:${snapshot.playlistRevision}\n")
            if (includePlaylistRevision) snapshot.recentCommandIds.forEach { append("ci:").append(it).append('\n') }
            append("a:${snapshot.availability.name}\n")
            snapshot.handover?.let {
                append("h:${it.transferId}|${it.sourceControllerId}|${it.targetControllerId}|${it.sourceTerm}|${it.targetTerm}|${it.baseSequence}|${it.baseHash}|${it.phase}|${it.readinessProof ?: "-"}\n")
            } ?: append("h:-\n")
            if (includeControllerRecovery) snapshot.lastControllerRecovery?.let {
                append("cr:${it.claimantId}|${it.baseControllerId}|${it.baseControllerTerm}|")
                append("${it.baseSequence}|${it.baseHash}|${it.connectionProof}\n")
            } ?: append("cr:-\n")
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

/** Canonical, topology-independent staggering for fenced controller recovery. */
internal object BoardCellRecoveryElection {
    private const val EXACT_SLOTS = 8
    private const val SLOT_MS = 250L
    private const val TAIL_JITTER_MS = 500L

    fun delayMs(snapshot: BoardCellSnapshot, candidateId: String, retry: Int): Long? {
        if (candidateId !in snapshot.members) return null
        // The same controller gets first chance after a transient physical
        // board disconnect while its mesh transport remains live. Bluetooth
        // OFF ends local membership and therefore never reaches this path.
        if (candidateId == snapshot.controllerId) return retry.coerceAtMost(3) * 1_500L
        val ranked = snapshot.members.asSequence().filter { it != snapshot.controllerId }
            .map { it to score(snapshot, it) }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .toList()
        val rank = ranked.indexOfFirst { it.first == candidateId }
        if (rank < 0) return null
        val initial = if (rank < EXACT_SLOTS) (rank + 1) * SLOT_MS else
            (EXACT_SLOTS + 1) * SLOT_MS + ranked[rank].second % (TAIL_JITTER_MS + 1)
        return initial + retry.coerceAtMost(3) * 1_500L
    }

    private fun score(snapshot: BoardCellSnapshot, candidateId: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(
            "board-cell-recovery-v1|${snapshot.cellId.value}|${snapshot.controllerTerm}|".plus(
                "${snapshot.stateHash}|$candidateId").encodeToByteArray())
        return ((bytes[0].toLong() and 0xff) shl 24) or
            ((bytes[1].toLong() and 0xff) shl 16) or
            ((bytes[2].toLong() and 0xff) shl 8) or
            (bytes[3].toLong() and 0xff)
    }
}
