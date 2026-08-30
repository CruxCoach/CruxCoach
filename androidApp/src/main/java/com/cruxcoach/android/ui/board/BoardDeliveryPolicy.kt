package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ClimbDeliveryMode
import com.cruxcoach.domain.board.ClimbDeliveryPolicy
import com.cruxcoach.domain.board.SharedBoardSessionRole

internal typealias BoardDeliveryTarget = com.cruxcoach.domain.board.BoardDeliveryTarget
internal typealias BoardDeliveryDecision = com.cruxcoach.domain.board.BoardDeliveryDecision
internal typealias BoardDetailLampMode = com.cruxcoach.domain.board.BoardDetailLampMode

/** Android type adapter for the portable climb-delivery policy. */
internal object BoardDeliveryPolicy {
    fun shouldAutoConnectSessionHost(
        newRole: SessionRole,
        previousRole: SessionRole,
        connectionState: ConnectionState,
    ): Boolean = ClimbDeliveryPolicy.shouldAutoConnectSessionHost(
        newRole.toPortable(),
        previousRole.toPortable(),
        connectionState.toPortable(),
    )

    fun shouldReleaseBoardForSessionParticipant(
        newRole: SessionRole,
        previousRole: SessionRole,
        connectionState: ConnectionState,
        connectionCapacity: com.cruxcoach.android.ble.BoardConnectionCapacity,
        connectionPinnedByAnotherFeature: Boolean = false,
    ): Boolean = ClimbDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
        newRole.toPortable(),
        previousRole.toPortable(),
        connectionState.toPortable(),
        connectionCapacity.toPortable(),
        connectionPinnedByAnotherFeature,
    )

    fun resolve(
        sendMode: BoardSendMode,
        boardBrand: BoardBrand?,
        sessionRole: SessionRole,
        sessionConnecting: Boolean = false,
        localPlaylist: Boolean = false,
        boardConnected: Boolean,
        hasDirectPayload: Boolean,
        @Suppress("UNUSED_PARAMETER") connectedViaRelay: Boolean = false,
    ): BoardDeliveryDecision = ClimbDeliveryPolicy.resolve(
        sendMode = when (sendMode) {
            BoardSendMode.AUTOMATIC -> ClimbDeliveryMode.AUTOMATIC
            BoardSendMode.EXPLICIT -> ClimbDeliveryMode.EXPLICIT
        },
        boardBrand = boardBrand,
        sessionRole = sessionRole.toPortable(),
        sessionConnecting = sessionConnecting,
        localPlaylist = localPlaylist,
        boardConnected = boardConnected,
        hasDirectPayload = hasDirectPayload,
    )

    fun lampMode(
        decision: BoardDeliveryDecision,
        hasDirectPayload: Boolean,
        boardConnected: Boolean,
        boardOwnedByOthers: Boolean,
        countdownRunning: Boolean,
    ): BoardDetailLampMode = ClimbDeliveryPolicy.lampMode(
        decision,
        hasDirectPayload,
        boardConnected,
        boardOwnedByOthers,
        countdownRunning,
    )
}

private fun SessionRole.toPortable(): SharedBoardSessionRole = when (this) {
    SessionRole.NONE -> SharedBoardSessionRole.NONE
    SessionRole.HOST -> SharedBoardSessionRole.HOST
    SessionRole.PARTICIPANT -> SharedBoardSessionRole.PARTICIPANT
}

private fun ConnectionState.toPortable(): com.cruxcoach.domain.board.BoardConnectionState =
    when (this) {
        ConnectionState.DISCONNECTED -> com.cruxcoach.domain.board.BoardConnectionState.DISCONNECTED
        ConnectionState.CONNECTING -> com.cruxcoach.domain.board.BoardConnectionState.CONNECTING
        ConnectionState.CONNECTED, ConnectionState.SENDING ->
            com.cruxcoach.domain.board.BoardConnectionState.CONNECTED
    }

private fun com.cruxcoach.android.ble.BoardConnectionCapacity.toPortable():
    com.cruxcoach.domain.board.BoardConnectionCapacity = when (this) {
    com.cruxcoach.android.ble.BoardConnectionCapacity.SINGLE ->
        com.cruxcoach.domain.board.BoardConnectionCapacity.SINGLE
    com.cruxcoach.android.ble.BoardConnectionCapacity.MULTIPLE ->
        com.cruxcoach.domain.board.BoardConnectionCapacity.MULTIPLE
    com.cruxcoach.android.ble.BoardConnectionCapacity.UNKNOWN ->
        com.cruxcoach.domain.board.BoardConnectionCapacity.UNKNOWN
}
