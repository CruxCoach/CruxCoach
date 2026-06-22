package com.cruxcoach.android.ui.board

import kotlin.math.abs

/**
 * Derives the left-right mirror map for a board layout: placementId -> the
 * placementId of its reflected partner across the board's vertical centre
 * (axis at centerX2 / 2). Pure + unit-testable; the detail-screen mirror
 * toggle applies the map to the climb's holds.
 *
 * Two regimes, because Aurora boards do NOT ship per-hole mirror data for
 * Kilter (the boardlib `mirrored_hole_id` is all-zero) so the mirror is
 * derived geometrically:
 *
 *  - **Row-symmetric sets** (every hand/start/finish set, and every Aurora
 *    board's holds): the partner sits at the SAME row (y) and reflected x.
 *    This is the exact, unambiguous case and is what the old code did.
 *
 *  - **Staggered sets** (Kilter's foot/kickboard lattice, set 20): the holds
 *    are mirror-symmetric as a SET but not row by row — reflecting x lands one
 *    row above/below, so there is no same-row partner. Take the nearest row;
 *    on a tie (a hole exactly above AND below the reflected point) pick
 *    antisymmetrically — the hold left of the axis reaches UP, the hold right
 *    reaches DOWN — so the pairing is consistent under reflection and rarely
 *    collides. Without this fallback the foot kept its original (un-mirrored)
 *    position, producing the half-mirrored-climb bug.
 *
 * On-axis holds (x == its own reflection) mirror to themselves and are simply
 * omitted from the map, so the renderer leaves them in place.
 *
 * NOTE: Kilter's foot lattice is not perfectly mirror-symmetric, so a mirrored
 * foot can shift by half a row and, for ~1% of climbs that use two vertically-
 * adjacent feet in one column, two feet can map to one position. That is an
 * inherent property of the staggered wall, not a derivation error — far less
 * jarring than a foot on the wrong side.
 */
object MirrorMapDeriver {

    data class Hold(val placementId: Int, val x: Int, val y: Int, val setId: Int)

    fun derive(holds: List<Hold>, centerX2: Int): Map<Int, Int> {
        if (holds.isEmpty()) return emptyMap()
        // (setId, x) -> the holes in that column, for partner lookup.
        val byColumn = HashMap<Pair<Int, Int>, MutableList<Hold>>()
        for (h in holds) byColumn.getOrPut(h.setId to h.x) { ArrayList() }.add(h)

        val result = HashMap<Int, Int>()
        for (h in holds) {
            val mirrorX = centerX2 - h.x
            // On-axis: mirrors to itself -> leave in place (no entry).
            if (mirrorX == h.x) continue
            val column = byColumn[h.setId to mirrorX] ?: continue

            // Exact same-row partner — the clean, unambiguous case.
            val sameRow = column.firstOrNull { it.y == h.y && it.placementId != h.placementId }
            val partner = sameRow ?: nearestRowPartner(h, mirrorX, column)
            if (partner != null) result[h.placementId] = partner.placementId
        }
        return result
    }

    /** Nearest-row partner in the reflected column for a staggered set, with the
     *  antisymmetric tie-break (left-of-axis reaches up, right reaches down). */
    private fun nearestRowPartner(h: Hold, mirrorX: Int, column: List<Hold>): Hold? {
        val preferHigher = h.x < mirrorX
        var best: Hold? = null
        var bestDy = Int.MAX_VALUE
        for (c in column) {
            if (c.placementId == h.placementId) continue
            val dy = abs(c.y - h.y)
            when {
                dy < bestDy -> { bestDy = dy; best = c }
                dy == bestDy && best != null -> {
                    if (preferHigher && c.y > best!!.y) best = c
                    else if (!preferHigher && c.y < best!!.y) best = c
                }
            }
        }
        return best
    }
}
