package com.cruxcoach.android.ui.common

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.board.LocalPlaylistBrowserCard
import com.cruxcoach.android.ui.board.SessionQueueSheet

private const val TAG = "CruxBLE/UI"

val LocalBleShareManager = staticCompositionLocalOf<BleShareManager> {
    error("BleShareManager not provided")
}

val LocalSessionQueueManager = staticCompositionLocalOf<SessionQueueManager> {
    error("SessionQueueManager not provided")
}

val LocalSessionGattBridge = staticCompositionLocalOf<SessionGattBridge> {
    error("SessionGattBridge not provided")
}

val LocalCruxRelayManager = staticCompositionLocalOf<CruxRelayManager> {
    error("CruxRelayManager not provided")
}

/** Opens the playlist player from anywhere (mini-player tap, join flow).
 *  Provided at NavGraph level; default no-op keeps previews harmless. */
val LocalOpenPlaylistPlayer = staticCompositionLocalOf<() -> Unit> { {} }

val LocalPlaylistPlayback = staticCompositionLocalOf<PlaylistPlaybackCoordinator> {
    error("PlaylistPlaybackCoordinator not provided")
}

/**
 * Universal BLE status area — unified composable for BLE sharing status,
 * session controls, and nearby climb information.
 *
 * Shows a collapsed chip when BLE activity or a session exists, expandable to full detail view.
 * Placed between TopAppBar and content on all screens.
 *
 * Session controls (join, pause, stop, queue sheet) are handled internally via CompositionLocals —
 * they work on every screen without external callbacks.
 *
 * @param currentClimbUuid If set, suppresses "on board" display when it matches (detail screen redundancy).
 * @param onClimbTapped Navigate to climb detail.
 * @param onAddToQueue Add current screen's climb to session queue (null = no + button).
 */
@Composable
fun BleStatusArea(
    currentClimbUuid: String? = null,
    onClimbTapped: ((uuid: String, angle: Int) -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRandomToQueue: (() -> Unit)? = null,
) {
    val bleShareManager = LocalBleShareManager.current
    val state by bleShareManager.uiState.collectAsStateWithLifecycle()

    // Bug 3: Session join handled internally via CompositionLocals — works on every screen
    val sessionQueueManager = LocalSessionQueueManager.current
    val queueState by sessionQueueManager.state.collectAsStateWithLifecycle()
    val isLocalPlaylist = queueState.isActive && queueState.isPlaylist
    val currentPlaylistClimbName by sessionQueueManager.currentClimbName.collectAsStateWithLifecycle()
    val playback = LocalPlaylistPlayback.current
    val playbackState by playback.state.collectAsStateWithLifecycle()
    val displayState = if (isLocalPlaylist) state.copy(ownSession = null) else state
    val relayManager = LocalCruxRelayManager.current
    val relayState by relayManager.state.collectAsStateWithLifecycle()

    // One universal Playlist-UX banner on every screen. It replaces the old
    // session mini-player everywhere, not just in the board browser.
    if (isLocalPlaylist) {
        LocalPlaylistBrowserCard(
            currentClimbName = currentPlaylistClimbName,
            currentIndex = queueState.currentIndex,
            totalCount = queueState.queue.size,
            hasPrevious = playbackState.hasPrevious,
            hasNext = playbackState.hasNext,
            canLight = playbackState.currentClimb != null && playbackState.boardConnected,
            onPrevious = playback::previous,
            onLight = playback::resendCurrentClimb,
            onNext = playback::next,
            onOpen = LocalOpenPlaylistPlayer.current,
        )
    }

    // Suppress on-board climb on detail screen:
    // 1. UUID match (same climb) — always suppress regardless of source
    // 2. LOCAL_ACTIVE — user is swiping through climbs they're sending to the board;
    //    the chip would briefly flash on each swipe because the new page UUID differs
    //    from the stale BLE state for one frame before the BLE state catches up.
    val effectiveOnBoard = displayState.onBoardClimb?.takeIf {
        if (currentClimbUuid == null) true
        else it.climbUuid != currentClimbUuid && it.source != OnBoardSource.LOCAL_ACTIVE
    }
    val hasContent = effectiveOnBoard != null || displayState.boardOccupiedCount > 0 ||
        displayState.ownSession != null

    if (!hasContent) return

    var expanded by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    // Queue sheet — managed internally, works on every screen
    if (showQueueSheet && queueState.role != SessionRole.NONE) {
        SessionQueueSheet(
            onDismiss = { showQueueSheet = false },
            onNavigateToClimb = { uuid, angle ->
                onClimbTapped?.invoke(uuid, angle)
            },
            canEdit = true
        )
    }

    if (expanded) {
        BleStatusExpanded(
            state = displayState,
            effectiveOnBoard = effectiveOnBoard,
            onCollapse = { Log.d(TAG, "COLLAPSE"); expanded = false },
            onClimbTapped = onClimbTapped,
            onRequestDisconnect = { bleShareManager.requestDisconnect() },
            onAddToQueue = onAddToQueue,
            onOpenQueueSheet = { showQueueSheet = true },
            relayClientCount = relayState.clientCount.takeIf { relayState.enabled },
            relayBoardName = relayState.boardName,
            onStopRelay = { relayManager.disable() },
        )
    } else {
        BleStatusChip(
            state = displayState,
            effectiveOnBoard = effectiveOnBoard,
            onExpand = { Log.d(TAG, "EXPAND"); expanded = true },
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue,
        )
    }
}
