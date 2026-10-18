package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionCapacity
import com.cruxcoach.app.ble.BoardDeliveryPolicy
import com.cruxcoach.app.ble.BoardProjectionPolicy
import com.cruxcoach.app.ble.BoardSendMode
import com.cruxcoach.app.ble.BoardSendModePolicy
import com.cruxcoach.app.ble.SessionRole
import com.cruxcoach.domain.board.BoardBrand

/**
 * Android's "Send to board: automatic / tap" (`BoardClimbDetailViewModel.sendAutomaticallyIfEnabled`,
 * `SessionQueueManager.resolveSendMode` and `QueueDeliveryPolicy`), as pure decisions.
 *
 * The order of the checks is Android's: whether a send can happen at all is settled
 * before the preference is consulted, so a later condition cannot slip in front of
 * the connection check.
 */
object BoardAutoSend {

    /** The settings value as the delivery policy reads it. */
    fun mode(value: com.cruxcoach.app.settings.BoardSendMode): BoardSendMode = when (value) {
        com.cruxcoach.app.settings.BoardSendMode.AUTOMATIC -> BoardSendMode.AUTOMATIC
        com.cruxcoach.app.settings.BoardSendMode.EXPLICIT -> BoardSendMode.EXPLICIT
    }

    /** The mode in force for the connected controller ([BoardSendModePolicy.resolve]). */
    fun resolvedMode(
        single: com.cruxcoach.app.settings.BoardSendMode,
        multi: com.cruxcoach.app.settings.BoardSendMode,
        capacity: BoardConnectionCapacity,
        hostingForOthers: Boolean,
    ): BoardSendMode = BoardSendModePolicy.resolve(
        connectionCapacity = capacity,
        singleConnectionMode = mode(single),
        multiConnectionMode = mode(multi),
        hostingForOthers = hostingForOthers,
    )

    /**
     * Whether the climb shown in the detail goes on the wall without a tap: after it
     * loaded (open, page swipe, angle), after a mirror flip, and when a board connects.
     *
     * - A shared session owns delivery: a participant adds climbs through the queue only.
     * - A running playlist owns the wall: Android's send controller drops every automatic
     *   detail send while one runs (only the explicit lamp may override a private playlist).
     * - Quantum is never sent by browsing: a retained layer rack is shared controller state.
     */
    fun detailSendsAutomatically(
        mode: BoardSendMode,
        climbBrand: BoardBrand?,
        sessionRole: SessionRole,
        sessionConnecting: Boolean,
        playlistRunning: Boolean,
        boardConnected: Boolean,
        holdCount: Int,
        frames: String?,
    ): Boolean {
        if (playlistRunning) return false
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = mode,
            boardBrand = climbBrand,
            sessionRole = sessionRole,
            sessionConnecting = sessionConnecting,
            localPlaylist = false,
            boardConnected = boardConnected,
            hasDirectPayload = BoardProjectionPolicy.hasSendablePayload(climbBrand, holdCount, frames),
        )
        return decision.dispatchAutomatically
    }
}
