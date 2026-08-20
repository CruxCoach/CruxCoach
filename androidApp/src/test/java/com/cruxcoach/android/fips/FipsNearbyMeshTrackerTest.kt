package com.cruxcoach.android.fips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsNearbyMeshTrackerTest {
    @Test
    fun `updates one advertisement and keeps current mesh first`() {
        val tracker = FipsNearbyMeshTracker(ttlMs = 1_000)
        tracker.record(mesh("foreign", "cell-a", -40, 100, current = false))
        tracker.record(mesh("own", "cell-b", -80, 110, current = true))
        val result = tracker.record(mesh("foreign", "cell-a", -55, 120, current = false))

        assertEquals(2, result.size)
        assertTrue(result.first().matchesActiveRealm)
        assertEquals(-55, result.last().rssi)
    }

    @Test
    fun `same address may advertise a different mesh and stale observations expire`() {
        val tracker = FipsNearbyMeshTracker(ttlMs = 100)
        tracker.record(mesh("realm-a", "cell-a", -60, 0, address = "AA"))
        val both = tracker.record(mesh("realm-b", "cell-b", -65, 50, address = "AA"))
        assertEquals(2, both.size)

        val fresh = tracker.prune(120)
        assertEquals(listOf("realm-b"), fresh.map { it.realmTag })
    }

    @Test
    fun `several advertisers of one mesh produce one named board card`() {
        val tracker = FipsNearbyMeshTracker(ttlMs = 1_000)
        tracker.record(mesh("realm-a", "cell-a", -60, 10, address = "AA").copy(
            boardName = "MoonBoard", joinableBoardCellId = "cell-id", psm = 129,
        ))
        val result = tracker.record(mesh("realm-a", "cell-a", -45, 20, address = "BB"))

        assertEquals(1, result.size)
        assertEquals("MoonBoard", result.single().boardName)
        assertEquals("cell-id", result.single().joinableBoardCellId)
        assertEquals(129, result.single().psm)
    }

    @Test
    fun `ended mesh expires without another advertisement`() {
        val tracker = FipsNearbyMeshTracker(ttlMs = 8_000)
        tracker.record(mesh("realm-a", "cell-a", -45, 1_000).copy(
            joinableBoardCellId = "cell-id",
        ))

        assertEquals(1, tracker.prune(8_999).size)
        assertTrue(tracker.prune(9_001).isEmpty())
    }

    @Test
    fun `default ttl survives an observed low power scan gap`() {
        val tracker = FipsNearbyMeshTracker()
        tracker.record(mesh("realm-a", "cell-a", -45, 1_000))

        assertEquals(1, tracker.prune(14_500).size)
        assertTrue(tracker.prune(21_001).isEmpty())
    }

    private fun mesh(
        realm: String,
        cell: String,
        rssi: Int,
        seen: Long,
        current: Boolean = false,
        address: String = realm,
    ) = FipsNearbyMesh(address, realm, cell, rssi, seen, current)
}
