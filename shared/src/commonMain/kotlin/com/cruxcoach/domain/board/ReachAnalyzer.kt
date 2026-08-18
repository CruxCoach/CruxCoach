package com.cruxcoach.domain.board

import kotlin.math.sqrt

/**
 * FEAT-043 reach metric — the Kotlin mirror of the blossom-sync cron's
 * `reach_metric.py` (the cron is the single source of truth server-side;
 * this exists only as the save-time fallback for locally created climbs,
 * so their score matches what the cron would compute). The parity
 * fixtures in ReachAnalyzerTest pin both implementations to the same
 * values — change them together or not at all.
 *
 * Metric: the LONGEST EDGE OF THE MINIMUM SPANNING TREE over HAND-role
 * holds (start/hand/finish; feet excluded by the caller) — the widest
 * gap that MUST be bridged to connect all hand holds, regardless of
 * sequence (frames carry no move order). Plain nearest-neighbour fails
 * here: two tight pairs far apart score the pair distance, not the big
 * move between them. Units are board-grid units; consumers convert to
 * cm via the board family's grid pitch.
 */
object ReachAnalyzer {

    data class Point(val x: Double, val y: Double)

    /** Reach score for one frame's hand holds. Null below 2 holds. */
    fun mstBottleneckGap(points: List<Point>): Double? {
        if (points.size < 2) return null
        // Prim's algorithm; hold counts are tiny (<= ~40), O(n^2) is fine.
        val inTree = BooleanArray(points.size)
        val best = DoubleArray(points.size) { Double.MAX_VALUE }
        best[0] = 0.0
        var longest = 0.0
        repeat(points.size) {
            var u = -1
            for (i in points.indices) {
                if (!inTree[i] && (u == -1 || best[i] < best[u])) u = i
            }
            inTree[u] = true
            if (best[u] > longest) longest = best[u]
            for (v in points.indices) {
                if (!inTree[v]) {
                    val dx = points[u].x - points[v].x
                    val dy = points[u].y - points[v].y
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < best[v]) best[v] = d
                }
            }
        }
        return longest
    }

    /** Multi-frame climbs (routes): the max score across frames. */
    fun climbReach(framesHandPoints: List<List<Point>>): Double? =
        framesHandPoints.mapNotNull { mstBottleneckGap(it) }.maxOrNull()
}
