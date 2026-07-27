package com.cruxcoach.android.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.BoardMismatch
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.NearbySessionEntry
import com.cruxcoach.android.data.OnBoardClimbEntry
import com.cruxcoach.android.data.OnBoardSource
import com.cruxcoach.android.data.OwnSessionState
import com.cruxcoach.android.ui.theme.OrangeAccent

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
            // What this picks is the *playlist* presentation versus the generic
            // sharing one — so it has to ask whether a queue is running, not
            // whether anyone may join. Keyed on visibility, a playlist started as
            // joinable was never shown as a playlist, and a participant promoted
            // to host inherited JOINABLE and so lost the playlist look mid-session
            // for no reason the user could see.
            val isLocalSession = state.ownSession?.let { session ->
                session.isHost && session.queue.isNotEmpty()
            } == true
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isLocalSession) Icons.AutoMirrored.Filled.QueueMusic else Icons.Default.CellTower,
                    null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (isLocalSession) {
                            R.string.ble_session_visibility_local
                        } else {
                            R.string.ble_sharing_title
                        },
                    ),
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
                    boardShowsInstead = state.boardShowsInstead,
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

/**
 * Running-playlist row in the expanded view — controls moved to the
 * player screen; this is just the pointer there. Reachable only in the
 * edge case where the sheet was already expanded when playback started
 * (e.g. it stays open across a join).
 */
@Composable
private fun SessionQueueSection(
    session: OwnSessionState,
    boardShowsInstead: BoardMismatch?,
    onAddToQueue: (() -> Unit)?,
    onOpenQueueSheet: (() -> Unit)? = null
) {
    val openPlayer = LocalOpenPlaylistPlayer.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openPlayer() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = OrangeAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ble_session_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent
            )
            Text(
                buildString {
                    append(session.currentClimbName ?: stringResource(R.string.ble_unknown))
                    if (session.queue.isNotEmpty()) {
                        append(" · ${session.currentIndex + 1}/${session.queue.size}")
                    }
                    if (session.participantCount > 1) {
                        append(" · ")
                        append(stringResource(R.string.ble_participants, session.participantCount))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Only when the wall disagrees with the queue. While they match, the
            // line above already says what is on the board, and saying it twice
            // is how the two banners came to show different names.
            if (boardShowsInstead != null) {
                Text(
                    stringResource(
                        R.string.ble_board_shows_instead,
                        listOfNotNull(
                            boardShowsInstead.name ?: stringResource(R.string.ble_unknown),
                            boardShowsInstead.grade,
                        ).joinToString(" "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.ChevronRight, stringResource(R.string.cd_open), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        OnBoardSource.REMOTE_LAST -> stringResource(
            if (climb.isStillProjected) R.string.ble_still_visible else R.string.ble_ready_to_resend
        )
        OnBoardSource.LOCAL_ACTIVE -> stringResource(R.string.ble_your_climb)
        OnBoardSource.LOCAL_MANAGER -> stringResource(
            if (climb.isStillProjected) R.string.ble_still_visible else R.string.ble_ready_to_resend
        )
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
                stringResource(
                    if (climb.isStillProjected) R.string.ble_on_board else R.string.ble_last_climb
                ),
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
