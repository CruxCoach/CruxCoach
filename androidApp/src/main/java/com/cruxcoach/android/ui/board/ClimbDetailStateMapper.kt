package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BoardDeliveryDecision
import com.cruxcoach.domain.board.BoardDeliveryTarget
import com.cruxcoach.domain.board.ClimbDetailDeliveryState
import com.cruxcoach.domain.board.ClimbDetailIdentity
import com.cruxcoach.domain.board.ClimbDetailScreenState

/** Projects the Android detail orchestrator into the portable screen contract. */
fun ClimbDetailState.toPortableState(
    deliveryDecision: BoardDeliveryDecision = BoardDeliveryDecision(
        target = BoardDeliveryTarget.NONE,
        dispatchAutomatically = false,
        showAction = false,
    ),
    sessionOwned: Boolean = false,
): ClimbDetailScreenState {
    if (isLoading) return ClimbDetailScreenState.Loading
    logbookOnly?.let { fallback ->
        return ClimbDetailScreenState.LogbookOnly(
            climbUuid = fallback.uuid,
            loggedAscentCount = fallback.ascents.size,
        )
    }
    issue?.let { return ClimbDetailScreenState.Error(it) }
    val loadedClimb = climb ?: return ClimbDetailScreenState.Loading

    return ClimbDetailScreenState.Content(
        identity = ClimbDetailIdentity(
            uuid = loadedClimb.uuid,
            name = loadedClimb.name,
            setterName = setterProfile?.displayName ?: loadedClimb.setterUsername,
            boardBrand = loadedClimb.brand,
            layoutId = loadedClimb.layoutId,
            angle = angle,
            difficultyAverage = loadedClimb.difficultyAverage,
            qualityAverage = loadedClimb.qualityAverage,
            isBenchmark = loadedClimb.benchmarkDifficulty > 0.0,
            isRoute = loadedClimb.isRoute || playback.isRoute,
            isMirrored = isMirrored,
            isMirrorable = isMirrorable,
        ),
        holds = holds,
        availableAngles = availableAngles.map { it.angle },
        delivery = ClimbDetailDeliveryState(
            connection = ble.connectionState.toPortableConnection(),
            decision = deliveryDecision,
            isSending = ble.isSending,
            isSent = ble.success,
            hasWarning = ble.warning != null,
            connectedViaRelay = ble.connectedViaRelay,
            sessionOwned = sessionOwned,
        ),
        isFavorited = isFavorited,
        isIgnored = isIgnored,
        hasPersonalNote = personalNote.isNotBlank() || personalNoteDraft.isNotBlank(),
        loggedAscentCount = userAscents.size,
    )
}

private fun ConnectionState.toPortableConnection(): BoardConnectionState = when (this) {
    ConnectionState.DISCONNECTED -> BoardConnectionState.DISCONNECTED
    ConnectionState.CONNECTING -> BoardConnectionState.CONNECTING
    ConnectionState.CONNECTED, ConnectionState.SENDING -> BoardConnectionState.CONNECTED
}
