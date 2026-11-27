package com.cruxcoach.domain.board

/**
 * Computes hold usage heatmaps from climb frame data.
 * Pure stateless functions — all computation in-memory from frame strings.
 */
object HoldHeatmapComputer {

    /** Hold usage count per placement — how often each hold appears across all climbs. */
    fun computeGlobalHeatmap(framesList: List<String>): Map<Int, Int> {
        val counter = HoldCounter()
        framesList.forEach(counter::add)
        return counter.counts()
    }

    /**
     * Hold usage count filtered by role (e.g., only start holds, only feet).
     * Matches by canonical role *class* ([HoldRole.roleClass]) so a query for
     * [HoldRole.START] also counts Aurora-family start holds (code 1) — the
     * per-role heatmap was previously empty on Tension/Grasshopper/Decoy/So
     * iLL/Touchstone, whose frames carry codes 1-4 rather than Kilter's 12-15.
     */
    fun computeHeatmapByRole(framesList: List<String>, role: Int): Map<Int, Int> {
        val counter = HoldCounter(role)
        framesList.forEach(counter::add)
        return counter.counts()
    }

    /**
     * Counts hold usage one climb at a time: all holds, or with [role] only the
     * holds of that role class, as [computeHeatmapByRole]. A caller can stream a
     * whole catalogue through [add] without holding its frames in a list.
     */
    class HoldCounter(role: Int? = null) {
        private val targetClass = role?.let { HoldRole.roleClass(it) }
        // Placement ids are small (Kilter: below 5,000), so an array counts without boxing.
        private var dense = IntArray(0)
        private val sparse = mutableMapOf<Int, Int>()

        fun add(frames: String) {
            BoardClimbParser.forEachHold(frames) { placementId, roleId ->
                if (targetClass == null || HoldRole.roleClass(roleId) == targetClass) count(placementId)
            }
        }

        private fun count(placementId: Int) {
            if (placementId !in 0 until MAX_DENSE) {
                sparse[placementId] = (sparse[placementId] ?: 0) + 1
                return
            }
            if (placementId >= dense.size) {
                dense = dense.copyOf(minOf(MAX_DENSE, maxOf(placementId + 1, dense.size * 2, 1024)))
            }
            dense[placementId]++
        }

        fun counts(): Map<Int, Int> {
            val result = HashMap<Int, Int>(sparse)
            for (id in dense.indices) if (dense[id] > 0) result[id] = dense[id]
            return result
        }

        private companion object {
            const val MAX_DENSE = 1 shl 16
        }
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
