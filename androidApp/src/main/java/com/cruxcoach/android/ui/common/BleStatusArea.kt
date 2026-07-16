package com.cruxcoach.android.ui.common

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.NearbySessionEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.SessionRole
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

    // Bug 3: Session join handled internally via CompositionLocals — works on every screen
    val sessionQueueManager = LocalSessionQueueManager.current
    val sessionGattBridge = LocalSessionGattBridge.current
    val queueState by sessionQueueManager.state.collectAsStateWithLifecycle()

    val boardSessionManager = LocalBoardSessionManager.current
    var pendingJoin by remember { mutableStateOf<NearbySessionEntry?>(null) }
    var joinCode by remember { mutableStateOf("") }
    val handleJoinSession: (NearbySessionEntry) -> Unit = { sessionEntry ->
        pendingJoin = sessionEntry
        joinCode = ""
    }

    pendingJoin?.let { sessionEntry ->
        AlertDialog(
            onDismissRequest = {
                pendingJoin = null
                joinCode = ""
            },
            title = { Text(stringResource(R.string.ble_session_code_title)) },
            text = {
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { value ->
                        joinCode = value.filter { it in '0'..'9' }.take(6)
                    },
                    label = { Text(stringResource(R.string.ble_session_code_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.ble_session_code_message,
                                sessionEntry.hostName.ifEmpty { stringResource(R.string.ble_unknown) },
                            ),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = joinCode.length == 6,
                    onClick = {
                        val device = sessionEntry.rawSession.device
                        if (device != null) {
                            boardSessionManager.startSession()
                            // Don't call startQueue() here — that sets role=HOST.
                            sessionGattBridge.joinSession(device, joinCode)
                        }
                        pendingJoin = null
                        joinCode = ""
                    },
                ) {
                    Text(stringResource(R.string.common_join))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingJoin = null
                    joinCode = ""
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
    val hasContent = effectiveOnBoard != null || state.boardOccupiedCount > 0 ||
        state.nearbySessions.isNotEmpty() || state.ownSession != null

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
            onJoinSession = handleJoinSession,
            onRequestDisconnect = { bleShareManager.requestDisconnect() },
            onAddToQueue = onAddToQueue,
            onOpenQueueSheet = { showQueueSheet = true }
        )
    } else {
        BleStatusChip(
            state = state,
            effectiveOnBoard = effectiveOnBoard,
            onExpand = { Log.d(TAG, "EXPAND"); expanded = true },
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue
        )
    }
}
