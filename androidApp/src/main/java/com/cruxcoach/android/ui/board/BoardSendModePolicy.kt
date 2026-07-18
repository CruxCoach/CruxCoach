package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode

/** Selects the user's send preference for the currently observed controller. */
internal object BoardSendModePolicy {
    fun resolve(
        connectionCapacity: BoardConnectionCapacity,
        singleConnectionMode: BoardSendMode,
        multiConnectionMode: BoardSendMode,
    ): BoardSendMode = when (connectionCapacity) {
        BoardConnectionCapacity.SINGLE -> singleConnectionMode
        BoardConnectionCapacity.MULTIPLE -> multiConnectionMode
        BoardConnectionCapacity.UNKNOWN -> if (singleConnectionMode == multiConnectionMode) {
            singleConnectionMode
        } else {
            // Do not auto-send before the short capacity probe has selected
            // which of two differing user preferences applies.
            BoardSendMode.EXPLICIT
        }
    }

    fun shouldAutoSendAfterCapacityResolution(
        previousCapacity: BoardConnectionCapacity,
        currentCapacity: BoardConnectionCapacity,
        previousResolvedMode: BoardSendMode,
        resolvedMode: BoardSendMode,
    ): Boolean = previousCapacity == BoardConnectionCapacity.UNKNOWN &&
        currentCapacity != BoardConnectionCapacity.UNKNOWN &&
        previousResolvedMode != BoardSendMode.AUTOMATIC &&
        resolvedMode == BoardSendMode.AUTOMATIC
}
