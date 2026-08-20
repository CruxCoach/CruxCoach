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

    @Test
    fun `active radio realm is hidden while membership snapshot is restoring`() {
        val own = mesh("own").copy(matchesActiveRealm = true)
        val foreign = mesh("foreign")

        assertEquals(listOf(foreign), visibleNearbyMeshes(listOf(own, foreign), null))
    }

    @Test
    fun `matching realm remains selectable when the retained membership is frozen`() {
        val restartedBoard = mesh("own").copy(matchesActiveRealm = true)

        assertEquals(
            listOf(restartedBoard),
            visibleNearbyMeshes(
                listOf(restartedBoard),
                activeCellId = "own",
                allowActiveRealmRejoin = true,
            ),
        )
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
