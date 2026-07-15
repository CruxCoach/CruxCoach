package com.cruxcoach.android.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.OnBoardClimbEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.OwnSessionState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow

@Composable
internal fun BleStatusChip(
    state: BleShareUiState,
    effectiveOnBoard: OnBoardClimbEntry?,
    onExpand: () -> Unit,
    onAddToQueue: (() -> Unit)?,
    onRandomToQueue: (() -> Unit)? = null
) {
    val session = state.ownSession

    // Session mode: show session chip with inline controls
    if (session != null) {
        SessionChipContent(
            session = session,
            effectiveOnBoard = effectiveOnBoard,
            onExpand = onExpand,
            onAddToQueue = onAddToQueue,
            onRandomToQueue = onRandomToQueue
        )
        return
    }

    // Normal BLE sharing chip
    val pulse = rememberInfiniteTransition(label = "ble_pulse")
    val iconAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "ble_icon_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onExpand() },
        colors = CardDefaults.cardColors(
            containerColor = OrangeAccent.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CellTower,
                contentDescription = null,
                tint = OrangeAccent,
                modifier = Modifier
                    .size(18.dp)
                    .alpha(if (effectiveOnBoard?.source == OnBoardSource.REMOTE_ACTIVE) iconAlpha else 1f)
            )
            Spacer(Modifier.width(8.dp))

            val summary = buildChipSummary(effectiveOnBoard, state)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (effectiveOnBoard != null) {
                SignalIndicator(rssi = effectiveOnBoard.rssi ?: -80)
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.cd_expand),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Session chip with inline controls — Add/Prev/Next/Pause/Stop + session info. */
@Composable
internal fun SessionChipContent(
    session: OwnSessionState,
    effectiveOnBoard: OnBoardClimbEntry?,
    onExpand: () -> Unit,
    onAddToQueue: (() -> Unit)?,
    onRandomToQueue: (() -> Unit)? = null
) {
    val queueManager = LocalSessionQueueManager.current
    val gattBridge = LocalSessionGattBridge.current
    val queueState by queueManager.state.collectAsStateWithLifecycle()
    val isParticipant = queueState.role == SessionRole.PARTICIPANT

    val sessionManager = LocalBoardSessionManager.current
    val sessionState by sessionManager.state.collectAsStateWithLifecycle()

    // Bug 6: Internalized pause/stop via CompositionLocals — works on every screen
    val handleTogglePause: () -> Unit = {
        if (sessionState.isPaused) sessionManager.resumeSession()
        else sessionManager.pauseSession()
    }
    val bleShareManager = LocalBleShareManager.current
    val handleStop: () -> Unit = {
        val lastClimb = queueManager.state.value.currentClimb
        if (queueState.role == SessionRole.HOST) {
            gattBridge.stopSharing()
            queueManager.endQueue()
        } else {
            gattBridge.leaveSession()
        }
        sessionManager.endSession()
        if (lastClimb != null) {
            bleShareManager.setLastClimbAfterSession(lastClimb.climbUuid, lastClimb.angle)
        }
    }

    val timerColor = when {
        !sessionState.isActive -> OrangeAccent
        sessionState.isPaused -> WarningYellow
        else -> SuccessGreen
    }

    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpand() },
            colors = CardDefaults.cardColors(containerColor = timerColor.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Expand chevron — left side, away from Stop to prevent accidental taps
                Icon(Icons.Default.ExpandMore, stringResource(R.string.cd_expand), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))

                // Timer
                if (sessionState.isActive) {
                    Icon(Icons.Default.Timer, null, tint = timerColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        formatSessionTime(sessionState.elapsedSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = timerColor
                    )
                    if (sessionState.isPaused) {
                        Text(" ⏸", style = MaterialTheme.typography.bodySmall, color = WarningYellow.copy(alpha = 0.7f))
                    }
                    Spacer(Modifier.width(10.dp))
                }

                // Board climb (preferred) or queue info
                if (effectiveOnBoard != null) {
                    // Board was externally overwritten — show what's actually on the board
                    Icon(Icons.Default.SignalCellularAlt, null, tint = WarningYellow, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        buildString {
                            append("\"${effectiveOnBoard.name ?: stringResource(R.string.ble_unknown)}\"")
                            if (effectiveOnBoard.grade != null) append(" ${effectiveOnBoard.grade}")
                            append(" ${effectiveOnBoard.angle}°")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WarningYellow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = OrangeAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    if (session.queue.isEmpty()) {
                        Text(stringResource(R.string.common_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                    } else {
                        Text(
                            "${session.currentIndex + 1}/${session.queue.size}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = OrangeAccent
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            buildString {
                                append(session.currentClimbName ?: "")
                                if (session.currentClimbGrade != null) append(" ${session.currentClimbGrade}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Participant count
                if (session.participantCount > 0) {
                    Icon(Icons.Default.People, null, tint = OrangeAccent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${session.participantCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OrangeAccent
                    )
                    Spacer(Modifier.width(6.dp))
                }

                // Random button on browser, Add button on detail screen
                if (onRandomToQueue != null) {
                    IconButton(onClick = onRandomToQueue, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Casino, stringResource(R.string.cd_random_add), modifier = Modifier.size(22.dp), tint = OrangeAccent)
                    }
                } else if (onAddToQueue != null) {
                    IconButton(onClick = onAddToQueue, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Add, stringResource(R.string.cd_add), modifier = Modifier.size(22.dp), tint = OrangeAccent)
                    }
                }

                // Inline controls: Prev/Next/Pause/Stop
                IconButton(
                    onClick = { if (isParticipant) gattBridge.sendPrev() else queueManager.previousClimb() },
                    enabled = session.currentIndex > 0,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_previous), modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { if (isParticipant) gattBridge.sendNext() else queueManager.nextClimb() },
                    enabled = session.currentIndex < session.queue.size - 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = handleTogglePause, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (sessionState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (sessionState.isPaused) stringResource(R.string.cd_resume) else stringResource(R.string.cd_pause),
                        tint = timerColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = handleStop, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Stop, stringResource(R.string.cd_stop), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Second line: queue info when board shows external climb
        if (effectiveOnBoard != null && session.queue.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = OrangeAccent.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append("Queue ${session.currentIndex + 1}/${session.queue.size}: ")
                            append(session.currentClimbName ?: stringResource(R.string.ble_unknown))
                            if (session.currentClimbGrade != null) append(" ${session.currentClimbGrade}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OrangeAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun SignalIndicator(rssi: Int) {
    val icon = when {
        rssi >= -50 -> Icons.Default.SignalCellular4Bar
        rssi >= -65 -> Icons.Default.SignalCellularAlt
        else -> Icons.Default.SignalCellularAlt1Bar
    }
    val tint = when {
        rssi >= -50 -> OrangeAccent
        rssi >= -65 -> OrangeAccent.copy(alpha = 0.7f)
        else -> OrangeAccent.copy(alpha = 0.4f)
    }
    Icon(icon, stringResource(R.string.cd_signal, rssi), tint = tint, modifier = Modifier.size(16.dp))
}

@Composable
internal fun buildChipSummary(
    onBoard: OnBoardClimbEntry?,
    state: BleShareUiState
): String = buildString {
    if (onBoard != null) {
        val name = onBoard.name ?: stringResource(R.string.ble_unknown_climb)
        append("\"$name\"")
        if (onBoard.grade != null) append(" ${onBoard.grade}")
        append(" ${onBoard.angle}°")
        when (onBoard.source) {
            OnBoardSource.REMOTE_ACTIVE -> append(" · ${stringResource(R.string.ble_climbing_now)}")
            OnBoardSource.REMOTE_LAST, OnBoardSource.LOCAL_MANAGER -> append(
                " · ${stringResource(if (onBoard.isStillProjected) R.string.ble_still_visible else R.string.ble_last_climb)}"
            )
            OnBoardSource.LOCAL_ACTIVE -> append(" · ${stringResource(R.string.ble_your_climb)}")
            OnBoardSource.SESSION_REMOTE -> append(" · ${stringResource(R.string.ble_session_climb)}")
        }
        // On-board climb is the primary info — skip secondary session/occupied counts
        return@buildString
    }
    if (state.boardOccupiedCount > 0) {
        append(stringResource(R.string.ble_board_occupied))
    }
    if (state.nearbySessions.isNotEmpty()) {
        if (isNotEmpty()) append(" · ")
        append("${state.nearbySessions.size} Session${if (state.nearbySessions.size > 1) "s" else ""}")
    }
}

internal fun formatSessionTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * FEAT-044 §12: persistent "board is shared" status with a one-tap stop.
 * Rendered by [BleStatusArea] on every screen while CruxRelay is active;
 * stopping runs the §7 host-leave ordering in CruxRelayManager.
 */
@Composable
internal fun RelayStatusChip(
    clientCount: Int,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("relay_status_chip"),
        colors = CardDefaults.cardColors(
            containerColor = SuccessGreen.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CellTower,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.relay_chip_text, clientCount),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop, modifier = Modifier.testTag("relay_chip_stop")) {
                Text(
                    stringResource(R.string.relay_chip_stop),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * FEAT-044 §12: terminal relay errors (board lost, failed start) rendered by
 * [BleStatusArea] once sharing is off — the sheet's error surface only exists
 * while its CONNECTED branch shows, which is gone by then. Dismiss clears the
 * error on the manager.
 */
@Composable
internal fun RelayErrorRow(
    text: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("relay_error_row"),
        colors = CardDefaults.cardColors(
            containerColor = ErrorRed.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("relay_error_dismiss")) {
                Text(
                    stringResource(R.string.action_ok),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
