package com.cruxcoach.android.data

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardRelayPolicyTest {
    @Test
    fun `relay follows the current Aurora advertising observation`() {
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board(advertisesWhileConnected = false)),
        )
        assertEquals(
            BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED,
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
    fun `MoonBoard does not use Aurora frame relay`() {
        assertEquals(
            BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
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
