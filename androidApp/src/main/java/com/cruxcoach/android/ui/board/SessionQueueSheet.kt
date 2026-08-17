package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.boardcell.BoardPlaylistProposalDecision
import com.cruxcoach.android.boardcell.LocalOverwriteRequest
import com.cruxcoach.android.data.MeshPlaylistView
import com.cruxcoach.android.data.PlaylistStartState
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.android.data.PlaylistCommandFeedbackKind
import com.cruxcoach.android.ui.theme.OrangeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionQueueSheet(
    onDismiss: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    canEdit: Boolean,
    /** When set (player context), the end/leave button delegates here so
     *  the caller can stage the summary instead of a silent teardown. */
    onEndPlaylist: (() -> Unit)? = null,
    viewModel: SessionQueueViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val climbInfos by viewModel.climbInfos.collectAsStateWithLifecycle()
    val pendingCommands by viewModel.pendingCommandCount.collectAsStateWithLifecycle()
    val startState by viewModel.playlistStartState.collectAsStateWithLifecycle()
    val localOverwriteRequest by viewModel.localOverwriteRequest.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val conflictMessage = stringResource(R.string.board_queue_command_conflict)
    val unavailableMessage = stringResource(R.string.board_queue_command_unavailable)
    val failedMessage = stringResource(R.string.board_queue_command_failed)
    LaunchedEffect(viewModel) {
        viewModel.commandFeedback.collect { feedback ->
            snackbarHostState.showSnackbar(
                when (feedback.kind) {
                    PlaylistCommandFeedbackKind.CONFLICT -> conflictMessage
                    PlaylistCommandFeedbackKind.UNAVAILABLE -> unavailableMessage
                    PlaylistCommandFeedbackKind.FAILED -> failedMessage
                },
            )
        }
    }

    // Drag-reorder state (only used when canEdit)
    var draggedFrom by remember { mutableIntStateOf(-1) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val itemHeightPx = 56f * 3f // ~56dp * density estimate
    val isDragging by remember { derivedStateOf { draggedFrom >= 0 } }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Block sheet dismiss-by-swipe while a queue item is being dragged.
        // Without this, dragging an item down also pulls the sheet closed.
        confirmValueChange = { !isDragging }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            SnackbarHost(snackbarHostState)
            if (pendingCommands > 0) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.board_queue_command_pending, pendingCommands),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.board_queue_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.queue.isNotEmpty()) {
                    Badge(containerColor = OrangeAccent) {
                        Text("${state.queue.size}")
                    }
                }
                if (state.participants.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" ${state.participantCount}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            LocalOverwriteConfirmation(
                request = localOverwriteRequest,
                onConfirm = viewModel::confirmLocalOverwrite,
                onDismiss = viewModel::dismissLocalOverwrite,
            )

            MeshPlaylistStatus(
                mesh = state.mesh,
                startState = startState,
                onRetrySend = viewModel::retryPlaylistProjection,
                onDecide = viewModel::decidePlaylistRequest,
                onDismissStartState = viewModel::acknowledgePlaylistStartState,
            )

            Spacer(Modifier.height(16.dp))

            // Navigation controls (only for editors)
            if (canEdit && state.queue.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.prev() },
                        enabled = state.currentIndex > 0
                    ) {
                        Icon(Icons.Default.SkipPrevious, stringResource(R.string.action_back), modifier = Modifier.size(32.dp))
                    }

                    Text(
                        "${state.currentIndex + 1} / ${state.queue.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )

                    IconButton(
                        onClick = { viewModel.next() },
                        enabled = state.currentIndex < state.queue.size - 1
                    ) {
                        Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // Queue list
            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(if (canEdit) R.string.board_queue_empty_editor else R.string.board_queue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Composite key: playlists may hold the SAME climb several
                    // times (limit-attempt structure) — a bare uuid key crashes
                    // LazyColumn with "Key was already used".
                    itemsIndexed(state.queue, key = { i, item -> "$i:${item.climbUuid}" }) { index, item ->
                        val isCurrent = index == state.currentIndex
                        val info = climbInfos[item.climbUuid]
                        val name = info?.name ?: item.climbUuid.take(8)

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) OrangeAccent.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(
                                width = 2.dp
                            ) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .padding(start = if (canEdit) 4.dp else 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (canEdit) {
                                    // Drag handle — long press to reorder
                                    Icon(
                                        Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.cd_reorder),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .pointerInput(index, state.queue.size) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        draggedFrom = index
                                                        dragAccumulator = 0f
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragAccumulator += dragAmount.y
                                                        val swapThreshold = itemHeightPx / 2
                                                        if (dragAccumulator > swapThreshold && draggedFrom < state.queue.size - 1) {
                                                            viewModel.moveClimb(draggedFrom, draggedFrom + 1)
                                                            draggedFrom += 1
                                                            dragAccumulator -= itemHeightPx
                                                        } else if (dragAccumulator < -swapThreshold && draggedFrom > 0) {
                                                            viewModel.moveClimb(draggedFrom, draggedFrom - 1)
                                                            draggedFrom -= 1
                                                            dragAccumulator += itemHeightPx
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        draggedFrom = -1
                                                        dragAccumulator = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggedFrom = -1
                                                        dragAccumulator = 0f
                                                    }
                                                )
                                            }
                                    )
                                    // Play/project button
                                    IconButton(
                                        onClick = { viewModel.setCurrent(index) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.cd_project),
                                            tint = if (isCurrent) OrangeAccent
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                // Climb name — tap to open details
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            // Set all queue UUIDs for swipe navigation in detail
                                            // screen — distinct: the pager keys by uuid and a
                                            // playlist may repeat climbs (attempt structure).
                                            viewModel.climbNavState.climbUuids =
                                                state.queue.map { it.climbUuid }.distinct()
                                            viewModel.climbNavState.angle = item.angle
                                            viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.QUEUE
                                            onNavigateToClimb(item.climbUuid, item.angle)
                                            onDismiss()
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            info?.gradeLabel?.let { append("$it · ") }
                                            append("${item.angle}°")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (canEdit) {
                                    IconButton(onClick = { viewModel.removeClimb(index) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.cd_remove),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Participants — always show host, plus connected participants
            if (state.hostName.isNotEmpty() || state.participants.isNotEmpty()) {
                Text(
                    stringResource(R.string.board_queue_participants, state.participantCount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                // Host (always first, with star icon)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Host",
                        tint = OrangeAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.role == SessionRole.HOST) stringResource(R.string.board_queue_you_host)
                        else state.hostName.ifEmpty { "Host" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                state.participants.forEach { participant ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            participant.displayName,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // End/Leave button. In a joinable playlist the wording follows the
            // canonical rule rather than the local role: ending is only on
            // offer while nobody else would lose their playlist.
            val mesh = state.mesh
            val buttonText = when {
                mesh != null && mesh.canEnd -> stringResource(R.string.mesh_playlist_end)
                mesh != null -> stringResource(R.string.mesh_playlist_leave)
                state.role == SessionRole.HOST -> stringResource(R.string.board_queue_end_session)
                state.role == SessionRole.PARTICIPANT -> stringResource(R.string.board_queue_leave_session)
                else -> return@Column
            }
            if (mesh != null && !mesh.canEnd) {
                Text(
                    stringResource(R.string.mesh_playlist_end_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = {
                    // The player routes this through its own stop() so the
                    // summary sheet appears; standalone contexts keep the
                    // direct end/leave.
                    if (onEndPlaylist != null) onEndPlaylist() else viewModel.endOrLeave()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(buttonText)
            }
        }
    }
}

/**
 * The joinable playlist's own state: who is in it, why the wall may be dark,
 * and the one question only its host can answer.
 *
 * Everything here comes from canonical BoardCell state, so a member that
 * reconnects or a device that inherits the playlist host role shows the same
 * thing without a separate sync path. Nothing in it mentions the technical
 * controller, which is deliberately not a product role.
 */
@Composable
private fun MeshPlaylistStatus(
    mesh: MeshPlaylistView?,
    startState: PlaylistStartState,
    onRetrySend: () -> Unit,
    onDecide: (String, BoardPlaylistProposalDecision) -> Unit,
    onDismissStartState: () -> Unit,
) {
    val startMessage = when (startState) {
        PlaylistStartState.WAITING -> stringResource(R.string.mesh_playlist_request_waiting)
        PlaylistStartState.REJECTED -> stringResource(R.string.mesh_playlist_request_rejected)
        PlaylistStartState.TIMED_OUT -> stringResource(R.string.mesh_playlist_request_timed_out)
        PlaylistStartState.BUSY -> stringResource(R.string.mesh_playlist_request_busy)
        PlaylistStartState.FAILED -> stringResource(R.string.mesh_playlist_request_failed)
        PlaylistStartState.IDLE, PlaylistStartState.STARTED -> null
    }
    if (startMessage != null) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                startMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (startState != PlaylistStartState.WAITING) {
                TextButton(onClick = onDismissStartState) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
    if (mesh == null) return

    Spacer(Modifier.height(8.dp))
    Text(
        buildString {
            append(stringResource(R.string.mesh_playlist_members, mesh.members.size))
            if (mesh.isHost) append(" · ").append(stringResource(R.string.mesh_playlist_you_host))
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // "Send pending" says only what is known: the climb is not on the wall.
    // There is no peer climb transfer in this build, so nothing here may
    // suggest that one is under way.
    mesh.pendingProjection?.let { pending ->
        val reason = when (pending.reason) {
            BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED ->
                stringResource(R.string.mesh_playlist_pending_send_write_failed)
            BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE ->
                stringResource(R.string.mesh_playlist_pending_send_unavailable)
        }
        val retryDescription = stringResource(R.string.mesh_playlist_retry_send_description)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.mesh_playlist_pending_send),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                // Retry is open to every playlist member, not just its host:
                // whoever is standing at the wall is the one who notices.
                TextButton(
                    onClick = onRetrySend,
                    modifier = Modifier.semantics { contentDescription = retryDescription },
                ) {
                    Text(stringResource(R.string.mesh_playlist_retry_send))
                }
            }
        }
    }

    mesh.decidableProposal?.let { proposal ->
        AlertDialog(
            onDismissRequest = { onDecide(proposal.requestId, BoardPlaylistProposalDecision.REJECT) },
            title = { Text(stringResource(R.string.mesh_playlist_request_title)) },
            text = {
                Text(stringResource(R.string.mesh_playlist_request_body, proposal.items.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDecide(proposal.requestId, BoardPlaylistProposalDecision.REPLACE)
                }) { Text(stringResource(R.string.mesh_playlist_request_replace)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDecide(proposal.requestId, BoardPlaylistProposalDecision.APPEND)
                    }) { Text(stringResource(R.string.mesh_playlist_request_append)) }
                    TextButton(onClick = {
                        onDecide(proposal.requestId, BoardPlaylistProposalDecision.REJECT)
                    }) { Text(stringResource(R.string.mesh_playlist_request_reject)) }
                }
            },
        )
    }
}

/**
 * The one question a local-only playlist has to ask before it lights the wall
 * over a running joinable playlist.
 *
 * Asked once per playlist, not per send: the point is consent to take the
 * board, not a confirmation habit. Declining leaves both playlists exactly as
 * they were — the shared queue and index never move because of a local send.
 */
@Composable
private fun LocalOverwriteConfirmation(
    request: LocalOverwriteRequest?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mesh_playlist_overwrite_title)) },
        text = { Text(stringResource(R.string.mesh_playlist_overwrite_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.mesh_playlist_overwrite_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
