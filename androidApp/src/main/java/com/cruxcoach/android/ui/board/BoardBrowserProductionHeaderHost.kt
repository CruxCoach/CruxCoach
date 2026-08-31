package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cruxcoach.android.ui.theme.CruxCoachSpacing

/**
 * Pixel-neutral production boundary for the reviewed board-context region.
 *
 * The Android orchestrator keeps first-run/catalogue reachability and owns the
 * callbacks. Only the portable board and connection projections cross into the
 * renderer, so expanding this region later cannot silently move search,
 * navigation, management or BLE responsibilities.
 */
@Composable
internal fun BoardBrowserProductionHeaderHost(
    state: BoardBrowserState,
    onSelectBoard: () -> Unit,
    onConnectBoard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasBoardData) return

    BoardBrowserContextHeader(
        board = state.toPortableBoardContext(),
        connection = state.toPortableConnection(),
        onSelectBoard = onSelectBoard,
        onConnectBoard = onConnectBoard,
        connectionTestTag = "board_ble_button",
        modifier = modifier.padding(
            horizontal = CruxCoachSpacing.large,
            vertical = CruxCoachSpacing.small,
        ),
    )
}
