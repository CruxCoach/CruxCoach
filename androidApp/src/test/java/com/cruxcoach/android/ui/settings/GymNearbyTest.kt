package com.cruxcoach.android.ui.settings

import com.cruxcoach.android.ui.map.mapBoard
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GymNearbyTest {
    // Munich centre.
    private val lat = 48.137
    private val lng = 11.575

    @Test
    fun `nearest supported gyms first, one entry per gym, within 100 km`() {
        val rows = listOf(
            mapBoard("far", 48.90, 11.575, name = "Ingolstadt Gym"),            // ~85 km
            mapBoard("k1", 48.14, 11.58, name = "City Boulder"),               // a few hundred m
            mapBoard("m1", 48.14, 11.58, name = "City Boulder", brand = BoardBrand.MOONBOARD),
            mapBoard("t1", 48.20, 11.60, name = "North Gym", brand = BoardBrand.TENSION),
            mapBoard("a1", 48.15, 11.57, name = "Info Only", brand = BoardBrand.AURORA), // map-only brand
            mapBoard("berlin", 52.52, 13.40, name = "Berlin Gym"),             // ~500 km
        )
        val gyms = nearbyGyms(rows, lat, lng)
        assertEquals(listOf("City Boulder", "North Gym", "Ingolstadt Gym"), gyms.map { it.rows.first().name })
        assertEquals(setOf("k1", "m1"), gyms.first().rows.map { it.id }.toSet())
        assertTrue(gyms.first().meters < 1_000)
        assertTrue(gyms.zipWithNext().all { (a, b) -> a.meters <= b.meters })
    }

    @Test
    fun `limit caps the list`() {
        val rows = (0 until 30).map { i -> mapBoard("g$i", lat + i * 0.01, lng, name = "Gym $i") }
        assertEquals(10, nearbyGyms(rows, lat, lng).size)
        assertEquals(3, nearbyGyms(rows, lat, lng, limit = 3).size)
    }

    @Test
    fun `nothing nearby gives an empty list`() {
        assertTrue(nearbyGyms(listOf(mapBoard("berlin", 52.52, 13.40)), lat, lng).isEmpty())
    }
}
