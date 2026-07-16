package com.cruxcoach.android.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.NearbySessionEntry
import com.cruxcoach.android.data.OnBoardClimbEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.OwnSessionState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

@Composable
internal fun BleStatusExpanded(
    state: BleShareUiState,
    effectiveOnBoard: OnBoardClimbEntry?,
    onCollapse: () -> Unit,
    onClimbTapped: ((uuid: String, angle: Int) -> Unit)?,
    onJoinSession: ((NearbySessionEntry) -> Unit)?,
    onRequestDisconnect: (() -> Unit)?,
    onAddToQueue: (() -> Unit)?,
    onOpenQueueSheet: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = OrangeAccent.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CellTower, null, tint = OrangeAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ble_sharing_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(color = OrangeAccent.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 8.dp))

            // Bug 1: Session queue section (shown when own session active)
            val session = state.ownSession
            if (session != null) {
                SessionQueueSection(
                    session = session,
                    onAddToQueue = onAddToQueue,
                    onOpenQueueSheet = onOpenQueueSheet
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = OrangeAccent.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 4.dp))
            }

            // On-board climb section
            if (effectiveOnBoard != null) {
                OnBoardClimbSection(climb = effectiveOnBoard, onClimbTapped = onClimbTapped)
                Spacer(Modifier.height(8.dp))
            }

            // Board occupied section
            if (state.boardOccupiedCount > 0) {
                Text(
                    stringResource(R.string.ble_board_occupied_detail, state.boardOccupiedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // Nearby sessions section — hide when already in a session (own or connecting)
            if (state.nearbySessions.isNotEmpty() && state.ownSession == null) {
                NearbySessionsSection(sessions = state.nearbySessions, onJoinSession = onJoinSession)
                Spacer(Modifier.height(8.dp))
            }

            // Disconnect request section
            if (state.canRequestDisconnect && onRequestDisconnect != null) {
                DisconnectRequestSection(
                    isRequesting = state.isRequestingDisconnect,
                    onRequest = onRequestDisconnect
                )
            }
        }
    }
}

/** Session queue controls in the expanded view. Stop is internalized via CompositionLocals. */
@Composable
private fun SessionQueueSection(
    session: OwnSessionState,
    onAddToQueue: (() -> Unit)?,
    onOpenQueueSheet: (() -> Unit)? = null
) {
    val queueManager = LocalSessionQueueManager.current
    val gattBridge = LocalSessionGattBridge.current
    val queueState by queueManager.state.collectAsStateWithLifecycle()
    val sessionJoinCode by queueManager.sessionJoinCode.collectAsStateWithLifecycle()
    val isParticipant = queueState.role == SessionRole.PARTICIPANT

    val boardSessionManager = LocalBoardSessionManager.current
    val bleShareManager = LocalBleShareManager.current

    // Bug 6: Internalized stop via CompositionLocals — works on every screen
    val handleStop: () -> Unit = {
        // Capture last queue climb BEFORE endQueue() clears it — needed for
        // "last on board" display after session ends.
        val lastClimb = queueManager.state.value.currentClimb
        if (queueState.role == SessionRole.HOST) {
            gattBridge.stopSharing()
            queueManager.endQueue()
        } else {
            gattBridge.leaveSession()
        }
        boardSessionManager.endSession()
        // Immediately set last climb so the chip shows what was on the board.
        // stopSharing() also does this but with a 500ms delay (GATT sentinel).
        if (lastClimb != null) {
            bleShareManager.setLastClimbAfterSession(lastClimb.climbUuid, lastClimb.angle)
        }
    }

    Column {
        // Session header with participant count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = OrangeAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.ble_session_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent
            )
            if (session.participantCount > 0) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.People, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    stringResource(R.string.ble_participants, session.participantCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (!isParticipant && sessionJoinCode.isNotEmpty()) {
            Text(
                stringResource(R.string.ble_session_host_code, sessionJoinCode),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent,
            )
            Text(
                stringResource(R.string.ble_session_host_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Prev / Current climb + Add / Next navigation — < climb + >
        if (session.queue.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (isParticipant) gattBridge.sendPrev() else queueManager.previousClimb() },
                    enabled = session.currentIndex > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_previous), modifier = Modifier.size(22.dp))
                }

                // Climb info — tap to open queue sheet
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onOpenQueueSheet != null) Modifier.clickable { onOpenQueueSheet() }
                            else Modifier
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        session.currentClimbName ?: stringResource(R.string.ble_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val currentClimb = session.queue.getOrNull(session.currentIndex)
                    if (currentClimb != null) {
                        Text(
                            buildString {
                                if (session.currentClimbGrade != null) append("${session.currentClimbGrade} · ")
                                append("${currentClimb.angle}° · ${session.currentIndex + 1}/${session.queue.size}")
                                if (onOpenQueueSheet != null) append(" ▸")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Add button inline (compact +)
                if (onAddToQueue != null) {
                    IconButton(
                        onClick = onAddToQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add), modifier = Modifier.size(22.dp), tint = OrangeAccent)
                    }
                }

                IconButton(
                    onClick = { if (isParticipant) gattBridge.sendNext() else queueManager.nextClimb() },
                    enabled = session.currentIndex < session.queue.size - 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), modifier = Modifier.size(22.dp))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.ble_queue_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (onAddToQueue != null) {
                    IconButton(
                        onClick = onAddToQueue,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add), modifier = Modifier.size(22.dp), tint = OrangeAccent)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Stop/Leave button (full width, no "Boulder hinzufügen" button anymore)
        OutlinedButton(
            onClick = handleStop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (session.isHost) stringResource(R.string.ble_end_session) else stringResource(R.string.ble_leave_session),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun OnBoardClimbSection(
    climb: OnBoardClimbEntry,
    onClimbTapped: ((uuid: String, angle: Int) -> Unit)?
) {
    val name = climb.name ?: stringResource(R.string.ble_unknown_climb)
    val statusText = when (climb.source) {
        OnBoardSource.REMOTE_ACTIVE -> stringResource(R.string.ble_climbing_now)
        OnBoardSource.REMOTE_LAST -> stringResource(R.string.ble_still_visible)
        OnBoardSource.LOCAL_ACTIVE -> stringResource(R.string.ble_your_climb)
        OnBoardSource.LOCAL_MANAGER -> stringResource(R.string.ble_still_visible)
        OnBoardSource.SESSION_REMOTE -> stringResource(R.string.ble_session_climb)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClimbTapped != null) Modifier.clickable {
                    onClimbTapped(climb.climbUuid, climb.angle)
                } else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ble_on_board),
                style = MaterialTheme.typography.labelMedium,
                color = OrangeAccent,
                fontWeight = FontWeight.Bold
            )
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    if (climb.grade != null) append("${climb.grade} · ")
                    append("${climb.angle}° · $statusText")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (climb.rssi != null) {
            SignalIndicator(rssi = climb.rssi)
            Spacer(Modifier.width(4.dp))
        }
        if (onClimbTapped != null) {
            Icon(Icons.Default.ChevronRight, stringResource(R.string.cd_open), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NearbySessionsSection(
    sessions: List<NearbySessionEntry>,
    onJoinSession: ((NearbySessionEntry) -> Unit)?
) {
    sessions.forEach { session ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.People, null, tint = OrangeAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.hostName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val details = buildString {
                    append(stringResource(R.string.ble_participants, session.participantCount))
                    val climbName = session.currentClimbName
                    if (climbName != null) {
                        append(" · $climbName")
                        if (session.currentClimbGrade != null) append(" ${session.currentClimbGrade}")
                    }
                }
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onJoinSession != null) {
                FilledTonalButton(
                    onClick = { onJoinSession(session) },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = OrangeAccent.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.common_join), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DisconnectRequestSection(
    isRequesting: Boolean,
    onRequest: () -> Unit
) {
    OutlinedButton(
        onClick = onRequest,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeAccent),
        enabled = !isRequesting
    ) {
        if (isRequesting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = OrangeAccent)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.common_request_sent))
        } else {
            Icon(Icons.Default.CellTower, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.common_request_disconnect))
        }
    }
}
