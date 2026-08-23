package com.cruxcoach.domain.board

/**
 * Which of a layer-capable controller's neutral lanes a climb would go into.
 *
 * A lane is a slot on the wall, not a person and not a playlist. It has a
 * number, it may hold one route, and anybody at the board may put the next
 * climb into it. Nothing here knows who is climbing; that is deliberate — the
 * moment a lane belongs to somebody, a group of four has to negotiate
 * ownership before it can light a warm-up.
 *
 * Everything in this file is pure: no controller, no database, no clock. It
 * answers one question — *given what is on the wall, what would happen if this
 * climb went into that lane* — and leaves acting on the answer to the callers
 * that can actually write.
 */

/** Where a lane's current content comes from, strongest physical truth last. */
enum class QuantumLaneSource {
    /** Nothing planned and nothing on the controller. */
    FREE,

    /** Staged on this device only. No LED has changed for it. */
    PREVIEW,

    /** A write for this lane is out and has not been answered. */
    SENDING,

    /** The controller reported this route for this lane. Physical truth. */
    CONFIRMED,

    /** A controller player this installation does not own. Read-only. */
    FOREIGN,
}

/**
 * One lane of the effective rack.
 *
 * [placements] is `null` when the holds are genuinely not known — a foreign
 * layer whose route this device cannot resolve is the normal case. `null` is
 * not an empty set: an empty set claims "this lane lights nothing", which
 * would let a send look safe on evidence nobody has.
 */
data class QuantumLaneOccupancy(
    /**
     * The lane number, or a negative id for a controller player that is not
     * one of this installation's lanes.
     *
     * Foreign players are part of the rack — they light holds and they take a
     * place — but they are not somewhere this device can put a climb. Giving
     * them ids outside the lane range keeps them in every comparison and out
     * of every target list, without a second collection that could drift.
     */
    val lane: Int,
    val source: QuantumLaneSource = QuantumLaneSource.FREE,
    /** Route identity as the controller names it, when there is one. */
    val routeKey: String? = null,
    val placements: Set<Int>? = null,
    /** Protocol colour in use, when known. Two lanes may not share one. */
    val color: Int? = null,
    /** The occurrence this lane was committed for, when one is known. */
    val entryId: String? = null,
) {
    /** Whether this lane holds anything at all — planned or physical. */
    val occupied: Boolean get() = source != QuantumLaneSource.FREE

    /** Whether this lane is physically lit right now. */
    val physical: Boolean
        get() = source == QuantumLaneSource.CONFIRMED || source == QuantumLaneSource.FOREIGN

    /** Occupied, but this device cannot say which holds it lights. */
    val unknownHolds: Boolean get() = occupied && placements == null

    /** A controller player outside this installation's own lanes. */
    val foreignSlot: Boolean get() = lane < 0
}

/** Why a lane cannot take this climb, or that it can. */
enum class QuantumLaneEligibility {
    ELIGIBLE,

    /** At least one hold is already lit by another lane. One is enough. */
    HOLD_CONFLICT,

    /** Another lane's holds are unknown, so "no conflict" is unprovable. */
    UNKNOWN_LAYER,

    /** A write for this lane is already out. */
    LANE_BUSY,

    /** The controller has no free place and this lane is not a replacement. */
    NO_CAPACITY,

    /** Every remaining protocol colour is already an identity on the wall. */
    NO_COLOR,

    /** Somebody else holds this lane; taking it over is an explicit action. */
    CLAIMED,
}

/** How many unique placements a candidate shares with exactly one lane. */
data class QuantumLaneOverlap(
    val lane: Int,
    val count: Int,
    /** False when that lane's holds are unknown; [count] is then meaningless. */
    val known: Boolean,
    /** The shared placements themselves, so the UI can point at a hold. */
    val placements: Set<Int> = emptySet(),
)

/**
 * What would happen if one candidate climb went into one lane.
 *
 * The lane being written to is excluded from the comparison: replacing what is
 * in lane 2 cannot conflict with what is in lane 2, and counting it would make
 * every re-send of a climb look impossible.
 */
data class QuantumLaneTarget(
    val lane: Int,
    val eligibility: QuantumLaneEligibility,
    /** Per lane, because one hold can conflict with several at once. */
    val perLane: List<QuantumLaneOverlap>,
    /**
     * Unique placement ids shared with *any* other known lane.
     *
     * Deliberately not the sum of [perLane]: a hold that two other lanes both
     * light is one hold on the wall, and the diode rule is about holds.
     */
    val uniqueOverlapCount: Int,
    val conflictingPlacements: Set<Int> = emptySet(),
) {
    /** Own lanes in the way. Foreign players are reported separately. */
    val conflictingLanes: List<Int>
        get() = perLane.filter { it.known && it.count > 0 && it.lane >= 0 }.map { it.lane }

    /** A conflict with a controller player this device does not own. */
    val conflictsWithForeign: Boolean
        get() = perLane.any { it.known && it.count > 0 && it.lane < 0 }

    val unknownLanes: List<Int> get() = perLane.filterNot { it.known }.map { it.lane }

    /** Every remaining layer is known, so the overlap count means something. */
    val known: Boolean get() = perLane.all { it.known }

    /** Zero overlaps and nothing else in the way: this can be sent now. */
    val sendable: Boolean get() = eligibility == QuantumLaneEligibility.ELIGIBLE

    /**
     * Exactly one hold in the way.
     *
     * A planning aid and nothing more. The controller cannot give one diode
     * two colours, so this is *not* sendable — it is "one hold away", which is
     * worth showing to somebody choosing what to climb next.
     */
    val nearCompatible: Boolean
        get() = known && uniqueOverlapCount == 1
}

/** The whole rack's answer for one candidate climb. */
data class QuantumRackCompatibility(
    val targets: List<QuantumLaneTarget>,
) {
    val eligibleLanes: List<Int> get() = targets.filter { it.sendable }.map { it.lane }

    /** True while at least one remaining layer's holds are unresolvable. */
    val anyUnknown: Boolean get() = targets.any { !it.known }

    fun forLane(lane: Int): QuantumLaneTarget? = targets.firstOrNull { it.lane == lane }

    /**
     * The lane to offer, when the app may choose for somebody.
     *
     * One eligible lane is not a choice, it is the answer. Several is a
     * preference: the emptiest lane first — replacing nothing beats replacing
     * something somebody may still be climbing — then the lowest number, so
     * the suggestion is stable across redraws instead of hopping about.
     */
    fun suggestedLane(rack: List<QuantumLaneOccupancy>): Int? {
        val bySlot = rack.associateBy { it.lane }
        return eligibleLanes.minByOrNull { lane ->
            val occupancy = bySlot[lane]
            val rank = when {
                occupancy == null || !occupancy.occupied -> 0
                occupancy.source == QuantumLaneSource.PREVIEW -> 1
                else -> 2
            }
            rank * 100 + lane
        }
    }
}

/**
 * A temporary reservation of a neutral lane.
 *
 * Not ownership of a climb, and emphatically not permission to climb it. It
 * exists so that four people at one wall do not overwrite each other's layer
 * in the same second, and it is a lease so that somebody who walks away with a
 * phone in their pocket does not hold a lane for the rest of the session.
 *
 * The expiry rule that matters is the one that is *not* here: a lease running
 * out releases the reservation and nothing else. It never turns an LED off.
 * Light on a wall was put there by a person; only a person takes it away.
 */
data class QuantumLaneClaim(
    val lane: Int,
    /** Session participant, not the technical controller/user UUID. */
    val holderId: String,
    /** Bumped on every renewal so a late duplicate cannot revive a lease. */
    val revision: Long = 0,
    val expiresAtEpochMs: Long = 0,
) {
    fun hasExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    fun heldBy(participantId: String, nowEpochMs: Long): Boolean =
        holderId == participantId && !hasExpired(nowEpochMs)

    fun heldByOther(participantId: String, nowEpochMs: Long): Boolean =
        holderId != participantId && !hasExpired(nowEpochMs)
}

/** Everything the eligibility rule needs that is not the rack itself. */
data class QuantumLaneFence(
    /** Lanes the controller model has at all. */
    val maxLanes: Int = 4,
    /** Protocol colours that identify a player; two lanes may not share one. */
    val palette: List<Int> = emptyList(),
    /** The participant asking. Null in single-user mode: no claims apply. */
    val participantId: String? = null,
    val claims: List<QuantumLaneClaim> = emptyList(),
    val nowEpochMs: Long = 0,
    /** False when the rack is staged for a board other than the connected one. */
    val boardMatches: Boolean = true,
)

/**
 * The compatibility matrix, derived on every read and stored nowhere.
 *
 * Eligibility is a fact about a rack at an instant, and a rack changes when
 * somebody else at the wall presses a lamp. Persisting "lane 3 is fine for
 * this entry" is how a list ends up offering a send that has been impossible
 * for ten minutes.
 */
object QuantumLaneCompatibilityPolicy {

    /**
     * The rack a plan should be judged against.
     *
     * A planned replacement wins over the confirmed layer it replaces: two
     * previews that conflict with each other must not both look valid merely
     * because each one only checked the physical layers it is about to
     * overwrite. Everything else is physical truth.
     */
    fun effectiveRack(
        maxLanes: Int,
        confirmed: List<QuantumLaneOccupancy>,
        planned: List<QuantumLaneOccupancy> = emptyList(),
    ): List<QuantumLaneOccupancy> {
        val plannedBySlot = planned.filterNot { it.foreignSlot }.associateBy { it.lane }
        val confirmedBySlot = confirmed.filterNot { it.foreignSlot }.associateBy { it.lane }
        val own = (0 until maxLanes).map { lane ->
            plannedBySlot[lane]
                ?: confirmedBySlot[lane]
                ?: QuantumLaneOccupancy(lane = lane)
        }
        // Nobody can plan over a foreign player, so it is carried through
        // untouched. Dropping it would make a hold it is lighting look free.
        return own + confirmed.filter { it.foreignSlot }
    }

    /** Unique placements the candidate shares with everything in [rack]. */
    fun uniqueOverlapCount(candidate: Set<Int>, rack: List<QuantumLaneOccupancy>): Int =
        conflictingPlacements(candidate, rack).size

    fun conflictingPlacements(
        candidate: Set<Int>,
        rack: List<QuantumLaneOccupancy>,
    ): Set<Int> {
        val lit = HashSet<Int>()
        rack.forEach { lane -> lane.placements?.let(lit::addAll) }
        return candidate.filterTo(HashSet()) { it in lit }
    }

    /** How many controller places the rack physically occupies. */
    fun occupiedCount(rack: List<QuantumLaneOccupancy>): Int = rack.count { it.physical }

    /**
     * The candidate against every lane it could go into.
     *
     * [candidate] is the set of placement ids the climb would light. An empty
     * set is a climb with no holds this board can light, which is a different
     * problem and is left to the caller — the matrix simply reports no
     * overlaps for it.
     */
    fun evaluate(
        candidate: Set<Int>,
        rack: List<QuantumLaneOccupancy>,
        fence: QuantumLaneFence = QuantumLaneFence(),
        /** The colour this candidate would use, when the caller has picked one. */
        candidateColor: Int? = null,
    ): QuantumRackCompatibility {
        val bySlot = rack.associateBy { it.lane }
        val targets = (0 until fence.maxLanes).map { lane ->
            evaluateLane(lane, candidate, rack, bySlot, fence, candidateColor)
        }
        return QuantumRackCompatibility(targets)
    }

    private fun evaluateLane(
        lane: Int,
        candidate: Set<Int>,
        rack: List<QuantumLaneOccupancy>,
        bySlot: Map<Int, QuantumLaneOccupancy>,
        fence: QuantumLaneFence,
        candidateColor: Int?,
    ): QuantumLaneTarget {
        // The climb being replaced in this lane cannot conflict with its own
        // replacement. Everything else on the rack still can.
        val others = rack.filter { it.lane != lane && it.occupied }
        val perLane = others.map { other ->
            val shared = other.placements?.let { placements ->
                candidate.filterTo(HashSet()) { it in placements }
            }
            QuantumLaneOverlap(
                lane = other.lane,
                count = shared?.size ?: 0,
                known = other.placements != null,
                placements = shared.orEmpty(),
            )
        }
        val conflicting = conflictingPlacements(candidate, others)
        val target = bySlot[lane]
        val eligibility = eligibilityFor(
            lane = lane,
            target = target,
            others = others,
            perLane = perLane,
            uniqueOverlap = conflicting.size,
            fence = fence,
            candidateColor = candidateColor,
        )
        return QuantumLaneTarget(
            lane = lane,
            eligibility = eligibility,
            perLane = perLane,
            uniqueOverlapCount = conflicting.size,
            conflictingPlacements = conflicting,
        )
    }

    /**
     * The order the reasons are checked in is the order somebody can act on
     * them. A lane on another board is not "full"; a lane mid-send is not
     * "conflicting". Reporting the first fixable reason keeps the message
     * honest even when several are true at once.
     */
    private fun eligibilityFor(
        lane: Int,
        target: QuantumLaneOccupancy?,
        others: List<QuantumLaneOccupancy>,
        perLane: List<QuantumLaneOverlap>,
        uniqueOverlap: Int,
        fence: QuantumLaneFence,
        candidateColor: Int?,
    ): QuantumLaneEligibility {
        if (!fence.boardMatches) return QuantumLaneEligibility.NO_CAPACITY
        if (target?.source == QuantumLaneSource.SENDING) return QuantumLaneEligibility.LANE_BUSY
        fence.participantId?.let { self ->
            val claim = fence.claims.firstOrNull { it.lane == lane }
            if (claim?.heldByOther(self, fence.nowEpochMs) == true) {
                return QuantumLaneEligibility.CLAIMED
            }
        }
        // Replacing a place this installation already holds costs nothing; a
        // brand new identity needs a free place on the controller.
        val replacesPhysical = target?.physical == true
        if (!replacesPhysical && others.count { it.physical } >= fence.maxLanes) {
            return QuantumLaneEligibility.NO_CAPACITY
        }
        if (perLane.any { !it.known }) return QuantumLaneEligibility.UNKNOWN_LAYER
        if (uniqueOverlap > 0) return QuantumLaneEligibility.HOLD_CONFLICT
        if (fence.palette.isNotEmpty()) {
            val taken = others.mapNotNullTo(HashSet()) { it.color }
            val usable = candidateColor?.takeIf { it !in taken }
                ?: fence.palette.firstOrNull { it !in taken }
            if (usable == null) return QuantumLaneEligibility.NO_COLOR
        }
        return QuantumLaneEligibility.ELIGIBLE
    }
}

/**
 * How a backlog row wears its lane information.
 *
 * A list of forty climbs cannot carry four sentences each, and colour alone
 * cannot carry any of it — two of the four protocol colours are hard to tell
 * apart and one in twelve men cannot tell them apart at all. So every chip is
 * a number and a symbol, and the sentence lives in its content description.
 */
enum class QuantumLaneBadgeKind {
    /** This lane is showing this occurrence right now. Physical truth. */
    ON_BOARD,

    /** Staged for this lane locally; no LED has changed. */
    PLANNED,

    /** A write for this occurrence and lane is out. */
    SENDING,

    /** Free of conflicts; the lamp would work. */
    COMPATIBLE,

    /** Exactly one hold in the way — one to consider, not one to send. */
    NEAR,

    /** More than one hold in the way. */
    CONFLICT,

    /** A remaining layer's holds are unknown; no claim can be made. */
    UNKNOWN,

    /** Capacity, colour or a foreign claim, rather than the holds. */
    BLOCKED,
}

data class QuantumLaneBadge(
    val lane: Int,
    val kind: QuantumLaneBadgeKind,
    /** Unique overlapping holds, when that is what the badge is about. */
    val overlapCount: Int = 0,
    /** Lanes this candidate collides with, for the expanded explanation. */
    val conflictingLanes: List<Int> = emptyList(),
) {
    /** Symbol-first so the chip never depends on its colour to be read. */
    val symbol: String
        get() = when (kind) {
            QuantumLaneBadgeKind.ON_BOARD -> "●"
            QuantumLaneBadgeKind.PLANNED -> "◐"
            QuantumLaneBadgeKind.SENDING -> "…"
            QuantumLaneBadgeKind.COMPATIBLE -> "✓"
            QuantumLaneBadgeKind.NEAR -> "·$overlapCount"
            QuantumLaneBadgeKind.CONFLICT -> "·$overlapCount"
            QuantumLaneBadgeKind.UNKNOWN -> "?"
            QuantumLaneBadgeKind.BLOCKED -> "✕"
        }

    val label: String get() = "L${lane + 1} $symbol"
}

object QuantumLaneBadgePolicy {
    /**
     * One row's chips.
     *
     * The occurrence's own lane wins over its compatibility: a row that is on
     * the wall in lane 2 says so, rather than reporting that it would fit
     * there — which is true, uninteresting, and would hide the only fact on
     * the row that a person is looking for.
     */
    fun badges(
        compatibility: QuantumRackCompatibility,
        rack: List<QuantumLaneOccupancy>,
        entryId: String?,
    ): List<QuantumLaneBadge> {
        val bySlot = rack.associateBy { it.lane }
        return compatibility.targets.map { target ->
            val occupancy = bySlot[target.lane]
            val holdsThisEntry = entryId != null && occupancy?.entryId == entryId
            val kind = when {
                holdsThisEntry && occupancy?.source == QuantumLaneSource.CONFIRMED ->
                    QuantumLaneBadgeKind.ON_BOARD
                holdsThisEntry && occupancy?.source == QuantumLaneSource.SENDING ->
                    QuantumLaneBadgeKind.SENDING
                holdsThisEntry -> QuantumLaneBadgeKind.PLANNED
                target.eligibility == QuantumLaneEligibility.UNKNOWN_LAYER ->
                    QuantumLaneBadgeKind.UNKNOWN
                target.eligibility == QuantumLaneEligibility.ELIGIBLE ->
                    QuantumLaneBadgeKind.COMPATIBLE
                target.eligibility == QuantumLaneEligibility.HOLD_CONFLICT &&
                    target.uniqueOverlapCount == 1 -> QuantumLaneBadgeKind.NEAR
                target.eligibility == QuantumLaneEligibility.HOLD_CONFLICT ->
                    QuantumLaneBadgeKind.CONFLICT
                target.eligibility == QuantumLaneEligibility.LANE_BUSY ->
                    QuantumLaneBadgeKind.SENDING
                else -> QuantumLaneBadgeKind.BLOCKED
            }
            QuantumLaneBadge(
                lane = target.lane,
                kind = kind,
                overlapCount = target.uniqueOverlapCount,
                conflictingLanes = target.conflictingLanes,
            )
        }
    }
}
