package com.cruxcoach.app.map

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapVenueTest {

    @Test
    fun `venueKey rounds to four decimals`() {
        assertEquals(venueKey(52.51234, 13.40001), venueKey(52.512344, 13.400009))
        // ~11 m apart in latitude is still one venue; ~22 m is two.
        assertEquals(venueKey(52.5, 13.4), venueKey(52.50004, 13.4))
        assertTrue(venueKey(52.5, 13.4) != venueKey(52.5002, 13.4))
    }

    @Test
    fun `co-located boards collapse into one venue`() {
        val venues = groupIntoVenues(
            listOf(
                location("moon", name = "Boulderwelt MoonBoard", lat = 52.5, lng = 13.4,
                    brand = BoardBrand.MOONBOARD, city = null, country = "??"),
                location("kilter", name = "Boulderwelt", lat = 52.50003, lng = 13.40002,
                    brand = BoardBrand.KILTER, city = "Berlin", country = "DE"),
            ),
        )
        val venue = venues.single()
        assertEquals(2, venue.boards.size)
        assertTrue(venue.isMultiBoard)
        // Kilter is the representative even though the MoonBoard row came first.
        assertEquals("Boulderwelt", venue.name)
        // City and country are filled from the first board that has a usable value.
        assertEquals("Berlin", venue.city)
        assertEquals("DE", venue.countryCode)
        assertEquals(VenueBrandKey.MULTI, venue.brandKey)
    }

    @Test
    fun `a blank name never becomes the representative`() {
        val venue = groupIntoVenues(
            listOf(
                location("blank", name = "   ", lat = 1.0, lng = 1.0),
                location("named", name = "Real Gym", lat = 1.0, lng = 1.0),
            ),
        ).single()
        assertEquals("Real Gym", venue.name)
    }

    @Test
    fun `brandKey buckets single-family venues`() {
        fun key(vararg brands: BoardBrand) = groupIntoVenues(
            brands.mapIndexed { i, b -> location("b$i", lat = 5.0, lng = 5.0, brand = b) },
        ).single().brandKey

        assertEquals(VenueBrandKey.KILTER, key(BoardBrand.KILTER, BoardBrand.KILTER))
        assertEquals(VenueBrandKey.MOONBOARD, key(BoardBrand.MOONBOARD))
        assertEquals(VenueBrandKey.OTHER, key(BoardBrand.TENSION))
        assertEquals(VenueBrandKey.MULTI, key(BoardBrand.KILTER, BoardBrand.TENSION))
    }

    @Test
    fun `hasHomewall only reacts to a Kilter homewall layout`() {
        fun venue(vararg rows: com.cruxcoach.data.repository.BoardLocation) =
            groupIntoVenues(rows.toList()).single()

        assertTrue(venue(location("h", layoutId = KILTER_HOMEWALL_LAYOUT)).hasHomewall)
        assertFalse(venue(location("o", layoutId = KILTER_ORIGINAL_LAYOUT)).hasHomewall)
        assertFalse(
            venue(location("t", brand = BoardBrand.TENSION, layoutId = KILTER_HOMEWALL_LAYOUT)).hasHomewall,
        )
    }

    @Test
    fun `merge enriches canonical rows from the synced dataset`() {
        val canonical = listOf(location("pages-1", lat = 10.0, lng = 10.0, url = null, phone = null))
        val dynamic = listOf(
            location("db-1", lat = 10.00002, lng = 10.0, url = "https://gym.example", phone = "+49"),
        )
        val merged = mergeCanonicalMapLocations(canonical, dynamic)
        assertEquals(1, merged.size)
        assertEquals("pages-1", merged.single().id)
        assertEquals("https://gym.example", merged.single().url)
        assertEquals("+49", merged.single().phone)
    }

    @Test
    fun `merge appends only installations the snapshot does not describe`() {
        val canonical = listOf(location("pages-kilter", lat = 10.0, lng = 10.0))
        val dynamic = listOf(
            location("db-kilter", lat = 10.0, lng = 10.0),
            location("db-moon", lat = 10.0, lng = 10.0, brand = BoardBrand.MOONBOARD),
            location("db-elsewhere", lat = 40.0, lng = 40.0),
        )
        assertEquals(
            listOf("pages-kilter", "db-moon", "db-elsewhere"),
            mergeCanonicalMapLocations(canonical, dynamic).map { it.id },
        )
    }

    @Test
    fun `merge degenerates to the non-empty side`() {
        val rows = listOf(location("only"))
        assertEquals(rows, mergeCanonicalMapLocations(emptyList(), rows))
        assertEquals(rows, mergeCanonicalMapLocations(rows, emptyList()))
        assertEquals(emptyList(), mergeCanonicalMapLocations(emptyList(), emptyList()))
    }
}
