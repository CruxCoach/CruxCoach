package com.cruxcoach.android.ui.map

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand

/**
 * Single source of truth for all map-side filtering. Applied Kotlin-
 * side because (a) the dataset is small (~1k items, sub-millisecond
 * to filter), (b) keeping the rendered list and stats aggregations in
 * sync is much simpler when one function produces both, and (c) it
 * sidesteps the cluster-boundary glitches that plagued an earlier
 * GL-Expression-only filter.
 *
 * Empty sets mean "no filter on this dimension" — the wildcard
 * semantic — except for [accessTypes]. An empty access selection is the
 * privacy-safe default and means PUBLIC only. Non-public installations
 * remain available only after an explicit filter choice.
 */
data class MapFilters(
    val showOriginal: Boolean = true,
    val showHomewalls: Boolean = false,
    val matchesMyBoard: Boolean = false,
    val countries: Set<String> = emptySet(),
    val accessTypes: Set<AccessType> = emptySet(),
    val adjustabilities: Set<Adjustability> = emptySet(),
    val sizeIds: Set<Int> = emptySet(),
    /** Board families to show. Empty = all brands (the wildcard). */
    val brands: Set<BoardBrand> = emptySet(),
    /** When true, keep only venues/boards that accept egym Wellpass. */
    val wellpassOnly: Boolean = false,
) {
    /** Effective access selection. The empty persisted value is also what
     *  every pre-fix install carries, so interpreting it as PUBLIC repairs
     *  existing installs without a preferences migration. */
    val effectiveAccessTypes: Set<AccessType>
        get() = accessTypes.ifEmpty { setOf(AccessType.PUBLIC) }

    /** True when no user-applied filter is active beyond the homewall default. */
    val isAtDefault: Boolean
        get() = showOriginal && !showHomewalls && !matchesMyBoard &&
            countries.isEmpty() && accessTypes.isEmpty() &&
            adjustabilities.isEmpty() && sizeIds.isEmpty() && brands.isEmpty() &&
            !wellpassOnly

    fun apply(
        locations: List<BoardLocation>,
        userBoardLayoutId: Int? = null,
        userBoardSizeId: Int? = null,
        userBoardBrand: BoardBrand? = null,
    ): List<BoardLocation> {
        if (locations.isEmpty()) return locations
        return locations.filter { loc ->
            // Brand gate (empty = all brands).
            if (brands.isNotEmpty() && loc.boardBrand !in brands) return@filter false

            // egym-Wellpass gate. Only venues curated as accepting Wellpass
            // (wellpass == true) pass; unknown (null) and explicit-no are
            // both excluded when the filter is on.
            if (wellpassOnly && loc.wellpass != true) return@filter false

            // Layout family gate (Original / Homewall) is a Kilter-only
            // concept — MoonBoard gyms are gated by the brand filter above,
            // not by the Original/Homewall toggles, so they always pass here.
            if (loc.boardBrand == BoardBrand.KILTER) {
                val layoutAllowed = when (loc.layoutId) {
                    BoardConstants.KILTER_ORIGINAL_LAYOUT -> showOriginal
                    BoardConstants.KILTER_HOMEWALL_LAYOUT -> showHomewalls
                    else -> showOriginal || showHomewalls
                }
                if (!layoutAllowed) return@filter false
            }

            if (matchesMyBoard && userBoardLayoutId != null) {
                // Brand-scope the match: Aurora layout ids overlap Kilter's, so
                // without the brand check a Kilter Original (layout 1) would also
                // match a Tension venue that happens to carry layout id 1.
                // userBoardBrand == null (legacy callers) skips the brand gate.
                if (userBoardBrand != null && loc.boardBrand != userBoardBrand) return@filter false
                if (loc.layoutId != userBoardLayoutId) return@filter false
                if (userBoardSizeId != null) {
                    val sizeOk = loc.productSizeId == null || loc.productSizeId == userBoardSizeId
                    if (!sizeOk) return@filter false
                }
            }

            if (countries.isNotEmpty() && loc.countryCode !in countries) return@filter false
            if (loc.accessType !in effectiveAccessTypes) return@filter false
            if (adjustabilities.isNotEmpty() && loc.adjustability !in adjustabilities) return@filter false
            if (sizeIds.isNotEmpty()) {
                val sizeId = loc.productSizeId ?: return@filter false
                if (sizeId !in sizeIds) return@filter false
            }
            true
        }
    }
}
