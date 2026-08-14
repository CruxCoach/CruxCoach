package com.cruxcoach.android.boardcell

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Stable identity of one physical wall/controller, independent of transport peers. */
@JvmInline
@Serializable
value class PhysicalBoardId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
@Serializable
value class BoardCellId(val value: String) {
    init { require(value.isNotBlank()) }

    companion object {
        /** Same physical identity yields the same bootstrap cell and removes the double-cell race. */
        fun forPhysical(board: PhysicalBoardId): BoardCellId {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("cruxcoach-board-cell-v1|${board.value}".encodeToByteArray())
            val hex = bytes.take(16).joinToString("") { "%02x".format(it) }
            return BoardCellId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}")
        }
    }
}

@Serializable
data class BoardProjection(
    val climbUuid: String,
    val angle: Int,
    val projectionSurvivesDisconnect: Boolean = true,
)

@Serializable
data class BoardPlaylistState(
    val sessionId: Int? = null,
    val currentIndex: Int = -1,
    val items: List<Pair<String, Int>> = emptyList(),
)

@Serializable
enum class BoardCellAvailability { SETTLING, ACTIVE, FROZEN_NEEDS_CONTROLLER, FROZEN_NEEDS_SNAPSHOT }

/** Complete, deterministically hashable state for exactly one physical board. */
@Serializable
data class BoardCellSnapshot(
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val sequence: Long,
    val controllerId: String,
    val leaseUntilMs: Long,
    val members: Set<String>,
    val projection: BoardProjection? = null,
    val projectionKnown: Boolean = true,
    val playlist: BoardPlaylistState = BoardPlaylistState(),
    val availability: BoardCellAvailability = BoardCellAvailability.ACTIVE,
    val stateHash: String = "",
) {
    fun withComputedHash(): BoardCellSnapshot = copy(stateHash = BoardCellHash.compute(this))
    fun hasValidHash(): Boolean = stateHash == BoardCellHash.compute(copy(stateHash = ""))
}

@Serializable
sealed interface BoardCellEvent {
    @Serializable
    data class ProjectCommitted(val projection: BoardProjection) : BoardCellEvent
    /** Board accepted bytes, but the external payload could not be mapped to a catalogue climb. */
    @Serializable
    data class ProjectUnknown(val reason: String = "unidentified_external_write") : BoardCellEvent
    @Serializable
    data class PlaylistReplaced(val playlist: BoardPlaylistState) : BoardCellEvent
    @Serializable
    data class MemberJoined(val memberId: String) : BoardCellEvent
    @Serializable
    data class LeaseRenewed(val leaseUntilMs: Long) : BoardCellEvent
    @Serializable
    data class ControllerTransferred(val controllerId: String, val leaseUntilMs: Long) : BoardCellEvent
}

@Serializable
data class BoardCellEnvelope(
    val cellId: BoardCellId,
    val physicalBoardId: PhysicalBoardId,
    val epoch: Long,
    val sequence: Long,
    val previousHash: String,
    val event: BoardCellEvent,
    val resultingHash: String,
)

/** Claim advertisements are discovery hints only and are never board state. */
@Serializable
data class BoardCellClaim(
    val physicalBoardId: PhysicalBoardId,
    val cellId: BoardCellId,
    val claimantId: String,
    val epoch: Long,
    val observedAtMs: Long,
) {
    val rank: String get() = "%020d|%s|%s".format(epoch, cellId.value, claimantId)
}

internal object BoardCellHash {
    fun compute(snapshot: BoardCellSnapshot): String {
        val canonical = buildString {
            append("board-cell-v1\n")
            append(snapshot.cellId.value).append('\n')
            append(snapshot.physicalBoardId.value).append('\n')
            append(snapshot.epoch).append('\n')
            append(snapshot.sequence).append('\n')
            append(snapshot.controllerId).append('\n')
            append(snapshot.leaseUntilMs).append('\n')
            snapshot.members.sorted().forEach { append("m:").append(it).append('\n') }
            snapshot.projection?.let {
                append("p:").append(it.climbUuid).append('|').append(it.angle).append('|')
                    .append(it.projectionSurvivesDisconnect).append('\n')
            } ?: append("p:-\n")
            append("pk:").append(snapshot.projectionKnown).append('\n')
            append("s:").append(snapshot.playlist.sessionId ?: "-").append('|')
                .append(snapshot.playlist.currentIndex).append('\n')
            snapshot.playlist.items.forEach { append("q:").append(it.first).append('|').append(it.second).append('\n') }
            append("a:").append(snapshot.availability.name).append('\n')
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
