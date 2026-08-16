package com.cruxcoach.android.ui.fips

import com.cruxcoach.android.fips.FipsNearbyMesh
import org.junit.Assert.assertEquals
import org.junit.Test

class FipsNearbyVisibilityTest {
    @Test
    fun `active cell appears only in current mesh section`() {
        val own = mesh("own")
        val foreign = mesh("foreign")

        assertEquals(listOf(foreign), visibleNearbyMeshes(listOf(own, foreign), "own"))
        assertEquals(listOf(own, foreign), visibleNearbyMeshes(listOf(own, foreign), null))
    }

    private fun mesh(cell: String) = FipsNearbyMesh(
        address = cell,
        realmTag = cell,
        cellTag = cell,
        rssi = -40,
        lastSeenMs = 1,
        matchesActiveRealm = false,
        joinableBoardCellId = cell,
        boardName = "Kilter Board",
    )
}
