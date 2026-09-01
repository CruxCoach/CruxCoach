package com.cruxcoach.android.ui.map

import java.text.Normalizer
import java.util.Locale

sealed interface MapSearchResult {
    data class Venue(val venue: MapVenue) : MapSearchResult
    data class Place(val place: MapPlace) : MapSearchResult
}

/** Local ranked search ported from the Pages board map: venue-name prefixes
 * beat substrings, every query term must match, and cities include English,
 * German and alternate spellings from the bundled GeoNames index. */
fun searchBoardMap(
    query: String,
    venues: List<MapVenue>,
    places: List<MapPlace>,
    locale: Locale = Locale.getDefault(),
    venueLimit: Int = 8,
    placeLimit: Int = 5,
): List<MapSearchResult> {
    val normalized = normalizeMapSearchText(query)
    if (normalized.length < 2) return emptyList()
    val terms = normalized.split(' ').filter(String::isNotBlank)
    val countryNames = java.util.Locale.getISOCountries().associateWith { code ->
        Locale("", code).getDisplayCountry(locale)
    }

    val venueMatches = venues.asSequence().mapNotNull { venue ->
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
        val haystack = normalizeMapSearchText(
            (listOfNotNull(venue.name, venue.city, venue.countryCode, country) + boardTerms).joinToString(" ")
        )
        if (terms.any { it !in haystack }) return@mapNotNull null
        val name = normalizeMapSearchText(venue.name)
        val city = normalizeMapSearchText(venue.city.orEmpty())
        val score = when {
            name == normalized -> 0
            name.startsWith(normalized) -> 1
            normalized in name -> 2
            city == normalized -> 3
            city.startsWith(normalized) -> 4
            else -> 5
        }
        score to venue
    }.sortedWith(compareBy<Pair<Int, MapVenue>> { it.first }.thenBy { it.second.name })
        .take(venueLimit)
        .map { MapSearchResult.Venue(it.second) }
        .toList()

    val placeMatches = places.asSequence().mapIndexedNotNull { rank, place ->
        val displayName = if (locale.language == "de") place.germanName ?: place.name else place.name
        val country = countryNames[place.countryCode].orEmpty()
        val forms = listOf(place.name, displayName) + place.aliases
        val normalizedForms = forms.map(::normalizeMapSearchText)
        val haystack = normalizeMapSearchText(
            (forms + listOfNotNull(place.region, place.countryCode, country)).joinToString(" ")
        )
        if (terms.any { it !in haystack }) return@mapIndexedNotNull null
        val score = when {
            normalizedForms.any { it == normalized } -> 0
            normalizedForms.any { it.startsWith(normalized) } -> 1
            normalizedForms.any { normalized in it } -> 2
            else -> 3
        }
        Triple(score, rank, place)
    }.sortedWith(compareBy<Triple<Int, Int, MapPlace>> { it.first }.thenBy { it.second })
        .take(placeLimit)
        .map { MapSearchResult.Place(it.third) }
        .toList()

    return venueMatches + placeMatches
}

internal fun normalizeMapSearchText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
