package com.cruxcoach.domain.board

/**
 * Rectangular zone on the board in placement coordinate space (the same
 * hole-grid units `placements.x` / `placements.y` use). Inclusive bounds.
 */
data class BoardZone(
    val minX: Long,
    val maxX: Long,
    val minY: Long,
    val maxY: Long
) {
    fun contains(x: Long, y: Long): Boolean = x in minX..maxX && y in minY..maxY
}

/**
 * Zone-box climb filtering: restrict search results to climbs whose holds
 * all lie inside a rectangle spanned by two corner holds. Pure functions —
 * all computation in-memory from frame strings + placement coordinates.
 */
object BoardZoneFilter {

    /** Zone spanned by two corner coordinates (any diagonal order). */
    fun zoneFromCorners(ax: Long, ay: Long, bx: Long, by: Long): BoardZone = BoardZone(
        minX = minOf(ax, bx),
        maxX = maxOf(ax, bx),
        minY = minOf(ay, by),
        maxY = maxOf(ay, by)
    )

    /**
     * True when EVERY hold of the climb lies inside [zone]. A placement id
     * missing from [xyByPlacement] counts as outside — frames can reference
     * holds of sets or sizes that aren't part of the active board, and those
     * climbs by definition don't fit the drawn area.
     */
    fun climbInZone(
        frames: String,
        xyByPlacement: Map<Int, Pair<Long, Long>>,
        zone: BoardZone
    ): Boolean {
        val holds = BoardClimbParser.parseFrames(frames)
        if (holds.isEmpty()) return false
        return holds.all { hold ->
            val xy = xyByPlacement[hold.placementId] ?: return false
            zone.contains(xy.first, xy.second)
        }
    }
}
