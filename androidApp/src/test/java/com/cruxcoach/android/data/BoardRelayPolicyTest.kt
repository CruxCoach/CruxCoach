package com.cruxcoach.android.data

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.boardcell.MeshMembershipTransition
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardRelayPolicyTest {
    @Test
    fun `continued advertising does not suppress relay for a physical board`() {
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board(advertisesWhileConnected = false)),
        )
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board(advertisesWhileConnected = true)),
        )
    }

    /** An unprobed controller is exclusive, and exclusive is what the relay
     *  exists for — offering it must not wait for an observation that may
     *  never arrive. */
    @Test
    fun `relay is offered before any advertising observation`() {
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board()),
        )
    }

    @Test
    fun `MoonBoard supports raw Nordic UART relay`() {
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board(brand = BoardBrand.MOONBOARD)),
        )
    }

    @Test
    fun `relay endpoint cannot be relayed again`() {
        assertEquals(
            BoardRelayAvailability.RELAY_ENDPOINT,
            BoardRelayPolicy.availability(board(isRelay = true)),
        )
    }

    @Test
    fun `missing board is unavailable`() {
        assertEquals(BoardRelayAvailability.NO_BOARD, BoardRelayPolicy.availability(null))
    }

    @Test
    fun `voluntary handover disconnect is not reported as board loss`() {
        assertEquals(
            false,
            BoardRelayPolicy.shouldReportBoardLoss(
                relayStillRequired = false,
                boardDisconnected = true,
                membershipTransition = MeshMembershipTransition.LEAVING,
            ),
        )
    }

    @Test
    fun `unexpected controller disconnect remains visible`() {
        assertEquals(
            true,
            BoardRelayPolicy.shouldReportBoardLoss(
                relayStillRequired = true,
                boardDisconnected = true,
                membershipTransition = MeshMembershipTransition.IDLE,
            ),
        )
    }

    private fun board(
        brand: BoardBrand = BoardBrand.KILTER,
        isRelay: Boolean = false,
        advertisesWhileConnected: Boolean? = null,
    ) = DiscoveredBoard(
        displayName = brand.displayName,
        serial = "",
        apiLevel = 3,
        address = "00:11:22:33:44:55",
        rssi = -50,
        boardBrand = brand,
        isCruxRelay = isRelay,
        advertisesWhileConnected = advertisesWhileConnected,
    )
}
