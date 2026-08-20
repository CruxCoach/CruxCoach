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
 * Keeps direct board control and shared-session control mutually exclusive.
 * A session always owns delivery: opening a climb must never add it implicitly,
 * while an explicit action routes it to the host through the shared queue.
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
    ): BoardDeliveryDecision {
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
}
