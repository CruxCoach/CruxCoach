package com.cruxcoach.android.data

import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardRelayPolicyTest {
    @Test
    fun `relay is available only for verified single-connect Aurora controllers`() {
        assertEquals(
            BoardRelayAvailability.AVAILABLE,
            BoardRelayPolicy.availability(board(apiLevel = 2)),
        )
        assertEquals(
            BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED,
            BoardRelayPolicy.availability(board(apiLevel = 3)),
        )
        assertEquals(
            BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED,
            BoardRelayPolicy.availability(board(apiLevel = 0)),
        )
    }

    @Test
    fun `MoonBoard does not use Aurora frame relay`() {
        assertEquals(
            BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
            BoardRelayPolicy.availability(board(brand = BoardBrand.MOONBOARD, apiLevel = 0)),
        )
    }

    @Test
    fun `relay endpoint cannot be relayed again`() {
        assertEquals(
            BoardRelayAvailability.RELAY_ENDPOINT,
            BoardRelayPolicy.availability(board(apiLevel = 2, isRelay = true)),
        )
    }

    @Test
    fun `missing board is unavailable`() {
        assertEquals(BoardRelayAvailability.NO_BOARD, BoardRelayPolicy.availability(null))
    }

    private fun board(
        brand: BoardBrand = BoardBrand.KILTER,
        apiLevel: Int,
        isRelay: Boolean = false,
    ) = DiscoveredBoard(
        displayName = brand.displayName,
        serial = "",
        apiLevel = apiLevel,
        address = "00:11:22:33:44:55",
        rssi = -50,
        boardBrand = brand,
        isCruxRelay = isRelay,
    )
}
