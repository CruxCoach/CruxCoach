package com.cruxcoach.android.ui.common

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.util.Log
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.CruxRelayManager
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

val LocalCruxRelayManager = staticCompositionLocalOf<CruxRelayManager> {
    error("CruxRelayManager not provided")
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
    val handleJoinSession: (NearbySessionEntry) -> Unit = { sessionEntry ->
        val device = sessionEntry.rawSession.device
        if (device != null) {
            boardSessionManager.startSession()
            // Don't call startQueue() here — that sets role=HOST and causes a
            // HOST→PARTICIPANT flicker during the 2-3s GATT connection.
            // joinSession() will call setConnecting() immediately and
            // setParticipantRole() once GATT connects successfully.
            sessionGattBridge.joinSession(device)
        }
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

    // FEAT-044 §12: persistent in-app sharing status with one-tap stop —
    // visible on every screen while the board is shared, independent of the
    // regular BLE chip content.
    val relayManager = LocalCruxRelayManager.current
    val relayState by relayManager.state.collectAsStateWithLifecycle()
    if (relayState.enabled) {
        RelayStatusChip(
            clientCount = relayState.clientCount,
            onStop = { relayManager.setEnabled(false) }
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
