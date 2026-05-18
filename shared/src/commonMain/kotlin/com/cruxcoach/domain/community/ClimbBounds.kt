package com.cruxcoach.domain.community

/**
 * Axis-aligned bounding box of a climb's selected holds in board
 * coordinates. Mirrors the `edge_left/right/bottom/top` columns on the
 * `climbs` table — they're populated from this box at publish time so
 * the browser's per-board-size compatibility filter works for
 * CruxCoach-authored climbs.
 *
 * Coordinate space matches `placements.x` / `placements.y` (the same
 * integer grid that `product_sizes.edge_*` defines as physical board
 * extents). A climb fits a board size iff:
 *   product_sizes.edge_left  <= climb.edge_left  AND
 *   product_sizes.edge_right >= climb.edge_right AND
 *   product_sizes.edge_bottom<= climb.edge_bottom AND
 *   product_sizes.edge_top   >= climb.edge_top
 *
 * (Browse code does the comparison; this type is just data.)
 */
data class ClimbBounds(
    val left: Int,
    val right: Int,
    val bottom: Int,
    val top: Int,
) {
    /** Wire shape for the Nostr `bounds` tag: `"L,R,B,T"`. */
    fun encode(): String = "$left,$right,$bottom,$top"

    companion object {
        /**
         * Build a bounding box from a list of (x, y) hold coordinates.
         * Returns `null` for an empty list — callers persist NULL edge_*
         * in that case (matches the historical default for hold-less
         * climbs and pre-Plan-2 rows).
         */
        fun fromCoords(coords: Collection<Pair<Int, Int>>): ClimbBounds? {
            if (coords.isEmpty()) return null
            var l = Int.MAX_VALUE
            var r = Int.MIN_VALUE
            var b = Int.MAX_VALUE
            var t = Int.MIN_VALUE
            for ((x, y) in coords) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < b) b = y
                if (y > t) t = y
            }
            return ClimbBounds(left = l, right = r, bottom = b, top = t)
        }

        /**
         * Inverse of [encode] — parse the Nostr `bounds` tag value.
         * Returns null on any malformed input (caller falls back to
         * NULL edge_* columns).
         */
        fun decode(raw: String): ClimbBounds? {
            val parts = raw.split(",")
            if (parts.size != 4) return null
            val l = parts[0].trim().toIntOrNull() ?: return null
            val r = parts[1].trim().toIntOrNull() ?: return null
            val b = parts[2].trim().toIntOrNull() ?: return null
            val t = parts[3].trim().toIntOrNull() ?: return null
            // Sanity: left <= right, bottom <= top — reject upside-down
            // boxes as malformed.
            if (l > r || b > t) return null
            return ClimbBounds(left = l, right = r, bottom = b, top = t)
        }
    }
}
