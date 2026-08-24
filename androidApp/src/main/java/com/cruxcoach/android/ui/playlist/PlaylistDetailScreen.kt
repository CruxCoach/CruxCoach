package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.BleConnectionViewModel
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.util.GradeDisplayHelper
import kotlinx.coroutines.launch

/**
 * Optional ordered training plan belonging to a normal list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (String, Int) -> Unit,
    /** Called after the plan was loaded into the session queue. */
    onPlayed: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var showAddRestDialog by rememberSaveable { mutableStateOf(false) }
    var addRestAfterEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showBleSheet by rememberSaveable { mutableStateOf(false) }

    // A plan can be started without a board attached, and BleStatusArea below
    // only surfaces once there is actual BLE activity — so with nothing
    // connected this screen offered no way to connect at all.
    val bleConnectionViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnectionState by bleConnectionViewModel.state.collectAsStateWithLifecycle()
    val isBleConnected =
        bleConnectionState.connectionState == ConnectionState.CONNECTED ||
            bleConnectionState.connectionState == ConnectionState.SENDING
    var draggedEntryId by remember { mutableStateOf<Long?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var dragScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val playlistListState = rememberLazyListState()
    val latestEntries by rememberUpdatedState(state.entries)
    val dragStepPx = with(LocalDensity.current) { 64.dp.toPx() }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val screenScope = androidx.compose.runtime.rememberCoroutineScope()
    val linkCopiedMessage = stringResource(R.string.board_detail_link_copied)
    val queueTitle = stringResource(R.string.board_queue_title)

    fun finishEntryDrag() {
        val fromIndex = dragStartIndex
        val toIndex = dragCurrentIndex
        draggedEntryId = null
        dragStartIndex = -1
        dragCurrentIndex = -1
        dragAccumulator = 0f
        dragScrollJob?.cancel()
        dragScrollJob = null
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
            viewModel.commitPreviewedOrder()
        }
    }

    // Session + rest-timer notifications (Android 13+) — same fire-and-
    // forget pattern as the browser's session start.
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // The ViewModel survives navigation to a climb. Refresh on return so a
    // newly added list member (and its automatically appended plan steps) is
    // visible without reopening the playlist.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startPlaylist = {
        requestNotificationPermissionIfNeeded()
        // The plan is already the playback source. Start it immediately as a
        // private local playlist; 0.2.2 has no join/visibility decision.
        bleConnectionViewModel.reconnectRememberedBoard()
        viewModel.play(state.name.ifBlank { queueTitle }, onPlayed)
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            onNavigateToClimb = onNavigateToClimb,
            viewModel = bleConnectionViewModel,
        )
    }

    if (state.showRenameDialog) {
        RenameDialog(
            value = state.renameValue,
            onValueChange = viewModel::updateRenameValue,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissRenameDialog,
        )
    }

    state.editRestEntryIds.takeIf { it.isNotEmpty() }?.let { entryIds ->
        val current = state.entries
            .firstOrNull { it.entryId == entryIds.first() }
            ?.restSeconds ?: 60L
        RestDurationDialog(
            title = stringResource(R.string.playlist_rest_edit_title),
            initialSeconds = current,
            affectedCount = entryIds.size,
            onConfirm = viewModel::updateSelectedRestSeconds,
            onDelete = viewModel::removeSelectedRests,
            onDismiss = viewModel::dismissEditRest,
        )
    }

    if (showAddRestDialog) {
        RestDurationDialog(
            title = stringResource(R.string.playlist_add_rest),
            initialSeconds = 180L,
            onConfirm = {
                viewModel.addRest(it, addRestAfterEntryId)
                showAddRestDialog = false
                addRestAfterEntryId = null
            },
            onDismiss = {
                showAddRestDialog = false
                addRestAfterEntryId = null
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.list_plan_reset_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.list_plan_reset_message)) },
            confirmButton = {
                Button(onClick = {
                    showResetConfirm = false
                    viewModel.resetFromList()
                }) { Text(stringResource(R.string.list_plan_reset_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.list_plan_clear_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.list_plan_clear_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearPlan()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                ) { Text(stringResource(R.string.list_plan_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.name) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showBleSheet = true },
                            modifier = Modifier.testTag("playlist_ble_button"),
                        ) {
                            Icon(
                                if (isBleConnected) Icons.Default.BluetoothConnected
                                else Icons.Default.Bluetooth,
                                contentDescription = stringResource(R.string.cd_bluetooth),
                                tint = if (isBleConnected) SuccessGreen
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.toggleEditMode() },
                            modifier = Modifier.testTag("playlist_edit_toggle"),
                        ) {
                            Icon(
                                Icons.Default.Reorder,
                                contentDescription = stringResource(R.string.playlist_reorder),
                                tint = if (state.editMode) OrangeAccent
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            if (!state.isBuiltin) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.showRenameDialog()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_add_rest)) },
                                leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    addRestAfterEntryId = null
                                    showAddRestDialog = true
                                },
                                enabled = state.entries.any { !it.isRest },
                                modifier = Modifier.testTag("playlist_add_rest"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.list_plan_reset_action)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showResetConfirm = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.list_plan_clear_action)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showClearConfirm = true
                                },
                            )
                            // Versioned /l/<payload> link: ordered steps,
                            // pinned angles, rests and playback defaults.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_detail_share_link)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    val link = com.cruxcoach.android.util.PlaylistShareLink.buildPlan(
                                        name = state.name,
                                        steps = state.entries.mapNotNull { entry ->
                                            if (entry.isRest) {
                                                com.cruxcoach.android.util.PlaylistShareLink.SharedStep.Rest(
                                                    (entry.restSeconds ?: 0L).toInt(),
                                                )
                                            } else {
                                                val uuid = entry.climbUuid ?: return@mapNotNull null
                                                com.cruxcoach.android.util.PlaylistShareLink.SharedStep.Climb(
                                                    uuid, entry.angle?.toInt() ?: 40,
                                                )
                                            }
                                        },
                                        order = state.playbackOrder,
                                        advance = state.playbackAdvance,
                                        defaultRestSeconds = state.playbackRestSeconds.toInt(),
                                    )
                                    if (link != null) {
                                        clipboardManager.setText(
                                            androidx.compose.ui.text.AnnotatedString(link)
                                        )
                                        screenScope.launch {
                                            snackbarHostState.showSnackbar(linkCopiedMessage)
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("playlist_share_link"),
                            )
                        }
                    },
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
        floatingActionButton = {
            val playable = state.entries.any { !it.isRest && it.climb != null }
            if (playable) {
                ExtendedFloatingActionButton(
                    onClick = startPlaylist,
                    containerColor = OrangeAccent,
                    icon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
                    },
                    text = {
                        Text(
                            stringResource(R.string.playlist_play),
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    modifier = Modifier.testTag("playlist_play_fab"),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Surface(
                color = InfoBlue.copy(alpha = 0.10f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.list_plan_membership_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = InfoBlue,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            if (state.unavailableCount > 0) {
                Text(
                    stringResource(R.string.playlist_unavailable_climbs, state.unavailableCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = InfoBlue,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.playbackBoardError) {
                Text(
                    stringResource(R.string.list_playback_error_multiple_boards),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                state = playlistListState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.entries.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.playlist_empty_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.editMode) {
                    // Edit mode: every row individually (precise reorder/remove).
                    itemsIndexed(state.entries, key = { _, e -> e.entryId }) { index, entry ->
                        val dragHandleModifier = Modifier
                            .testTag("playlist_drag_${entry.entryId}")
                            .pointerInput(entry.entryId) {
                                detectDragGestures(
                                    onDragStart = {
                                        val currentIndex = latestEntries
                                            .indexOfFirst { it.entryId == entry.entryId }
                                        if (currentIndex >= 0) {
                                            draggedEntryId = entry.entryId
                                            dragStartIndex = currentIndex
                                            dragCurrentIndex = currentIndex
                                            dragAccumulator = 0f
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (draggedEntryId == entry.entryId) {
                                            dragAccumulator += dragAmount.y
                                            val swapThreshold = dragStepPx * 0.55f
                                            while (
                                                dragAccumulator > swapThreshold &&
                                                dragCurrentIndex < latestEntries.lastIndex
                                            ) {
                                                viewModel.previewMoveEntry(
                                                    dragCurrentIndex,
                                                    dragCurrentIndex + 1,
                                                )
                                                dragCurrentIndex++
                                                dragAccumulator -= dragStepPx
                                            }
                                            while (
                                                dragAccumulator < -swapThreshold &&
                                                dragCurrentIndex > 0
                                            ) {
                                                viewModel.previewMoveEntry(
                                                    dragCurrentIndex,
                                                    dragCurrentIndex - 1,
                                                )
                                                dragCurrentIndex--
                                                dragAccumulator += dragStepPx
                                            }

                                            // Keep long playlists moving when
                                            // the dragged row reaches either
                                            // visible edge.
                                            val visibleItems = playlistListState
                                                .layoutInfo.visibleItemsInfo
                                            val firstVisible = visibleItems.firstOrNull()?.index
                                            val lastVisible = visibleItems.lastOrNull()?.index
                                            val shouldScrollUp =
                                                dragAmount.y < 0f && firstVisible != null &&
                                                    dragCurrentIndex <= firstVisible + 1
                                            val shouldScrollDown =
                                                dragAmount.y > 0f && lastVisible != null &&
                                                    dragCurrentIndex >= lastVisible - 1
                                            val shouldScroll = shouldScrollUp || shouldScrollDown
                                            if (shouldScroll) {
                                                dragScrollJob?.cancel()
                                                dragScrollJob = screenScope.launch {
                                                    playlistListState.scrollBy(dragAmount.y)
                                                }
                                            } else {
                                                dragScrollJob?.cancel()
                                                dragScrollJob = null
                                            }
                                        }
                                    },
                                    onDragEnd = ::finishEntryDrag,
                                    onDragCancel = ::finishEntryDrag,
                                )
                            }
                        if (entry.isRest) {
                            RestRow(
                                seconds = entry.restSeconds ?: 0L,
                                editMode = true,
                                isDragging = draggedEntryId == entry.entryId,
                                dragHandleModifier = dragHandleModifier,
                                rowModifier = Modifier.animateItem(),
                                onClick = { viewModel.showEditRest(entry.entryId) },
                                onRemove = { viewModel.removeEntry(entry.entryId) },
                                onDuplicate = { viewModel.duplicateRest(entry.entryId) },
                                onMoveUp = if (index > 0) {
                                    { viewModel.moveEntry(index, index - 1) }
                                } else null,
                                onMoveDown = if (index < state.entries.lastIndex) {
                                    { viewModel.moveEntry(index, index + 1) }
                                } else null,
                                testTag = "playlist_rest_${entry.entryId}",
                            )
                        } else {
                            ClimbRow(
                                entry = entry,
                                gradeScale = state.gradeScale,
                                editMode = true,
                                isDragging = draggedEntryId == entry.entryId,
                                dragHandleModifier = dragHandleModifier,
                                rowModifier = Modifier.animateItem(),
                                attemptCount = 1,
                                attemptRestSeconds = null,
                                onEditAttemptRests = null,
                                onClick = {},
                                onRemove = { viewModel.removeEntry(entry.entryId) },
                                onDuplicate = { viewModel.duplicateClimb(entry.entryId) },
                                // The following rest row is already directly
                                // editable; only offer insertion when this
                                // climb does not have one yet.
                                onAddRestAfter = if (
                                    state.entries.getOrNull(index + 1)?.isRest != true
                                ) {
                                    {
                                        addRestAfterEntryId = entry.entryId
                                        showAddRestDialog = true
                                    }
                                } else null,
                                onMoveUp = if (index > 0) {
                                    { viewModel.moveEntry(index, index - 1) }
                                } else null,
                                onMoveDown = if (index < state.entries.lastIndex) {
                                    { viewModel.moveEntry(index, index + 1) }
                                } else null,
                            )
                        }
                    }
                } else {
                    // View mode: consecutive attempts on the same climb
                    // (limit/projecting structure) collapse into one card
                    // with an attempt badge — 5 identical rows read as
                    // noise, "5 Versuche · Pause 3 min" reads as a plan.
                    val rows = groupAttempts(state.entries)
                    itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
                        when (row) {
                            is PlaylistRow.Rest -> RestRow(
                                seconds = row.entry.restSeconds ?: 0L,
                                editMode = false,
                                isDragging = false,
                                dragHandleModifier = Modifier,
                                rowModifier = Modifier,
                                onClick = { viewModel.showEditRest(row.entry.entryId) },
                                onRemove = {},
                                onDuplicate = null,
                                onMoveUp = null,
                                onMoveDown = null,
                                testTag = "playlist_rest_${row.entry.entryId}",
                            )
                            is PlaylistRow.Climb -> ClimbRow(
                                entry = row.entry,
                                gradeScale = state.gradeScale,
                                editMode = false,
                                isDragging = false,
                                dragHandleModifier = Modifier,
                                rowModifier = Modifier,
                                attemptCount = row.attemptCount,
                                attemptRestSeconds = row.attemptRestSeconds,
                                onEditAttemptRests = row.attemptRestEntryIds
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { restIds ->
                                        { viewModel.showEditRests(restIds) }
                                    },
                                onClick = {
                                    val uuid = row.entry.climbUuid ?: return@ClimbRow
                                    if (row.entry.climb == null) return@ClimbRow
                                    val angle = row.entry.angle?.toInt() ?: 40
                                    // Pager over the playlist's resolvable climbs.
                                    viewModel.climbNavState.climbUuids =
                                        viewModel.playableEntries().map { it.first }.distinct()
                                    viewModel.climbNavState.angle = angle
                                    viewModel.climbNavState.source = ClimbNavigationSource.LIST
                                    onNavigateToClimb(uuid, angle)
                                },
                                onRemove = {},
                                onDuplicate = null,
                                onAddRestAfter = null,
                                onMoveUp = null,
                                onMoveDown = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** View-mode row model: attempts on the same climb collapsed. */
internal sealed interface PlaylistRow {
    val key: String

    data class Climb(
        val entry: PlaylistUiEntry,
        val attemptCount: Int,
        /** Rest between the collapsed attempts (null when single). */
        val attemptRestSeconds: Long?,
        /** Uniform rest rows represented by this collapsed attempt card. */
        val attemptRestEntryIds: List<Long>,
    ) : PlaylistRow {
        override val key get() = "c${entry.entryId}"
    }

    data class Rest(val entry: PlaylistUiEntry) : PlaylistRow {
        override val key get() = "r${entry.entryId}"
    }
}

/**
 * Collapse runs of [climb X, rest, climb X, rest, climb X] (same uuid)
 * into one Climb row with attemptCount=3 — the limit/projecting attempt
 * structure. Rests BETWEEN different climbs stay as rows.
 */
internal fun groupAttempts(entries: List<PlaylistUiEntry>): List<PlaylistRow> {
    val rows = mutableListOf<PlaylistRow>()
    var i = 0
    while (i < entries.size) {
        val e = entries[i]
        if (e.isRest) {
            rows.add(PlaylistRow.Rest(e))
            i++
            continue
        }
        // Extend the run: (rest? climb-with-same-uuid)* — attempts.
        var count = 1
        var attemptRest: Long? = null
        var separatorInitialized = false
        val attemptRestEntryIds = mutableListOf<Long>()
        var j = i + 1
        while (j < entries.size) {
            val next = entries[j]
            val afterRest = entries.getOrNull(j + 1)
            when {
                !next.isRest && next.isSameAttemptTargetAs(e) -> {
                    // No-rest and timed-rest separators are different plans.
                    // Keep the latter visible instead of folding both into a
                    // misleading single pause value.
                    if (separatorInitialized && attemptRest != null) break
                    separatorInitialized = true
                    attemptRest = null
                    count++; j++
                }
                next.isRest && afterRest != null && !afterRest.isRest &&
                    afterRest.isSameAttemptTargetAs(e) -> {
                    val restSeconds = next.restSeconds ?: 0L
                    // A collapsed row may state one inter-attempt rest value.
                    // Keep different rest durations visible as separate rows
                    // instead of displaying the final value for every attempt.
                    if (separatorInitialized && attemptRest != restSeconds) break
                    separatorInitialized = true
                    attemptRest = restSeconds
                    attemptRestEntryIds += next.entryId
                    count++; j += 2
                }
                else -> break
            }
        }
        rows.add(
            PlaylistRow.Climb(
                entry = e,
                attemptCount = count,
                attemptRestSeconds = if (count > 1) attemptRest else null,
                attemptRestEntryIds = if (count > 1) attemptRestEntryIds else emptyList(),
            )
        )
        i = j
    }
    return rows
}

private fun PlaylistUiEntry.isSameAttemptTargetAs(other: PlaylistUiEntry): Boolean {
    val thisUuid = climbUuid ?: return false
    val otherUuid = other.climbUuid ?: return false
    return PlaylistDetailViewModel.normUuidKey(thisUuid) ==
        PlaylistDetailViewModel.normUuidKey(otherUuid) &&
        (angle ?: 40L) == (other.angle ?: 40L)
}

@Composable
private fun ClimbRow(
    entry: PlaylistUiEntry,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    editMode: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    rowModifier: Modifier,
    attemptCount: Int,
    attemptRestSeconds: Long?,
    onEditAttemptRests: (() -> Unit)?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onAddRestAfter: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val climb = entry.climb
    Card(
        onClick = onClick,
        modifier = rowModifier
            .fillMaxWidth()
            .testTag("playlist_climb_${entry.entryId}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                OrangeAccent.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            },
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editMode) {
                PlaylistDragHandle(
                    modifier = dragHandleModifier,
                    isDragging = isDragging,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (climb == null) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
            if (climb == null) Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb?.name ?: stringResource(R.string.playlist_climb_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (climb == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = buildString {
                    climb?.difficultyAverage?.let {
                        append(GradeDisplayHelper.formatDifficulty(it, gradeScale))
                    }
                    entry.angle?.let {
                        if (isNotEmpty()) append(" · ")
                        append(stringResource(R.string.playlist_angle_label, it))
                    }
                    if (attemptCount > 1) {
                        if (isNotEmpty()) append(" · ")
                        append(stringResource(R.string.playlist_attempts_badge, attemptCount))
                        attemptRestSeconds?.let { rest ->
                            append(" (")
                            append(stringResource(R.string.playlist_rest_entry, formatRest(rest)))
                            append(")")
                        }
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
            if (onEditAttemptRests != null) {
                IconButton(
                    onClick = onEditAttemptRests,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("playlist_edit_attempt_rests_${entry.entryId}"),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.playlist_rest_edit_title),
                        tint = InfoBlue,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (editMode) {
                ReorderControls(
                    onRemove = onRemove,
                    onDuplicate = onDuplicate,
                    duplicateContentDescription = stringResource(R.string.list_plan_duplicate_step),
                    onAddRestAfter = onAddRestAfter,
                )
            }
        }
    }
}

@Composable
private fun RestRow(
    seconds: Long,
    editMode: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    rowModifier: Modifier,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDuplicate: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    testTag: String,
) {
    Card(
        onClick = onClick,
        modifier = rowModifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                OrangeAccent.copy(alpha = 0.16f)
            } else {
                InfoBlue.copy(alpha = 0.10f)
            },
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editMode) {
                PlaylistDragHandle(
                    modifier = dragHandleModifier,
                    isDragging = isDragging,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                Icons.Default.HourglassBottom,
                contentDescription = null,
                tint = InfoBlue,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                stringResource(R.string.playlist_rest_entry, formatRest(seconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = InfoBlue,
                modifier = Modifier.weight(1f),
            )
            if (editMode) {
                ReorderControls(
                    onRemove = onRemove,
                    onDuplicate = onDuplicate,
                    duplicateContentDescription = stringResource(R.string.playlist_duplicate_rest),
                    onAddRestAfter = null,
                )
            } else {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.playlist_rest_edit_title),
                    tint = InfoBlue,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistDragHandle(
    modifier: Modifier,
    isDragging: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val moveUpLabel = stringResource(R.string.playlist_move_up)
    val moveDownLabel = stringResource(R.string.playlist_move_down)
    val reorderActions = buildList {
        onMoveUp?.let { moveUp ->
            add(CustomAccessibilityAction(moveUpLabel) {
                moveUp()
                true
            })
        }
        onMoveDown?.let { moveDown ->
            add(CustomAccessibilityAction(moveDownLabel) {
                moveDown()
                true
            })
        }
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .then(modifier)
            .semantics(mergeDescendants = true) { customActions = reorderActions },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.cd_reorder),
            tint = if (isDragging) {
                OrangeAccent
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ReorderControls(
    onRemove: () -> Unit,
    onDuplicate: (() -> Unit)?,
    duplicateContentDescription: String,
    onAddRestAfter: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onDuplicate != null) {
            IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = duplicateContentDescription,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onAddRestAfter != null) {
            IconButton(onClick = onAddRestAfter, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.HourglassBottom,
                    contentDescription = stringResource(R.string.playlist_add_rest_after),
                    tint = InfoBlue,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_delete),
                tint = ErrorRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_rename), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
