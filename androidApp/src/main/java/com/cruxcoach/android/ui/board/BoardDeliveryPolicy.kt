package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole

/** The user-facing route for the primary action on a climb detail. */
internal enum class BoardDeliveryTarget {
    NONE,
    DIRECT_BOARD,
    MESH_BOARD,
    SHARED_QUEUE,
}

internal data class BoardDeliveryDecision(
    val target: BoardDeliveryTarget,
    val dispatchAutomatically: Boolean,
    val showAction: Boolean,
)

/**
 * Keeps direct board control and shared control mutually exclusive.
 *
 * A shared playlist always owns delivery: opening a climb must never add it
 * implicitly, and on a board with a group on it this screen offers no route to
 * the wall at all — the list's lamp is the only one, and Add / Add as next are
 * how a climb gets in front of it.
 */
internal object BoardDeliveryPolicy {
    fun shouldAutoConnectSessionHost(
        newRole: SessionRole,
        previousRole: SessionRole,
        connectionState: ConnectionState,
        boardRoutedByMesh: Boolean = false,
    ): Boolean = newRole == SessionRole.HOST &&
        previousRole != SessionRole.HOST &&
        connectionState == ConnectionState.DISCONNECTED &&
        !boardRoutedByMesh

    fun shouldReleaseBoardForSessionParticipant(
        newRole: SessionRole,
        previousRole: SessionRole,
        connectionState: ConnectionState,
        connectionCapacity: BoardConnectionCapacity,
        connectionPinnedByAnotherFeature: Boolean = false,
    ): Boolean = newRole == SessionRole.PARTICIPANT &&
        previousRole != SessionRole.PARTICIPANT &&
        connectionState != ConnectionState.DISCONNECTED &&
        connectionCapacity == BoardConnectionCapacity.SINGLE &&
        !connectionPinnedByAnotherFeature

    fun resolve(
        sendMode: BoardSendMode,
        sessionRole: SessionRole,
        sessionConnecting: Boolean = false,
        boardConnected: Boolean,
        hasDirectPayload: Boolean,
        connectedViaRelay: Boolean = false,
        connectedViaMesh: Boolean = false,
        /** This device is in an active BoardCell, so the board has a list. */
        boardCellActive: Boolean = false,
    ): BoardDeliveryDecision {
        // A board with a group on it has exactly one way onto the wall — the
        // lamp on the shared list — and exactly one way into the list, which
        // is Add / Add as next on this very screen. A third control here would
        // be a second thing that lights a wall somebody may be climbing on,
        // reachable without ever having seen the group's list.
        if (boardCellActive) {
            return BoardDeliveryDecision(
                target = BoardDeliveryTarget.NONE,
                dispatchAutomatically = false,
                showAction = false,
            )
        }

        // Joining has already handed ownership to the shared-session flow, but
        // the participant GATT command channel is not ready yet. Hide both
        // actions so a tap cannot become a local queue mutation or direct send.
        if (sessionConnecting) {
            return BoardDeliveryDecision(
                target = BoardDeliveryTarget.NONE,
                dispatchAutomatically = false,
                showAction = false,
            )
        }

        if (sessionRole != SessionRole.NONE) {
            return BoardDeliveryDecision(
                target = BoardDeliveryTarget.SHARED_QUEUE,
                dispatchAutomatically = false,
                showAction = true,
            )
        }

        if ((!boardConnected && !connectedViaMesh) || !hasDirectPayload) {
            return BoardDeliveryDecision(
                target = BoardDeliveryTarget.NONE,
                dispatchAutomatically = false,
                showAction = false,
            )
        }

        // Both sides of a relay are multi-connection situations and both are
        // already expressed in [sendMode]: a relay endpoint is classified as
        // MULTIPLE by [BoardControllerProfiles], and hosting resolves to the
        // multi-connection preference in [BoardSendModePolicy]. So the
        // preference decides here, full stop.
        //
        // No second override. The "don't grab a shared wall by swiping" rule
        // lives in that preference's default (EXPLICIT); re-applying it here
        // meant a climber who deliberately switched to AUTOMATIC still got a
        // button, which is not a default any more — it is ignoring them.
        return BoardDeliveryDecision(
            target = if (connectedViaMesh) BoardDeliveryTarget.MESH_BOARD else BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = sendMode == BoardSendMode.AUTOMATIC,
            showAction = sendMode == BoardSendMode.EXPLICIT,
        )
    }
}
