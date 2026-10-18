package com.cruxcoach.app.map

import com.cruxcoach.domain.board.BoardBrand

sealed interface MapSearchResult {
    data class Venue(val venue: MapVenue) : MapSearchResult
    data class Place(val place: MapPlace) : MapSearchResult
}

/**
 * Local ranked search ported from Android `MapSearch.kt` (and the Pages board
 * map): venue-name prefixes beat substrings, every query term must match, and
 * cities include English, German and alternate spellings from the bundled
 * index. Country names come from the host ([countryNames], ISO code → name in
 * the user's language) because common code has no locale database.
 */
fun searchBoardMap(
    query: String,
    venues: List<MapVenue>,
    places: List<MapPlace>,
    countryNames: Map<String, String> = emptyMap(),
    german: Boolean = false,
    venueLimit: Int = 8,
    placeLimit: Int = 5,
): List<MapSearchResult> {
    if (normalizeMapSearchText(query).length < 2) return emptyList()
    return BoardMapSearchIndex(venues, places, countryNames, german).search(query, venueLimit, placeLimit)
}

/** Pre-normalised corpus: build once per dataset off the main thread, then [search] only compares. */
class BoardMapSearchIndex(
    venues: List<MapVenue>,
    places: List<MapPlace>,
    countryNames: Map<String, String> = emptyMap(),
    german: Boolean = false,
) {
    private class VenueEntry(val venue: MapVenue, val haystack: String, val name: String, val city: String)
    private class PlaceEntry(val place: MapPlace, val haystack: String, val forms: List<String>)

    private val venueEntries: List<VenueEntry> = venues.map { venue ->
        val boardTerms = venue.boards.flatMap { board ->
            listOfNotNull(board.boardBrand.displayName, board.layoutName, board.sizeLabel, board.address, board.url) +
                board.alternateSearchTerms
        }
        val country = countryNames[venue.countryCode].orEmpty()
        VenueEntry(
            venue = venue,
            haystack = normalizeMapSearchText(
                (listOfNotNull(venue.name, venue.city, venue.countryCode, country) + boardTerms).joinToString(" "),
            ),
            name = normalizeMapSearchText(venue.name),
            city = normalizeMapSearchText(venue.city.orEmpty()),
        )
    }

    private val placeEntries: List<PlaceEntry> = places.map { place ->
        val displayName = if (german) place.germanName ?: place.name else place.name
        val country = countryNames[place.countryCode].orEmpty()
        val forms = listOf(place.name, displayName) + place.aliases
        PlaceEntry(
            place = place,
            haystack = normalizeMapSearchText((forms + listOfNotNull(place.region, place.countryCode, country)).joinToString(" ")),
            forms = forms.map(::normalizeMapSearchText),
        )
    }

    fun search(query: String, venueLimit: Int = 8, placeLimit: Int = 5): List<MapSearchResult> {
        val normalized = normalizeMapSearchText(query)
        if (normalized.length < 2) return emptyList()
        val terms = normalized.split(' ').filter(String::isNotBlank)

        val venueMatches = venueEntries.asSequence().mapNotNull { entry ->
            if (terms.any { it !in entry.haystack }) return@mapNotNull null
            val score = when {
                entry.name == normalized -> 0
                entry.name.startsWith(normalized) -> 1
                normalized in entry.name -> 2
                entry.city == normalized -> 3
                entry.city.startsWith(normalized) -> 4
                else -> 5
            }
            score to entry.venue
        }.sortedWith(compareBy<Pair<Int, MapVenue>> { it.first }.thenBy { it.second.name })
            .take(venueLimit)
            .map { MapSearchResult.Venue(it.second) }
            .toList()

        val placeMatches = placeEntries.asSequence().mapIndexedNotNull { rank, entry ->
            if (terms.any { it !in entry.haystack }) return@mapIndexedNotNull null
            val score = when {
                entry.forms.any { it == normalized } -> 0
                entry.forms.any { it.startsWith(normalized) } -> 1
                entry.forms.any { normalized in it } -> 2
                else -> 3
            }
            Triple(score, rank, entry.place)
        }.sortedWith(compareBy<Triple<Int, Int, MapPlace>> { it.first }.thenBy { it.second })
            .take(placeLimit)
            .map { MapSearchResult.Place(it.third) }
            .toList()

        return venueMatches + placeMatches
    }
}

/**
 * Marker colour (0xAARRGGBB) and monogram per board family, the cruxcoach.org
 * hues (Kilter red, MoonBoard yellow, …). [onColor] keeps the letter readable.
 * Port of Android `BoardBrand.markerStyle()`.
 */
data class BrandMarkerStyle(val color: Long, val onColor: Long, val monogram: String)

object MapMarkerStyles {
    private const val WHITE = 0xFFFFFFFFL
    private const val INK = 0xFF1A1A1AL

    fun of(brand: BoardBrand): BrandMarkerStyle = when (brand) {
        BoardBrand.KILTER -> BrandMarkerStyle(0xFFE5232AL, WHITE, "K")
        BoardBrand.MOONBOARD -> BrandMarkerStyle(0xFFFDB515L, INK, "M")
        BoardBrand.TENSION -> BrandMarkerStyle(0xFF566573L, WHITE, "T")
        BoardBrand.GRASSHOPPER -> BrandMarkerStyle(0xFF12B5CBL, INK, "G")
        BoardBrand.DECOY -> BrandMarkerStyle(0xFFAB47BCL, WHITE, "D")
        BoardBrand.SOILL -> BrandMarkerStyle(0xFF5E8F6EL, WHITE, "S")
        BoardBrand.TOUCHSTONE -> BrandMarkerStyle(0xFF3F7FC1L, WHITE, "Ts")
        BoardBrand.QUANTUM -> BrandMarkerStyle(0xFF00897BL, WHITE, "Q")
        BoardBrand.AURORA -> BrandMarkerStyle(0xFFB93655L, WHITE, "A")
        BoardBrand.TWELVECLIMB -> BrandMarkerStyle(0xFFE0185FL, WHITE, "12")
    }

    /** Stable id per family combination (Android `MapMarkerIcons.iconId`). */
    fun iconId(brands: List<BoardBrand>): String = "venue-" + brands.joinToString("+") { it.wireValue }
}
