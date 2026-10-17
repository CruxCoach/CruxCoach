package com.cruxcoach.android.ui.map

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {
    private val munich = MapViewport(47.9, 11.2, 48.4, 12.0, centerLat = 48.14, centerLng = 11.58)

    private val venues = groupIntoVenues(
        listOf(
            mapBoard("center", 48.140, 11.580, name = "Zentrum"),
            mapBoard("east", 48.130, 11.700, name = "Ost", brand = BoardBrand.MOONBOARD),
            mapBoard("west", 48.150, 11.400, name = "West"),
            mapBoard("berlin", 52.52, 13.40, name = "Berlin", city = "Berlin"),
        )
    )

    @Test
    fun `lists only venues in view, nearest to the centre first`() {
        val result = venuesInView(venues, munich)
        assertEquals(3, result.total)
        assertEquals(listOf("Zentrum", "Ost", "West"), result.nearest.map { it.name })
    }

    @Test
    fun `caps the list but keeps the true total`() {
        val result = venuesInView(venues, munich, limit = 2)
        assertEquals(3, result.total)
        assertEquals(2, result.nearest.size)
    }

    @Test
    fun `a view across the antimeridian wraps`() {
        val pacific = MapViewport(-50.0, 170.0, -30.0, -170.0, centerLat = -40.0, centerLng = 180.0)
        assertTrue(pacific.contains(-40.0, 175.0))
        assertTrue(pacific.contains(-40.0, -175.0))
        assertFalse(pacific.contains(-40.0, 0.0))
        assertFalse(pacific.contains(-20.0, 175.0))
    }

    @Test
    fun `nearest boards are framed together with the current centre`() {
        val sea = MapViewport(53.0, 4.0, 55.0, 7.0, centerLat = 54.0, centerLng = 5.5) // North Sea
        val frame = nearestFrame(venues, sea, count = 1)!!
        assertEquals(52.52, frame.south, 1e-9)    // Berlin is the closest
        assertEquals(54.0, frame.north, 1e-9)     // … and the centre stays in view
        assertEquals(5.5, frame.west, 1e-9)
        assertEquals(13.40, frame.east, 1e-9)
    }

    @Test
    fun `distance is roughly right`() {
        val km = distanceMeters(48.137, 11.575, 52.520, 13.405) / 1000
        assertTrue("Munich–Berlin was $km km", km in 500.0..510.0)
    }

    private val places = listOf(
        MapPlace("Vienna", "AT", 48.2, 16.37, null, listOf("Wien"), "Wien"),
        MapPlace("Zürich", "CH", 47.37, 8.54, null, emptyList(), null),
        MapPlace("Berlin", "DE", 52.52, 13.40, null, emptyList(), null),
    )

    private val countries = groupIntoVenues(
        listOf(
            mapBoard("v1", 48.20, 16.37, country = "AT"),
            mapBoard("v2", 47.26, 11.39, country = "AT"),
            mapBoard("z1", 47.37, 8.54, country = "CH"),
        ) + (0 until 40).map { i -> mapBoard("d$i", 47.5 + i * 0.1, 6.0 + i * 0.2, country = "DE") } +
            mapBoard("d-far", 10.0, -60.0, country = "DE") // a mis-geocoded outlier
    )

    @Test
    fun `timezone city decides the country and its boards frame the start view`() {
        val view = guessStartView("Europe/Vienna", "DE", places, countries) as MapStartView.Bounds
        assertEquals(47.26, view.south, 1e-9)
        assertEquals(48.20, view.north, 1e-9)
        assertEquals(11.39, view.west, 1e-9)
        assertEquals(16.37, view.east, 1e-9)
    }

    @Test
    fun `timezone names match places without accents`() {
        val view = guessStartView("Europe/Zurich", null, places, countries) as MapStartView.Bounds
        // One venue: framed with some surroundings instead of a zero-size box.
        assertTrue(view.north - view.south >= 0.6 - 1e-9)
        assertTrue(view.south < 47.37 && view.north > 47.37)
    }

    @Test
    fun `falls back to the locale country and trims outliers`() {
        val view = guessStartView("Etc/UTC", "de", places, countries) as MapStartView.Bounds
        assertTrue("outlier at lat 10 must not stretch the frame", view.south > 40.0)
        assertTrue(view.west > 0.0)
    }

    @Test
    fun `without any country match the whole world shows`() {
        assertEquals(MapStartView.World, guessStartView(null, null, places, countries))
        assertEquals(MapStartView.World, guessStartView("Asia/Tokyo", "JP", places, countries))
    }
}
