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

/** Why the canonical current climb is not on the wall. */
@Serializable
enum class BoardPlaylistProjectionPendingReason { BOARD_WRITE_FAILED, CLIMB_UNAVAILABLE }

/**
 * The playlist is started, but its current climb is not on the board.
 *
 * Deliberately carries no promise that anybody is fetching the climb: this
 * build has no peer climb transfer, so the honest UI wording is "not
 * available"/"send pending" plus a retry every playlist member may press.
 */
@Serializable
data class BoardPlaylistPendingProjection(
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
    /** The index the queue already points at while this rest runs. */
    val nextIndex: Int,
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

@Serializable
enum class BoardPlaylistProposalDecision { REPLACE, APPEND, REJECT }

/**
 * Somebody asked to start a playlist while one was already running.
 *
 * Lives in the canonical playlist state so a pending question survives
 * reconnect, process restart and technical controller handover, and so the
 * bounded "at most one open request" rule is decided by canonical state
 * rather than by whichever device happened to see the request first.
 *
 * [expiresAtEpochMs] makes the promised 30 s a property of the request rather
 * than of whichever device is currently serializing it. A controller-local
 * timer restarted the full window on every handover and every process
 * restart, so the open state survived but the deadline it promised did not; a
 * canonical instant means a new controller inherits the original deadline and
 * refuses a request that has already run out.
 */
@Serializable
data class BoardPlaylistProposal(
    val requestId: String,
    val requesterId: String,
    val sessionId: Int,
    val items: List<Pair<String, Int>> = emptyList(),
    val restAfterSeconds: List<Int> = emptyList(),
    /** UTC epoch milliseconds after which this request counts as declined. */
    val expiresAtEpochMs: Long = 0,
    /**
     * UTC epoch milliseconds at which the controller accepted this request.
     *
     * Paired with [expiresAtEpochMs] so every replica can check that the
     * window really is the promised 30 s. Bounding only the far end left a
     * controller free to stamp a request that stayed open for years.
     */
    val requestedAtEpochMs: Long = 0,
) {
    fun hasExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs
}

/**
 * The one joinable playlist of a physical BoardCell.
 *
 * A non-null [hostId] *is* the existence of a joinable playlist. Local-only
 * playlists never reach this type at all — they stay in the host's own
 * [com.cruxcoach.android.data.SessionQueueManager] and may run alongside a
 * joinable one.
 *
 * [members] is the playlist membership, which is a different set from the
 * BoardCell membership: being in the mesh only makes the playlist visible.
 * The list is kept in join order, so index 0 is the longest-active member and
 * therefore the deterministic successor when a host is lost unexpectedly.
 */
@Serializable
data class BoardPlaylistState(
    val sessionId: Int? = null,
    val currentIndex: Int = -1,
    val items: List<Pair<String, Int>> = emptyList(),
    /** Planned rest after each entry, index-parallel to [items]. */
    val restAfterSeconds: List<Int> = emptyList(),
    /** Playlist host; null means no joinable playlist exists. */
    val hostId: String? = null,
    /** Playlist members in join order; [hostId] is always one of them. */
    val members: List<String> = emptyList(),
    val activeRest: BoardPlaylistRest? = null,
    val pendingProjection: BoardPlaylistPendingProjection? = null,
    val proposal: BoardPlaylistProposal? = null,
) {
    val isJoinable: Boolean get() = hostId != null
    fun restAt(index: Int): Int = restAfterSeconds.getOrElse(index) { 0 }
    fun currentItem(): Pair<String, Int>? = items.getOrNull(currentIndex)

    /** True while nothing beyond the pre-joinable fields is in use. */
    internal val usesLegacyShapeOnly: Boolean
        get() = restAfterSeconds.isEmpty() && hostId == null && members.isEmpty() &&
            activeRest == null && pendingProjection == null && proposal == null
}

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

    /**
     * Every legacy schema stays acceptable only while the fields it could not
     * cover are still at their defaults. A durable V5 snapshot written before
     * joinable playlists existed therefore keeps validating and is not
     * silently reinterpreted, while any snapshot that actually carries
     * playlist host/membership/rest state must hash under V6.
     */
    fun hasValidHash(): Boolean = stateHash == BoardCellHash.compute(copy(stateHash = "")) ||
        (playlist.usesLegacyShapeOnly &&
            stateHash == BoardCellHash.computeLegacyV5(copy(stateHash = ""))) ||
        (playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
            stateHash == BoardCellHash.computeLegacyV4(copy(stateHash = ""))) ||
        (playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
            lastControllerRecovery == null &&
            stateHash == BoardCellHash.computeLegacyV3(copy(stateHash = ""))) ||
        (playlist.usesLegacyShapeOnly && membershipRevision == 0L &&
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
    fun compute(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v6", true, true, true, true)
    fun computeLegacyV5(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v5", true, true, true, false)
    fun computeLegacyV4(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v4", true, true, false, false)
    fun computeLegacyV3(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v3", true, false, false, false)
    fun computeLegacyV2(snapshot: BoardCellSnapshot): String =
        compute(snapshot, "board-cell-v2", false, false, false, false)

    private fun compute(snapshot: BoardCellSnapshot, schema: String, includePlaylistRevision: Boolean,
        includeControllerRecovery: Boolean, includeMembershipRevision: Boolean,
        includeJoinablePlaylist: Boolean): String {
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
            if (includeJoinablePlaylist) {
                val playlist = snapshot.playlist
                playlist.restAfterSeconds.forEach { append("qr:").append(it).append('\n') }
                append("ph:${playlist.hostId ?: "-"}\n")
                playlist.members.forEach { append("pm:").append(it).append('\n') }
                playlist.activeRest?.let {
                    append("pa:${it.totalSeconds}|${it.generation}|${it.nextIndex}")
                    append("|${it.startedAtEpochMs}|${it.endsAtEpochMs}\n")
                } ?: append("pa:-\n")
                playlist.pendingProjection?.let {
                    append("pp:${it.climbUuid}|${it.angle}|${it.reason.name}\n")
                } ?: append("pp:-\n")
                playlist.proposal?.let { proposal ->
                    append("pq:${proposal.requestId}|${proposal.requesterId}|${proposal.sessionId}")
                    append("|${proposal.requestedAtEpochMs}|${proposal.expiresAtEpochMs}\n")
                    proposal.items.forEach { append("pqi:${it.first}|${it.second}\n") }
                    proposal.restAfterSeconds.forEach { append("pqr:").append(it).append('\n') }
                } ?: append("pq:-\n")
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
            ) &&
            localNodeId in it.members && it.controllerId != localNodeId &&
            it.handover?.phase !in setOf(
                HandoverPhase.PREPARED,
                HandoverPhase.SOURCE_RELEASED,
                HandoverPhase.TARGET_READY,
                HandoverPhase.COMMITTED,
            )
    }
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
