package com.cruxcoach.android.ui.map

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapVenueTest {

    private fun loc(
        id: String,
        lat: Double,
        lng: Double,
        name: String = "Gym $id",
        brand: BoardBrand = BoardBrand.KILTER,
        layoutId: Int? = 1,
        city: String? = "Munich",
        country: String = "DE",
    ) = BoardLocation(
        id = id, name = name, lat = lat, lng = lng,
        address = null, city = city, countryCode = country,
        phone = null, email = null, url = null, instagram = null,
        layoutName = null, layoutId = layoutId,
        sizeLabel = null, productSizeId = null,
        accessType = AccessType.PUBLIC, adjustability = Adjustability.ADJUSTABLE,
        fixedAngle = null, frameMaker = null, boardBrand = brand,
    )

    @Test
    fun `empty input yields no venues`() {
        assertTrue(groupIntoVenues(emptyList()).isEmpty())
    }

    @Test
    fun `boards within ~11m collapse into one venue`() {
        // 0.00005° ≈ 5.5 m apart → same venueKey.
        val venues = groupIntoVenues(
            listOf(
                loc("kilter", 48.13700, 11.57500, brand = BoardBrand.KILTER),
                loc("moon", 48.13703, 11.57498, brand = BoardBrand.MOONBOARD, layoutId = 5),
            )
        )
        assertEquals(1, venues.size)
        val v = venues.first()
        assertEquals(2, v.boards.size)
        assertTrue(v.isMultiBoard)
        assertEquals(VenueBrandKey.MULTI, v.brandKey)
        assertEquals(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), v.brands)
    }

    @Test
    fun `boards far apart stay separate venues`() {
        val venues = groupIntoVenues(
            listOf(
                loc("a", 48.1370, 11.5750),
                loc("b", 52.5200, 13.4050), // Berlin — different venueKey
            )
        )
        assertEquals(2, venues.size)
    }

    @Test
    fun `representative prefers Kilter and fills country from a co-located board`() {
        // MoonBoard row has no country (??); the Kilter row at the same spot
        // should supply the venue name + country.
        val venues = groupIntoVenues(
            listOf(
                loc("moon", 48.13700, 11.57500, name = "MoonGym", brand = BoardBrand.MOONBOARD, layoutId = 5, city = null, country = "??"),
                loc("kilter", 48.13701, 11.57501, name = "Boulderwelt", brand = BoardBrand.KILTER, city = "Munich", country = "DE"),
            )
        )
        assertEquals(1, venues.size)
        val v = venues.first()
        assertEquals("Boulderwelt", v.name) // Kilter wins as representative
        assertEquals("DE", v.countryCode)   // filled from the Kilter row, not "??"
        assertEquals("Munich", v.city)
        // Boards ordered Kilter-first.
        assertEquals(BoardBrand.KILTER, v.boards.first().boardBrand)
    }

    @Test
    fun `single Kilter venue has KILTER brand key`() {
        val v = groupIntoVenues(listOf(loc("a", 48.1370, 11.5750))).single()
        assertEquals(VenueBrandKey.KILTER, v.brandKey)
        assertTrue(!v.isMultiBoard)
    }

    @Test
    fun `single MoonBoard venue has MOONBOARD brand key`() {
        val v = groupIntoVenues(
            listOf(loc("m", 48.1370, 11.5750, brand = BoardBrand.MOONBOARD, layoutId = 5))
        ).single()
        assertEquals(VenueBrandKey.MOONBOARD, v.brandKey)
    }

    @Test
    fun `canonical snapshot suppresses dynamic duplicates but keeps new venues`() {
        val canonical = loc("canonical", 48.1370, 11.5750)
        val duplicate = loc("dynamic-duplicate", 48.13701, 11.57501).copy(phone = "+49 89 123")
        val newVenue = loc("dynamic-new", 52.5200, 13.4050)
        val merged = mergeCanonicalMapLocations(listOf(canonical), listOf(duplicate, newVenue))
        assertEquals(listOf("canonical", "dynamic-new"), merged.map { it.id })
        assertEquals("+49 89 123", merged.first().phone)
    }
}
