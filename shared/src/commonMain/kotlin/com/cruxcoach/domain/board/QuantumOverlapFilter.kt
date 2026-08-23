package com.cruxcoach.domain.board

/**
 * "What can I still put on this wall?", as a browse filter.
 *
 * Distinct from the lane matrix in [QuantumLaneCompatibilityPolicy] on
 * purpose. The matrix answers a planning question — *if this occurrence went
 * into lane 3, what would happen* — and therefore judges against the
 * **effective** rack, previews included. This one answers a shopping question:
 * *what fits next to what is on the wall right now*. Its promise is about the
 * controller, so it is judged against controller-confirmed layers only. A
 * preview is somebody's idea; it has not taken a hold.
 */
enum class QuantumOverlapFilter {
    OFF,

    /** No hold in common with any confirmed layer. Directly sendable. */
    NONE,

    /**
     * At most one hold in common.
     *
     * A discovery aid, not a promise. The controller cannot give one diode two
     * colours, so a single overlap is still a refused send — it is "one hold
     * away", which is exactly the thing worth finding when the wall is busy
     * and the alternative is scrolling.
     */
    AT_MOST_ONE;

    val active: Boolean get() = this != OFF

    /** Unique overlapping holds this filter still admits. */
    val maxOverlap: Int
        get() = when (this) {
            OFF -> Int.MAX_VALUE
            NONE -> 0
            AT_MOST_ONE -> 1
        }

    /** Whether a match under this filter can be sent as-is. */
    val impliesSendable: Boolean get() = this == NONE

    companion object {
        fun fromWire(value: String?): QuantumOverlapFilter =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * The catalogue-side half of the overlap question.
 *
 * Built once per (rack, filter) and then asked per climb, because the browse
 * list asks it thousands of times. The lit set is a plain hash set of
 * placement ids: a climb is a handful of holds, so the per-climb cost is the
 * size of the climb rather than the size of the wall or the catalogue.
 */
class QuantumOverlapIndex(
    /** Placement ids the controller-confirmed layers currently light. */
    val litPlacements: Set<Int>,
    /**
     * True when at least one confirmed layer's holds could not be resolved.
     *
     * The filter still narrows on what is known, but nothing here may be
     * presented as a guarantee: an unresolvable foreign layer can be lighting
     * any hold on the board.
     */
    val complete: Boolean = true,
) {
    /** Unique placements this climb would share with the wall. */
    fun overlapCount(candidate: Set<Int>): Int = candidate.count { it in litPlacements }

    fun matches(candidate: Set<Int>, filter: QuantumOverlapFilter): Boolean =
        !filter.active || overlapCount(candidate) <= filter.maxOverlap

    /** Nothing on the wall, so every climb trivially fits: do not narrow. */
    val inert: Boolean get() = litPlacements.isEmpty()

    companion object {
        fun of(layers: List<QuantumLaneOccupancy>): QuantumOverlapIndex {
            val physical = layers.filter { it.physical }
            val lit = HashSet<Int>()
            physical.forEach { layer -> layer.placements?.let(lit::addAll) }
            return QuantumOverlapIndex(
                litPlacements = lit,
                complete = physical.none { it.placements == null },
            )
        }
    }
}
