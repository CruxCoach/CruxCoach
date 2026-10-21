package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand

/** The board the app sends to: brand plus the layout and size its LED map comes from. */
internal data class ActiveBoardSelection(
    val brand: BoardBrand,
    val layoutId: Int,
    val productSizeId: Int,
)

/**
 * Releases a board link when the user switches to another board.
 *
 * A send builds its LED map from the ACTIVE board. Switching from a 16 x 12 to
 * a 12 x 12 Kilter while connected used to keep the link, so the next climb
 * lit size-10 positions on the 16 x 12 wall — both brand and size guards pass.
 * The official Kilter app fixed the same in 2.11.3 by disconnecting on a wall
 * switch; this does the same.
 *
 * Only a link whose board matched the selection when it came up is released:
 * - a link made during onboarding is made before the board is chosen;
 * - a link to a board of another brand than the selected one (the mismatch
 *   prompt) is corrected by changing the selection TOWARD the connected board,
 *   and a catalogue download may write that selection twice.
 * Such a link follows every change and is never released by this policy; the
 * next connection is bound normally.
 */
internal class BoardSwitchLinkPolicy {
    private var bound: ActiveBoardSelection? = null
    private var boundToLink = false

    fun onLinkUp(selection: ActiveBoardSelection, connectedBrand: BoardBrand?, onboarded: Boolean) {
        bound = selection
        boundToLink = onboarded && connectedBrand != null && selection.brand == connectedBrand
    }

    fun onLinkDown() {
        bound = null
        boundToLink = false
    }

    /** @return true when the link must be released because the user switched boards. */
    fun onSelectionChanged(
        selection: ActiveBoardSelection,
        connectedBrand: BoardBrand?,
        onboarded: Boolean,
    ): Boolean {
        val previous = bound ?: return false
        if (selection == previous) return false
        if (boundToLink) return true
        bound = selection
        return false
    }
}
