package com.cruxcoach.android.ui.map

import java.text.Normalizer
import java.util.Locale

sealed interface MapSearchResult {
    data class Venue(val venue: MapVenue) : MapSearchResult
    data class Place(val place: MapPlace) : MapSearchResult
}

/** Local ranked search ported from the Pages board map: venue-name prefixes
 * beat substrings, every query term must match, and cities include English,
 * German and alternate spellings from the bundled GeoNames index.
 *
 * Convenience entry point for one-off searches and tests. Interactive callers
 * must reuse a [BoardMapSearchIndex] off the main thread: normalising every
 * venue and place again per keystroke froze the map (ANR on slower phones). */
fun searchBoardMap(
    query: String,
    venues: List<MapVenue>,
    places: List<MapPlace>,
    locale: Locale = Locale.getDefault(),
    venueLimit: Int = 8,
    placeLimit: Int = 5,
): List<MapSearchResult> {
    if (normalizeMapSearchText(query).length < 2) return emptyList()
    return BoardMapSearchIndex(venues, places, locale).search(query, venueLimit, placeLimit)
}

/** Pre-normalised search corpus. Build once per dataset (background thread),
 *  then [search] only does substring checks. */
class BoardMapSearchIndex(
    venues: List<MapVenue>,
    places: List<MapPlace>,
    locale: Locale = Locale.getDefault(),
) {
    private class VenueEntry(val venue: MapVenue, val haystack: String, val name: String, val city: String)
    private class PlaceEntry(val place: MapPlace, val haystack: String, val forms: List<String>)

    private val venueEntries: List<VenueEntry>
    private val placeEntries: List<PlaceEntry>

    init {
        val countryNames = Locale.getISOCountries().associateWith { code ->
            Locale("", code).getDisplayCountry(locale)
        }
        venueEntries = venues.map { venue ->
            val boardTerms = venue.boards.flatMap { board ->
                listOfNotNull(
                    board.boardBrand.displayName,
                    board.layoutName,
                    board.sizeLabel,
                    board.address,
                    board.url,
                ) + board.alternateSearchTerms
            }
            val country = countryNames[venue.countryCode].orEmpty()
            VenueEntry(
                venue = venue,
                haystack = normalizeMapSearchText(
                    (listOfNotNull(venue.name, venue.city, venue.countryCode, country) + boardTerms).joinToString(" ")
                ),
                name = normalizeMapSearchText(venue.name),
                city = normalizeMapSearchText(venue.city.orEmpty()),
            )
        }
        placeEntries = places.map { place ->
            val displayName = if (locale.language == "de") place.germanName ?: place.name else place.name
            val country = countryNames[place.countryCode].orEmpty()
            val forms = listOf(place.name, displayName) + place.aliases
            PlaceEntry(
                place = place,
                haystack = normalizeMapSearchText(
                    (forms + listOfNotNull(place.region, place.countryCode, country)).joinToString(" ")
                ),
                forms = forms.map(::normalizeMapSearchText),
            )
        }
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

private val MAP_SEARCH_MARKS = Regex("\\p{M}+")
private val MAP_SEARCH_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

internal fun normalizeMapSearchText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKD)
    .replace(MAP_SEARCH_MARKS, "")
    .lowercase(Locale.ROOT)
    .replace(MAP_SEARCH_SEPARATORS, " ")
    .trim()
