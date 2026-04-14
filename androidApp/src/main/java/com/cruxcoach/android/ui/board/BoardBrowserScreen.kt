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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.SessionQueueState
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.util.GradeConverter
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
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
    viewModel: BoardBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isSessionActive by viewModel.isSessionActive.collectAsStateWithLifecycle()
    val randomClimbEvent by viewModel.randomClimbEvent.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showBleSheet by remember { mutableStateOf(false) }
    var showEndSessionDialog by remember { mutableStateOf(false) }
    val queueManager = LocalSessionQueueManager.current
    val queueState by queueManager.state.collectAsStateWithLifecycle()
    var lastEndedSession by remember { mutableStateOf<com.cruxcoach.data.repository.BoardSession?>(null) }

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
            autoStartScan = true,
            sessionRole = queueState.role
        )
    }

    // Hold search sheet
    if (state.holdSearch.showSheet) {
        HoldSearchSheet(
            selectedHolds = state.holdSearch.selectedHolds,
            heatmapMode = state.holdSearch.heatmapMode,
            heatmapData = state.holdSearch.heatmapData,
            matchCount = state.holdSearch.matchCount,
            isSearching = state.holdSearch.isSearching,
            placements = state.placements,
            boardSize = state.boardSize,
            boardImages = state.boardImages,
            onHoldTapped = { viewModel.toggleHoldSelection(it) },
            onHeatmapModeSelect = { viewModel.setHeatmapMode(it) },
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
                        viewModel.stopQueueSharing()
                        lastEndedSession = viewModel.endSession()
                        queueManager.endQueue()
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.board_browser_title)) },
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
                    onClick = { showFilters = !showFilters },
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
                    Icon(Icons.Default.FormatListBulleted, contentDescription = stringResource(R.string.board_lists_title))
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
        } else if (!state.hasBoardData) {
            NoBoardDataCard(onSyncClick = onNavigateToSync)
        } else {
            // 2-button action bar (Session + Zufall) — only visible when no session is active
            if (!isSessionActive && !queueState.isActive && !queueState.isConnecting) {
                val queueLabel = stringResource(R.string.board_queue_title)
                SessionTimerBar(
                    onStart = {
                        requestNotificationPermissionIfNeeded()
                        viewModel.startSession()
                        queueManager.startQueue(queueLabel)
                        viewModel.startQueueSharing()
                    },
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
                    heatmapActive = state.holdSearch.heatmapMode != HeatmapMode.OFF,
                    selectedCount = state.holdSearch.selectedHolds.size,
                    matchCount = state.holdSearch.holdFilterUuids.size,
                    onOpenSheet = { viewModel.toggleHoldSearchSheet() },
                    onClearFilter = { viewModel.clearHoldSelection() }
                )
            }


            // Search bar + Holds button (compact)
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
                val holdsTint = if (state.holdSearch.holdFilterActive ||
                    state.holdSearch.heatmapMode != HeatmapMode.OFF) OrangeAccent
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

            // Filters (collapsible)
            if (showFilters) {
                FilterSection(state, viewModel)
            }

            // Result count (hidden until count query completes)
            if (state.filteredCount >= 0) {
                val countText = if (state.filteredCount > state.climbs.size) {
                    stringResource(R.string.board_browser_climbs_loaded, state.filteredCount, state.climbs.size)
                } else {
                    stringResource(R.string.board_browser_climbs_count, state.filteredCount)
                }
                Text(
                    countText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Results
            if (state.isLoading && state.climbs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            } else {
                val listState = rememberLazyListState()

                // Reset scroll to top when filter results change
                LaunchedEffect(state.climbs.firstOrNull()?.uuid) {
                    if (state.climbs.isNotEmpty()) listState.scrollToItem(0)
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
                // Using viewModel.state.value at click time ensures fresh data.
                val onSetterClick = remember<(String) -> Unit>(viewModel) {
                    { setter -> viewModel.updateSearchQuery(setter) }
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
                            onSetterClick = onSetterClick,
                            onClimbClick = onClimbClick
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
}

@Composable
private fun FilterSection(state: BoardBrowserState, viewModel: BoardBrowserViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(max = 400.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Angle selector
            Text(stringResource(R.string.board_filter_angle, state.filter.angle), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Slider(
                value = state.filter.angle.toFloat(),
                onValueChange = { viewModel.setAngle(it.toInt()) },
                onValueChangeFinished = { viewModel.commitFilterChange() },
                valueRange = 0f..70f,
                steps = 13,
                modifier = Modifier.testTag("board_angle_slider"),
                colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
            )

            // Grade range — scale-aware stops (V-Scale: 18 grades, Font: 23 grades)
            val frenchMode = state.gradeScale == GradeScale.FRENCH
            val vScaleIndices = remember { GradeConverter.V_SCALE_INDICES }
            val gradeStops = if (frenchMode) GradeConverter.MAX_INDEX + 1 else vScaleIndices.size
            val gradeSliderMax = (gradeStops - 1).toFloat()

            fun toSliderPos(unifiedIndex: Int): Float {
                if (frenchMode) return unifiedIndex.toFloat()
                val pos = vScaleIndices.indexOfFirst { it >= unifiedIndex }
                return (if (pos < 0) vScaleIndices.size - 1 else pos).toFloat()
            }
            fun toUnifiedIndex(sliderPos: Float): Int {
                if (frenchMode) return sliderPos.toInt()
                return vScaleIndices.getOrElse(sliderPos.toInt()) { vScaleIndices.last() }
            }

            val minLabel = GradeDisplayHelper.formatByIndex(state.filter.minGradeIndex, state.gradeScale)
            val maxLabel = GradeDisplayHelper.formatByIndex(state.filter.maxGradeIndex, state.gradeScale)
            Text(
                stringResource(R.string.board_filter_grade_range, minLabel, maxLabel),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            RangeSlider(
                value = toSliderPos(state.filter.minGradeIndex)..toSliderPos(state.filter.maxGradeIndex),
                onValueChange = {
                    viewModel.setGradeRange(
                        toUnifiedIndex(it.start),
                        toUnifiedIndex(it.endInclusive)
                    )
                },
                onValueChangeFinished = { viewModel.commitFilterChange() },
                valueRange = 0f..gradeSliderMax,
                steps = (gradeStops - 2).coerceAtLeast(0),
                modifier = Modifier.testTag("board_grade_slider"),
                colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
            )

            // Min ascensionists
            Text(
                stringResource(R.string.board_filter_min_ascents, state.filter.minAscensionists),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = state.filter.minAscensionists.toFloat(),
                onValueChange = { viewModel.setMinAscensionists(it.toInt()) },
                onValueChangeFinished = { viewModel.commitFilterChange() },
                valueRange = 0f..50f,
                steps = 49,
                colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
            )

            // Climb type filter
            Text(stringResource(R.string.board_filter_type), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                val typeOptions = listOf(
                    ClimbTypeFilter.BOULDER to stringResource(R.string.board_filter_type_boulder),
                    ClimbTypeFilter.ROUTE to stringResource(R.string.board_filter_type_routes),
                    ClimbTypeFilter.ALL to stringResource(R.string.board_filter_all)
                )
                typeOptions.forEach { (filter, label) ->
                    FilterChip(
                        selected = state.filter.climbTypeFilter == filter,
                        onClick = { viewModel.updateClimbTypeFilter(filter) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            // Status filter
            Text(stringResource(R.string.board_filter_status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                val statusOptions = listOf(
                    ClimbStatusFilter.NEW to stringResource(R.string.board_filter_status_new),
                    ClimbStatusFilter.UNSENT to stringResource(R.string.board_filter_status_unsent),
                    ClimbStatusFilter.SENT to stringResource(R.string.board_filter_status_sent),
                    ClimbStatusFilter.ATTEMPTED to stringResource(R.string.board_filter_status_attempted),
                    ClimbStatusFilter.ALL to stringResource(R.string.board_filter_all)
                )
                statusOptions.forEach { (filter, label) ->
                    FilterChip(
                        selected = state.filter.statusFilter == filter,
                        onClick = { viewModel.updateStatusFilter(filter) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            // Benchmark filter
            FilterChip(
                selected = state.filter.benchmarkOnly,
                onClick = { viewModel.updateBenchmarkFilter(!state.filter.benchmarkOnly) },
                label = { Text(stringResource(R.string.board_filter_benchmarks_only), style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (state.filter.benchmarkOnly) {
                    { Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                ),
                modifier = Modifier.height(32.dp)
            )

            // Sort options
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.board_filter_sort), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { viewModel.toggleSortDirection() },
                    modifier = Modifier.size(32.dp).testTag("board_sort_direction")
                ) {
                    Icon(
                        if (state.filter.sortDirection == SortDirection.DESC) Icons.Default.ArrowDownward
                        else Icons.Default.ArrowUpward,
                        contentDescription = stringResource(if (state.filter.sortDirection == SortDirection.DESC) R.string.board_filter_sort_desc else R.string.board_filter_sort_asc),
                        tint = OrangeAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag("board_sort_row")
            ) {
                val sortOptions = listOf(
                    ClimbSortField.ASCENSIONISTS to stringResource(R.string.board_sends),
                    ClimbSortField.REPEATS to stringResource(R.string.board_sort_repeats),
                    ClimbSortField.QUALITY to stringResource(R.string.board_quality),
                    ClimbSortField.HOLDS to stringResource(R.string.board_moves)
                )
                sortOptions.forEach { (field, label) ->
                    FilterChip(
                        selected = state.filter.sortField == field,
                        onClick = { viewModel.updateSortField(field) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }
    }
}
