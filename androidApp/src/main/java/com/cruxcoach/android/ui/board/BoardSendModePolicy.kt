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
        meshParticipant: Boolean = false,
    ): BoardSendMode = when {
        meshParticipant -> multiConnectionMode
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
            // No production path builds UNKNOWN any more — an unclassified
            // controller resolves to SINGLE, which is what real hardware is.
            // The branch stays as the cautious answer should that change back:
            // with two differing preferences it withholds the automatic send
            // until the probe has said which one applies. Note that nothing
            // reaches it today, so it is not the guard it reads like.
            BoardConnectionCapacity.UNKNOWN -> if (singleConnectionMode == multiConnectionMode) {
                singleConnectionMode
            } else {
                BoardSendMode.EXPLICIT
            }
        }
    }

    /**
     * The probe changed our mind about the controller, and that flipped the
     * resolved preference to AUTOMATIC — so the climb the user has open should
     * go to the wall now, without a second tap.
     *
     * This used to require [BoardConnectionCapacity.UNKNOWN] as the previous
     * value. Once an unclassified controller began resolving to SINGLE straight
     * away, no production path produced UNKNOWN any more and this could never
     * fire again; the tests stayed green only because they called it with a
     * value the app no longer builds. What actually matters is that the
     * capacity changed and the mode followed it, which holds in both
     * directions.
     */
    fun shouldAutoSendAfterCapacityResolution(
        previousCapacity: BoardConnectionCapacity,
        currentCapacity: BoardConnectionCapacity,
        previousResolvedMode: BoardSendMode,
        resolvedMode: BoardSendMode,
    ): Boolean = previousCapacity != currentCapacity &&
        previousResolvedMode != BoardSendMode.AUTOMATIC &&
        resolvedMode == BoardSendMode.AUTOMATIC
}
