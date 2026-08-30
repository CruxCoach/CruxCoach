package com.cruxcoach.domain.board

enum class ClimbDeliveryMode { AUTOMATIC, EXPLICIT }

enum class SharedBoardSessionRole { NONE, HOST, PARTICIPANT }

enum class BoardConnectionCapacity { SINGLE, MULTIPLE, UNKNOWN }

enum class BoardDeliveryTarget { NONE, DIRECT_BOARD, SHARED_QUEUE }

data class BoardDeliveryDecision(
    val target: BoardDeliveryTarget,
    val dispatchAutomatically: Boolean,
    val showAction: Boolean,
)

enum class BoardDetailLampMode { HIDDEN, LIGHT, SHARED_QUEUE, CONNECT }

/**
 * Portable policy for the one delivery action shown by climb detail.
 *
 * Shared sessions own delivery and always require an explicit queue action;
 * merely opening detail never mutates that queue. A locally running playlist
 * may use the same queue machinery without becoming a shared-session owner.
 * Independent-layer boards also require an explicit layer choice, while an
 * opted-in automatic mode remains available to single-projection boards.
 */
object ClimbDeliveryPolicy {
    fun shouldAutoConnectSessionHost(
        newRole: SharedBoardSessionRole,
        previousRole: SharedBoardSessionRole,
        connectionState: BoardConnectionState,
    ): Boolean = newRole == SharedBoardSessionRole.HOST &&
        previousRole != SharedBoardSessionRole.HOST &&
        connectionState == BoardConnectionState.DISCONNECTED

    fun shouldReleaseBoardForSessionParticipant(
        newRole: SharedBoardSessionRole,
        previousRole: SharedBoardSessionRole,
        connectionState: BoardConnectionState,
        connectionCapacity: BoardConnectionCapacity,
        connectionPinnedByAnotherFeature: Boolean = false,
    ): Boolean = newRole == SharedBoardSessionRole.PARTICIPANT &&
        previousRole != SharedBoardSessionRole.PARTICIPANT &&
        connectionState != BoardConnectionState.DISCONNECTED &&
        connectionCapacity == BoardConnectionCapacity.SINGLE &&
        !connectionPinnedByAnotherFeature

    fun resolve(
        sendMode: ClimbDeliveryMode,
        boardBrand: BoardBrand?,
        sessionRole: SharedBoardSessionRole,
        sessionConnecting: Boolean = false,
        localPlaylist: Boolean = false,
        boardConnected: Boolean,
        hasDirectPayload: Boolean,
    ): BoardDeliveryDecision {
        if (sessionConnecting) return noDelivery()
        if (sessionRole != SharedBoardSessionRole.NONE && !localPlaylist) {
            return BoardDeliveryDecision(
                target = BoardDeliveryTarget.SHARED_QUEUE,
                dispatchAutomatically = false,
                showAction = true,
            )
        }
        if (!boardConnected || !hasDirectPayload) return noDelivery()

        val requiresExplicitLayerSelection =
            boardBrand?.supportsIndependentClimbLayers == true
        return BoardDeliveryDecision(
            target = BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = !requiresExplicitLayerSelection &&
                sendMode == ClimbDeliveryMode.AUTOMATIC,
            showAction = requiresExplicitLayerSelection ||
                sendMode == ClimbDeliveryMode.EXPLICIT,
        )
    }

    fun lampMode(
        decision: BoardDeliveryDecision,
        hasDirectPayload: Boolean,
        boardConnected: Boolean,
        boardOwnedByOthers: Boolean,
        countdownRunning: Boolean,
    ): BoardDetailLampMode = when {
        // A bounded countdown already explains that delivery is in progress.
        countdownRunning -> BoardDetailLampMode.HIDDEN
        decision.showAction && decision.target == BoardDeliveryTarget.SHARED_QUEUE ->
            BoardDetailLampMode.SHARED_QUEUE
        decision.showAction -> BoardDetailLampMode.LIGHT
        // Never offer a competing physical-board connection while a group owns it.
        boardOwnedByOthers -> BoardDetailLampMode.HIDDEN
        hasDirectPayload && !boardConnected -> BoardDetailLampMode.CONNECT
        else -> BoardDetailLampMode.HIDDEN
    }

    private fun noDelivery() = BoardDeliveryDecision(
        target = BoardDeliveryTarget.NONE,
        dispatchAutomatically = false,
        showAction = false,
    )
}
