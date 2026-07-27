package com.cruxcoach.android.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.common.LocalPlaylistPlayback
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SessionVisibilityDialog
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.board.sync.BoardSyncInlineCard
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.util.GradeConverter
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.util.PerfLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardBrowserScreen(
    onNavigateToClimb: (climbUuid: String, angle: Int) -> Unit,
    onNavigateToSync: () -> Unit = {},
    onNavigateToLogbook: () -> Unit = {},
    onNavigateToLists: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFilter: () -> Unit = {},
    onNavigateToClimbCreator: () -> Unit = {},
    onNavigateToSetter: (pubkey: String) -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    viewModel: BoardBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSessionActive by viewModel.isSessionActive.collectAsStateWithLifecycle()
    val randomClimbEvent by viewModel.randomClimbEvent.collectAsStateWithLifecycle()
    var showBleSheet by remember { mutableStateOf(false) }
    var showEndSessionDialog by remember { mutableStateOf(false) }
    var showSessionVisibilityDialog by rememberSaveable { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    val queueManager = LocalSessionQueueManager.current
    val playbackCoordinator = LocalPlaylistPlayback.current
    val queueState by queueManager.state.collectAsStateWithLifecycle()
    var lastEndedSession by remember { mutableStateOf<com.cruxcoach.data.repository.Board_sessions?>(null) }
    val queueLabel = stringResource(R.string.board_queue_title)

    // Notification permission request (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* Result doesn't matter -- timer works regardless via vibration */ }

    // Request notification permission once when session starts
    val context = LocalContext.current
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

    if (showSessionVisibilityDialog) {
        SessionVisibilityDialog(
            onDismiss = { showSessionVisibilityDialog = false },
            onSelect = { visibility ->
                showSessionVisibilityDialog = false
                requestNotificationPermissionIfNeeded()
                // Ad-hoc playlist: stay in the browser so climbs can be
                // added while the mini-player links to the player.
                playbackCoordinator.startEmpty(queueLabel, visibility)
            },
        )
    }

    // Safety guard: end orphaned BoardSessionManager if queue is not active.
    // This can happen when a session ends via BLE (host migration, disconnect) and
    // boardSessionManager.endSession() wasn't called on an older build.
    LaunchedEffect(isSessionActive, queueState.isActive) {
        if (isSessionActive && !queueState.isActive && !queueState.isConnecting) {
            android.util.Log.d("CruxBLE/Browser", "GUARD: orphaned session detected, ending")
            viewModel.endSession()
        }
    }

    // Re-check board data when returning from sync or other screens
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBoardData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Navigate to random climb when picked — single-UUID list (no pager, direct display)
    LaunchedEffect(randomClimbEvent) {
        val event = randomClimbEvent ?: return@LaunchedEffect
        PerfLogger.navStart("BoardBrowser", "ClimbDetail(random:${event.uuid})")
        viewModel.clearRandomClimb()
        viewModel.climbNavState.climbUuids = listOf(event.uuid)
        viewModel.climbNavState.angle = state.filter.angle
        viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.BROWSER
        onNavigateToClimb(event.uuid, state.filter.angle)
    }

    val isBleConnected = state.ble.connectionState == ConnectionState.CONNECTED ||
        state.ble.connectionState == ConnectionState.SENDING

    // Remote disconnect request dialog (received from nearby user)
    val bleConnViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnState by bleConnViewModel.state.collectAsStateWithLifecycle()
    if (bleConnState.showDisconnectRequestDialog) {
        AlertDialog(
            onDismissRequest = { bleConnViewModel.dismissDisconnectRequest() },
            title = { Text(stringResource(R.string.board_ble_disconnect_request_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_ble_disconnect_request_message)) },
            confirmButton = {
                Button(
                    onClick = { bleConnViewModel.acceptRemoteDisconnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.board_ble_disconnect_request_confirm), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { bleConnViewModel.dismissDisconnectRequest() }) {
                    Text(stringResource(R.string.board_ble_disconnect_request_deny))
                }
            }
        )
    }

    // Bluetooth state monitor — prompt user if BT turns off while BLE is in use
    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result doesn't matter — BT state change is detected automatically */ }
    var showBtOffDialog by remember { mutableStateOf(false) }
    val bleInUse = isBleConnected || queueState.isActive || isSessionActive
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (btState == BluetoothAdapter.STATE_OFF) {
                    showBtOffDialog = true
                } else if (btState == BluetoothAdapter.STATE_ON) {
                    showBtOffDialog = false
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    if (showBtOffDialog && bleInUse) {
        AlertDialog(
            onDismissRequest = { showBtOffDialog = false },
            title = { Text(stringResource(R.string.board_bt_off_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_bt_off_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        showBtOffDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.board_bt_off_enable), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showBtOffDialog = false }) {
                    Text(stringResource(R.string.board_bt_off_ignore))
                }
            }
        )
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            onNavigateToClimb = { uuid, angle ->
                viewModel.climbNavState.climbUuids = listOf(uuid)
                viewModel.climbNavState.angle = angle
                viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.BROWSER
                onNavigateToClimb(uuid, angle)
            },
        )
    }

    // Hold search sheet — pure hold-filter UI now (heatmaps moved to logbook stats)
    if (state.holdSearch.showSheet) {
        HoldSearchSheet(
            selectedHolds = state.holdSearch.selectedHolds,
            matchCount = state.holdSearch.matchCount,
            isSearching = state.holdSearch.isSearching,
            placements = state.placements,
            boardSize = state.boardSize,
            boardImages = state.boardImages,
            zoneSelectMode = state.holdSearch.zoneSelectMode,
            zone = state.holdSearch.zone,
            onToggleZoneMode = { viewModel.toggleZoneSelectMode() },
            onZoneSelected = { viewModel.setZone(it) },
            onClearZone = { viewModel.clearZone() },
            onHoldTapped = { viewModel.toggleHoldSelection(it) },
            onClearSelection = { viewModel.clearHoldSelection() },
            onSearchByHolds = { viewModel.applyHoldFilter() },
            onDismiss = { viewModel.toggleHoldSearchSheet() }
        )
    }

    // End session confirmation
    if (showEndSessionDialog) {
        val dialogSessionState by viewModel.sessionState.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { showEndSessionDialog = false },
            title = { Text(stringResource(R.string.board_session_end_title), fontWeight = FontWeight.Bold) },
            text = {
                val s = dialogSessionState
                Text(stringResource(
                    R.string.board_session_end_summary,
                    formatSessionTime(s.elapsedSeconds),
                    formatSessionTime(s.activeSeconds),
                    formatSessionTime(s.pauseSeconds),
                    s.ascentCount,
                    s.bidCount
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        lastEndedSession = viewModel.endSharedSession()
                        showEndSessionDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.board_session_end_confirm), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showEndSessionDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Session summary sheet after ending
    lastEndedSession?.let { session ->
        val sessionSummary by viewModel.lastSessionSummary.collectAsStateWithLifecycle()
        SessionSummarySheet(
            session = session,
            summary = sessionSummary,
            zones = state.zones,
            onDismiss = {
                lastEndedSession = null
                viewModel.clearSessionSummary()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            // The title wrapped to two lines on a narrow screen and pushed the
            // whole row of actions down with it. The logo says the same thing
            // in a quarter of the width, and it is the natural place to hang a
            // drawer off later.
            title = {},
            navigationIcon = {
                IconButton(
                    onClick = { /* reserved: opens the navigation drawer */ },
                    enabled = false,
                    modifier = Modifier.testTag("board_browser_home"),
                ) {
                    // A vector, deliberately. R.mipmap.ic_launcher_round is an
                    // <adaptive-icon>, which Compose cannot load at all — it
                    // threw "Only VectorDrawables and rasterized asset types
                    // are supported" and took the whole browser down on open.
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_monochrome),
                        contentDescription = stringResource(R.string.board_browser_title),
                        tint = OrangeAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = { showBleSheet = true },
                    modifier = Modifier.testTag("board_ble_button")
                ) {
                    Icon(
                        if (isBleConnected) Icons.Default.BluetoothConnected
                        else Icons.Default.Bluetooth,
                        contentDescription = stringResource(R.string.cd_bluetooth),
                        tint = if (isBleConnected) SuccessGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onNavigateToFilter,
                    modifier = Modifier.testTag("board_filter_toggle")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.cd_filter))
                }
                IconButton(
                    onClick = onNavigateToLogbook,
                    modifier = Modifier.testTag("board_logbook_icon")
                ) {
                    Icon(Icons.Default.Book, contentDescription = stringResource(R.string.board_logbook_title))
                }
                IconButton(
                    onClick = onNavigateToLists,
                    modifier = Modifier.testTag("board_lists_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.board_lists_title))
                }
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.testTag("board_settings_button")
                ) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            },
            windowInsets = WindowInsets(0.dp)
        )
        RestTimerBannerSlot()
        SyncStatusBannerSlot()
        if (state.isLoading && !state.hasBoardData) {
            // First DB access lazily runs any pending schema migration +
            // the onOpen VACUUM / index rebuild on the ~190k-row board DB.
            // On slower devices (mid-range eMMC) this blocks the first
            // query for 1-2+ minutes. A bare spinner here reads as a
            // freeze and tempts the user to force-kill mid-migration —
            // the migration is atomic + recoverable so that's data-safe,
            // but it wastes their time re-running it. Tell them what's
            // happening instead.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.board_browser_preparing_db),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.board_browser_preparing_db_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else if (!state.hasBoardData) {
            // Render the same inline sync UI the onboarding flow uses,
            // instead of a static "go to settings" hint. The user can
            // kick off the first Blossom sync, watch the import-step
            // checklist, and resolve network/wifi prompts without
            // leaving the browser. The empty-data state is the only
            // path that ever reaches this branch — once the catalog
            // is populated, the LazyColumn below takes over.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                BoardSyncInlineCard(modifier = Modifier.fillMaxWidth())
            }
        } else {
            // 2-button action bar (Playlist + Zufall) — only visible when no playlist is running
            if (!isSessionActive && !queueState.isActive && !queueState.isConnecting) {
                SessionTimerBar(
                    onStart = { showSessionVisibilityDialog = true },
                    onRandomClimb = { viewModel.pickRandomClimb() }
                )
            }

            // Unified BLE status area — nearby climbs, sessions, board status
            BleStatusArea(
                onClimbTapped = { uuid, angle ->
                    viewModel.climbNavState.climbUuids = listOf(uuid)
                    viewModel.climbNavState.angle = angle
                    onNavigateToClimb(uuid, angle)
                },
                onRandomToQueue = { viewModel.addRandomClimbToQueue() }
            )

            // Connecting indicator while GATT connection is being established
            if (queueState.isConnecting) {
                Surface(
                    color = OrangeAccent.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = OrangeAccent,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.board_session_connecting),
                            color = OrangeAccent,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Active hold filter banner (only shown when filter is active)
            if (state.holdSearch.holdFilterActive) {
                HoldSearchActionBar(
                    holdFilterActive = true,
                    heatmapActive = false,
                    selectedCount = state.holdSearch.selectedHolds.size,
                    zoneActive = state.holdSearch.zone != null,
                    matchCount = state.holdSearch.holdFilterUuids.size,
                    onOpenSheet = { viewModel.toggleHoldSearchSheet() },
                    onClearFilter = { viewModel.clearHoldSelection() }
                )
            }


            // Search bar + Holds button — hidden until user taps the FAB overlay
            if (searchVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = state.filter.searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        placeholder = { Text(stringResource(R.string.board_browser_search_hint), style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (state.filter.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear_search), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .testTag("board_search_field"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    // Hold-search uses the placement-id heatmap machinery, which
                    // only Aurora-protocol boards have. Gate explicitly on the
                    // capability so non-Aurora boards (MoonBoard) don't show an
                    // affordance that would run a meaningless cross-board search.
                    if (BoardBrand.fromWire(state.filter.boardBrand).hasHeatmap) {
                        val holdsTint = if (state.holdSearch.holdFilterActive) OrangeAccent
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        IconButton(
                            onClick = { viewModel.toggleHoldSearchSheet() },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("board_hold_search")
                        ) {
                            Icon(
                                Icons.Default.GridView,
                                contentDescription = stringResource(R.string.cd_hold_search),
                                tint = holdsTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    // FEAT-006: discover Kilter Boards on a map. Lives next
                    // to hold-search as a peer "find climbs by another
                    // dimension" action.
                    IconButton(
                        onClick = onNavigateToMap,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("board_map_button")
                    ) {
                        Icon(
                            Icons.Outlined.Map,
                            contentDescription = stringResource(R.string.cd_map),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Results
            if (state.isLoading && state.climbs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            } else if (state.climbs.isEmpty()) {
                // Zero-results empty state — this area used to render a
                // silently blank list. Two causes, two recoveries: the active
                // board's catalogue was never downloaded (board switch without
                // WiFi → the auto-load defers), or the filters simply match
                // nothing.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.activeBrandImporting) {
                        // Third case: the active board's catalogue is being
                        // imported right now — neither "no catalogue" nor
                        // "no results" is true yet, so show real progress
                        // instead of a misleading recovery prompt.
                        CircularProgressIndicator(color = OrangeAccent)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.board_browser_empty_catalogue_loading),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    } else if (!state.activeBrandHasCatalogue) {
                        Text(
                            text = stringResource(R.string.board_browser_empty_no_catalogue),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadActiveBoardCatalogue() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OrangeAccent,
                                contentColor = DarkBackground,
                            ),
                            modifier = Modifier.testTag("board_empty_load_catalogue")
                        ) {
                            Text(stringResource(R.string.board_browser_empty_load_catalogue))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.board_browser_empty_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.board_browser_empty_no_results_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { viewModel.clearAllBrowseFilters() },
                            modifier = Modifier.testTag("board_empty_clear_filters")
                        ) {
                            Text(stringResource(R.string.board_browser_empty_clear_filters))
                        }
                    }
                }
            } else {
                val listState = rememberLazyListState()

                // Reset scroll to top when the result set actually changes
                // (filter / sort / board change → different first climb).
                // LaunchedEffect also runs on every INITIAL composition, so
                // coming back from a climb detail used to scroll-to-top and
                // lose the position the restored listState had just brought
                // back. Track the last seen top uuid in a rememberSaveable
                // (it survives the back stack alongside listState): on
                // re-entry with an unchanged list the keys match and we skip
                // the jump; a real result-set change still resets to top.
                var lastTopUuid by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(state.climbs.firstOrNull()?.uuid) {
                    val topUuid = state.climbs.firstOrNull()?.uuid ?: return@LaunchedEffect
                    if (lastTopUuid != null && lastTopUuid != topUuid) listState.scrollToItem(0)
                    lastTopUuid = topUuid
                }

                // Trigger loadMore when near bottom
                val shouldLoadMore by remember {
                    derivedStateOf {
                        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisibleItem >= state.climbs.size - 10 && state.canLoadMore && !state.isLoadingMore && !state.isLoading
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (shouldLoadMore) viewModel.loadMore()
                }

                // Pre-compute values outside LazyListScope (non-composable context)
                val gradeScale = state.gradeScale
                val zones = state.zones
                // Stable lambda references — avoid new closures per item per recomposition.
                // Setter-link goes to SetterDetailScreen for cruxcoach rows
                // with a known pubkey (decision lives inside ClimbCard).
                // Foreign Kilter rows render the setter line unclickable.
                val onSetterClickFromCard = remember<(String) -> Unit>(onNavigateToSetter) {
                    { pubkey -> onNavigateToSetter(pubkey) }
                }
                val onClimbClick = remember<(String) -> Unit>(viewModel, onNavigateToClimb) {
                    { uuid ->
                        PerfLogger.navStart("BoardBrowser", "ClimbDetail($uuid)")
                        val s = viewModel.state.value
                        viewModel.climbNavState.climbUuids = s.climbs.map { it.uuid }
                        viewModel.climbNavState.angle = s.filter.angle
                        viewModel.climbNavState.source = com.cruxcoach.android.ui.navigation.ClimbNavigationSource.BROWSER
                        onNavigateToClimb(uuid, s.filter.angle)
                    }
                }
                // Long-press on a row: add to list/playlist (incl. the
                // running playlist) without opening the detail screen.
                var addToListClimbUuid by remember { mutableStateOf<String?>(null) }
                addToListClimbUuid?.let { uuid ->
                    AddToListDialogHost(
                        climbUuid = uuid,
                        angle = state.filter.angle,
                        onDismiss = { addToListClimbUuid = null },
                    )
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        state.climbs,
                        key = { it.uuid },
                        contentType = { "climb" }
                    ) { climb ->
                        ClimbCard(
                            climb = climb,
                            gradeScale = gradeScale,
                            zones = zones,
                            onNavigateToSetter = onSetterClickFromCard,
                            onClimbClick = onClimbClick,
                            onClimbLongClick = { addToListClimbUuid = it },
                        )
                    }

                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = OrangeAccent,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
        // Floating action stack: Search (bottom) + Create-climb (top).
        if (state.hasBoardData) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                // Climb authoring is supported for every interactive board:
                // Kilter (also mirrors to the user's own Kilter account),
                // MoonBoard, and the Aurora family (Tension / Grasshopper /
                // Decoy / So iLL / Touchstone) — the latter all publish to the
                // CruxCoach community only. The editor resolves the brand from
                // the active board and threads it into the draft-insert +
                // publish paths, so each climb stays on its own board. Only the
                // info-layer brands (aurora, 12climb, map-only) lack authoring,
                // and they never reach the browser, so no extra gate is needed.
                FloatingActionButton(
                    onClick = onNavigateToClimbCreator,
                    containerColor = OrangeAccent,
                    contentColor = DarkBackground,
                    modifier = Modifier.testTag("board_create_fab")
                ) {
                    Icon(Icons.Default.Create, contentDescription = stringResource(R.string.climb_creator_open))
                }
                FloatingActionButton(
                    onClick = { searchVisible = !searchVisible },
                    containerColor = OrangeAccent,
                    contentColor = DarkBackground,
                    modifier = Modifier.testTag("board_search_fab")
                ) {
                    Icon(
                        if (searchVisible) Icons.Default.Clear else Icons.Default.Search,
                        contentDescription = stringResource(
                            if (searchVisible) R.string.cd_clear_search else R.string.board_browser_search_hint
                        )
                    )
                }
            }
        }
    }
}
