package com.cruxcoach.domain.board

/**
 * Computes hold usage heatmaps from climb frame data.
 * Pure stateless functions — all computation in-memory from frame strings.
 */
object HoldHeatmapComputer {

    /** Hold usage count per placement — how often each hold appears across all climbs. */
    fun computeGlobalHeatmap(framesList: List<String>): Map<Int, Int> {
        val heatmap = mutableMapOf<Int, Int>()
        for (frames in framesList) {
            val holds = BoardClimbParser.parseFrames(frames)
            for (hold in holds) {
                heatmap[hold.placementId] = (heatmap[hold.placementId] ?: 0) + 1
            }
        }
        return heatmap
    }

    /**
     * Hold usage count filtered by role (e.g., only start holds, only feet).
     * Matches by canonical role *class* ([HoldRole.roleClass]) so a query for
     * [HoldRole.START] also counts Aurora-family start holds (code 1) — the
     * per-role heatmap was previously empty on Tension/Grasshopper/Decoy/So
     * iLL/Touchstone, whose frames carry codes 1-4 rather than Kilter's 12-15.
     */
    fun computeHeatmapByRole(framesList: List<String>, role: Int): Map<Int, Int> {
        val heatmap = mutableMapOf<Int, Int>()
        val targetClass = HoldRole.roleClass(role)
        for (frames in framesList) {
            val holds = BoardClimbParser.parseFrames(frames)
            for (hold in holds) {
                if (HoldRole.roleClass(hold.roleId) == targetClass) {
                    heatmap[hold.placementId] = (heatmap[hold.placementId] ?: 0) + 1
                }
            }
        }
        return heatmap
    }

    /**
     * Find climb UUIDs that contain ALL of the selected holds.
     * Each selectedHold is matched by placement ID only (any role).
     */
    fun filterClimbsByHolds(
        framesByUuid: Map<String, String>,
        selectedPlacementIds: Set<Int>
    ): Set<String> {
        if (selectedPlacementIds.isEmpty()) return framesByUuid.keys
        return framesByUuid.filter { (_, frames) ->
            val holdIds = BoardClimbParser.parseFrames(frames).map { it.placementId }.toSet()
            selectedPlacementIds.all { it in holdIds }
        }.keys
    }

    /** Build the LIKE pattern for a placement ID in delta-format frames. */
    fun holdLikePattern(placementId: Int): String = "p${placementId}r"

    /**
     * Normalize heatmap values to 0.0..1.0 range using logarithmic scaling.
     * Logarithmic gives better visual distribution when some holds are used 1000x and others 1x.
     */
    fun normalizeHeatmap(heatmap: Map<Int, Int>): Map<Int, Float> {
        if (heatmap.isEmpty()) return emptyMap()
        val maxCount = heatmap.values.max()
        if (maxCount <= 0) return emptyMap()
        val logMax = kotlin.math.ln(maxCount.toDouble() + 1)
        return heatmap.mapValues { (_, count) ->
            (kotlin.math.ln(count.toDouble() + 1) / logMax).toFloat()
        }
    }
}
