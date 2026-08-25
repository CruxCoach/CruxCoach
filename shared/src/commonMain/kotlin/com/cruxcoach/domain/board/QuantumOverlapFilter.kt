package com.cruxcoach.domain.board

/** Quantum-only catalogue filter for climbs that fit beside the live wall. */
enum class QuantumOverlapFilter {
    OFF,
    NONE,
    AT_MOST_ONE;

    val active: Boolean get() = this != OFF

    val maxOverlap: Int
        get() = when (this) {
            OFF -> Int.MAX_VALUE
            NONE -> 0
            AT_MOST_ONE -> 1
        }

    companion object {
        fun fromWire(value: String?): QuantumOverlapFilter =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/** Fast placement-id index of the controller-confirmed Quantum layers. */
class QuantumOverlapIndex(
    val litPlacements: Set<Int>,
    val complete: Boolean = true,
) {
    fun overlapCount(candidate: Set<Int>): Int = candidate.count(litPlacements::contains)

    fun matches(candidate: Set<Int>, filter: QuantumOverlapFilter): Boolean =
        !filter.active || overlapCount(candidate) <= filter.maxOverlap

    val inert: Boolean get() = litPlacements.isEmpty()
}
