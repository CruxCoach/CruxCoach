package com.cruxcoach.app.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand

data class MapLayoutCount(val brand: BoardBrand, val layout: String?, val count: Int)

/**
 * Port of Android `com.cruxcoach.android.ui.map.MapStats`.
 *
 * Pre-aggregated counts driving the statistics section. Every count is over
 * BOARD rows, not venues: one location may hold several boards, and the
 * statistics deliberately describe all recorded boards (Android's
 * `MapState.unfilteredStats`, shown with the `map_stats_scope` note).
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
    /** Countries sorted desc by count — full list, UI takes top N. */
    val byCountry: List<Pair<String, Int>>,
    /** Sizes sorted desc by count, label kept as-is from BoardLocation.sizeLabel. */
    val bySize: List<Pair<String, Int>>,
    val byBrand: List<Pair<BoardBrand, Int>> = emptyList(),
    val byLayout: List<MapLayoutCount> = emptyList(),
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
            val brandCounts = HashMap<BoardBrand, Int>()
            val layoutCounts = HashMap<Pair<BoardBrand, String?>, Int>()
            val countryCounts = HashMap<String, Int>()
            val sizeCounts = HashMap<String, Int>()
            for (loc in locations) {
                brandCounts.increment(loc.boardBrand)
                val layout = loc.layoutName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: if (loc.boardBrand == BoardBrand.KILTER) when (loc.layoutId) {
                        KILTER_ORIGINAL_LAYOUT -> "Original"
                        KILTER_HOMEWALL_LAYOUT -> "Homewall"
                        else -> null
                    } else null
                layoutCounts.increment(loc.boardBrand to layout)
                // Original/Homewall is a Kilter-only split; brand-scope the
                // count so an Aurora venue at a colliding layout id isn't
                // miscounted as a Kilter Original/Homewall (FEAT-031).
                if (loc.boardBrand == BoardBrand.KILTER) {
                    when (loc.layoutId) {
                        KILTER_ORIGINAL_LAYOUT -> original++
                        KILTER_HOMEWALL_LAYOUT -> homewall++
                    }
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
                countryCounts.increment(loc.countryCode)
                val size = loc.sizeLabel
                if (!size.isNullOrBlank()) {
                    sizeCounts.increment(size)
                }
            }
            return MapStats(
                total = locations.size,
                byBrand = brandCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<BoardBrand, Int>> { it.value }.thenBy { it.key.displayName })
                    .map { it.key to it.value },
                byLayout = layoutCounts.map { (key, count) -> MapLayoutCount(key.first, key.second, count) }
                    .sortedWith(
                        compareByDescending<MapLayoutCount> { it.count }
                            .thenBy { it.brand.displayName }.thenBy { it.layout.orEmpty() },
                    ),
                originalCount = original, homewallCount = homewall,
                publicCount = pub, privateCount = priv, membersCount = mem, accessUnknownCount = accUnk,
                adjustableCount = adj, fixedCount = fix, adjUnknownCount = adjUnk,
                // Android sorts these by count alone. Iteration order of a hash
                // map is unspecified and differs between the JVM and Kotlin/Native,
                // so equal counts would order differently on the two platforms and
                // between runs. The alphabetical tie-break changes no count; it
                // only makes the rendered order (and the tests) deterministic.
                byCountry = countryCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { it.key to it.value },
                bySize = sizeCounts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { it.key to it.value },
            )
        }

        /** `HashMap.merge` is a JVM-only API; commonMain needs this. */
        private fun <K> HashMap<K, Int>.increment(key: K) {
            this[key] = (this[key] ?: 0) + 1
        }
    }
}
