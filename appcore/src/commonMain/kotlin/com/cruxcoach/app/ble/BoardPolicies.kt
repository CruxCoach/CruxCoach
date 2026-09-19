package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, SENDING }

enum class BoardConnectionCapacity { SINGLE, MULTIPLE, UNKNOWN }

enum class BoardProjectionLifetime { RETAINED_AFTER_DISCONNECT, UNTIL_LAST_CONNECTION }

enum class BoardSendMode { AUTOMATIC, EXPLICIT }

enum class SessionRole { NONE, HOST, PARTICIPANT }

data class BoardControllerProfile(
    val connectionCapacity: BoardConnectionCapacity,
    val projectionLifetime: BoardProjectionLifetime,
    val relaySupported: Boolean,
)

/** Every physical controller counts as exclusive until observed otherwise. */
object BoardControllerProfiles {
    fun forBoard(board: DiscoveredBoard?): BoardControllerProfile = resolve(
        brand = board?.boardBrand,
        isCruxRelay = board?.isCruxRelay == true,
        advertisesWhileConnected = board?.advertisesWhileConnected,
    )

    fun resolve(
        brand: BoardBrand?,
        isCruxRelay: Boolean = false,
        advertisesWhileConnected: Boolean? = null,
    ): BoardControllerProfile {
        if (isCruxRelay) {
            return BoardControllerProfile(
                BoardConnectionCapacity.MULTIPLE,
                BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT,
                relaySupported = false,
            )
        }
        val capacity = if (advertisesWhileConnected == true) {
            BoardConnectionCapacity.MULTIPLE
        } else {
            BoardConnectionCapacity.SINGLE
        }
        return BoardControllerProfile(
            connectionCapacity = capacity,
            projectionLifetime = if (brand == BoardBrand.MOONBOARD) {
                BoardProjectionLifetime.UNTIL_LAST_CONNECTION
            } else {
                BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT
            },
            relaySupported = brand?.isInteractive == true && capacity == BoardConnectionCapacity.SINGLE,
        )
    }
}

/**
 * Stable identity for one physical board. Serials are preferred; on iOS the
 * fallback is the CBPeripheral identifier, which is stable per device only, so
 * a `ble:` id must never be shared between installations.
 */
object PhysicalBoardIdentity {
    /** Null when the board has neither a serial, a persistent binding nor an identifier. */
    fun resolve(board: DiscoveredBoard, persistentFallback: String? = null): String? {
        val brand = board.boardBrand.name.lowercase()
        return when {
            board.serial.isNotBlank() -> "$brand:serial:${board.serial.lowercase()}"
            !persistentFallback.isNullOrBlank() -> "crux:$persistentFallback"
            board.identifier.isNotBlank() -> "$brand:ble:${board.identifier.uppercase()}"
            else -> null
        }
    }
}

enum class BoardConnectFlow { DISCOVER, DIRECT_THEN_DISCOVER }

object BoardConnectFlowPolicy {
    /**
     * Android tries the remembered controller first only where scanning costs
     * the location permission. An iOS scan costs nothing beyond the Bluetooth
     * permission itself, so the host passes false and always discovers.
     */
    fun initialFlow(hasRememberedController: Boolean, scanRequiresLocationAccess: Boolean = false): BoardConnectFlow =
        when {
            !scanRequiresLocationAccess -> BoardConnectFlow.DISCOVER
            hasRememberedController -> BoardConnectFlow.DIRECT_THEN_DISCOVER
            else -> BoardConnectFlow.DISCOVER
        }

    /** Exactly one board in range is not a choice; anything else stays one. */
    fun <T> autoConnectTarget(candidates: List<T>): T? = candidates.singleOrNull()
}

object BoardProjectionPolicy {
    fun projectionSurvivesDisconnect(brand: BoardBrand?): Boolean = brand != BoardBrand.MOONBOARD

    fun shouldReleaseBoardAfterHosting(
        hasSuccessor: Boolean,
        projectionSurvivesDisconnect: Boolean,
        connectionCapacity: BoardConnectionCapacity,
        pinnedByAnotherFeature: Boolean = false,
    ): Boolean = connectionCapacity == BoardConnectionCapacity.SINGLE &&
        !pinnedByAnotherFeature &&
        (hasSuccessor || projectionSurvivesDisconnect)

    /** Keep a projected MoonBoard climb alive by retaining the link. */
    fun shouldArmIdleDisconnect(
        seconds: Int,
        connectionState: ConnectionState,
        explicitlySuppressed: Boolean,
        connectionCapacity: BoardConnectionCapacity,
        projectionSurvivesDisconnect: Boolean,
    ): Boolean = seconds > 0 &&
        connectionState == ConnectionState.CONNECTED &&
        !explicitlySuppressed &&
        connectionCapacity == BoardConnectionCapacity.SINGLE &&
        projectionSurvivesDisconnect

    fun hasSendablePayload(brand: BoardBrand?, holdCount: Int, frames: String?): Boolean = when (brand) {
        BoardBrand.MOONBOARD -> !frames.isNullOrBlank()
        null -> false
        else -> holdCount > 0
    }
}

enum class BoardDeliveryTarget { NONE, DIRECT_BOARD, SHARED_QUEUE }

data class BoardDeliveryDecision(
    val target: BoardDeliveryTarget,
    val dispatchAutomatically: Boolean,
    val showAction: Boolean,
)

enum class BoardDetailLampMode { HIDDEN, LIGHT, SHARED_QUEUE, CONNECT }

object BoardDeliveryPolicy {
    fun resolve(
        sendMode: BoardSendMode,
        boardBrand: BoardBrand?,
        sessionRole: SessionRole,
        sessionConnecting: Boolean = false,
        localPlaylist: Boolean = false,
        boardConnected: Boolean,
        hasDirectPayload: Boolean,
    ): BoardDeliveryDecision {
        if (sessionConnecting) return BoardDeliveryDecision(BoardDeliveryTarget.NONE, false, false)
        if (sessionRole != SessionRole.NONE && !localPlaylist) {
            return BoardDeliveryDecision(BoardDeliveryTarget.SHARED_QUEUE, false, true)
        }
        if (!boardConnected || !hasDirectPayload) {
            return BoardDeliveryDecision(BoardDeliveryTarget.NONE, false, false)
        }
        // A retained multi-layer wall is shared controller state: browsing a
        // Quantum climb must never select or replace a slot by itself.
        val requiresExplicitLayerSelection = boardBrand?.supportsIndependentClimbLayers == true
        return BoardDeliveryDecision(
            target = BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = !requiresExplicitLayerSelection && sendMode == BoardSendMode.AUTOMATIC,
            showAction = true,
        )
    }

    fun lampMode(
        decision: BoardDeliveryDecision,
        hasDirectPayload: Boolean,
        boardConnected: Boolean,
        boardOwnedByOthers: Boolean,
        countdownRunning: Boolean,
    ): BoardDetailLampMode = when {
        countdownRunning -> BoardDetailLampMode.HIDDEN
        decision.showAction && decision.target == BoardDeliveryTarget.SHARED_QUEUE -> BoardDetailLampMode.SHARED_QUEUE
        decision.showAction -> BoardDetailLampMode.LIGHT
        boardOwnedByOthers -> BoardDetailLampMode.HIDDEN
        hasDirectPayload && !boardConnected -> BoardDetailLampMode.CONNECT
        else -> BoardDetailLampMode.HIDDEN
    }
}

object BoardSendModePolicy {
    fun resolve(
        connectionCapacity: BoardConnectionCapacity,
        singleConnectionMode: BoardSendMode,
        multiConnectionMode: BoardSendMode,
        hostingForOthers: Boolean = false,
    ): BoardSendMode = when {
        hostingForOthers -> multiConnectionMode
        else -> when (connectionCapacity) {
            BoardConnectionCapacity.SINGLE -> singleConnectionMode
            BoardConnectionCapacity.MULTIPLE -> multiConnectionMode
            BoardConnectionCapacity.UNKNOWN ->
                if (singleConnectionMode == multiConnectionMode) singleConnectionMode else BoardSendMode.EXPLICIT
        }
    }

    fun shouldAutoSendAfterCapacityResolution(
        previousCapacity: BoardConnectionCapacity,
        currentCapacity: BoardConnectionCapacity,
        previousResolvedMode: BoardSendMode,
        resolvedMode: BoardSendMode,
    ): Boolean = previousCapacity != currentCapacity &&
        previousResolvedMode != BoardSendMode.AUTOMATIC &&
        resolvedMode == BoardSendMode.AUTOMATIC
}
