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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    onRandomToQueue: (() -> Unit)? = null,
    /** Client count while the board is shared, or null when it is not. */
    relayClientCount: Int? = null,
    onStopRelay: (() -> Unit)? = null,
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
        // The mini-player owns its own card, so the sharing line trails it
        // rather than sitting inside — still one block, not a detached strip.
        if (relayClientCount != null && onStopRelay != null) {
            RelaySharingLine(clientCount = relayClientCount, onStop = onStopRelay)
        }
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
      Column {
        // Sharing alone is enough to show this block, but it has nothing to
        // say in the summary line — rendering the row anyway left a bare
        // icon + chevron above the sharing line.
        val summary = buildChipSummary(effectiveOnBoard, state)
        if (summary.isNotBlank()) {
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
        if (relayClientCount != null && onStopRelay != null) {
            RelaySharingLine(
                clientCount = relayClientCount,
                onStop = onStopRelay,
                showDivider = summary.isNotBlank(),
            )
        }
      }
    }
}

/**
 * Mini-player: the compact "playlist is running" line. One glance
 * (timer, position, climb, participants), one shortcut (Next) — every
 * other control lives in the player screen, which a tap opens. Replaces
 * the old inline Prev/Pause/Stop strip that crowded the chip.
 */
@Composable
internal fun SessionChipContent(
    session: OwnSessionState,
    effectiveOnBoard: OnBoardClimbEntry?,
    onExpand: () -> Unit,
    onAddToQueue: (() -> Unit)?,
    onRandomToQueue: (() -> Unit)? = null
) {
    val playback = LocalPlaylistPlayback.current
    val playbackState by playback.state.collectAsStateWithLifecycle()

    val sessionManager = LocalBoardSessionManager.current
    val sessionState by sessionManager.state.collectAsStateWithLifecycle()
    val openPlayer = LocalOpenPlaylistPlayer.current

    val timerColor = when {
        !sessionState.isActive -> OrangeAccent
        sessionState.isPaused -> WarningYellow
        else -> SuccessGreen
    }

    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openPlayer() }
                .testTag("ble_mini_player"),
            colors = CardDefaults.cardColors(containerColor = timerColor.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

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

                // The playlist keeps this line. It used to lose it to whatever
                // was on the wall, in a row that otherwise carries only session
                // things — timer, participant count, next button — so an
                // unrelated climb read as "this is what you are playing". What
                // the wall shows is a statement about the wall; it belongs in
                // the line below, and only when the two disagree.
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
                    // Re-sending the queue's climb used to live in the conflict
                    // line, so it was reachable only once something had already
                    // gone wrong — though wanting the wall to show your climb
                    // again is an ordinary wish.
                    if (playbackState.isHost) {
                        IconButton(
                            onClick = { playback.resendCurrentClimb() },
                            modifier = Modifier.size(28.dp).testTag("ble_queue_resend")
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                stringResource(R.string.ble_queue_resend),
                                tint = OrangeAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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

                // Single shortcut: Next — the one control you want at the
                // wall without opening the player. Routed through the
                // coordinator so it stays phase-aware (skips a running
                // rest instead of jumping past the upcoming climb).
                IconButton(
                    onClick = { playback.next() },
                    enabled = playbackState.hasNext,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), modifier = Modifier.size(22.dp))
                }
                // Open-player affordance.
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.cd_open),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        // Second line: the wall, and only when it disagrees with the queue.
        // These two were the other way round — the queue was banished here and
        // appeared only during a conflict, while the wall took the line above.
        // Compared by UUID, never by name: names resolve asynchronously, so a
        // name comparison would report a mismatch for a moment on every advance.
        val boardDiffers = effectiveOnBoard != null &&
            effectiveOnBoard.climbUuid.isNotBlank() &&
            effectiveOnBoard.climbUuid != session.queue.getOrNull(session.currentIndex)?.climbUuid
        if (session.externalBoardOverride || boardDiffers) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = WarningYellow.copy(alpha = 0.10f)),
                shape = RoundedCornerShape(0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SignalCellularAlt, null, tint = WarningYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (session.externalBoardOverride || effectiveOnBoard == null) {
                            stringResource(R.string.ble_external_board_override)
                        } else {
                            stringResource(
                                R.string.ble_board_shows_instead,
                                buildString {
                                    append(effectiveOnBoard.name ?: stringResource(R.string.ble_unknown))
                                    if (effectiveOnBoard.grade != null) append(" ${effectiveOnBoard.grade}")
                                    append(" ${effectiveOnBoard.angle}°")
                                },
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningYellow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
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
 * FEAT-044 §12: "board is shared" status with a one-tap stop.
 *
 * Rides inside the regular BLE status block rather than as a card of its own.
 * As a separate strip it read as unrelated to the board state directly above
 * it, and on a screen with nothing else to show the block below it collapsed
 * entirely — the host then saw sharing but not the climb on the board.
 *
 * Stopping affects only relay transport; queue and board ownership stay intact.
 */
@Composable
internal fun RelaySharingLine(
    clientCount: Int,
    onStop: () -> Unit,
    /** Off when this line is the only content of its block. */
    showDivider: Boolean = true,
) {
    if (showDivider) {
        HorizontalDivider(
            color = SuccessGreen.copy(alpha = 0.25f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("relay_status_chip")
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
