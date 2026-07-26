package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode

/** Selects the user's send preference for the currently observed controller. */
internal object BoardSendModePolicy {
    fun resolve(
        connectionCapacity: BoardConnectionCapacity,
        singleConnectionMode: BoardSendMode,
        multiConnectionMode: BoardSendMode,
        hostingForOthers: Boolean = false,
    ): BoardSendMode = when {
        // Relaying makes the wall multi-user regardless of what the physical
        // controller can do — that is the entire point of CruxRelay, and the
        // board underneath is usually SINGLE. So the climber's multi-
        // connection preference is the one that describes this situation, and
        // it decides on its own: its default (EXPLICIT) already carries the
        // "don't grab a shared wall by swiping" rule, and a climber who set it
        // to AUTOMATIC has said they want the wall to follow them. Overriding
        // that downstream took the choice away from them a second time.
        hostingForOthers -> multiConnectionMode
        else -> when (connectionCapacity) {
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
