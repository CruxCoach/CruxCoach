package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.QuantumLaneBadge
import com.cruxcoach.domain.board.QuantumLaneBadgePolicy
import com.cruxcoach.domain.board.QuantumLaneCompatibilityPolicy
import com.cruxcoach.domain.board.QuantumLaneEligibility
import com.cruxcoach.domain.board.QuantumLaneFence
import com.cruxcoach.domain.board.QuantumLaneOccupancy
import com.cruxcoach.domain.board.QuantumLaneSource
import com.cruxcoach.domain.board.QuantumRackCompatibility

/**
 * One lane of the rack, as the shared list draws it.
 *
 * [entryId] is the occurrence the lane was written for, when the list still
 * has it. When the list does not — somebody removed the entry, or the list was
 * cleared — the layer stays exactly as lit as it was and the card says so
 * through [onList]. Removing a row from a backlog is not a command to a
 * controller, and quietly turning a lane off because a list changed would be
 * the single worst thing this feature could do.
 */
data class BoardPlaylistLaneCard(
    val lane: Int,
    val climbName: String?,
    val color: Int?,
    val source: QuantumLaneSource,
    val entryId: String?,
    /** False when this lane's occurrence is no longer in the list. */
    val onList: Boolean,
    /** False when this device cannot say which holds the lane lights. */
    val holdsKnown: Boolean,
)

/** Why this device may not commit a lane, when it may not. */
enum class BoardPlaylistLaneBlock {
    /** No direct link to the controller from here. */
    NOT_CONNECTED,

    /**
     * This device is a member, not the controller.
     *
     * The mesh carries one canonical projection — climb, angle, disconnect
     * semantics — and a layer needs a route id, a slot identity and a colour
     * on top of that. A lane command sent through it would come back
     * "accepted" having named no lane at all. So members plan and read; the
     * device holding the board writes.
     */
    MESH_CANNOT_CARRY_LAYERS,
}

/**
 * Everything the list knows about the rack.
 *
 * [available] is the capability gate and nothing else: it is false on every
 * board that shows one climb at a time, and while it is false no part of this
 * file has any effect on the list.
 */
data class BoardPlaylistLaneState(
    val available: Boolean = false,
    val maxLanes: Int = 0,
    val lanes: List<BoardPlaylistLaneCard> = emptyList(),
    /** Controller players belonging to somebody else. Capacity, not lanes. */
    val externalLayers: Int = 0,
    /** Layers on the wall whose holds could not be resolved locally. */
    val unknownLayers: Int = 0,
    /** Lit lanes whose occurrence has left the list. Never auto-removed. */
    val orphanedLanes: List<Int> = emptyList(),
    val commitAllowed: Boolean = false,
    val blocked: BoardPlaylistLaneBlock? = null,
) {
    val occupiedCount: Int get() = lanes.count { it.source == QuantumLaneSource.CONFIRMED } + externalLayers
}

/** A row's lane facts, derived on every render and stored nowhere. */
data class BoardPlaylistRowLanes(
    val badges: List<QuantumLaneBadge> = emptyList(),
    /** The lane this occurrence is meant for, when somebody has said. */
    val assignedLane: Int? = null,
    /** What the app would pick if asked. Null when nothing is eligible. */
    val suggestedLane: Int? = null,
    val eligibleLanes: List<Int> = emptyList(),
    /** True while a layer on the wall cannot be resolved to holds. */
    val unknown: Boolean = false,
    /** The row is showing on the wall in this lane right now. */
    val onBoardLane: Int? = null,
) {
    val hasEligibleLane: Boolean get() = eligibleLanes.isNotEmpty()
}

/**
 * The list's half of the lane model.
 *
 * Pure on purpose. Everything here is a function of a rack snapshot and a
 * backlog snapshot, so it is re-derived on every render and never stored: a
 * lane is eligible because of what is on the wall *now*, and somebody else at
 * the same wall changes that without asking. A cached "lane 3 is fine for this
 * entry" is a send that has been impossible for ten minutes.
 */
object BoardPlaylistLanePolicy {

    /**
     * @param placementsFor the holds an occurrence would light, or null when
     *   this device does not have the climb. Null propagates as unknown; it is
     *   never treated as "lights nothing".
     */
    fun rowLanes(
        rack: List<QuantumLaneOccupancy>,
        maxLanes: Int,
        entryId: String,
        placements: Set<Int>?,
        assignedLane: Int?,
        palette: List<Int>,
    ): BoardPlaylistRowLanes {
        val onBoardLane = rack.firstOrNull {
            !it.foreignSlot && it.entryId == entryId && it.source == QuantumLaneSource.CONFIRMED
        }?.lane
        if (placements == null) {
            // The climb itself is missing locally. Nothing can be claimed about
            // it, including that it is safe, so every lane reads unknown.
            return BoardPlaylistRowLanes(
                badges = (0 until maxLanes).map {
                    QuantumLaneBadge(it, com.cruxcoach.domain.board.QuantumLaneBadgeKind.UNKNOWN)
                },
                assignedLane = assignedLane,
                unknown = true,
                onBoardLane = onBoardLane,
            )
        }
        val compatibility = QuantumLaneCompatibilityPolicy.evaluate(
            candidate = placements,
            rack = rack,
            fence = QuantumLaneFence(maxLanes = maxLanes, palette = palette),
        )
        return BoardPlaylistRowLanes(
            badges = QuantumLaneBadgePolicy.badges(compatibility, rack, entryId),
            assignedLane = assignedLane,
            suggestedLane = suggested(compatibility, rack, assignedLane, onBoardLane),
            eligibleLanes = compatibility.eligibleLanes,
            unknown = compatibility.anyUnknown,
            onBoardLane = onBoardLane,
        )
    }

    /**
     * Which lane to offer for this occurrence.
     *
     * An explicit assignment is an answer, not a suggestion, so it wins as
     * long as it is still eligible — and when it is not, the row surfaces the
     * conflict rather than silently moving to another lane. A quiet
     * reassignment is how somebody ends up lighting lane 4 while looking at
     * lane 2. Re-lighting the lane this occurrence is already in comes next,
     * because a resend belongs where it was.
     */
    private fun suggested(
        compatibility: QuantumRackCompatibility,
        rack: List<QuantumLaneOccupancy>,
        assignedLane: Int?,
        onBoardLane: Int?,
    ): Int? {
        assignedLane?.let { lane ->
            return lane.takeIf { compatibility.forLane(it)?.sendable == true }
        }
        onBoardLane?.let { lane ->
            if (compatibility.forLane(lane)?.sendable == true) return lane
        }
        return compatibility.suggestedLane(rack)
    }

    /**
     * Whether an assignment can still be honoured, and if not, why.
     *
     * Returned rather than acted on: an invalid preference is surfaced to the
     * person who made it. The rack changed underneath them and they get to
     * decide, because the alternative — moving their climb to whichever lane
     * happens to be free — is a change nobody asked for to a wall everybody
     * can see.
     */
    fun assignmentConflict(
        rack: List<QuantumLaneOccupancy>,
        maxLanes: Int,
        placements: Set<Int>?,
        assignedLane: Int?,
        palette: List<Int>,
    ): QuantumLaneEligibility? {
        val lane = assignedLane ?: return null
        if (placements == null) return QuantumLaneEligibility.UNKNOWN_LAYER
        val target = QuantumLaneCompatibilityPolicy.evaluate(
            candidate = placements,
            rack = rack,
            fence = QuantumLaneFence(maxLanes = maxLanes, palette = palette),
        ).forLane(lane) ?: return null
        return target.eligibility.takeIf { it != QuantumLaneEligibility.ELIGIBLE }
    }

    /** The rack, as cards, with each lane's occurrence checked against the list. */
    fun laneCards(
        rack: List<QuantumLaneOccupancy>,
        maxLanes: Int,
        liveEntryIds: Set<String>,
        nameForLane: (Int) -> String?,
    ): List<BoardPlaylistLaneCard> = (0 until maxLanes).map { lane ->
        val occupancy = rack.firstOrNull { it.lane == lane }
        BoardPlaylistLaneCard(
            lane = lane,
            climbName = nameForLane(lane),
            color = occupancy?.color,
            source = occupancy?.source ?: QuantumLaneSource.FREE,
            entryId = occupancy?.entryId,
            // A free lane is not "off the list"; only a lit one can be.
            onList = occupancy?.entryId?.let { it in liveEntryIds }
                ?: (occupancy?.occupied != true),
            holdsKnown = occupancy?.unknownHolds != true,
        )
    }
}

/** Why a lane commit did not happen, in the terms the user can act on. */
sealed interface BoardPlaylistLaneFeedback {
    /** This device may not write layers at all. */
    data class Blocked(val reason: BoardPlaylistLaneBlock) : BoardPlaylistLaneFeedback

    /**
     * Nothing on the rack can take this climb right now.
     *
     * The occurrence stays in the backlog, unassigned. It is not rejected, not
     * duplicated and not moved: the wall will change, and then it will fit.
     */
    data class NoEligibleLane(val entryId: String) : BoardPlaylistLaneFeedback

    /** A named lane refused, and this is which rule said so. */
    data class LaneRefused(
        val lane: Int,
        val reason: QuantumLaneEligibility,
    ) : BoardPlaylistLaneFeedback
}
