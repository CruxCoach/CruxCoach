package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole

/** The user-facing route for the primary action on a climb detail. */
internal enum class BoardDeliveryTarget {
    NONE,
    DIRECT_BOARD,
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
    ): Boolean = newRole == SessionRole.HOST &&
        previousRole != SessionRole.HOST &&
        connectionState == ConnectionState.DISCONNECTED

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

        if (!boardConnected || !hasDirectPayload) {
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
            target = BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = sendMode == BoardSendMode.AUTOMATIC,
            showAction = sendMode == BoardSendMode.EXPLICIT,
        )
    }
}
