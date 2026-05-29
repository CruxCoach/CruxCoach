package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.roundToInt

/**
 * A physical location that may host more than one board — a gym with both a
 * Kilter and a MoonBoard, or a Kilter Original *and* Homewall in the same
 * room. Produced by [groupIntoVenues] from the per-board location rows so
 * the map renders one pin per place instead of several single-board markers
 * stacked on the exact same coordinate.
 *
 * [boards] keeps every board at the venue (already filter-passed by the
 * caller), brand-ordered so the detail sheet lists Kilter before MoonBoard
 * before everything else.
 */
data class MapVenue(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String,
    val city: String?,
    val countryCode: String,
    val boards: List<BoardLocation>,
) {
    /** Distinct board families present at this venue. */
    val brands: Set<BoardBrand> get() = boards.mapTo(linkedSetOf()) { it.boardBrand }

    val isMultiBoard: Boolean get() = boards.size > 1

    /**
     * Single colouring axis for the marker: the venue's brand when it hosts
     * exactly one family, or [VenueBrandKey.MULTI] when it mixes families.
     * Brand-level (not layout-level) because a venue can hold several
     * layouts; the detail sheet carries the per-board specifics.
     */
    val brandKey: VenueBrandKey
        get() {
            val b = brands
            return when {
                b.size > 1 -> VenueBrandKey.MULTI
                b.singleOrNull() == BoardBrand.KILTER -> VenueBrandKey.KILTER
                b.singleOrNull() == BoardBrand.MOONBOARD -> VenueBrandKey.MOONBOARD
                else -> VenueBrandKey.OTHER
            }
        }
}

/** Marker colour buckets — kept as a typed key so the GL layer and any
 *  legend stay in lockstep. */
enum class VenueBrandKey(val wire: String) {
    KILTER("kilter"),
    MOONBOARD("moonboard"),
    MULTI("multi"),
    OTHER("other"),
}

/**
 * ~11 m rounding (4 decimal places). Mirrors the cruxcoach.org map's
 * `venueKey` so the in-app map groups boards into venues the same way the
 * website does — two boards within ~11 m collapse to one pin.
 */
internal fun venueKey(lat: Double, lng: Double): String {
    fun q(v: Double) = (v * 1e4).roundToInt()
    return "${q(lat)},${q(lng)}"
}

// Known families first (Kilter, then MoonBoard); anything else sorts last but
// stays grouped. Used both to pick a venue's representative board and to
// order the boards list.
private val BRAND_ORDER = listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)

private fun brandRank(brand: BoardBrand): Int =
    BRAND_ORDER.indexOf(brand).let { if (it < 0) Int.MAX_VALUE else it }

/**
 * Collapse per-board location rows into venues keyed by [venueKey].
 *
 * The representative name/coords come from the highest-priority board
 * (Kilter > MoonBoard > other, then a non-blank name), since different
 * brands at the same gym often carry slightly different names. City and
 * country are taken from the first board that has a usable value (MoonBoard
 * rows, for instance, ship no country — `??` — so a co-located Kilter row
 * fills it in).
 */
fun groupIntoVenues(locations: List<BoardLocation>): List<MapVenue> {
    if (locations.isEmpty()) return emptyList()
    return locations
        .groupBy { venueKey(it.lat, it.lng) }
        .map { (key, boards) ->
            val ordered = boards.sortedWith(
                compareBy({ brandRank(it.boardBrand) }, { if (it.name.isBlank()) 1 else 0 }),
            )
            val rep = ordered.first()
            MapVenue(
                id = key,
                lat = rep.lat,
                lng = rep.lng,
                name = rep.name,
                city = ordered.firstNotNullOfOrNull { it.city?.takeIf { c -> c.isNotBlank() } },
                countryCode = ordered
                    .firstNotNullOfOrNull { it.countryCode.takeIf { c -> c.isNotBlank() && c != "??" } }
                    ?: rep.countryCode,
                boards = ordered,
            )
        }
}
