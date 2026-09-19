package com.cruxcoach.app.browse

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumOverlapFilter

/** Active board identity as persisted by the shared board picker. */
data class BoardSelection(
    val boardBrand: String,
    val layoutId: Int,
    val productSizeId: Int,
    val angle: Int,
)

/**
 * Browser preferences over [KeyValueStore]. Key names and defaults are those of
 * Android's data/UserPreferences.kt (DataStore); values are stored as strings
 * because the iOS store is string-typed. Unparseable values fall back to the default.
 */
class BrowsePreferences(private val store: KeyValueStore) {
    companion object {
        const val BOARD_BRAND = "board_brand"
        const val BOARD_LAYOUT_ID = "board_layout_id"
        const val BOARD_PRODUCT_SIZE_ID = "board_product_size_id"
        const val BOARD_ANGLE = "board_angle"
        const val BOARD_MIN_GRADE = "board_min_grade"
        const val BOARD_MAX_GRADE = "board_max_grade"
        const val BOARD_MIN_ASCENSIONISTS = "board_min_ascensionists"
        const val BOARD_SORT_FIELD = "board_sort_field"
        const val BOARD_SORT_DIRECTION = "board_sort_direction"
        const val BOARD_STATUS_FILTER = "board_status_filter"
        const val BOARD_CLIMB_TYPE = "board_climb_type"
        const val BOARD_BENCHMARK_ONLY = "board_benchmark_only"
        const val BOARD_ORIGIN_FILTER = "board_origin_filter"
        const val BOARD_MY_CLIMBS_ONLY = "board_my_climbs_only"
        const val BOARD_UNGRADED_ONLY = "board_ungraded_only"
        const val BOARD_QUANTUM_RULE_MASK = "board_quantum_rule_mask"
        const val BOARD_QUANTUM_OVERLAP_FILTER = "board_quantum_overlap_filter"
        const val GRADE_SCALE = "grade_scale"

        const val KILTER_ORIGINAL_LAYOUT = 1
        const val KILTER_DEFAULT_SIZE = 10
        const val DEFAULT_ANGLE = 40

        fun moonBoardHoldSetsKey(layoutId: Long): String = "moonboard_hold_sets_$layoutId"
    }

    private fun int(key: String, default: Int) = store.getString(key)?.toIntOrNull() ?: default
    private fun bool(key: String) = store.getString(key) == "true"

    fun boardSelection() = BoardSelection(
        boardBrand = store.getString(BOARD_BRAND) ?: "kilter",
        layoutId = int(BOARD_LAYOUT_ID, KILTER_ORIGINAL_LAYOUT),
        productSizeId = int(BOARD_PRODUCT_SIZE_ID, KILTER_DEFAULT_SIZE),
        angle = int(BOARD_ANGLE, DEFAULT_ANGLE),
    )

    /** Null [productSizeId] / [angle] keep the stored value, as Android's setBoardSelection does. */
    fun setBoardSelection(brand: String, layoutId: Int, productSizeId: Int?, angle: Int?) {
        store.putString(BOARD_BRAND, brand)
        store.putString(BOARD_LAYOUT_ID, layoutId.toString())
        if (productSizeId != null) store.putString(BOARD_PRODUCT_SIZE_ID, productSizeId.toString())
        if (angle != null) store.putString(BOARD_ANGLE, angle.toString())
    }

    /** Android default is FRENCH. */
    fun frenchGrades(): Boolean = (store.getString(GRADE_SCALE) ?: "FRENCH") != "V_SCALE"

    /** Filter snapshot; board-capability clamping is the caller's job (needs the brand policy). */
    fun loadFilter(): BrowserFilterState {
        val board = boardSelection()
        return BrowserFilterState(
            angle = board.angle,
            layoutId = board.layoutId,
            boardBrand = board.boardBrand,
            minGradeIndex = int(BOARD_MIN_GRADE, 0),
            maxGradeIndex = int(BOARD_MAX_GRADE, 16),
            minAscensionists = int(BOARD_MIN_ASCENSIONISTS, 0),
            sortField = ClimbSortField.entries.firstOrNull { it.name == store.getString(BOARD_SORT_FIELD) }
                ?: ClimbSortField.ASCENSIONISTS,
            sortDirection = SortDirection.entries.firstOrNull { it.name == store.getString(BOARD_SORT_DIRECTION) }
                ?: SortDirection.DESC,
            statusFilter = parseStatusFilter(store.getString(BOARD_STATUS_FILTER) ?: "ALL"),
            climbTypeFilter = ClimbTypeFilter.entries.firstOrNull { it.name == store.getString(BOARD_CLIMB_TYPE) }
                ?: ClimbTypeFilter.BOULDER,
            benchmarkOnly = bool(BOARD_BENCHMARK_ONLY),
            originFilter = OriginFilter.entries.firstOrNull { it.name == store.getString(BOARD_ORIGIN_FILTER) }
                ?: OriginFilter.ALL,
            quantumRuleMask = store.getString(BOARD_QUANTUM_RULE_MASK)?.toLongOrNull() ?: 0L,
            quantumOverlapFilter = QuantumOverlapFilter.fromWire(store.getString(BOARD_QUANTUM_OVERLAP_FILTER)),
            myClimbsOnly = bool(BOARD_MY_CLIMBS_ONLY),
            ungradedOnly = bool(BOARD_UNGRADED_ONLY),
        )
    }

    /** Same field set as Android's setBoardFilters (the search query is never persisted). */
    fun saveFilter(f: BrowserFilterState) {
        store.putString(BOARD_ANGLE, f.angle.toString())
        store.putString(BOARD_MIN_GRADE, f.minGradeIndex.toString())
        store.putString(BOARD_MAX_GRADE, f.maxGradeIndex.toString())
        store.putString(BOARD_MIN_ASCENSIONISTS, f.minAscensionists.toString())
        store.putString(BOARD_SORT_FIELD, f.sortField.name)
        store.putString(BOARD_SORT_DIRECTION, f.sortDirection.name)
        store.putString(BOARD_STATUS_FILTER, serializeStatusFilter(f.statusFilter))
        store.putString(BOARD_CLIMB_TYPE, f.climbTypeFilter.name)
        store.putString(BOARD_BENCHMARK_ONLY, f.benchmarkOnly.toString())
        store.putString(BOARD_ORIGIN_FILTER, f.originFilter.name)
        store.putString(BOARD_MY_CLIMBS_ONLY, f.myClimbsOnly.toString())
        store.putString(BOARD_UNGRADED_ONLY, f.ungradedOnly.toString())
        store.putString(BOARD_QUANTUM_RULE_MASK, f.quantumRuleMask.toString())
        store.putString(BOARD_QUANTUM_OVERLAP_FILTER, f.quantumOverlapFilter.name)
    }

    /** Mounted MoonBoard hold sets; nothing stored or nothing valid = the complete setup. */
    fun moonBoardHoldSets(variant: MoonBoardVariant): List<Long> {
        val universe = MoonBoardHoldSets.setIdsFor(variant)
        val stored = store.getString(moonBoardHoldSetsKey(variant.layoutId))
        if (stored.isNullOrBlank()) return universe
        val selected = stored.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toLongOrNull() }
        return universe.filter { it in selected }.ifEmpty { universe }
    }
}
