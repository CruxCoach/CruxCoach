package com.cruxcoach.android.ui.map

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
 * semantic. `showOriginal` and `showHomewalls` are explicit booleans
 * because they're commonly toggled together and the boolean form is
 * easier to bind to two separate switch UI components.
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
) {
    /** True when no user-applied filter is active beyond the homewall default. */
    val isAtDefault: Boolean
        get() = showOriginal && !showHomewalls && !matchesMyBoard &&
            countries.isEmpty() && accessTypes.isEmpty() &&
            adjustabilities.isEmpty() && sizeIds.isEmpty() && brands.isEmpty()

    fun apply(
        locations: List<BoardLocation>,
        userBoardLayoutId: Int? = null,
        userBoardSizeId: Int? = null,
    ): List<BoardLocation> {
        if (locations.isEmpty()) return locations
        return locations.filter { loc ->
            // Brand gate (empty = all brands).
            if (brands.isNotEmpty() && loc.boardBrand !in brands) return@filter false

            // Layout family gate (Original=1 / Homewall=8) is a Kilter-only
            // concept — MoonBoard gyms are gated by the brand filter above,
            // not by the Original/Homewall toggles, so they always pass here.
            if (loc.boardBrand == BoardBrand.KILTER) {
                val layoutAllowed = when (loc.layoutId) {
                    1 -> showOriginal
                    8 -> showHomewalls
                    else -> showOriginal || showHomewalls
                }
                if (!layoutAllowed) return@filter false
            }

            if (matchesMyBoard && userBoardLayoutId != null) {
                if (loc.layoutId != userBoardLayoutId) return@filter false
                if (userBoardSizeId != null) {
                    val sizeOk = loc.productSizeId == null || loc.productSizeId == userBoardSizeId
                    if (!sizeOk) return@filter false
                }
            }

            if (countries.isNotEmpty() && loc.countryCode !in countries) return@filter false
            if (accessTypes.isNotEmpty() && loc.accessType !in accessTypes) return@filter false
            if (adjustabilities.isNotEmpty() && loc.adjustability !in adjustabilities) return@filter false
            if (sizeIds.isNotEmpty()) {
                val sizeId = loc.productSizeId ?: return@filter false
                if (sizeId !in sizeIds) return@filter false
            }
            true
        }
    }
}
