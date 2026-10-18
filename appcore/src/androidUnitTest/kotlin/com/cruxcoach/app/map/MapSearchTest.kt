package com.cruxcoach.app.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of Android `MapSearchTest`, assertions unchanged; `Locale.GERMAN` becomes `german = true`. */
class MapSearchTest {
    private val munichVenue = groupIntoVenues(
        listOf(
            BoardLocation(
                id = "munich",
                name = "Boulderwelt München Ost",
                lat = 48.12,
                lng = 11.63,
                address = "Hanne-Hiob-Straße 4",
                city = "Munich",
                countryCode = "DE",
                phone = null,
                email = null,
                url = null,
                instagram = null,
                layoutName = "Original",
                layoutId = 1,
                sizeLabel = "12x12",
                productSizeId = 10,
                accessType = AccessType.UNKNOWN,
                adjustability = Adjustability.ADJUSTABLE,
                fixedAngle = null,
                frameMaker = null,
                boardBrand = BoardBrand.KILTER,
                alternateSearchTerms = listOf("München"),
            )
        )
    ).single()

    @Test
    fun `venue search is accent-insensitive and uses localized aliases`() {
        val results = searchBoardMap("munchen", listOf(munichVenue), emptyList(), german = true)
        assertEquals(munichVenue, (results.single() as MapSearchResult.Venue).venue)
    }

    @Test
    fun `place aliases find a city when no venue carries that spelling`() {
        val place = MapPlace("Munich", "DE", 48.14, 11.58, "Bavaria", listOf("München"), "München")
        val results = searchBoardMap("münchen", emptyList(), listOf(place), german = true)
        assertEquals(place, (results.single() as MapSearchResult.Place).place)
    }

    @Test
    fun `one-character queries do not scan the full place index`() {
        assertTrue(searchBoardMap("m", listOf(munichVenue), emptyList()).isEmpty())
    }

    @Test
    fun `country names from the host make a venue findable by its country`() {
        val results = searchBoardMap("deutschland", listOf(munichVenue), emptyList(), mapOf("DE" to "Deutschland"))
        assertEquals(munichVenue, (results.single() as MapSearchResult.Venue).venue)
    }
}
