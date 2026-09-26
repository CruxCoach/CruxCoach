package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.domain.board.BoardBrand

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
        boardBrand: BoardBrand?,
        sessionRole: SessionRole,
        sessionConnecting: Boolean = false,
        /** A playlist uses queue mechanics locally but is not a shared
         * session. Its detail lamp remains a direct board action. */
        localPlaylist: Boolean = false,
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

        if (sessionRole != SessionRole.NONE && !localPlaylist) {
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

        // A retained multi-layer wall is shared mutable controller state. Merely
        // browsing a Quantum climb must not select/replace a slot, so that
        // capability always starts at the visible lamp. Single-projection boards
        // keep automatic dispatch, but the lamp remains available as an explicit
        // re-light action. Automatic delivery is a convenience, not a reason to
        // remove the primary board control from the detail screen. Keeping this
        // branch capability-scoped is the
        // compatibility fence that prevents Quantum UX from changing Kilter,
        // MoonBoard, or Aurora-family delivery semantics.
        val requiresExplicitLayerSelection =
            boardBrand?.supportsIndependentClimbLayers == true
        return BoardDeliveryDecision(
            target = BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = !requiresExplicitLayerSelection &&
                sendMode == BoardSendMode.AUTOMATIC,
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
        countdownRunning: Boolean,
    ): BoardDetailLampMode = when {
        // Mid-countdown the climb is already on its way to the wall.
        countdownRunning -> BoardDetailLampMode.HIDDEN
        decision.showAction && decision.target == BoardDeliveryTarget.SHARED_QUEUE ->
            BoardDetailLampMode.SHARED_QUEUE
        decision.showAction -> BoardDetailLampMode.LIGHT
        boardOwnedByOthers -> BoardDetailLampMode.HIDDEN
        hasDirectPayload && !boardConnected -> BoardDetailLampMode.CONNECT
        else -> BoardDetailLampMode.HIDDEN
    }
}
