package com.cruxcoach.android.boardcell

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
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

/**
 * How well this device knows what the wall is showing.
 *
 * Kept as five distinct answers rather than a boolean, because the UI has to
 * be able to say which one it is: "on its way" and "the board refused it" are
 * both "not confirmed" and mean opposite things to somebody standing in front
 * of the wall, and "nobody knows" is not the same as "not yours".
 *
 * Derived, never transmitted. Adding a confidence field to the BoardCell wire
 * model would need a protocol version and a mixed-client rollout; every input
 * this needs is already canonical.
 */
enum class BoardProjectionConfidence {
    /** A projection is on its way and has not been answered yet. */
    PENDING,
    /** The transport completed. The strongest claim a write-only board allows. */
    TRANSPORTED,
    /** The controller was asked and named this climb — Quantum only. */
    CONTROLLER_CONFIRMED,
    /** Something wrote to the board outside CruxCoach, or state was lost. */
    UNKNOWN,
    /** The write was attempted and did not land. */
    FAILED,
}

/** Why the canonical current climb is not on the wall. */
@Serializable
enum class BoardPlaylistProjectionPendingReason { BOARD_WRITE_FAILED, CLIMB_UNAVAILABLE }

/**
 * The shared playlist's current entry is not on the board.
 *
 * Deliberately carries no promise that anybody is fetching the climb: this
 * build has no peer climb transfer, so the honest UI wording is "not
 * available"/"send pending" plus a retry every board member may press.
 *
 * [entryId] rather than the climb alone, because the same climb may sit in the
 * playlist any number of times and a pending send belongs to exactly one of
 * those occurrences.
 */
@Serializable
data class BoardPlaylistPendingProjection(
    val entryId: String,
    val climbUuid: String,
    val angle: Int,
    val reason: BoardPlaylistProjectionPendingReason,
)

/**
 * A planned rest that is running right now.
 *
 * [totalSeconds] is the plan and [endsAtEpochMs] is when it is over, as UTC
 * wall-clock time. Monotonic values are deliberately absent: they are
 * per-boot and meaningless on another device, whereas a UTC instant survives
 * the wire, the state hash, the durable snapshot, anti-entropy repair, a
 * technical controller handover and a process restart, which is exactly the
 * set of transitions the rest has to stay synchronised across.
 *
 * A peer therefore shows the *remaining* time, not the full duration again:
 * joining 40 s into a 2:00 rest shows 1:20, and a rest whose end has already
 * passed is over rather than starting afresh. [totalSeconds] stays as the
 * plausibility bound and as the "of how much" for progress UI, and
 * [generation] still distinguishes a genuinely new rest from a replay of the
 * one this device already observed.
 */
@Serializable
data class BoardPlaylistRest(
    val totalSeconds: Int,
    val generation: Long,
    /**
     * The entry the queue already points at while this rest runs.
     *
     * An entry id rather than an index: a concurrent add or move shifts every
     * index, and a rest that silently came to mean a different climb was the
     * whole reason occurrences became addressable in the first place.
     */
    val nextEntryId: String = "",
    /**
     * UTC epoch milliseconds at which this rest is over.
     *
     * Defaulted rather than required so a durable document written before this
     * field existed still decodes. The durable store decodes the whole
     * snapshot in one go and swallows a failure, so a missing field here would
     * throw away the entire cell — controller, term, membership and playlist —
     * over one transient pause. Zero is outside [BoardPlaylistInstant]'s valid
     * window, so normalization drops such a rest instead of honouring a
     * deadline in 1970.
     */
    val endsAtEpochMs: Long = 0,
    /**
     * UTC epoch milliseconds at which this rest was armed.
     *
     * The pair is what makes the duration checkable: every replica can verify
     * `endsAt - startedAt == totalSeconds` without reading a clock, so a
     * controller cannot stamp a "two minute" pause that actually runs until
     * 2099. Without it, only the far end was bounded, and any value inside the
     * next eighty years passed.
     */
    val startedAtEpochMs: Long = 0,
) {
    /**
     * Seconds still to run at [nowEpochMs]; zero once it is over.
     *
     * Capped at [totalSeconds] so a peer whose clock runs behind the arming
     * device cannot be shown a longer rest than was ever planned.
     */
    fun remainingSeconds(nowEpochMs: Long): Int {
        val remainingMs = endsAtEpochMs - nowEpochMs
        if (remainingMs <= 0) return 0
        return ((remainingMs + 999) / 1000).coerceAtMost(totalSeconds.toLong()).toInt()
    }

    fun hasExpired(nowEpochMs: Long): Boolean = nowEpochMs >= endsAtEpochMs

    /**
     * Whether this rest claims to begin in a future this device cannot
     * believe in.
     *
     * The canonical pair is self-consistent by construction, so a rest that
     * has not started yet means the stamping device's clock is wrong or
     * hostile. Restarting the full duration on every process restart is what
     * that produced; refusing to start it at all is the honest answer, and
     * unlike the wire checks this one is deliberately local — it depends on
     * this device's own clock and must never enter the state hash.
     */
    fun startsAfter(nowEpochMs: Long, toleranceMs: Long): Boolean =
        nowEpochMs < startedAtEpochMs - toleranceMs
}

/**
 * Bounds for the UTC instants that cross the wire.
 *
 * A canonical deadline is only useful if it cannot be absurd: without this a
 * peer could publish a rest ending in the year 40000 and every replica would
 * faithfully hash and honour it. The window is deliberately wide enough to
 * need no maintenance and narrow enough to reject a garbage or hostile value.
 */
internal object BoardPlaylistInstant {
    /** 2020-09-13T12:26:40Z — comfortably before this feature could exist. */
    const val MIN_EPOCH_MS = 1_600_000_000_000L
    /** 2100-01-01T00:00:00Z. */
    const val MAX_EPOCH_MS = 4_102_444_800_000L

    /**
     * How far apart two devices' clocks may be before a canonical window is
     * treated as untrustworthy rather than merely early.
     *
     * Local only: it decides what this device is willing to display, never
     * what the canonical state is, so it can never make two replicas disagree
     * about the hash.
     */
    const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1_000L

    fun isValid(epochMs: Long): Boolean = epochMs in MIN_EPOCH_MS..MAX_EPOCH_MS

    /**
     * Whether a start/end pair really describes a window of [expectedMs].
     *
     * Both ends inside the epoch range is not enough on its own: it still
     * permits a "two minute" pause that ends in eighty years. Checking the
     * difference is what bounds the duration, and it needs no clock, so every
     * replica reaches the same verdict and the state hash stays deterministic.
     */
    fun isWindow(startEpochMs: Long, endEpochMs: Long, expectedMs: Long): Boolean =
        isValid(startEpochMs) && isValid(endEpochMs) && endEpochMs - startEpochMs == expectedMs
}

/**
 * One occurrence of a climb in the shared playlist.
 *
 * [entryId] identifies *this occurrence*, not the climb. The same climb may
 * legitimately appear any number of times — 4x4s and limit-attempt blocks are
 * written out exactly that way — and an index or a climb id cannot say which
 * of them a concurrent edit meant. Two people removing "the second Zombie
 * Hands" at the same moment used to be able to delete two different entries,
 * or the same one twice; addressing the occurrence removes the ambiguity
 * without any tie-breaking heuristic.
 *
 * The id is minted by the device that adds the entry and never changes, so a
 * retry of the same add is a no-op rather than a duplicate, on any controller.
 */
@Serializable
data class BoardPlaylistEntry(
    val entryId: String,
    val climbUuid: String,
    val angle: Int,
    /** Planned rest after this entry, in seconds. */
    val restAfterSeconds: Int = 0,
)

/**
 * The list a clear emptied, kept just long enough to take it back.
 *
 * Clearing the board's list is the one edit nobody can reconstruct by hand,
 * and it is available to every member — which is exactly the combination that
 * needs a way back. The record is canonical rather than local because the
 * person who has to undo it is very often not the person who pressed it: the
 * whole group watches the wall's list vanish, so the whole group must be able
 * to bring it back.
 *
 * [restorableUntilEpochMs] is stamped by the controller together with
 * [clearedAtEpochMs], so every replica derives the same deadline from the same
 * bytes and shows the same countdown without reading its own clock into the
 * state hash. The pair is what makes the window checkable — bounding only the
 * far end would permit a "30 second" offer that stays open until 2099.
 *
 * The entries keep their original occurrence ids, which is what makes a
 * restore idempotent: replaying it re-adds nothing that is already there, and
 * a climb somebody added *after* the clear is untouched by it.
 */
@Serializable
data class BoardPlaylistClearUndo(
    /** The clear generation this record belongs to; never reused. */
    val generation: Long,
    val entries: List<BoardPlaylistEntry> = emptyList(),
    /** What the group was looking at when the list was emptied. */
    val currentEntryId: String? = null,
    val clearedAtEpochMs: Long = 0,
    val restorableUntilEpochMs: Long = 0,
) {
    /**
     * Seconds still on the offer at [nowEpochMs]; zero once it has run out.
     *
     * Capped at the window length so a device whose clock runs behind the
     * controller is never shown a longer offer than was ever made.
     */
    fun remainingSeconds(nowEpochMs: Long): Int {
        val remainingMs = restorableUntilEpochMs - nowEpochMs
        if (remainingMs <= 0) return 0
        val windowSeconds = ((restorableUntilEpochMs - clearedAtEpochMs) / 1000L).coerceAtLeast(0)
        return ((remainingMs + 999) / 1000).coerceAtMost(windowSeconds).toInt()
    }

    fun hasExpired(nowEpochMs: Long): Boolean = nowEpochMs >= restorableUntilEpochMs
}

/**
 * The one canonical playlist of a physical BoardCell.
 *
 * It is created with the cell and lives exactly as long as it. There is no
 * playlist host, no separate playlist join or leave, no approval and no
 * independent end: being in the mesh *is* taking part, and every member may
 * edit it arbitrarily. The technical BoardCell controller serializes the edits
 * and is the single writer to the physical board, but has no product-level
 * authority over the playlist whatsoever.
 *
 * Local-only playlists never reach this type at all — a device outside a
 * BoardCell keeps its own queue in
 * [com.cruxcoach.android.data.SessionQueueManager].
 */
@Serializable
data class BoardPlaylistState(
    /** Deterministically derived from the cell; stable for the cell's life. */
    val sessionId: Int? = null,
    val entries: List<BoardPlaylistEntry> = emptyList(),
    /** The occurrence the group is on; null exactly while [entries] is empty. */
    val currentEntryId: String? = null,
    val activeRest: BoardPlaylistRest? = null,
    val pendingProjection: BoardPlaylistPendingProjection? = null,
    /**
     * How many times this playlist has been cleared.
     *
     * An edit carries the generation it was composed against, so an add that
     * was in flight while somebody else emptied the playlist is dropped
     * instead of resurrecting one entry of a list that no longer exists. It
     * also makes the clear itself idempotent: a retry names a generation the
     * playlist has already reached and changes nothing.
     */
    val clearGeneration: Long = 0,
    /**
     * The list the most recent clear emptied, while it can still be taken
     * back.
     *
     * Null is the normal state: no clear has happened, the offer was taken, or
     * the controller has already retired an expired one. See
     * [BoardPlaylistClearUndo] for why this is canonical rather than a local
     * snackbar on the device that pressed the button.
     */
    val lastClear: BoardPlaylistClearUndo? = null,
) {
    val currentIndex: Int
        get() = currentEntryId?.let { id -> entries.indexOfFirst { it.entryId == id } } ?: -1

    fun currentEntry(): BoardPlaylistEntry? = entries.getOrNull(currentIndex)
    fun entry(entryId: String): BoardPlaylistEntry? = entries.firstOrNull { it.entryId == entryId }
    fun indexOf(entryId: String): Int = entries.indexOfFirst { it.entryId == entryId }
    fun entryIdAt(index: Int): String? = entries.getOrNull(index)?.entryId
    fun restAt(index: Int): Int = entries.getOrNull(index)?.restAfterSeconds ?: 0
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Nothing beyond the pre-shared-playlist fields is in use.
     *
     * The only remaining purpose is the legacy state-hash chain: a durable
     * snapshot written before the shared playlist existed may still validate,
     * but only while its playlist is genuinely empty, so no older shape is
     * ever silently reinterpreted as a populated one.
     */
    internal val usesLegacyShapeOnly: Boolean
        get() = sessionId == null && entries.isEmpty() && currentEntryId == null &&
            activeRest == null && pendingProjection == null && clearGeneration == 0L &&
            lastClear == null

    /**
     * Nothing a pre-V9 schema could not express is in use.
     *
     * The restorable clear is the only thing V9 added, so a durable snapshot
     * written by the previous build stays verifiable under its own schema for
     * as long as this is true — which is every snapshot that build could
     * possibly have written.
     */
    internal val usesPreRestoreShapeOnly: Boolean get() = lastClear == null
}

/**
 * The playlist session id of one cell, derived rather than negotiated.
 *
 * Every replica computes the same value from state it already agrees on, so
 * the playlist that is created with the cell needs no start command, no
 * proposal and no round trip — and a controller handover or a restart cannot
 * change what the group is looking at.
 */
internal object BoardPlaylistSession {
    fun idFor(cellId: BoardCellId, epoch: Long): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("board-cell-playlist-session-v1|${cellId.value}|$epoch".encodeToByteArray())
        val raw = ((digest[0].toInt() and 0x7f) shl 24) or ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
        return if (raw == 0) 1 else raw
    }
}
@Serializable
enum class BoardCellAvailability {
    SETTLING, ACTIVE, FROZEN_NEEDS_CONTROLLER, FROZEN_NEEDS_SNAPSHOT,
    FROZEN_WRITE_RECOVERY, FROZEN_FORK,
}

/** User-facing admission rule for one live board session. */
@Serializable
enum class BoardJoinMode { OPEN, APPROVAL_REQUIRED }

/** Rejects completions from an older local join/leave attempt. */
internal class MeshMembershipAttemptGate {
    private val generation = AtomicLong(0)

    fun begin(): Long = generation.incrementAndGet()
    fun supersede(): Long = generation.incrementAndGet()
    fun current(): Long = generation.get()
    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate
}

internal object BoardCellAdmissionResultPolicy {
    fun shouldApplyRejection(
        candidateId: String,
        localNodeId: String,
        approved: Boolean,
        activeMembership: Boolean,
        transition: MeshMembershipTransition,
    ): Boolean = candidateId == localNodeId && !approved && !activeMembership &&
        transition in setOf(
            MeshMembershipTransition.JOINING,
            MeshMembershipTransition.WAITING_APPROVAL,
        )
}

internal object MeshMembershipTransitionPolicy {
    const val ERROR_VISIBLE_MS = 5_000L

    fun resetDelayMs(
        transition: MeshMembershipTransition,
        retryAfterEpochMs: Long,
        nowEpochMs: Long,
    ): Long? = when (transition) {
        MeshMembershipTransition.ERROR -> ERROR_VISIBLE_MS
        MeshMembershipTransition.COOLDOWN -> (retryAfterEpochMs - nowEpochMs).coerceAtLeast(0L)
        else -> null
    }
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

/** A join request shown to every current member of an occupied board. */
@Serializable
data class BoardCellAdmissionPrompt(
    val requestId: String,
    val candidateId: String,
    val sponsorId: String,
    val requestedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

/** Any current member may answer; the controller only serializes the first answer. */
@Serializable
data class BoardCellAdmissionDecision(
    val requestId: String,
    val candidateId: String,
    val approved: Boolean,
)

/** Explicit outcome for the requester and all members, including retry timing. */
@Serializable
data class BoardCellAdmissionResult(
    val requestId: String,
    val candidateId: String,
    val approved: Boolean,
    val retryAfterEpochMs: Long = 0,
)

internal object BoardCellAdmissionCooldownPolicy {
    fun retryAfterEpochMs(
        approved: Boolean,
        expired: Boolean,
        nowEpochMs: Long,
        cooldownMs: Long,
    ): Long = if (!approved && !expired) nowEpochMs + cooldownMs else 0L
}

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
    val joinMode: BoardJoinMode = BoardJoinMode.OPEN,
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

    /**
     * Every legacy schema stays acceptable only while the fields it could not
     * cover are still at their defaults.
     *
     * V9 adds the restorable clear, so V8 stays exact for as long as no clear
     * is restorable — which covers every snapshot the previous build could
     * write, and lets a durable one survive the upgrade untouched.
     *
     * V8 replaces the index-addressed playlist with stable per-occurrence
     * entry ids, a derived current entry and a clear generation. None of the
     * older schemas can express any of that, so they stay valid only while the
     * playlist is genuinely empty — a populated pre-V8 playlist is never
     * silently reinterpreted under the new shape, it fails closed and is
     * repaired from a canonical snapshot instead.
     */
    fun hasValidHash(): Boolean = stateHash == BoardCellHash.compute(copy(stateHash = "")) ||
        (playlist.usesPreRestoreShapeOnly &&
            stateHash == BoardCellHash.computeLegacyV8(copy(stateHash = ""))) ||
        (joinMode == BoardJoinMode.OPEN && playlist.usesLegacyShapeOnly &&
            stateHash == BoardCellHash.computeLegacyV6(copy(stateHash = ""))) ||
        (joinMode == BoardJoinMode.OPEN && playlist.usesLegacyShapeOnly &&
            stateHash == BoardCellHash.computeLegacyV5(copy(stateHash = ""))) ||
        (joinMode == BoardJoinMode.OPEN && playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
            stateHash == BoardCellHash.computeLegacyV4(copy(stateHash = ""))) ||
        (joinMode == BoardJoinMode.OPEN && playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
            lastControllerRecovery == null &&
            stateHash == BoardCellHash.computeLegacyV3(copy(stateHash = ""))) ||
        (joinMode == BoardJoinMode.OPEN && playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
            lastControllerRecovery == null && playlistRevision == 0L &&
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
    /**
     * One committed batch of playlist operations.
     *
     * The delta, not the list: a normal edit no longer puts the whole playlist
     * on the wire. Replicas replay [ops] against the identical predecessor
     * state and verify the resulting hash, so a divergence is detected rather
     * than absorbed, and full snapshots stay the repair path for join,
     * restart, gaps, anti-entropy, recovery and handover.
     */
    @Serializable data class PlaylistOpsCommitted(
        val ops: List<BoardPlaylistOp>,
        val commandId: String,
    ) : BoardCellEvent
    @Serializable data class MemberJoined(val memberId: String) : BoardCellEvent
    @Serializable data class JoinModeChanged(val mode: BoardJoinMode) : BoardCellEvent
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
    /** The winning first controller's preference seeds the shared session. */
    val proposedJoinMode: BoardJoinMode = BoardJoinMode.OPEN,
) {
    val rank: String get() = "%020d|%s|%s".format(proposedTerm, cellId.value, claimantId)
}

@Serializable
enum class BoardCommandStatus {
    ACCEPTED, COMMITTED, SUPERSEDED, REJECTED_STALE, REJECTED_CONFLICT,
    NOT_CONTROLLER, BOARD_WRITE_FAILED;

    /**
     * Whether this answer decided the command, and may therefore be cached and
     * replayed to a retry.
     *
     * [ACCEPTED] has not decided anything yet. [NOT_CONTROLLER] and
     * [REJECTED_STALE] are statements about the answering device at that
     * moment — it was mid-handover, or it was behind — so replaying them to a
     * later retry would repeat a refusal whose cause had already gone, and the
     * edit would be lost with no error anybody could act on.
     */
    val isTerminalDecision: Boolean
        get() = this == COMMITTED || this == SUPERSEDED || this == REJECTED_CONFLICT ||
            this == BOARD_WRITE_FAILED
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
) {
    /** A COMMITTED no-op is idempotent success, but it must not create an Undo offer. */
    val changedPlaylist: Boolean get() =
        status == BoardCommandStatus.COMMITTED && detail != DETAIL_ALREADY_IN_REQUESTED_STATE

    companion object {
        const val DETAIL_ALREADY_IN_REQUESTED_STATE = "already in the requested state"
    }
}

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
    /**
     * The current schema.
     *
     * V9 adds the restorable clear: the list a clear emptied, with the
     * controller-stamped window in which anybody may bring it back. V8 was the
     * entry-addressed shared playlist: per-occurrence entry ids, a current
     * *entry* rather than a current index, the per-entry rest plan and the
     * clear generation. Everything a pre-V8 schema could express about a
     * playlist is a strict subset that is only reachable while the playlist is
     * empty, which is exactly the condition [BoardCellSnapshot.hasValidHash]
     * puts on the legacy chain; V8 itself stays exact for as long as no clear
     * is restorable, which is every snapshot that build could write.
     */
    fun compute(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v9", true, true, true, true, true, true, true)
    fun computeLegacyV8(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v8", true, true, true, true, true, true, false)
    fun computeLegacyV6(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v6", true, true, true, true, false, false, false)
    fun computeLegacyV5(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v5", true, true, true, false, false, false, false)
    fun computeLegacyV4(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v4", true, true, false, false, false, false, false)
    fun computeLegacyV3(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v3", true, false, false, false, false, false, false)
    fun computeLegacyV2(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v2", false, false, false, false, false, false, false)

    private fun compute(snapshot: BoardCellSnapshot, schema: String, includePlaylistRevision: Boolean,
        includeControllerRecovery: Boolean, includeMembershipRevision: Boolean,
        includeLegacyPlaylistShape: Boolean, includeJoinMode: Boolean,
        includeEntryPlaylist: Boolean, includeClearUndo: Boolean): String {
        val canonical = buildString {
            append(schema).append('\n').append(snapshot.cellId.value).append('\n')
            append(snapshot.physicalBoardId.value).append('\n').append(snapshot.epoch).append('\n')
            append(snapshot.sequence).append('\n').append(snapshot.controllerId).append('\n')
            append(snapshot.controllerTerm).append('\n').append(snapshot.controllerHeartbeat).append('\n')
            append(snapshot.lineageId).append('\n')
            snapshot.resolvedLineages.sorted().forEach { append("r:").append(it).append('\n') }
            snapshot.members.sorted().forEach { append("m:").append(it).append('\n') }
            if (includeMembershipRevision) append("mr:${snapshot.membershipRevision}\n")
            if (includeJoinMode) append("jm:${snapshot.joinMode.name}\n")
            snapshot.projection?.let { append("p:${it.climbUuid}|${it.angle}|${it.projectionSurvivesDisconnect}\n") }
                ?: append("p:-\n")
            append("pk:${snapshot.projectionKnown}\n")
            val playlist = snapshot.playlist
            if (includeEntryPlaylist) {
                append("s:${playlist.sessionId ?: "-"}|${playlist.currentEntryId ?: "-"}\n")
                playlist.entries.forEach {
                    append("q:${it.entryId}|${it.climbUuid}|${it.angle}|${it.restAfterSeconds}\n")
                }
                append("pg:${playlist.clearGeneration}\n")
                playlist.activeRest?.let {
                    append("pa:${it.totalSeconds}|${it.generation}|${it.nextEntryId}")
                    append("|${it.startedAtEpochMs}|${it.endsAtEpochMs}\n")
                } ?: append("pa:-\n")
                playlist.pendingProjection?.let {
                    append("pp:${it.entryId}|${it.climbUuid}|${it.angle}|${it.reason.name}\n")
                } ?: append("pp:-\n")
                if (includeClearUndo) {
                    playlist.lastClear?.let { undo ->
                        append("pc:${undo.generation}|${undo.currentEntryId ?: "-"}")
                        append("|${undo.clearedAtEpochMs}|${undo.restorableUntilEpochMs}\n")
                        undo.entries.forEach {
                            append("pu:${it.entryId}|${it.climbUuid}|${it.angle}|${it.restAfterSeconds}\n")
                        }
                    } ?: append("pc:-\n")
                }
            } else {
                // The legacy chain is only ever consulted for an empty
                // playlist, so these are the exact bytes an older build wrote
                // for one. Reconstructing them here keeps a pre-V8 durable
                // snapshot verifiable without keeping its fields alive in the
                // model.
                append("s:-|-1\n")
                if (includeLegacyPlaylistShape) {
                    append("ph:-\n")
                    append("pa:-\n")
                    append("pp:-\n")
                    append("pq:-\n")
                }
            }
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

/**
 * When a member may take over an abandoned controller, and when it may not.
 *
 * Split out of [BoardCellManager] so the fence is testable on the JVM: the
 * manager needs a Context, a BLE stack and a native FIPS runtime, and the rule
 * that decides whether a device seizes a physical board is not something to
 * leave only integration-tested.
 */
internal object BoardCellRecoveryFence {

    /**
     * Whether [localNodeId] may begin a fenced recovery attempt.
     *
     * Liveness is decided by [controllerSilentMs] — the canonical observation
     * of authenticated controller traffic — and never by the radio's
     * direct-peer set. That set is a transport cache: on the 2026-08-17 Nokia
     * capture it still listed the old controller a full minute after its
     * L2CAP channel had closed, and gating on it stalled the election for that
     * entire minute while the cell sat frozen.
     *
     * A controller that is genuinely alive keeps refreshing that observation
     * and the cell never freezes, so this cannot take a board from a working
     * controller.
     */
    fun mayAttemptRecovery(
        snapshot: BoardCellSnapshot,
        localNodeId: String,
        controllerSilentMs: Long?,
        leaseTimeoutMs: Long,
    ): Boolean {
        if (snapshot.availability != BoardCellAvailability.FROZEN_NEEDS_CONTROLLER) return false
        if (localNodeId !in snapshot.members) return false
        if (snapshot.controllerId == localNodeId) return false
        // Never observed at all is not evidence of death; the freeze itself
        // only happens after an observation window, so a missing value here
        // means the replica was just adopted and should wait one window.
        val silent = controllerSilentMs ?: return false
        return silent >= leaseTimeoutMs
    }

    /**
     * Whether the recovery this device started is still the right thing to do.
     *
     * Checked immediately before taking the exclusive board connection and
     * again immediately before the canonical commit. Anything that moved in
     * between — a new controller term, a repaired snapshot, the controller
     * coming back — means somebody else resolved it and this device must not
     * seize the board on top of them.
     */
    fun stillRecoverable(
        current: BoardCellSnapshot?,
        localNodeId: String,
        baseTerm: Long,
        baseHash: String,
        controllerSilentMs: Long?,
        leaseTimeoutMs: Long,
    ): Boolean {
        if (current == null) return false
        if (current.controllerTerm != baseTerm || current.stateHash != baseHash) return false
        return mayAttemptRecovery(current, localNodeId, controllerSilentMs, leaseTimeoutMs)
    }
}

/**
 * What a physical-board connection means for the canonical replica.
 *
 * A reconnect requested by controller recovery or an approved handover is not
 * the same event as a user plugging into a different wall, but all three arrive as one
 * `connectedBoardDescriptor` emission. Treating them alike destroyed the
 * canonical replica: the normal path builds a fresh coordinator and resets the
 * realm, so the operation loses its snapshot, term, membership and handover.
 */
internal object BoardCellReconnectPolicy {
    /**
     * The decision *and* the exact snapshot it was made about.
     *
     * Carrying the snapshot is the point: the caller must not re-read the
     * live flow afterwards. A concurrent update or teardown between the check
     * and the act would otherwise mean binding a different cell id, or
     * dereferencing a value that has since become null.
     */
    sealed interface Decision {
        /** Keep coordinator, snapshot, term, membership and playlist. */
        data class PreserveReplica(val retained: BoardCellSnapshot) : Decision
        /** Ordinary selection: build the cell for this board from scratch. */
        data object Initialize : Decision
    }

    fun decide(
        reconnecting: PhysicalBoardId,
        authorizedBoard: PhysicalBoardId?,
        retained: BoardCellSnapshot?,
        localNodeId: String,
    ): Decision {
        if (authorizedBoard == null || authorizedBoard != reconnecting)
            return Decision.Initialize
        if (retained == null || retained.physicalBoardId != reconnecting)
            return Decision.Initialize
        // Only a member has a base worth preserving; anything else would be
        // reviving state this device has no standing in.
        if (localNodeId !in retained.members) return Decision.Initialize
        return Decision.PreserveReplica(retained)
    }
}

/**
 * Rejects frames which were already queued when an explicit local leave tore
 * down the cell. They are still authenticated, but they no longer grant this
 * device membership and must not recreate a deleted durable replica.
 */
internal object BoardCellLocalLeaveFrameFence {
    fun shouldDrop(departedCell: BoardCellId?, incomingRealmId: String): Boolean =
        departedCell?.value == incomingRealmId
}

/**
 * Narrow authority for breaking an all-peers-restarted join deadlock.
 *
 * A durable member list is not live membership and must not generally be
 * resurrected. The one exception is the exact canonical controller from an
 * ACTIVE, hash-valid snapshot: it can resume the same lineage and term long
 * enough to admit directly authenticated former/new peers. A non-controller,
 * frozen state, foreign realm, or in-flight handover has no such authority.
 */
internal object BoardCellDurableResumePolicy {
    enum class Context {
        PHYSICAL_BOARD_RECONNECT,
        LIVE_NEARBY_JOIN,
    }

    fun controllerSeed(
        snapshot: BoardCellSnapshot?,
        cellId: BoardCellId,
        localNodeId: String,
        context: Context = Context.PHYSICAL_BOARD_RECONNECT,
    ): BoardCellSnapshot? = snapshot?.takeIf {
        context == Context.PHYSICAL_BOARD_RECONNECT &&
            it.cellId == cellId && it.hasValidHash() &&
            it.availability == BoardCellAvailability.ACTIVE &&
            it.controllerId == localNodeId && localNodeId in it.members &&
            it.handover?.phase !in setOf(
                HandoverPhase.PREPARED,
                HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY,
                HandoverPhase.COMMITTED,
            )
    }

    /**
     * A directly connected board is the exclusive physical fence needed to
     * recover a cell whose previous controller is gone.  Preserve a durable
     * non-controller member only as that recovery base; it is never writable
     * and must pass through the normal frozen/recovery transition before this
     * device can host again.
     */
    fun memberRecoverySeed(
        snapshot: BoardCellSnapshot?,
        cellId: BoardCellId,
        localNodeId: String,
    ): BoardCellSnapshot? = snapshot?.takeIf {
        it.cellId == cellId && it.hasValidHash() &&
            it.availability in setOf(
                BoardCellAvailability.ACTIVE,
                BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
                BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT,
            ) &&
            localNodeId in it.members && it.controllerId != localNodeId &&
            it.handover?.phase !in setOf(
                HandoverPhase.PREPARED,
                HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY,
                HandoverPhase.COMMITTED,
            )
    }

    /**
     * Migration escape hatch for snapshots frozen by the old simultaneous-connect bug.
     * A live sponsor always gets the first chance to supply canonical state. Only after
     * that grace period, and only while this device physically owns this exact board, may
     * the unrecoverable local fork record be replaced by a fresh claim.
     */
    fun mayReplaceUnrecoverableFork(
        snapshot: BoardCellSnapshot?,
        cellId: BoardCellId,
        localNodeId: String,
    ): Boolean = snapshot?.let {
        it.cellId == cellId && it.hasValidHash() && localNodeId in it.members &&
            it.availability == BoardCellAvailability.FROZEN_FORK
    } == true
}

/** Distinguishes a live-mesh replica from a snapshot created without FIPS. */
internal object BoardCellFipsBootstrapPolicy {
    /**
     * A singleton `local-*` controller was created by the process-local
     * fallback transport and was never a remotely shared mesh membership.
     * When FIPS becomes available it may be replaced by this device's stable
     * realm npub. Every real foreign identity remains fail-closed.
     */
    fun isLocalFallbackSingleton(snapshot: BoardCellSnapshot?): Boolean = snapshot?.let {
        it.members.size == 1 && it.members.single() == it.controllerId &&
            it.controllerId.startsWith(LOCAL_FALLBACK_PREFIX)
    } == true

    fun hasKnownSharedCell(snapshot: BoardCellSnapshot?, activeNodeId: String): Boolean =
        snapshot != null && !isLocalFallbackSingleton(snapshot) &&
            (snapshot.members - activeNodeId).isNotEmpty()

    private const val LOCAL_FALLBACK_PREFIX = "local-"
}

/**
 * When a replica that has noticed a gap should ask for the snapshot again.
 *
 * A missed delta is repaired the moment the *next* one arrives — the replica
 * detects the break in the chain and asks for canonical state there and then.
 * What this covers is the case where that request itself is lost and no
 * further delta is coming: without it the only repair left was the 2 s
 * maintenance tick's anti-entropy digest, so a member could sit in front of a
 * silently stale playlist for seconds at a time. The first retry is immediate
 * and the backoff widens quickly, so a genuinely unreachable controller is not
 * hammered.
 */
internal object BoardCellGapRepairPolicy {
    /** How often the repair loop looks; well under one maintenance tick. */
    const val TICK_MS = 250L
    const val MAX_BACKOFF_MS = 2_000L

    fun nextDelayMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (TICK_MS shl (attempt - 1).coerceAtMost(8))
            .coerceAtMost(MAX_BACKOFF_MS)

    /**
     * Only a member missing canonical state repairs, and never the controller:
     * it *is* the canonical state, so asking itself would strand it for ever.
     */
    fun needsRepair(snapshot: BoardCellSnapshot?, localNodeId: String): Boolean =
        snapshot != null &&
            snapshot.availability == BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT &&
            snapshot.controllerId != localNodeId && localNodeId in snapshot.members
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
