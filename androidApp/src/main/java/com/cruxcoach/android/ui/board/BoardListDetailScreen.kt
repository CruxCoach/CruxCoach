package com.cruxcoach.android.ui.board

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SessionVisibilityDialog
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.Climb_list_entries
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.android.ui.settings.DurationStepper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardListDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    onNavigateToPlaybackPlan: (Long) -> Unit,
    onPlayed: () -> Unit,
    viewModel: BoardListDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bleConnectionViewModel: BleConnectionViewModel = hiltViewModel()
    var menuExpanded by remember { mutableStateOf(false) }
    var showSessionVisibilityDialog by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val queueTitle = stringResource(R.string.board_queue_title)
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }
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

    if (state.showPlaybackOptions) {
        PlaybackOptionsSheet(
            state = state,
            onDismiss = viewModel::dismissPlaybackOptions,
            onUsePlanChange = viewModel::setUsePlaybackPlan,
            onOrderChange = viewModel::setPlaybackOrder,
            onAdvanceChange = viewModel::setPlaybackAdvance,
            onRestChange = viewModel::setPlaybackRestSeconds,
            onEditPlan = {
                viewModel.dismissPlaybackOptions()
                viewModel.preparePlaybackPlan { onNavigateToPlaybackPlan(state.listId) }
            },
            onStart = {
                viewModel.dismissPlaybackOptions()
                showSessionVisibilityDialog = true
            },
        )
    }

    if (showSessionVisibilityDialog) {
        SessionVisibilityDialog(
            onDismiss = { showSessionVisibilityDialog = false },
            onSelect = { visibility ->
                showSessionVisibilityDialog = false
                requestNotificationPermissionIfNeeded()
                // Lists and generated plans share the same playlist player:
                // always try the remembered physical controller before the
                // queue changes this screen into session-host mode.
                bleConnectionViewModel.reconnectRememberedBoard()
                viewModel.startPlayback(queueTitle, visibility, onPlayed)
            },
        )
    }

    if (state.showRenameDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRenameDialog,
            title = { Text(stringResource(R.string.board_lists_list_name), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = state.renameValue,
                    onValueChange = viewModel::updateRenameValue,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmRename,
                    enabled = state.renameValue.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRenameDialog) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Refresh entries on return so an edit/delete/publish done on a climb's
    // detail reflects instantly (the ViewModel is retained across back-nav).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(state.listName.ifEmpty { stringResource(R.string.board_list_default_name) }) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (!state.isIgnored && state.totalCount > 0) {
                            IconButton(
                                onClick = {
                                    viewModel.preparePlaybackPlan {
                                        onNavigateToPlaybackPlan(state.listId)
                                    }
                                },
                                modifier = Modifier.testTag("list_edit_training_plan"),
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = stringResource(R.string.list_playback_edit_plan),
                                )
                            }
                        }
                        if (!state.isBuiltin) {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.action_more_options),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.showRenameDialog()
                                    },
                                )
                            }
                        }
                    },
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
        floatingActionButton = {
            if (!state.isIgnored && state.totalCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::showPlaybackOptions,
                    containerColor = OrangeAccent,
                    icon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
                    },
                    text = {
                        Text(
                            stringResource(R.string.list_playback_start),
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    modifier = Modifier.testTag("list_play_fab"),
                )
            }
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            }
            state.entries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.board_list_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.board_list_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else -> {
                val listState = rememberLazyListState()

                Column(modifier = Modifier.padding(padding)) {
                    Text(
                        stringResource(R.string.board_list_climb_count, state.totalCount),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // FEAT-023: entries whose board catalogue isn't downloaded
                    // can't be resolved — surface the gap instead of silently
                    // dropping them.
                    if (state.unavailableCount > 0) {
                        Text(
                            stringResource(R.string.board_list_unavailable_count, state.unavailableCount),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // FEAT-023: a list is the user's selection, shown in FULL
                    // (every board) — each card's BoardBrandBadge labels its own
                    // board. When the list spans >1 board, offer a MULTI-SELECT
                    // per-board filter: a brand roll-up ("MoonBoard") + each
                    // MoonBoard variant / Kilter Original vs Homewall.
                    if (state.boardFilters.isNotEmpty()) {
                        BoardFilterRow(
                            options = state.boardFilters,
                            selected = state.selectedFilters,
                            onToggle = { viewModel.toggleBoardFilter(it) },
                            onClear = { viewModel.clearBoardFilters() }
                        )
                    }

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.entries, key = { it.climb.uuid }) { entry ->
                            ListEntryCard(
                                entry = entry,
                                gradeScale = state.gradeScale,
                                zones = state.zones,
                                onClick = {
                                    // Pager follows the currently-FILTERED set so
                                    // swiping in detail matches what's on screen.
                                    viewModel.climbNavState.climbUuids = state.entries.map { it.climb.uuid }
                                    viewModel.climbNavState.angle = state.angle
                                    viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.LIST
                                    onNavigateToClimb(entry.climb.uuid, state.angle)
                                },
                                onRemove = { viewModel.removeFromList(entry.climb.uuid) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackOptionsSheet(
    state: BoardListDetailState,
    onDismiss: () -> Unit,
    onUsePlanChange: (Boolean) -> Unit,
    onOrderChange: (ListPlaybackOrder) -> Unit,
    onAdvanceChange: (ListPlaybackAdvance) -> Unit,
    onRestChange: (Long) -> Unit,
    onEditPlan: () -> Unit,
    onStart: () -> Unit,
) {
    val visibleBoardCount = state.entries
        .map { it.climb.boardBrand to it.climb.layoutId }
        .distinct()
        .size
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    // The fixed action below must never cover the final option.
                    .padding(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                stringResource(R.string.list_playback_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            if (state.hasPlaybackPlan) {
                Text(
                    stringResource(R.string.list_playback_source),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { index, usePlan ->
                        SegmentedButton(
                            selected = state.usePlaybackPlan == usePlan,
                            onClick = { onUsePlanChange(usePlan) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            label = {
                                Text(
                                    stringResource(
                                        if (usePlan) R.string.list_playback_source_plan
                                        else R.string.list_playback_source_list
                                    )
                                )
                            },
                        )
                    }
                }
            }

            if (state.usePlaybackPlan) {
                PlaybackInfoBox(
                    text = stringResource(R.string.list_playback_plan_info),
                    actionLabel = stringResource(R.string.list_playback_edit_plan),
                    onAction = onEditPlan,
                )
            } else {
                Text(
                    stringResource(R.string.list_playback_order),
                    style = MaterialTheme.typography.labelLarge,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ListPlaybackOrder.entries.forEachIndexed { index, order ->
                        SegmentedButton(
                            selected = state.playbackOrder == order,
                            onClick = { onOrderChange(order) },
                            shape = SegmentedButtonDefaults.itemShape(index, ListPlaybackOrder.entries.size),
                            label = {
                                Text(
                                    stringResource(
                                        if (order == ListPlaybackOrder.LIST) {
                                            R.string.list_playback_order_list
                                        } else R.string.list_playback_order_shuffle
                                    )
                                )
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.list_playback_default_rest),
                    style = MaterialTheme.typography.labelLarge,
                )
                DurationStepper(
                    seconds = state.playbackRestSeconds.toInt(),
                    onChange = { onRestChange(it.toLong()) },
                    minSeconds = 0,
                    maxSeconds = 3600,
                    minuteLabel = stringResource(R.string.settings_duration_minutes_label),
                    secondLabel = stringResource(R.string.settings_duration_seconds_label),
                )
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.list_playback_advance),
                style = MaterialTheme.typography.labelLarge,
            )
            ListPlaybackAdvance.entries.forEach { advance ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = state.playbackAdvance == advance,
                        onClick = { onAdvanceChange(advance) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                when (advance) {
                                    ListPlaybackAdvance.MANUAL -> R.string.list_playback_advance_manual
                                    ListPlaybackAdvance.AFTER_SEND -> R.string.list_playback_advance_send
                                    ListPlaybackAdvance.AFTER_LOG -> R.string.list_playback_advance_log
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (!state.usePlaybackPlan && visibleBoardCount > 1) {
                PlaybackInfoBox(stringResource(R.string.list_playback_multiple_boards_hint))
            }
            if (state.unavailableCount > 0) {
                PlaybackInfoBox(
                    stringResource(R.string.list_playback_unavailable_hint, state.unavailableCount)
                )
            }
            state.playbackStartError?.let { error ->
                PlaybackInfoBox(
                    text = stringResource(
                        when (error) {
                            PlaybackStartError.EMPTY -> R.string.list_playback_error_empty
                            PlaybackStartError.MULTIPLE_BOARDS -> R.string.list_playback_error_multiple_boards
                        }
                    ),
                    isError = true,
                )
            }
            }

            Button(
                onClick = onStart,
                enabled = !state.isStartingPlayback &&
                    (state.usePlaybackPlan || (state.entries.isNotEmpty() && visibleBoardCount == 1)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .testTag("list_playback_confirm"),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.isStartingPlayback) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = DarkBackground,
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.list_playback_start),
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackInfoBox(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else InfoBlue
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** FEAT-023: per-list board filter (MULTI-SELECT, union). "Alle" + one chip per
 *  distinct board present — variant-granular (MoonBoard 2019, Kilter Homewall,
 *  …) plus a brand roll-up chip ("MoonBoard") when a brand has >1 variant.
 *  Tapping toggles a chip; "Alle" clears the selection. */
@Composable
private fun BoardFilterRow(
    options: List<BoardFilterOption>,
    selected: Set<BoardFilterOption>,
    onToggle: (BoardFilterOption) -> Unit,
    onClear: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = onClear,
                label = { Text(stringResource(R.string.board_list_filter_all)) }
            )
        }
        items(options, key = { "${it.brandWire}:${it.layoutKey}" }) { opt ->
            val isActive = selected.any { it.brandWire == opt.brandWire && it.layoutKey == opt.layoutKey }
            FilterChip(
                selected = isActive,
                onClick = { onToggle(opt) },
                label = { Text("${boardFilterLabel(opt.brandWire, opt.layoutKey)} · ${opt.count}") }
            )
        }
    }
}

/** Human label for a board-filter chip: a brand roll-up / Aurora brand
 *  (layoutKey < 0) → brand name; Kilter Original / Homewall; the MoonBoard
 *  variant name; else the Aurora brand display name. */
@Composable
private fun boardFilterLabel(brandWire: String, layoutKey: Long): String {
    val brand = BoardBrand.fromWire(brandWire)
    return when {
        layoutKey < 0L -> brand.displayName
        brand == BoardBrand.KILTER &&
            layoutKey == com.cruxcoach.android.data.BoardConstants.KILTER_HOMEWALL_LAYOUT.toLong() ->
            stringResource(R.string.board_category_kilter_homewall)
        brand == BoardBrand.KILTER ->
            stringResource(R.string.board_category_kilter_original)
        brand == BoardBrand.MOONBOARD ->
            com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(layoutKey)?.displayName
                ?: brand.displayName
        else -> brand.displayName
    }
}

@Composable
private fun ListEntryCard(
    entry: Climb_list_entries,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    zones: IntensityZones? = null,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val climb = entry.climb
    val grade = climb.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) } ?: "?"
    val moveCount = climb.moveCount

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("board_list_entry_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = zoneColorForDifficulty(climb.difficultyAverage ?: 0.0, zones),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        grade,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkBackground
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ClimbMetaLine(
                    setter = climb.setterUsername,
                    isRoute = climb.isRoute,
                    framesCount = climb.framesCount,
                    moveCount = moveCount,
                    spacing = 8.dp,
                    leading = {
                        // Per-entry board type, analogous to the logbook badge.
                        BoardBrandBadge(BoardBrand.fromWire(climb.boardBrand), climb.layoutId)
                    },
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                climb.qualityAverage?.let {
                    Text(
                        "${"%.1f".format(it)}★",
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningYellow,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_remove),
                    tint = ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
