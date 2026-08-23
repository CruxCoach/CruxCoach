package com.cruxcoach.android.boardcell

import kotlinx.serialization.Serializable

/**
 * The additive, versioned shape a shared Quantum rack would take.
 *
 * Read the boundary before the code. Today this type is **not** on the
 * BoardCell wire and is not part of any canonical state hash. The wire carries
 * one [BoardProjection] — climb, angle, disconnect semantics — and a layer
 * needs a route id, a slot identity, a colour and its own readback on top of
 * that. Squeezing those through the existing projection would make the mesh
 * report "accepted" for a write that never named a lane, and the rack would
 * then show a layer as confirmed on a controller that had never heard of it.
 * That is the one failure this whole area is built to prevent.
 *
 * So the shape is designed, serialisable, mixed-version safe and tested here,
 * and the transport is a separate, reviewable step. What that step needs is
 * listed in `docs/architecture/QUANTUM_PLAYLIST_LAYERS.md`: a schema version
 * bump with an unchanged legacy hash branch, a mixed-client rollout window,
 * deterministic merge, and a controller that refuses lane commands it cannot
 * physically perform.
 *
 * Everything here defaults to empty. A replica that has never seen a lane
 * produces exactly the bytes it produced before this type existed, which is
 * what makes adding it later a compatible change rather than a fork.
 */
@Serializable
data class BoardQuantumRackState(
    /**
     * The shape these bytes were written in.
     *
     * Present from the first version so a future reader never has to guess
     * whether an absent field means "old writer" or "not set". A reader that
     * meets a higher version keeps the fields it understands and refuses to
     * *act* on the rest rather than discarding the record.
     */
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Which neutral lane each occurrence prefers. Absent = no preference. */
    val assignments: List<BoardQuantumLaneAssignment> = emptyList(),
    /** Temporary lane reservations. Absent = single-user, no coordination. */
    val claims: List<BoardQuantumLaneClaim> = emptyList(),
) {
    /**
     * Nothing a pre-rack replica could not express.
     *
     * The legacy-hash branch a future wire version would need: while this is
     * true, the state is byte-identical to one written before lanes existed,
     * so an older client's hash still verifies and no unhashed field can be
     * altered behind it.
     */
    val usesPreRackShapeOnly: Boolean
        get() = assignments.isEmpty() && claims.isEmpty()

    fun laneFor(entryId: String): Int? =
        assignments.firstOrNull { it.entryId == entryId }?.lane

    fun entryInLane(lane: Int): String? =
        assignments.firstOrNull { it.lane == lane }?.entryId

    fun claimFor(lane: Int): BoardQuantumLaneClaim? = claims.firstOrNull { it.lane == lane }

    companion object {
        const val SCHEMA_VERSION = 1
        val EMPTY = BoardQuantumRackState()
    }
}

/**
 * One occurrence's preferred lane.
 *
 * Addressed by [entryId] rather than climb uuid, because a 4x4 is the same
 * climb four times and "the second Zombie Hands goes in lane 3" has to mean
 * the second one. [revision] makes a repeated assignment idempotent and a late
 * duplicate harmless: the same revision is the same intention, and a lower one
 * is a message that arrived out of order.
 */
@Serializable
data class BoardQuantumLaneAssignment(
    val entryId: String,
    val lane: Int,
    val revision: Long = 0,
    /** Who last set it, for a deterministic tie-break rather than a coin toss. */
    val setBy: String = "",
)

/**
 * A lease on a neutral lane.
 *
 * A claim is control of a *slot*, for a while. It is not ownership of a climb,
 * it does not say who may climb it, and it never survives its expiry. Soft by
 * default: meeting somebody else's claim asks for confirmation instead of
 * overwriting it, because at a real wall the usual reason two people want lane
 * 2 is that one of them has finished and not said so.
 *
 * The rule that carries the most weight is a negative one. Expiry, release,
 * a participant disappearing, a playlist entry being removed and the whole
 * group dissolving all do the same thing to the wall: nothing. Light stays
 * until a person removes or replaces it.
 */
@Serializable
data class BoardQuantumLaneClaim(
    val lane: Int,
    /**
     * The human participant, not the controller identity.
     *
     * Kept apart deliberately: the Quantum user UUID is an installation-derived
     * technical address for a controller slot, and binding it to a person would
     * leak a stable identifier onto a wall that anybody can listen to.
     */
    val holderId: String,
    val revision: Long = 0,
    val expiresAtEpochMs: Long = 0,
    /** The window this lease was granted for; renewals reuse it. */
    val leaseMs: Long = DEFAULT_LEASE_MS,
) {
    fun hasExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    fun heldBy(participantId: String, nowEpochMs: Long): Boolean =
        holderId == participantId && !hasExpired(nowEpochMs)

    fun heldByOther(participantId: String, nowEpochMs: Long): Boolean =
        holderId != participantId && !hasExpired(nowEpochMs)

    companion object {
        /** Long enough to climb something, short enough to forgive a walk-off. */
        const val DEFAULT_LEASE_MS = 10 * 60 * 1000L
    }
}

/** What asking for a lane produced. */
sealed interface BoardQuantumClaimOutcome {
    /** The lane was free, or already this participant's; state carries it now. */
    data class Granted(val state: BoardQuantumRackState) : BoardQuantumClaimOutcome

    /**
     * Somebody else holds it and their lease is still running.
     *
     * Deliberately not an error and deliberately not an overwrite: the caller
     * shows who has it and offers a takeover, which is a separate, explicit
     * action. Nothing physical changes either way.
     */
    data class RequiresTakeover(val existing: BoardQuantumLaneClaim) : BoardQuantumClaimOutcome
}

/**
 * The pure rules over a rack record.
 *
 * Every function returns a new state and touches nothing physical. That is
 * the whole point of keeping them here: a lane record and a lit diode are
 * different things, and the only code allowed to confuse them is code that can
 * actually talk to a controller — which this cannot.
 */
object BoardQuantumRackPolicy {

    /** Assign, replace or confirm one occurrence's lane. Idempotent. */
    fun assign(
        state: BoardQuantumRackState,
        entryId: String,
        lane: Int,
        revision: Long,
        setBy: String = "",
    ): BoardQuantumRackState {
        val existing = state.assignments.firstOrNull { it.entryId == entryId }
        // A message from behind the current revision is a duplicate that took
        // the long way round, not a new intention.
        if (existing != null && revision < existing.revision) return state
        if (existing != null && existing.lane == lane && revision == existing.revision) return state
        val assignment = BoardQuantumLaneAssignment(entryId, lane, revision, setBy)
        return state.copy(
            // One lane holds one occurrence: assigning lane 2 to another entry
            // displaces the record, never the light.
            assignments = state.assignments
                .filterNot { it.entryId == entryId || it.lane == lane } + assignment,
        )
    }

    /** Drop one occurrence's preference. The wall is not consulted. */
    fun release(state: BoardQuantumRackState, entryId: String): BoardQuantumRackState =
        if (state.assignments.none { it.entryId == entryId }) state
        else state.copy(assignments = state.assignments.filterNot { it.entryId == entryId })

    /**
     * Keep only the occurrences the playlist still has.
     *
     * Removing, reordering or clearing entries changes a list. A lane record
     * whose occurrence is gone stops describing anything, so it is dropped —
     * and the layer it used to describe stays exactly as lit as it was, now
     * with nothing on the list pointing at it. That state has a name in the UI
     * ("no longer on the list") and a single remedy: somebody replaces or
     * removes it on purpose.
     */
    fun retainEntries(
        state: BoardQuantumRackState,
        entryIds: Set<String>,
    ): BoardQuantumRackState {
        val kept = state.assignments.filter { it.entryId in entryIds }
        return if (kept.size == state.assignments.size) state
        else state.copy(assignments = kept)
    }

    /** Lanes whose recorded occurrence has left the list. */
    fun orphanedLanes(state: BoardQuantumRackState, entryIds: Set<String>): List<Int> =
        state.assignments.filterNot { it.entryId in entryIds }.map { it.lane }.sorted()

    fun claim(
        state: BoardQuantumRackState,
        lane: Int,
        participantId: String,
        nowEpochMs: Long,
        leaseMs: Long = BoardQuantumLaneClaim.DEFAULT_LEASE_MS,
    ): BoardQuantumClaimOutcome {
        val existing = state.claimFor(lane)
        if (existing != null && existing.heldByOther(participantId, nowEpochMs)) {
            return BoardQuantumClaimOutcome.RequiresTakeover(existing)
        }
        return BoardQuantumClaimOutcome.Granted(
            grant(state, lane, participantId, nowEpochMs, leaseMs, existing),
        )
    }

    /** The confirmed second half of [BoardQuantumClaimOutcome.RequiresTakeover]. */
    fun takeover(
        state: BoardQuantumRackState,
        lane: Int,
        participantId: String,
        nowEpochMs: Long,
        leaseMs: Long = BoardQuantumLaneClaim.DEFAULT_LEASE_MS,
    ): BoardQuantumRackState =
        grant(state, lane, participantId, nowEpochMs, leaseMs, state.claimFor(lane))

    private fun grant(
        state: BoardQuantumRackState,
        lane: Int,
        participantId: String,
        nowEpochMs: Long,
        leaseMs: Long,
        existing: BoardQuantumLaneClaim?,
    ): BoardQuantumRackState {
        val claim = BoardQuantumLaneClaim(
            lane = lane,
            holderId = participantId,
            // Monotonic per lane, so a renewal that overtakes its predecessor
            // still lands as the newer of the two.
            revision = (existing?.revision ?: 0) + 1,
            expiresAtEpochMs = nowEpochMs + leaseMs,
            leaseMs = leaseMs,
        )
        return state.copy(claims = state.claims.filterNot { it.lane == lane } + claim)
    }

    /** Give a lane back on leaving. Only the holder may. */
    fun releaseClaim(
        state: BoardQuantumRackState,
        lane: Int,
        participantId: String,
    ): BoardQuantumRackState {
        val existing = state.claimFor(lane) ?: return state
        if (existing.holderId != participantId) return state
        return state.copy(claims = state.claims.filterNot { it.lane == lane })
    }

    /**
     * Retire leases nobody renewed.
     *
     * This is the function it is worth being explicit about: it removes
     * *records* and returns a state. It does not, and must never, produce a
     * command. A lease that ran out while its holder was making tea leaves
     * their climb on the wall.
     */
    fun pruneExpiredClaims(
        state: BoardQuantumRackState,
        nowEpochMs: Long,
    ): BoardQuantumRackState {
        val live = state.claims.filterNot { it.hasExpired(nowEpochMs) }
        return if (live.size == state.claims.size) state else state.copy(claims = live)
    }

    /**
     * Two replicas' records, resolved the same way on both.
     *
     * Per lane and per occurrence rather than per document: the two devices
     * were editing different lanes far more often than the same one, and a
     * whole-document last-writer-wins would throw away one of them for no
     * reason. Where they *do* collide, the higher revision wins and the
     * participant id breaks an exact tie, so both sides compute one answer
     * without a round trip.
     */
    fun merge(
        local: BoardQuantumRackState,
        remote: BoardQuantumRackState,
    ): BoardQuantumRackState {
        val assignments = (local.assignments + remote.assignments)
            .groupBy { it.entryId }
            .values
            .map { candidates ->
                candidates.maxWith(
                    compareBy<BoardQuantumLaneAssignment> { it.revision }.thenBy { it.setBy },
                )
            }
            // One lane, one occurrence — after the per-entry resolution two
            // entries can still name the same lane; the newer intention keeps
            // it and the older loses its preference rather than the list
            // growing a second record for one slot.
            .groupBy { it.lane }
            .values
            .map { candidates ->
                candidates.maxWith(
                    compareBy<BoardQuantumLaneAssignment> { it.revision }.thenBy { it.entryId },
                )
            }
            .sortedBy { it.lane }
        val claims = (local.claims + remote.claims)
            .groupBy { it.lane }
            .values
            .map { candidates ->
                candidates.maxWith(
                    compareBy<BoardQuantumLaneClaim> { it.revision }.thenBy { it.holderId },
                )
            }
            .sortedBy { it.lane }
        return BoardQuantumRackState(
            schemaVersion = maxOf(local.schemaVersion, remote.schemaVersion),
            assignments = assignments,
            claims = claims,
        )
    }
}
