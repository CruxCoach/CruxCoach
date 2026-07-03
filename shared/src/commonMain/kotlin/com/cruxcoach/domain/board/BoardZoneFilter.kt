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
     *
     * Hot path: this runs over every frames string of the catalogue, so the
     * delta format ("p{id}r{role}…") is scanned manually with an early exit
     * on the first out-of-zone hold instead of going through the allocating
     * regex parser. Kilter climbConcat frames ("h…") fall back to the parser
     * — they never occur in bulk catalogue rows.
     */
    fun climbInZone(
        frames: String,
        xyByPlacement: Map<Int, Pair<Long, Long>>,
        zone: BoardZone
    ): Boolean {
        if (frames.isBlank()) return false
        if (BoardClimbParser.isClimbConcat(frames)) {
            val holds = BoardClimbParser.parseFrames(frames)
            if (holds.isEmpty()) return false
            return holds.all { hold ->
                val xy = xyByPlacement[hold.placementId] ?: return false
                zone.contains(xy.first, xy.second)
            }
        }
        var i = 0
        var sawHold = false
        val n = frames.length
        while (i < n) {
            if (frames[i] != 'p') { i++; continue }
            var j = i + 1
            var id = 0
            var digits = false
            while (j < n && frames[j] in '0'..'9') {
                id = id * 10 + (frames[j] - '0')
                j++
                digits = true
            }
            if (digits && j < n && frames[j] == 'r') {
                sawHold = true
                val xy = xyByPlacement[id] ?: return false
                if (!zone.contains(xy.first, xy.second)) return false
                i = j + 1
            } else {
                // Not a hold entry — resume at j (which may itself be a 'p').
                i = maxOf(j, i + 1)
            }
        }
        return sawHold
    }
}
