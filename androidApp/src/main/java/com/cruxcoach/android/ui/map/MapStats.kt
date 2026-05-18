package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation

/**
 * Pre-aggregated counts driving the stats tab. Computed once per
 * (allLocations, filters) change and consumed by chart composables.
 * 1k rows aggregate in <5ms, so we compute on the main pipeline
 * without offloading.
 */
data class MapStats(
    val total: Int,
    val originalCount: Int,
    val homewallCount: Int,
    val publicCount: Int,
    val privateCount: Int,
    val membersCount: Int,
    val accessUnknownCount: Int,
    val adjustableCount: Int,
    val fixedCount: Int,
    val adjUnknownCount: Int,
    /** Top countries sorted desc by count — full list, UI takes top N. */
    val byCountry: List<Pair<String, Int>>,
    /** Sizes sorted desc by count, label kept as-is from BoardLocation.sizeLabel. */
    val bySize: List<Pair<String, Int>>,
) {
    companion object {
        val Empty = MapStats(
            total = 0,
            originalCount = 0, homewallCount = 0,
            publicCount = 0, privateCount = 0, membersCount = 0, accessUnknownCount = 0,
            adjustableCount = 0, fixedCount = 0, adjUnknownCount = 0,
            byCountry = emptyList(),
            bySize = emptyList(),
        )

        fun from(locations: List<BoardLocation>): MapStats {
            if (locations.isEmpty()) return Empty
            var original = 0; var homewall = 0
            var pub = 0; var priv = 0; var mem = 0; var accUnk = 0
            var adj = 0; var fix = 0; var adjUnk = 0
            val countryCounts = HashMap<String, Int>()
            val sizeCounts = HashMap<String, Int>()
            for (loc in locations) {
                when (loc.layoutId) {
                    1 -> original++
                    8 -> homewall++
                }
                when (loc.accessType) {
                    AccessType.PUBLIC -> pub++
                    AccessType.PRIVATE -> priv++
                    AccessType.MEMBERS -> mem++
                    AccessType.UNKNOWN -> accUnk++
                }
                when (loc.adjustability) {
                    Adjustability.ADJUSTABLE, Adjustability.FULL, Adjustability.LIMITED -> adj++
                    Adjustability.FIXED -> fix++
                    Adjustability.UNKNOWN -> adjUnk++
                }
                countryCounts.merge(loc.countryCode, 1, Int::plus)
                val size = loc.sizeLabel
                if (!size.isNullOrBlank()) {
                    sizeCounts.merge(size, 1, Int::plus)
                }
            }
            return MapStats(
                total = locations.size,
                originalCount = original, homewallCount = homewall,
                publicCount = pub, privateCount = priv, membersCount = mem, accessUnknownCount = accUnk,
                adjustableCount = adj, fixedCount = fix, adjUnknownCount = adjUnk,
                byCountry = countryCounts.entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value },
                bySize = sizeCounts.entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value },
            )
        }
    }
}
