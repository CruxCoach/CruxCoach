package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.ble.DiscoveredBoard

internal enum class BoardRelayAvailability {
    AVAILABLE,
    NO_BOARD,
    UNSUPPORTED_PROTOCOL,
    MULTI_CONNECT_NOT_NEEDED,
    CAPACITY_UNKNOWN,
    RELAY_ENDPOINT,
}

/** Capability and ownership rules shared by the relay manager and its UI. */
internal object BoardRelayPolicy {
    fun availability(
        board: DiscoveredBoard?,
    ): BoardRelayAvailability = when {
        board == null -> BoardRelayAvailability.NO_BOARD
        board.isCruxRelay -> BoardRelayAvailability.RELAY_ENDPOINT
        !board.boardBrand.usesAuroraProtocol -> BoardRelayAvailability.UNSUPPORTED_PROTOCOL
        else -> when (BoardControllerProfiles.forBoard(board).connectionCapacity) {
            BoardConnectionCapacity.SINGLE -> BoardRelayAvailability.AVAILABLE
            BoardConnectionCapacity.MULTIPLE -> BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED
            BoardConnectionCapacity.UNKNOWN -> BoardRelayAvailability.CAPACITY_UNKNOWN
        }
    }
}
