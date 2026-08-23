package com.cruxcoach.android.ble

import com.cruxcoach.android.boardcell.BoardQuantumRackPolicy
import com.cruxcoach.android.boardcell.BoardQuantumRackState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which neutral lane each playlist occurrence is meant for, on this device.
 *
 * The playlist is a backlog in time: one shared, ordered list of what the
 * group intends to climb, with duplicates that mean something. The rack is
 * four things happening at once on a wall. They are not the same collection
 * and this class is the only place they meet — a small map from occurrence id
 * to lane number, and a record of which occurrence each lane was last written
 * for.
 *
 * Three properties are worth stating because they are what stops this being a
 * second copy of the truth:
 *
 *  - **It never writes.** Assigning a lane changes no LED. Only an explicit
 *    lamp does, and it goes through the ordinary occurrence-addressed
 *    transport.
 *  - **It is scoped to one board.** A lane is a diode plan for one controller
 *    and one model; carrying it to the next wall would be a plan for holds
 *    that are not there.
 *  - **It never removes light.** Dropping an occurrence from the list drops
 *    its lane *record*. The layer stays lit, now with nothing on the list
 *    pointing at it, and only a person takes it down.
 *
 * The record is deliberately the same [BoardQuantumRackState] a shared rack
 * would use. It is local today — see that type for why the transport is a
 * separate step — but the shape does not have to change to become canonical.
 */
@Singleton
class QuantumLanePlanner @Inject constructor(
    private val boardLayerManager: BoardLayerManager,
) {

    private val _state = MutableStateFlow(QuantumLanePlan())
    val state: StateFlow<QuantumLanePlan> = _state.asStateFlow()

    /**
     * Point the plan at the board the rack is staged for.
     *
     * Same board: keep everything, including across a disconnect, because the
     * controller keeps its projections and a reconnect has to be able to
     * recognise them. A different board empties the plan, for exactly the
     * reason [BoardLayerManager.bindBoard] empties the rack. A null identity
     * is a reconnect in progress, not a board change.
     */
    fun syncBoard(identity: BoardLayerBoardIdentity? = boardLayerManager.state.value.board) {
        if (identity == null) return
        val key = "${identity.physicalBoardId}|${identity.productSizeId}"
        _state.update { plan ->
            if (plan.boardKey == key) plan else QuantumLanePlan(boardKey = key)
        }
    }

    /** Forget everything: a different cell, or no cell at all. */
    fun clear() {
        _state.value = QuantumLanePlan(boardKey = _state.value.boardKey)
    }

    fun laneFor(entryId: String): Int? = _state.value.rack.laneFor(entryId)

    /**
     * Record that this occurrence is meant for this lane.
     *
     * Idempotent under a revision so a repeated tap, a replayed command or two
     * screens asking at once produce one preference rather than a fight. The
     * revision is local and monotonic; it is the same field a replicated rack
     * would order by.
     */
    fun assign(entryId: String, lane: Int) {
        _state.update { plan ->
            plan.copy(
                revision = plan.revision + 1,
                rack = BoardQuantumRackPolicy.assign(
                    plan.rack, entryId, lane, plan.revision + 1,
                ),
            )
        }
    }

    /** Drop one occurrence's preference. Nothing physical happens. */
    fun release(entryId: String) {
        _state.update { it.copy(rack = BoardQuantumRackPolicy.release(it.rack, entryId)) }
    }

    /**
     * Follow the list.
     *
     * Removals, reorders and clears all arrive here as "these are the
     * occurrences that still exist". Preferences for occurrences that have
     * gone are dropped; [QuantumLanePlan.committed] is deliberately *not*,
     * because it describes light on a wall rather than a row in a list.
     */
    fun retainEntries(entryIds: Set<String>) {
        _state.update { it.copy(rack = BoardQuantumRackPolicy.retainEntries(it.rack, entryIds)) }
    }

    /** A lane write for this occurrence has started. */
    fun noteSending(lane: Int, entryId: String?) {
        _state.update { it.copy(sendingLane = lane, sendingEntryId = entryId) }
    }

    /**
     * A lane write terminated.
     *
     * Success records which occurrence that lane is now showing — the label
     * for a physical fact, not the fact itself, which stays with the
     * controller readback in [BoardLayerManager]. Failure clears only the
     * in-flight marker: a lane that refused a write is still showing whatever
     * it showed before.
     */
    fun noteSent(lane: Int, entryId: String?, success: Boolean) {
        _state.update { plan ->
            plan.copy(
                sendingLane = null,
                sendingEntryId = null,
                committed = if (success && entryId != null) plan.committed + (lane to entryId)
                else plan.committed,
            )
        }
    }

    /** A lane is physically empty again, so its label means nothing. */
    fun noteRemoved(lane: Int) {
        _state.update { it.copy(committed = it.committed - lane) }
    }

    /** Lanes still lit for occurrences the list no longer has. */
    fun orphanedLanes(entryIds: Set<String>): List<Int> =
        _state.value.committed.filterValues { it !in entryIds }.keys.sorted()
}

/**
 * The plan, as one value.
 *
 * [committed] and [BoardQuantumRackState.assignments] answer different
 * questions and are kept apart for that reason: the first is "which occurrence
 * did lane 2 last get written for", the second is "which lane should this
 * occurrence go to next". Removing an entry from the list changes the second
 * and must not change the first.
 */
data class QuantumLanePlan(
    /** Physical board + model the plan was staged for; null before the first bind. */
    val boardKey: String? = null,
    val rack: BoardQuantumRackState = BoardQuantumRackState.EMPTY,
    /** Local monotonic revision behind the idempotent assignment rule. */
    val revision: Long = 0,
    val committed: Map<Int, String> = emptyMap(),
    /** The lane a write is currently out for, if any. */
    val sendingLane: Int? = null,
    val sendingEntryId: String? = null,
) {
    fun entryForLane(lane: Int): String? = committed[lane]
}
