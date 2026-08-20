package com.cruxcoach.android.ui.common

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import android.util.Log
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.board.SessionQueueSheet
import com.cruxcoach.android.ui.board.BoardPlaylistBrowserCard
import com.cruxcoach.android.ui.board.BoardPlaylistViewModel
import com.cruxcoach.android.ui.board.relayErrorText
import com.cruxcoach.android.ui.fips.FipsMeshViewModel

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

/** Opens the connected board's shared playlist from any screen banner. */
val LocalOpenBoardPlaylist = staticCompositionLocalOf<() -> Unit> { {} }

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
    /**
     * Whether discovery alone may create this banner. The board browser keeps
     * discovery in its BLE menu and reserves its pinned context slot for the
     * Board-Playlist; other screens may still opt into the legacy shortcut.
     */
    showNearbyBoards: Boolean = true,
) {
    val boardPlaylistViewModel: BoardPlaylistViewModel = hiltViewModel()
    val boardPlaylistState by boardPlaylistViewModel.state.collectAsStateWithLifecycle()
    val openBoardPlaylist = LocalOpenBoardPlaylist.current
    val bleShareManager = LocalBleShareManager.current
    val state by bleShareManager.uiState.collectAsStateWithLifecycle()
    val meshViewModel: FipsMeshViewModel = hiltViewModel()
    val meshState by meshViewModel.state.collectAsStateWithLifecycle()
    val joiningBoardCellId by meshViewModel.joiningBoardCellId.collectAsStateWithLifecycle()
    val meshJoinFailed by meshViewModel.joinFailed.collectAsStateWithLifecycle()
    val nearbyMeshes = meshState.nearbyMeshes.filterNot { it.currentMesh }
    val visibleNearbyMeshes = if (showNearbyBoards) nearbyMeshes else emptyList()
    val joiningMeshName = joiningBoardCellId?.let { cellId ->
        meshState.nearbyMeshes.firstOrNull { it.joinableBoardCellId == cellId }?.boardName
            ?: stringResource(com.cruxcoach.android.R.string.fips_mesh_nearby_other)
    }
    LaunchedEffect(meshState.running) {
        if (!meshState.running) meshViewModel.ensureDiscovery()
    }

    val sessionQueueManager = LocalSessionQueueManager.current
    val queueState by sessionQueueManager.state.collectAsStateWithLifecycle()

    // Suppress on-board climb on detail screen:
    // 1. UUID match (same climb) — always suppress regardless of source
    // 2. LOCAL_ACTIVE — user is swiping through climbs they're sending to the board;
    //    the chip would briefly flash on each swipe because the new page UUID differs
    //    from the stale BLE state for one frame before the BLE state catches up.
    val effectiveOnBoard = state.onBoardClimb?.takeIf {
        if (currentClimbUuid == null) true
        else it.climbUuid != currentClimbUuid && it.source != OnBoardSource.LOCAL_ACTIVE
    }
    // The browser's BLE menu already contains all discovery details. When its
    // pinned shortcut is disabled, suppress the complete discovery summary —
    // not just FIPS board counts — so an occupied-board or nearby-playlist
    // count cannot recreate the same redundant banner through another field.
    val visibleOnBoard = effectiveOnBoard.takeIf { showNearbyBoards }
    val visibleState = if (showNearbyBoards) state else state.copy(
        boardOccupiedCount = 0,
        nearbySessions = emptyList(),
    )
    // FEAT-044 §12: persistent in-app sharing status with one-tap stop —
    // visible on every screen while the board is shared.
    val relayManager = LocalCruxRelayManager.current
    val relayState by relayManager.state.collectAsStateWithLifecycle()

    // CruxRelay is an implementation detail of the active BoardCell controller,
    // not a second user-facing connection. It must never create another row or
    // keep an otherwise empty Nearby card visible. Relay failures still surface
    // below because they can explain a temporarily unavailable board.
    val hasContent = visibleOnBoard != null || visibleState.boardOccupiedCount > 0 ||
        state.ownSession != null ||
        visibleNearbyMeshes.isNotEmpty() || joiningMeshName != null ||
        meshState.cellId != null || meshJoinFailed

    // Terminal relay errors (BOARD_LOST, a failed start) land AFTER the
    // sharing sheet's error surface is gone — show them here so the stop
    // is never silent (§12); dismissible, on every screen. Independent of
    // hasContent: an error means sharing already stopped.
    if (!relayState.enabled) {
        relayState.error?.let { error ->
            RelayErrorRow(
                text = relayErrorText(error, relayState.errorDetail),
                onDismiss = { relayManager.clearError() }
            )
        }
    }
    if (meshJoinFailed) {
        RelayErrorRow(
            text = stringResource(com.cruxcoach.android.R.string.fips_mesh_join_failed),
            onDismiss = meshViewModel::dismissJoinError,
        )
    }
    // Connected has one app-wide meaning: the board's shared playlist is the
    // useful shortcut. Nearby returns automatically when membership is gone.
    if (boardPlaylistState.available) {
        BoardPlaylistBrowserCard(
            onOpen = openBoardPlaylist,
            viewModel = boardPlaylistViewModel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        return
    }
    if (!hasContent) return

    var expanded by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    // The board's shared list has a screen of its own, reached from the
    // browser card; this sheet is for a private local playlist only. Two
    // places to edit one shared list is how the wall ends up with two things
    // that can light it.
    val boardPlaylistActive = queueState.mesh != null
    // Queue sheet — managed internally, works on every screen
    if (showQueueSheet && queueState.role != SessionRole.NONE && !boardPlaylistActive) {
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
            state = visibleState,
            effectiveOnBoard = visibleOnBoard,
            onCollapse = { Log.d(TAG, "COLLAPSE"); expanded = false },
            onClimbTapped = onClimbTapped,
            onJoinSession = null,
            onAddToQueue = onAddToQueue,
            onOpenQueueSheet = { showQueueSheet = true },
            activeMesh = meshState.takeIf { it.cellId != null },
            onLeaveMesh = meshViewModel::leave,
            nearbyMeshes = visibleNearbyMeshes,
            onJoinMesh = if (joiningBoardCellId == null) meshViewModel::join else null,
            joiningMeshName = joiningMeshName,
        )
    } else {
        BleStatusChip(
            state = visibleState,
            effectiveOnBoard = visibleOnBoard,
            onExpand = { Log.d(TAG, "EXPAND"); expanded = true },
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue,
            boardPlaylistActive = boardPlaylistActive,
            nearbyMeshCount = visibleNearbyMeshes.size,
            joiningMeshName = joiningMeshName,
            // Membership, not transient advertisement metadata, decides
            // whether this is an active mesh. The fallback prevents the chip
            // becoming an empty/disconnected-looking card after the nearby
            // advertisement expires while the realm remains healthy.
            activeMeshName = meshState.takeIf { it.cellId != null }?.boardName
                ?: meshState.cellId?.let { stringResource(com.cruxcoach.android.R.string.fips_mesh_nearby_own) },
            activeMeshMemberCount = meshState.memberCount,
            meshControllerAvailable = meshState.availability == "ACTIVE",
        )
    }
}

/** App-root host for admission prompts. It must not live in [BleStatusArea]:
 * several secondary screens intentionally have no BLE status row, while a
 * time-limited request still has to be visible above whichever screen is open. */
@Composable
fun BoardJoinRequestHost(
    meshViewModel: FipsMeshViewModel = hiltViewModel(),
) {
    val requests by meshViewModel.incomingJoinRequests.collectAsStateWithLifecycle()
    requests.firstOrNull()?.let { request ->
        BoardJoinRequestDialog(
            remaining = requests.size,
            onAllow = { meshViewModel.allowBoardJoin(request) },
            onDeny = { meshViewModel.denyBoardJoin(request) },
        )
    }
}

@Composable
private fun BoardJoinRequestDialog(
    remaining: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* Ignoring is intentionally not a decline. */ },
        title = {
            Text(stringResource(com.cruxcoach.android.R.string.board_join_request_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(com.cruxcoach.android.R.string.board_join_request_body))
                if (remaining > 1) {
                    Text(stringResource(
                        com.cruxcoach.android.R.string.board_join_request_more,
                        remaining - 1,
                    ))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = com.cruxcoach.android.ui.theme.OrangeAccent,
                ),
            ) {
                Text(stringResource(com.cruxcoach.android.R.string.board_join_request_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(com.cruxcoach.android.R.string.board_join_request_deny))
            }
        },
    )
}
