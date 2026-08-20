package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.fips.FipsNearbyMesh
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyBoardVisibilityTest {
    @Test
    fun `mesh capable CruxCoach hides relay endpoints`() {
        val relay = board("Kilter Board", "AA", relay = true)
        val physical = board("MoonBoard", "BB")

        assertEquals(listOf(physical), visibleBoardsForPlatform(listOf(relay, physical), true))
    }

    @Test
    fun `Android 9 exposes relay fallback and hides meshes`() {
        val relay = board("Kilter Board", "AA", relay = true)

        assertEquals(listOf(relay), visibleBoardsForPlatform(listOf(relay), false))
        assertEquals(emptyList<FipsNearbyMesh>(), visibleMeshesForPlatform(listOf(mesh("cell")), false))
    }

    @Test
    fun `relay for active mesh is hidden but unrelated relay remains`() {
        val ownRelay = board("Kilter Board Original", "AA", relay = true)
        val otherRelay = board("MoonBoard 2019", "BB", relay = true)
        val physical = board("Other Kilter Board", "CC")

        assertEquals(
            listOf(otherRelay, physical),
            visibleStandaloneBoards(
                listOf(ownRelay, otherRelay, physical),
                nearbyMeshes = emptyList(),
                activeBoardCellId = "active-cell",
                activeMeshBoardName = "Kilter Board Original",
            ),
        )
    }

    @Test
    fun `trimmed active relay label is still associated`() {
        val relay = board("Kilter Board Orig", "AA", relay = true)

        assertEquals(
            emptyList<DiscoveredBoard>(),
            visibleStandaloneBoards(
                listOf(relay),
                nearbyMeshes = emptyList(),
                activeBoardCellId = "active-cell",
                activeMeshBoardName = "Kilter Board Original",
            ),
        )
    }

    @Test
    fun `relay remains visible when no mesh is joined`() {
        val relay = board("Kilter Board", "AA", relay = true)

        assertEquals(
            listOf(relay),
            visibleStandaloneBoards(listOf(relay), emptyList(), null, "Kilter Board"),
        )
    }

    private fun board(name: String, address: String, relay: Boolean = false) = DiscoveredBoard(
        displayName = name,
        serial = "",
        apiLevel = 3,
        address = address,
        rssi = -40,
        isCruxRelay = relay,
    )

    private fun mesh(cell: String) = FipsNearbyMesh(
        address = cell,
        realmTag = cell,
        cellTag = cell,
        rssi = -40,
        lastSeenMs = 1,
        matchesActiveRealm = false,
        joinableBoardCellId = cell,
    )
}
