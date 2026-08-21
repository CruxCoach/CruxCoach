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

/** What the middle seat of the climb detail's action dock is, right now. */
internal enum class BoardDetailLampMode {
    /** No board action belongs on this climb page at all. */
    HIDDEN,
    /** Light the climb on the board this device drives. */
    LIGHT,
    /** Put the climb on the shared session queue that drives the board. */
    SHARED_QUEUE,
    /** Nothing is connected yet, and this climb could go on a wall. */
    CONNECT,
}

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

        // Detail browsing is never a board command. The user can swipe, open a
        // deep link or reconnect without replacing what somebody is climbing.
        // Every directly projected climb now starts at the visible lamp. The
        // legacy preference is still consumed by explicit playlist/playback
        // flows, but it no longer hides this action or dispatches page changes.
        return BoardDeliveryDecision(
            target = if (connectedViaMesh) BoardDeliveryTarget.MESH_BOARD else BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = false,
            showAction = true,
        )
    }

    /**
     * Turns a delivery decision into the one control the detail dock shows.
     *
     * The distinction that matters is between the two ways [resolve] says no.
     * "Nothing is connected" is an invitation — the wall is free, the climb
     * would fit on it, connect one. "Somebody else owns this board" is not:
     * offering to connect there would be offering to take a wall a group is
     * climbing on, one tap away from a page that never showed their list.
     */
    fun lampMode(
        decision: BoardDeliveryDecision,
        hasDirectPayload: Boolean,
        boardConnected: Boolean,
        /** A BoardCell group or a joining shared session owns delivery. */
        boardOwnedByOthers: Boolean,
    ): BoardDetailLampMode = when {
        decision.showAction && decision.target == BoardDeliveryTarget.SHARED_QUEUE ->
            BoardDetailLampMode.SHARED_QUEUE
        decision.showAction -> BoardDetailLampMode.LIGHT
        boardOwnedByOthers -> BoardDetailLampMode.HIDDEN
        hasDirectPayload && !boardConnected -> BoardDetailLampMode.CONNECT
        else -> BoardDetailLampMode.HIDDEN
    }
}
