package com.cruxcoach.android.boardcell

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BoardCellScopeTest {
    @After fun reset() = BoardCellScopeRegistry.resetForTest()

    @Test fun `identity prefers hardware serial and never name or rssi`() {
        val a = DiscoveredBoard("Gym Wall", "ABC123", 3, "AA:AA:AA:AA:AA:AA", -20, BoardBrand.KILTER)
        val b = a.copy(displayName = "Renamed", address = "BB:BB:BB:BB:BB:BB", rssi = -90)
        assertEquals(PhysicalBoardIdentity.resolve(a), PhysicalBoardIdentity.resolve(b))
    }

    @Test fun `moon fallback uses BLE address not model name`() {
        val a = DiscoveredBoard("MoonBoard", "", 0, "AA:BB:CC:DD:EE:FF", -30, BoardBrand.MOONBOARD)
        val b = a.copy(address = "11:22:33:44:55:66")
        assertNotEquals(PhysicalBoardIdentity.resolve(a), PhysicalBoardIdentity.resolve(b))
    }

    @Test fun `explicit durable binding overrides rotating BLE address`() {
        val a = DiscoveredBoard("MoonBoard", "", 0, "AA:BB:CC:DD:EE:FF", -30, BoardBrand.MOONBOARD)
        val b = a.copy(address = "11:22:33:44:55:66")
        assertEquals(PhysicalBoardIdentity.resolve(a, "gym-wall-7"),
            PhysicalBoardIdentity.resolve(b, "gym-wall-7"))
    }

    @Test fun `legacy unscoped nearby becomes unsafe with two boards`() {
        BoardCellScopeRegistry.observe(PhysicalBoardId("board-a"))
        assertTrue(BoardCellScopeRegistry.acceptsLegacyUnscoped())
        BoardCellScopeRegistry.observe(PhysicalBoardId("board-b"))
        assertFalse(BoardCellScopeRegistry.acceptsLegacyUnscoped())
    }

    @Test fun `snapshot fanout supports forty cell members`() = runTest {
        class Link : AuthenticatedMeshLink {
            override val localNpub = "n00"
            val sent = mutableListOf<String>()
            override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
                sent += authenticatedPeerNpub; return true
            }
        }
        val link = Link()
        val transport = BoardCellMeshTransport(link)
        val members = (0 until 40).map { "n%02d".format(it) }.toSet()
        transport.publishSnapshot(BoardCellSnapshot(
            cellId = BoardCellId("cell"), physicalBoardId = PhysicalBoardId("board"),
            epoch = 1, sequence = 0, controllerId = "n00", lineageId = "lineage", members = members,
        ).withComputedHash())
        assertEquals(39, link.sent.distinct().size)
    }
}
