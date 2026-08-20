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
    val nearbyMeshes = meshState.nearbyMeshes.filterNot { it.currentMesh }
    val joiningMeshName = joiningBoardCellId?.let { cellId ->
        meshState.nearbyMeshes.firstOrNull { it.joinableBoardCellId == cellId }?.boardName
            ?: stringResource(com.cruxcoach.android.R.string.fips_mesh_nearby_other)
    }
    LaunchedEffect(meshState.running) {
        if (!meshState.running) meshViewModel.ensureDiscovery()
    }

    val sessionQueueManager = LocalSessionQueueManager.current
    val queueState by sessionQueueManager.state.collectAsStateWithLifecycle()

    val playback = LocalPlaylistPlayback.current

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

    // CruxRelay is an implementation detail of the active BoardCell controller,
    // not a second user-facing connection. It must never create another row or
    // keep an otherwise empty Nearby card visible. Relay failures still surface
    // below because they can explain a temporarily unavailable board.
    val hasContent = effectiveOnBoard != null || state.boardOccupiedCount > 0 ||
        state.ownSession != null ||
        nearbyMeshes.isNotEmpty() || joiningMeshName != null ||
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

    if (expanded) {
        BleStatusExpanded(
            state = state,
            effectiveOnBoard = effectiveOnBoard,
            onCollapse = { Log.d(TAG, "COLLAPSE"); expanded = false },
            onClimbTapped = onClimbTapped,
            onJoinSession = null,
            onAddToQueue = onAddToQueue,
            onOpenQueueSheet = { showQueueSheet = true },
            activeMesh = meshState.takeIf { it.cellId != null },
            onLeaveMesh = meshViewModel::leave,
            nearbyMeshes = nearbyMeshes,
            onJoinMesh = if (joiningBoardCellId == null) meshViewModel::join else null,
            joiningMeshName = joiningMeshName,
            // Explicit and user-driven, which is the whole rule: mesh
            // membership only made the playlist visible above. Opening the
            // queue straight away means the button has a visible consequence
            // rather than silently changing state somewhere off screen.
            onJoinPlaylist = {
                playback.joinCanonicalPlaylist()
                expanded = false
                showQueueSheet = true
            },
        )
    } else {
        BleStatusChip(
            state = state,
            effectiveOnBoard = effectiveOnBoard,
            onExpand = { Log.d(TAG, "EXPAND"); expanded = true },
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue,
            nearbyMeshCount = nearbyMeshes.size,
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
