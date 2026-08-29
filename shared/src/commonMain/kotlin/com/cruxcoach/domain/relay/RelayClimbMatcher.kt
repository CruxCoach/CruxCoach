package com.cruxcoach.domain.relay

import com.cruxcoach.domain.board.BoardClimbParser

/**
 * The arithmetic behind identifying a relayed climb: how long the stored frame
 * string of a given hold set must be, and whether a candidate really carries
 * exactly those holds.
 *
 * Kept apart from the Android-side lookup because this is where an off-by-one
 * silently turns every lookup into a miss — the query would simply return
 * nothing and the banner would stay blank, with no error anywhere.
 */
object RelayClimbMatcher {

    /**
     * Inclusive bounds for `length(frames)` of a climb made of [placements].
     *
     * A frame entry reads `p<placement>r<role>`, so everything except the role
     * ids is known exactly; [minRoleDigits]/[maxRoleDigits] carry the only
     * unknown (12-15 on Kilter, 1-4 on the Aurora family).
     */
    fun frameLengthRange(
        placements: Set<Int>,
        minRoleDigits: Int,
        maxRoleDigits: Int,
    ): IntRange {
        val fixed = placements.sumOf { 2 + digitCount(it) }
        return (fixed + placements.size * minRoleDigits)..(fixed + placements.size * maxRoleDigits)
    }

    /** True when [frames] consists of exactly the holds in [placements]. */
    fun holdsMatch(frames: String, placements: Set<Int>): Boolean {
        val parsed = BoardClimbParser.parseFrames(frames)
        if (parsed.size != placements.size) return false
        return parsed.mapTo(HashSet()) { it.placementId } == placements
    }

    /** `LIKE` pattern that finds [placement] as a hold in a frame string. */
    fun anchorPattern(placement: Int): String = "%p${placement}r%"

    private fun digitCount(value: Int): Int {
        var v = value
        var digits = 1
        while (v >= 10) { v /= 10; digits++ }
        return digits
    }
}
