package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.toPortableState
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.domain.board.ActiveSessionClimb
import com.cruxcoach.domain.board.BoardConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow production host for the reviewed continue surface. It owns live-flow
 * collection so the browser list does not recompose on every session tick.
 */
@Composable
internal fun BoardBrowserActiveSessionHost(
    sessionState: StateFlow<BoardSessionState>,
    restTimerState: StateFlow<RestTimerState>,
    currentQueueClimb: QueueItem?,
    currentClimbName: StateFlow<String?>,
    connectionState: ConnectionState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = sessionState.collectAsStateWithLifecycle().value
    val restTimer = restTimerState.collectAsStateWithLifecycle().value
    val resolvedName = currentClimbName.collectAsStateWithLifecycle().value
    val currentClimb = currentQueueClimb?.let { queueItem ->
        resolvedName?.let { name ->
            ActiveSessionClimb(
                uuid = queueItem.climbUuid,
                name = name,
                angle = queueItem.angle.toLong(),
                // QueueItem's published BLE contract carries UUID + angle only.
                // Do not infer or mutate the detail screen's mirror control.
                isMirrored = false,
            )
        }
    }
    val portable = session.toPortableState(
        restTimer = restTimer,
        currentClimb = currentClimb,
        connection = connectionState.toPortableConnectionState(),
    ) ?: return

    ActiveSessionContinueCard(
        state = portable,
        onContinue = onContinue,
        modifier = modifier.padding(
            horizontal = CruxCoachSpacing.large,
            vertical = CruxCoachSpacing.small,
        ),
    )
}

private fun ConnectionState.toPortableConnectionState(): BoardConnectionState = when (this) {
    ConnectionState.DISCONNECTED -> BoardConnectionState.DISCONNECTED
    ConnectionState.CONNECTING -> BoardConnectionState.CONNECTING
    ConnectionState.CONNECTED, ConnectionState.SENDING -> BoardConnectionState.CONNECTED
}
