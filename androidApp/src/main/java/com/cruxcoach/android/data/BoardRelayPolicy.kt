package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ble.BoardControllerProfiles
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.boardcell.MeshMembershipTransition

internal enum class BoardRelayAvailability {
    AVAILABLE,
    NO_BOARD,
    UNSUPPORTED_PROTOCOL,
    MULTI_CONNECT_NOT_NEEDED,
    RELAY_ENDPOINT,
}

/** Capability and ownership rules shared by the relay manager and its UI. */
internal object BoardRelayPolicy {
    fun availability(
        board: DiscoveredBoard?,
    ): BoardRelayAvailability = when {
        board == null -> BoardRelayAvailability.NO_BOARD
        board.isCruxRelay -> BoardRelayAvailability.RELAY_ENDPOINT
        // Physical controllers are relayable regardless of family or inferred
        // capacity. Continued
        // advertising cannot prove that a second GATT central is accepted,
        // and an exclusive board is exactly what the relay is for.
        else -> when (BoardControllerProfiles.forBoard(board).connectionCapacity) {
            BoardConnectionCapacity.MULTIPLE -> BoardRelayAvailability.MULTI_CONNECT_NOT_NEEDED
            BoardConnectionCapacity.SINGLE,
            BoardConnectionCapacity.UNKNOWN -> BoardRelayAvailability.AVAILABLE
        }
    }

    /** A disconnect is user-visible only while this device still owns relay
     * infrastructure and is not deliberately leaving. Re-read both values
     * after the asynchronous relay shutdown so a handover cannot be reported
     * as an unexpected board loss from an older combined-flow emission. */
    fun shouldReportBoardLoss(
        relayStillRequired: Boolean,
        boardDisconnected: Boolean,
        membershipTransition: MeshMembershipTransition,
    ): Boolean = relayStillRequired && boardDisconnected &&
        membershipTransition != MeshMembershipTransition.LEAVING
}
