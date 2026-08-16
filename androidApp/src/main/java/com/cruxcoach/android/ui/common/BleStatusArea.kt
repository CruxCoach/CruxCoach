package com.cruxcoach.android.ui.common

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import android.util.Log
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.CruxRelayManager
import com.cruxcoach.android.data.NearbySessionEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.board.SessionQueueSheet
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
    onRandomToQueue: (() -> Unit)? = null
) {
    val bleShareManager = LocalBleShareManager.current
    val state by bleShareManager.uiState.collectAsStateWithLifecycle()
    val meshViewModel: FipsMeshViewModel = hiltViewModel()
    val meshState by meshViewModel.state.collectAsStateWithLifecycle()
    val joiningBoardCellId by meshViewModel.joiningBoardCellId.collectAsStateWithLifecycle()
    val meshJoinFailed by meshViewModel.joinFailed.collectAsStateWithLifecycle()
    val incomingControllerRequest by meshViewModel.incomingControllerRequest.collectAsStateWithLifecycle()
    val nearbyMeshes = meshState.nearbyMeshes.filterNot { it.currentMesh }
    val joiningMeshName = joiningBoardCellId?.let { cellId ->
        meshState.nearbyMeshes.firstOrNull { it.joinableBoardCellId == cellId }?.boardName
            ?: stringResource(com.cruxcoach.android.R.string.fips_mesh_nearby_other)
    }
    LaunchedEffect(meshState.running) {
        if (!meshState.running) meshViewModel.ensureDiscovery()
    }

    incomingControllerRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { meshViewModel.denyControllerTransfer(request) },
            title = { Text(stringResource(com.cruxcoach.android.R.string.mesh_controller_request_title)) },
            text = { Text(stringResource(com.cruxcoach.android.R.string.mesh_controller_request_text)) },
            confirmButton = {
                TextButton(onClick = { meshViewModel.approveControllerTransfer(request) }) {
                    Text(stringResource(com.cruxcoach.android.R.string.mesh_controller_request_approve))
                }
            },
            dismissButton = {
                TextButton(onClick = { meshViewModel.denyControllerTransfer(request) }) {
                    Text(stringResource(com.cruxcoach.android.R.string.mesh_controller_request_deny))
                }
            },
        )
    }

    // Bug 3: Session join handled internally via CompositionLocals — works on every screen
    val sessionQueueManager = LocalSessionQueueManager.current
    val sessionGattBridge = LocalSessionGattBridge.current
    val queueState by sessionQueueManager.state.collectAsStateWithLifecycle()

    val playback = LocalPlaylistPlayback.current
    val openPlayer = LocalOpenPlaylistPlayer.current
    val handleJoinSession: (NearbySessionEntry) -> Unit = { sessionEntry ->
        // Joining lands directly in the player — it shows the connecting
        // state and becomes the participant's home for the playlist.
        playback.join(sessionEntry)
        openPlayer()
    }

    // Suppress on-board climb on detail screen:
    // 1. UUID match (same climb) — always suppress regardless of source
    // 2. LOCAL_ACTIVE — user is swiping through climbs they're sending to the board;
    //    the chip would briefly flash on each swipe because the new page UUID differs
    //    from the stale BLE state for one frame before the BLE state catches up.
    val effectiveOnBoard = state.onBoardClimb?.takeIf {
        if (currentClimbUuid == null) true
        else it.climbUuid != currentClimbUuid && it.source != OnBoardSource.LOCAL_ACTIVE
    }
    // FEAT-044 §12: persistent in-app sharing status with one-tap stop —
    // visible on every screen while the board is shared.
    val relayManager = LocalCruxRelayManager.current
    val relayState by relayManager.state.collectAsStateWithLifecycle()

    // Sharing counts as content in its own right. It used to render as a
    // separate card above this area, which meant the host saw the sharing
    // state and the board state as two disconnected strips — and with nothing
    // else going on, the area below returned early and the climb currently on
    // the board never appeared at all. The relay line now rides along inside
    // the regular chip instead.
    val hasContent = effectiveOnBoard != null || state.boardOccupiedCount > 0 ||
        state.nearbySessions.isNotEmpty() || state.ownSession != null ||
        relayState.enabled || nearbyMeshes.isNotEmpty() || joiningMeshName != null ||
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

    val relayClientCount = relayState.clientCount.takeIf { relayState.enabled }
    val stopRelay: () -> Unit = { relayManager.setEnabled(false) }

    if (expanded) {
        BleStatusExpanded(
            state = state,
            effectiveOnBoard = effectiveOnBoard,
            onCollapse = { Log.d(TAG, "COLLAPSE"); expanded = false },
            onClimbTapped = onClimbTapped,
            onJoinSession = handleJoinSession,
            onRequestDisconnect = { bleShareManager.requestDisconnect() },
            onAddToQueue = onAddToQueue,
            onOpenQueueSheet = { showQueueSheet = true },
            relayClientCount = relayClientCount,
            onStopRelay = stopRelay,
            activeMesh = meshState.takeIf { it.cellId != null },
            nearbyMeshes = nearbyMeshes,
            onJoinMesh = if (joiningBoardCellId == null) meshViewModel::join else null,
            joiningMeshName = joiningMeshName,
        )
    } else {
        BleStatusChip(
            state = state,
            effectiveOnBoard = effectiveOnBoard,
            onExpand = { Log.d(TAG, "EXPAND"); expanded = true },
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue,
            relayClientCount = relayClientCount,
            onStopRelay = stopRelay,
            nearbyMeshCount = nearbyMeshes.size,
            joiningMeshName = joiningMeshName,
            activeMeshName = meshState.boardName,
            meshControllerAvailable = meshState.availability == "ACTIVE",
            localMeshController = meshState.controllerNpub != null &&
                meshState.controllerNpub == meshState.localNpub,
        )
    }
}
