package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.boardcell.BoardPlaylistAnchor
import com.cruxcoach.android.boardcell.BoardPlaylistEditKind
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.data.BoardPlaylistLogMark
import com.cruxcoach.android.data.PlaylistCommandFeedbackKind
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun moveBoardPlaylistPreview(order: List<String>, from: Int, to: Int): List<String> {
    if (from !in order.indices || to !in order.indices || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun boardPlaylistDragStartIndex(order: List<String>, entryId: String): Int =
    order.indexOf(entryId)

internal fun canonicalOrderMatchesPreview(canonical: List<String>, preview: List<String>): Boolean {
    val previewIds = preview.toHashSet()
    return canonical.filter { it in previewIds } == preview
}

internal fun boardPlaylistAnchorForOrder(
    order: List<String>,
    entryId: String,
): BoardPlaylistAnchor? {
    val index = order.indexOf(entryId)
    if (index < 0) return null
    return if (index == 0) BoardPlaylistAnchor.Head
    else BoardPlaylistAnchor.After(order[index - 1])
}

/**
 * The board's shared list — the screen the Board-Playlist actually lives on.
 *
 * It is the first thing a board shows you, and everything about the list is
 * done here: what the group is on, what the wall is actually showing, adding,
 * reordering, removing, clearing and putting a clear back. The focused player
 * is one layer deeper and is about the *current* climb; it never has to be
 * open for the list to work, and closing it never means leaving the board.
 *
 * There is no host and no session in any of it. Being on the board is taking
 * part, every member may do every one of these things, and the technical
 * controller — the device that happens to serialize the edits and write the
 * wall — is not named anywhere on this screen because it is not a role anybody
 * holds over anybody.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardPlaylistScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBrowser: () -> Unit,
    onOpenPlayer: () -> Unit,
    /** Opens one occurrence on this device only. Carries its stable entry id. */
    onOpenEntry: (entryId: String, climbUuid: String, angle: Int) -> Unit,
    viewModel: BoardPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Re-entering this destination after logging a climb must immediately
    // repaint the personal (never shared) success/attempt colours.
    LaunchedEffect(Unit) { viewModel.refreshPersonalLogs() }

    val conflictMessage = stringResource(R.string.board_playlist_command_conflict)
    val unavailableMessage = stringResource(R.string.board_playlist_command_unavailable)
    val failedMessage = stringResource(R.string.board_playlist_command_failed)
    val noRandomMatchMessage = stringResource(R.string.board_playlist_random_unavailable)
    val unknownBoardMessage = stringResource(R.string.board_playlist_random_board_unknown)
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
    LaunchedEffect(viewModel) {
        viewModel.randomAddUnavailable.collect { roll ->
            snackbarHostState.showSnackbar(
                if (roll is RandomClimbRoll.BoardUnknown) unknownBoardMessage
                else noRandomMatchMessage,
            )
        }
    }

    if (showClearConfirm) {
        // One confirmation, not two. What makes emptying a shared list safe
        // enough to offer everybody is the canonical restore behind it, not a
        // second dialog nobody reads.
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text(stringResource(R.string.board_playlist_clear_title),
                    fontWeight = FontWeight.Bold)
            },
            text = { Text(stringResource(R.string.board_playlist_clear_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clear()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag("board_playlist_clear_confirm"),
                ) {
                    Text(stringResource(R.string.board_playlist_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.board_playlist_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val subtitle = buildString {
                                state.boardName?.let { append(it) }
                                if (state.memberCount > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append(pluralStringResource(R.plurals.board_people_count,
                                        state.memberCount, state.memberCount))
                                }
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        if (state.memberCount > 1) {
                            Icon(Icons.Default.People, contentDescription = null,
                                tint = OrangeAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("${state.memberCount}", color = OrangeAccent,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                        }
                        // The focused player is a second layer on the same
                        // list, not a different mode: it opens on whatever the
                        // group is on and closes back to here.
                        IconButton(
                            onClick = {
                                viewModel.resumePlayer()
                                onOpenPlayer()
                            },
                            enabled = !state.isEmpty,
                            modifier = Modifier.testTag("board_playlist_open_player"),
                        ) {
                            Icon(
                                Icons.Default.OpenInFull,
                                contentDescription =
                                    stringResource(R.string.board_playlist_open_player),
                            )
                        }
                        IconButton(
                            onClick = { showClearConfirm = true },
                            enabled = !state.isEmpty,
                            modifier = Modifier.testTag("board_playlist_clear"),
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.board_playlist_clear_title),
                                tint = if (state.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
                if (state.pendingCommands > 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
        bottomBar = {
            if (state.available) {
                BoardPlaylistTransport(
                    state = state,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                    onLamp = viewModel::projectSelectedEntry,
                    onAdd = onNavigateToBrowser,
                    onAddRandom = viewModel::appendRandom,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.available) {
                BoardPlaylistUnavailable()
                return@Column
            }
            BoardPlaylistStatus(state)
            state.restore?.let { offer ->
                RestoreOfferCard(offer = offer, onRestore = viewModel::restore)
            }
            if (state.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.board_playlist_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.board_playlist_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNavigateToBrowser) {
                            Icon(Icons.Default.Add, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.board_playlist_add_climbs))
                        }
                    }
                }
            } else {
                BoardPlaylistRows(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onOpen = { entryId, climbUuid, angle ->
                        viewModel.rememberOpenedEntry(entryId, climbUuid)
                        onOpenEntry(entryId, climbUuid, angle)
                    },
                    onLight = viewModel::lightEntry,
                    onRemove = viewModel::remove,
                    onRepeat = viewModel::repeatAfter,
                    onMove = viewModel::move,
                )
            }
            state.undo?.let { edit ->
                UndoBar(
                    kind = edit.kind,
                    onUndo = viewModel::undo,
                    onDismiss = viewModel::dismissUndo,
                )
            }
        }
    }
}

/**
 * Selected, confirmed, and the difference between them.
 *
 * Stepping through the list changes what the group is looking at; it does not
 * change what is on the wall. Those are two facts and this says both, because
 * collapsing them into one is how somebody ends up climbing the wrong problem.
 */
@Composable
private fun BoardPlaylistStatus(state: BoardPlaylistUiState) {
    val pending = state.pendingProjection
    val status = when {
        pending != null -> stringResource(
            when (pending.reason) {
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED ->
                    R.string.board_playlist_send_write_failed
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE ->
                    R.string.board_playlist_send_unavailable
            },
        ) to MaterialTheme.colorScheme.error
        state.isEmpty -> null
        state.selectionOnBoard ->
            stringResource(R.string.board_playlist_on_board) to SuccessGreen
        state.boardClimbUnknown ->
            stringResource(R.string.board_playlist_board_unknown) to
                MaterialTheme.colorScheme.onSurfaceVariant
        state.confirmedClimbName != null -> stringResource(
            R.string.board_playlist_board_shows, state.confirmedClimbName,
        ) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.board_playlist_not_on_board) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    if (status == null && state.synchronized && state.pendingCommands == 0) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        // Having a copy of the list and being up to date with the group are
        // different things, and only one of them is safe to act on.
        if (!state.synchronized) {
            Text(
                stringResource(R.string.board_playlist_out_of_sync),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        status?.let { (text, color) ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (state.pendingCommands > 0) {
            Text(
                stringResource(R.string.board_playlist_command_pending, state.pendingCommands),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The canonical way back from a clear.
 *
 * Shown to every member, actionable by every member, and counted down from the
 * controller's stamped deadline rather than from whenever this device happened
 * to notice — so two phones never disagree about how long is left.
 */
@Composable
private fun RestoreOfferCard(
    offer: BoardPlaylistRestoreOffer,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("board_playlist_restore"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp,
                bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.board_playlist_restore_title,
                        offer.entryCount, offer.entryCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.board_playlist_restore_remaining,
                        offer.secondsRemaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onRestore,
                modifier = Modifier.testTag("board_playlist_restore_action"),
            ) {
                Text(stringResource(R.string.board_playlist_restore_action),
                    fontWeight = FontWeight.Bold, color = OrangeAccent)
            }
        }
    }
}

/**
 * The undo this device is offered for the edit it just made.
 *
 * Names the operation rather than saying "Undo" on its own: several people are
 * editing this list at once, and an offer that does not say what it reverses
 * is one nobody can safely accept.
 */
@Composable
private fun UndoBar(
    kind: BoardPlaylistEditKind,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Snackbar(
        modifier = Modifier.padding(12.dp).testTag("board_playlist_undo"),
        action = {
            TextButton(onClick = onUndo) {
                Text(stringResource(R.string.board_playlist_undo_action), color = OrangeAccent)
            }
        },
        dismissAction = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    ) {
        Text(
            stringResource(
                when (kind) {
                    BoardPlaylistEditKind.ADD -> R.string.board_playlist_undo_add
                    BoardPlaylistEditKind.REMOVE -> R.string.board_playlist_undo_remove
                    BoardPlaylistEditKind.MOVE -> R.string.board_playlist_undo_move
                    BoardPlaylistEditKind.SELECT -> R.string.board_playlist_undo_select
                    BoardPlaylistEditKind.REST -> R.string.board_playlist_undo_rest
                    BoardPlaylistEditKind.CLEAR_REPEATS -> R.string.board_playlist_undo_repeats
                },
            ),
        )
    }
}

@Composable
private fun BoardPlaylistUnavailable() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.board_playlist_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Previous, the lamp, next.
 *
 * The lamp sits between them because that is the shape of the decision: the
 * two arrows move the group's selection and deliberately leave the wall alone,
 * and the one control in the middle is the whole of "and now put it up there".
 * It is the only thing in the Board-Playlist that writes the physical board,
 * on any screen, which is what makes an accidental projection impossible
 * rather than merely unlikely.
 */
@Composable
private fun BoardPlaylistTransport(
    state: BoardPlaylistUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLamp: () -> Unit,
    onAdd: () -> Unit,
    onAddRandom: () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun withHaptic(action: () -> Unit): () -> Unit = {
        haptics.performHapticFeedback(
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        action()
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // The three that belong together sit together, centred. "Add climbs"
        // is a different kind of thing and stays out of the group rather than
        // pulling the lamp off centre.
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            IconButton(
                onClick = withHaptic(onPrevious),
                enabled = state.hasPrevious,
                modifier = Modifier.size(56.dp).testTag("board_playlist_prev"),
            ) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_previous),
                    modifier = Modifier.size(36.dp))
            }
            // A lit wall is not a disabled action: pressing the lamp again is
            // the explicit resend for a board that was changed or missed the
            // previous write. Keep the control visibly active whenever there
            // is a selected climb instead of turning it into a grey status
            // indicator after the first successful send.
            FilledIconButton(
                onClick = withHaptic(onLamp),
                enabled = !state.isEmpty,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = OrangeAccent,
                    contentColor = DarkBackground,
                ),
                modifier = Modifier.size(64.dp).testTag("board_playlist_lamp"),
            ) {
                Icon(Icons.Default.Lightbulb, stringResource(R.string.board_playlist_lamp),
                    modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = withHaptic(onNext),
                enabled = state.hasNext,
                modifier = Modifier.size(56.dp).testTag("board_playlist_next"),
            ) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next),
                    modifier = Modifier.size(36.dp))
            }
        }
        IconButton(
            onClick = onAddRandom,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .testTag("board_playlist_add_random"),
        ) {
            Icon(Icons.Default.Casino, stringResource(R.string.board_playlist_add_random),
                modifier = Modifier.size(26.dp))
        }
        IconButton(
            onClick = onAdd,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp)
                .testTag("board_playlist_add"),
        ) {
            Icon(Icons.Default.Add, stringResource(R.string.board_playlist_add_climbs),
                modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun BoardPlaylistRows(
    state: BoardPlaylistUiState,
    modifier: Modifier,
    onOpen: (entryId: String, climbUuid: String, angle: Int) -> Unit,
    onLight: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onMove: (String, BoardPlaylistAnchor) -> Unit,
) {
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    var draggedFrom by remember { mutableIntStateOf(-1) }
    var dragStartedAt by remember { mutableIntStateOf(-1) }
    var draggedEntryId by remember { mutableStateOf<String?>(null) }
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var awaitingDragCommit by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var autoScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val itemHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
    val rowsById = state.rows.associateBy { it.entryId }
    val visibleRows = previewOrder?.mapNotNull(rowsById::get) ?: state.rows
    val canonicalOrder = state.rows.map { it.entryId }
    val latestCanonicalOrder by rememberUpdatedState(canonicalOrder)

    // Keep the local preview in place across the mesh round-trip. New entries
    // from other members do not prevent reconciliation: compare only the ids
    // that existed in the preview.
    LaunchedEffect(canonicalOrder, awaitingDragCommit) {
        val preview = previewOrder ?: return@LaunchedEffect
        if (!awaitingDragCommit) return@LaunchedEffect
        if (canonicalOrderMatchesPreview(canonicalOrder, preview)) {
            previewOrder = null
            awaitingDragCommit = false
        }
    }
    LaunchedEffect(awaitingDragCommit) {
        if (!awaitingDragCommit) return@LaunchedEffect
        delay(5_000)
        // A rejected or unreachable edit must eventually return to canonical
        // truth rather than leaving an optimistic order on screen forever.
        previewOrder = null
        awaitingDragCommit = false
    }

    fun finishDrag(commit: Boolean) {
        val entryId = draggedEntryId
        val target = draggedFrom
        val finalOrder = previewOrder
        val changed = dragStartedAt >= 0 && target >= 0 && dragStartedAt != target
        draggedFrom = -1
        dragStartedAt = -1
        draggedEntryId = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        dragAccumulator = 0f
        val anchor = if (entryId == null || finalOrder == null) null
        else boardPlaylistAnchorForOrder(finalOrder, entryId)
        if (commit && changed && entryId != null && anchor != null) {
            awaitingDragCommit = true
            onMove(entryId, anchor)
        } else {
            previewOrder = null
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 4.dp),
    ) {
        // Occurrence ids, not positions: the same climb legitimately appears
        // several times and a position key would make Compose reuse the wrong
        // row the moment anybody else inserts anything.
        itemsIndexed(visibleRows, key = { _, row -> row.entryId }) { _, row ->
            val canonicalIndex = canonicalOrder.indexOf(row.entryId)
            val moveUp = if (canonicalIndex > 0 && !awaitingDragCommit) {
                {
                    val moved = moveBoardPlaylistPreview(
                        canonicalOrder, canonicalIndex, canonicalIndex - 1)
                    boardPlaylistAnchorForOrder(moved, row.entryId)?.let {
                        onMove(row.entryId, it)
                    }
                    Unit
                }
            } else null
            val moveDown = if (canonicalIndex in 0 until canonicalOrder.lastIndex &&
                !awaitingDragCommit
            ) {
                {
                    val moved = moveBoardPlaylistPreview(
                        canonicalOrder, canonicalIndex, canonicalIndex + 1)
                    boardPlaylistAnchorForOrder(moved, row.entryId)?.let {
                        onMove(row.entryId, it)
                    }
                    Unit
                }
            } else null
            BoardPlaylistRowCard(
                row = row,
                onOpen = { onOpen(row.entryId, row.climbUuid, row.angle) },
                onLight = { onLight(row.entryId) },
                onRemove = { onRemove(row.entryId) },
                onRepeat = { onRepeat(row.entryId) },
                onMoveUp = moveUp,
                onMoveDown = moveDown,
                dragModifier = Modifier.pointerInput(
                    row.entryId, awaitingDragCommit,
                ) {
                    if (awaitingDragCommit) return@pointerInput
                    try {
                        detectDragGestures(
                        onDragStart = {
                            val order = latestCanonicalOrder
                            val from = boardPlaylistDragStartIndex(order, row.entryId)
                            if (from >= 0) {
                                draggedFrom = from
                                dragStartedAt = from
                                draggedEntryId = row.entryId
                                previewOrder = order
                                dragAccumulator = 0f
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragAccumulator += amount.y
                            val threshold = itemHeightPx / 2
                            var order = previewOrder ?: return@detectDragGestures
                            while (dragAccumulator > threshold && draggedFrom < order.size - 1) {
                                val from = draggedFrom
                                draggedFrom += 1
                                order = moveBoardPlaylistPreview(order, from, draggedFrom)
                                previewOrder = order
                                dragAccumulator -= itemHeightPx
                            }
                            while (dragAccumulator < -threshold && draggedFrom > 0) {
                                val from = draggedFrom
                                draggedFrom -= 1
                                order = moveBoardPlaylistPreview(order, from, draggedFrom)
                                previewOrder = order
                                dragAccumulator += itemHeightPx
                            }
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val scrollBy = when {
                                amount.y < 0 && draggedFrom <= (visible.firstOrNull()?.index ?: 0) + 1 ->
                                    -itemHeightPx / 2
                                amount.y > 0 && draggedFrom >= (visible.lastOrNull()?.index ?: 0) - 1 ->
                                    itemHeightPx / 2
                                else -> 0f
                            }
                            if (scrollBy != 0f && autoScrollJob?.isActive != true) {
                                autoScrollJob = dragScope.launch { listState.scrollBy(scrollBy) }
                            }
                        },
                        onDragEnd = { finishDrag(commit = true) },
                        onDragCancel = { finishDrag(commit = false) },
                        )
                    } finally {
                        // Disposal (for example when this occurrence is removed
                        // remotely) must not strand an optimistic preview.
                        if (draggedEntryId == row.entryId && !awaitingDragCommit) {
                            finishDrag(commit = false)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BoardPlaylistRowCard(
    row: BoardPlaylistRow,
    onOpen: () -> Unit,
    onLight: () -> Unit,
    onRemove: () -> Unit,
    onRepeat: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    dragModifier: Modifier,
) {
    // Lightly dimmed, not greyed out: an entry the group has gone past is
    // still there, still editable and still something somebody may want
    // another go at. It is behind you, not gone.
    val dim = if (row.isPast && !row.isCurrent) 0.62f else 1f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(dim)
            // Opening an entry is a local act. It shows this device the climb;
            // it does not move the group's current, which is what the lamp is
            // for. The two used to be the same tap, which meant looking at
            // something changed what everybody else was looking at.
            .clickable(onClick = onOpen)
            .testTag("board_playlist_row"),
        colors = CardDefaults.cardColors(
            containerColor = if (row.isCurrent) OrangeAccent.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp,
                bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val moveUpLabel = stringResource(R.string.playlist_move_up)
            val moveDownLabel = stringResource(R.string.playlist_move_down)
            val reorderActions = buildList {
                onMoveUp?.let { move ->
                    add(CustomAccessibilityAction(moveUpLabel) { move(); true })
                }
                onMoveDown?.let { move ->
                    add(CustomAccessibilityAction(moveDownLabel) { move(); true })
                }
            }
            Box(
                modifier = Modifier.size(48.dp).then(dragModifier)
                    .semantics(mergeDescendants = true) { customActions = reorderActions },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.cd_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
            SendMarkDot(row.mark)
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.isCurrent) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The same problem queued more than once is the normal
                    // shape of a 4x4 or a limit block, so the list says which
                    // go this is rather than looking like it repeated itself.
                    if (row.duplicateCount > 1) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = OrangeAccent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                stringResource(R.string.board_playlist_duplicate,
                                    row.duplicateIndex, row.duplicateCount),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    buildString {
                        row.gradeLabel?.let { append("$it · ") }
                        append("${row.angle}°")
                        if (row.restAfterSeconds > 0) {
                            append(" · ")
                            append(stringResource(R.string.board_playlist_rest_after,
                                row.restAfterSeconds))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onLight,
                modifier = Modifier.size(48.dp).testTag("board_playlist_row_light"),
            ) {
                Icon(
                    Icons.Default.Lightbulb,
                    contentDescription = stringResource(R.string.board_playlist_light_entry),
                    tint = if (row.isCurrent) OrangeAccent
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRepeat, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = stringResource(R.string.board_playlist_repeat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(48.dp).testTag("board_playlist_row_remove"),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** This user's own logbook: sent, attempted, or not attempted. Never shared. */
@Composable
private fun SendMarkDot(mark: BoardPlaylistLogMark) {
    val color = when (mark) {
        BoardPlaylistLogMark.SENT -> SuccessGreen
        BoardPlaylistLogMark.ATTEMPTED -> ErrorRed
        BoardPlaylistLogMark.UNATTEMPTED ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    val description = stringResource(
        when (mark) {
            BoardPlaylistLogMark.SENT -> R.string.board_playlist_mark_sent
            BoardPlaylistLogMark.ATTEMPTED -> R.string.board_playlist_mark_attempted
            BoardPlaylistLogMark.UNATTEMPTED -> R.string.board_playlist_mark_unattempted
        },
    )
    Icon(
        imageVector = when (mark) {
            BoardPlaylistLogMark.SENT -> Icons.Default.CheckCircle
            BoardPlaylistLogMark.ATTEMPTED -> Icons.Default.Cancel
            BoardPlaylistLogMark.UNATTEMPTED -> Icons.Default.RadioButtonUnchecked
        },
        contentDescription = description,
        tint = color,
        modifier = Modifier.size(18.dp),
    )
}
