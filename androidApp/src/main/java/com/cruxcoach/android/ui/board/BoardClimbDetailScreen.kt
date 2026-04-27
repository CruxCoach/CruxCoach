package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.domain.board.IntensityZones
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.util.PerfLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardClimbDetailScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: ((climbUuid: String, angle: Int) -> Unit)? = null,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: BoardClimbDetailViewModel = hiltViewModel()
) {
    PerfLogger.navMilestone("BoardClimbDetailScreen composing")
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRestTimerRunning by viewModel.isRestTimerRunning.collectAsStateWithLifecycle()
    val isSharingEnabled by viewModel.isSharingEnabled.collectAsStateWithLifecycle()
    val pageCache by viewModel.pageCache.collectAsStateWithLifecycle()
    var showBleSheet by remember { mutableStateOf(false) }

    // BLE sheet lives here (once), not inside per-page content
    val detailQueueManager = com.cruxcoach.android.ui.common.LocalSessionQueueManager.current
    val detailQueueState by detailQueueManager.state.collectAsStateWithLifecycle()

    // Swipe navigation: only use queue items when the user navigated FROM the queue sheet.
    // When navigating from the browser, always use the full browser climb list —
    // regardless of whether a session queue is active.
    val navigatedFromQueue = viewModel.climbNavState.source == com.cruxcoach.android.ui.navigation.ClimbNavigationSource.QUEUE
    val navUuids = if (navigatedFromQueue && detailQueueState.isActive && detailQueueState.queue.isNotEmpty()) {
        detailQueueState.queue.map { it.climbUuid }
    } else {
        remember { viewModel.climbNavState.climbUuids }
    }
    val navAngle = if (navigatedFromQueue && detailQueueState.isActive && detailQueueState.queue.isNotEmpty()) {
        detailQueueState.queue.firstOrNull()?.angle ?: remember { viewModel.climbNavState.angle }
    } else {
        remember { viewModel.climbNavState.angle }
    }
    val initialIndex = remember(navUuids) {
        val idx = navUuids.indexOf(viewModel.initialClimbUuid)
        if (idx >= 0) idx else 0
    }
    val hasPager = navUuids.size > 1

    // Navigation perf: mark when first meaningful content is visible
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && state.climb != null) {
            PerfLogger.navEnd("BoardClimbDetail(${state.climb?.uuid})")
        }
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            autoStartScan = true,
            sessionRole = detailQueueState.role
        )
    }

    // Dialogs — driven by active-page state, only need one instance
    if (state.ascent.showDialog) {
        AscentLoggingDialog(
            isEditing = state.ascent.editingUuid != null,
            isSend = state.ascent.isSend,
            bidCount = state.ascent.bidCount,
            quality = state.ascent.quality,
            comment = state.ascent.comment,
            isBenchmark = state.ascent.isBenchmark,
            onIsBenchmarkChanged = { viewModel.updateAscentIsBenchmark(it) },
            onIsSendChanged = { viewModel.updateAscentIsSend(it) },
            onBidCountChanged = { viewModel.updateAscentBidCount(it) },
            onQualityChanged = { viewModel.updateAscentQuality(it) },
            onCommentChanged = { viewModel.updateAscentComment(it) },
            onSave = { viewModel.saveAscent() },
            onDismiss = { viewModel.dismissAscentDialog() }
        )
    }

    state.ascent.deleteConfirmUuid?.let {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text(stringResource(R.string.board_detail_delete_ascent_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.board_detail_delete_ascent_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteAscent() },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.action_delete), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (state.listDialog.show) {
        AddToListDialog(
            lists = state.listDialog.lists,
            climbInListIds = state.listDialog.climbInListIds,
            newListName = state.listDialog.newListName,
            onToggleList = { viewModel.toggleClimbInList(it) },
            onNewListNameChanged = { viewModel.updateNewListName(it) },
            onCreateAndAdd = { viewModel.createNewListAndAdd() },
            onDismiss = { viewModel.dismissAddToListDialog() }
        )
    }

    // Remote disconnect request dialog (single instance)
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

    // Quick-Send macro: surface progress + outcomes as snackbars and escalate
    // multi-board ambiguity into the existing connection sheet for manual pick.
    val quickSendStatus by bleConnViewModel.quickSend.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(quickSendStatus) {
        val msg = when (val s = quickSendStatus) {
            QuickSendStatus.Idle -> null
            QuickSendStatus.Scanning -> context.getString(R.string.ble_quick_send_scanning)
            is QuickSendStatus.Connecting -> context.getString(R.string.ble_quick_send_connecting, s.boardName)
            QuickSendStatus.Sending -> context.getString(R.string.ble_quick_send_sending)
            QuickSendStatus.Disconnecting -> context.getString(R.string.ble_quick_send_disconnecting)
            QuickSendStatus.Done -> context.getString(R.string.ble_quick_send_done)
            is QuickSendStatus.NeedsManualPick -> {
                // Auto-escalate: open the existing connection sheet so the user
                // picks, then drop the macro. Once they tap a board the sheet
                // takes over the connect-and-send flow.
                showBleSheet = true
                bleConnViewModel.resetQuickSend()
                null
            }
            is QuickSendStatus.Error -> context.getString(
                when (s.reason) {
                    QuickSendStatus.ErrorReason.NoBoardsFound -> R.string.ble_quick_send_no_boards
                    QuickSendStatus.ErrorReason.ConnectFailed -> R.string.ble_quick_send_connect_failed
                    QuickSendStatus.ErrorReason.SendFailed -> R.string.ble_quick_send_send_failed
                    QuickSendStatus.ErrorReason.BluetoothOff -> R.string.ble_quick_send_bluetooth_off
                    QuickSendStatus.ErrorReason.NoPermissions -> R.string.ble_quick_send_no_permissions
                }
            )
        }
        if (msg != null) snackbarHostState.showSnackbar(msg)
        // Reset Done/Error to Idle after the snackbar fires so the next tap
        // starts fresh; transient states (Scanning/Sending/Disconnecting/
        // Connecting) reset themselves when the macro advances.
        if (quickSendStatus is QuickSendStatus.Done || quickSendStatus is QuickSendStatus.Error) {
            bleConnViewModel.resetQuickSend()
        }
    }

    // Single Scaffold — shared across all pager pages
    val bleConnected = state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.board_detail_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("boarddetail_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.toggleFavorite() },
                            modifier = Modifier.testTag("boarddetail_favorite_button")
                        ) {
                            Icon(
                                if (state.isFavorited) Icons.Default.Star else Icons.Outlined.Star,
                                contentDescription = stringResource(if (state.isFavorited) R.string.cd_remove_favorite else R.string.cd_add_favorite),
                                tint = if (state.isFavorited) WarningYellow else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.showAddToListDialog() },
                            modifier = Modifier.testTag("boarddetail_add_to_list_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.cd_add_to_list),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                // Quick-Send-Mode setting (Settings → BLE) routes the
                                // tap through the macro: scan → auto-connect-on-single
                                // → existing CONNECTED-collector auto-fires send →
                                // disconnect (boulders only — routes need the
                                // connection alive for the remaining frames during
                                // playback, so the macro stops after connect for
                                // those). Multi-board case escalates back into the
                                // manual sheet via NeedsManualPick (handled in the
                                // LaunchedEffect below).
                                if (bleConnState.quickBoardSendEnabled) {
                                    bleConnViewModel.startQuickSend(isRoute = state.playback.isRoute)
                                } else {
                                    showBleSheet = true
                                }
                            },
                            modifier = Modifier.testTag("boarddetail_ble_connect_button")
                        ) {
                            Icon(
                                if (bleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                contentDescription = stringResource(if (bleConnected) R.string.cd_board_connected else R.string.cd_board_connect),
                                tint = if (bleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { viewModel.startRestTimer() },
                            modifier = Modifier.testTag("boarddetail_rest_timer_button")
                        ) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = stringResource(R.string.cd_rest_timer),
                                tint = if (isRestTimerRunning) OrangeAccent
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.showAscentDialog() },
                            modifier = Modifier.testTag("boarddetail_log_button")
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_log_ascent),
                                tint = OrangeAccent
                            )
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                run {
                    val qm = LocalSessionQueueManager.current
                    BleStatusArea(
                        currentClimbUuid = state.climb?.uuid,
                        onClimbTapped = { uuid, angle -> viewModel.switchClimb(uuid, angle) },
                        onAddToQueue = if (state.climb != null) {
                            { qm.addClimb(state.climb!!.uuid, state.angle) }
                        } else null
                    )
                }
            }
        }
    ) { padding ->
        if (hasPager) {
            val pagerState = rememberPagerState(
                initialPage = initialIndex,
                pageCount = { navUuids.size }
            )

            // Switch active climb when user settles on a new page
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect { page ->
                    val uuid = navUuids.getOrNull(page) ?: return@collect
                    viewModel.switchClimb(uuid, navAngle)
                }
            }

            // Preload adjacent pages for smooth swiping (wait until current page is loaded)
            LaunchedEffect(pagerState.settledPage, state.isLoading) {
                if (state.isLoading) return@LaunchedEffect
                val settled = pagerState.settledPage
                listOf(settled - 1, settled + 1)
                    .filter { it in navUuids.indices }
                    .forEach { viewModel.preloadClimb(navUuids[it], navAngle) }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                beyondViewportPageCount = 1,
                key = { navUuids[it] }
            ) { pageIndex ->
                val pageUuid = navUuids[pageIndex]
                val isActivePage = pagerState.settledPage == pageIndex
                val pageState = if (isActivePage) {
                    state
                } else {
                    pageCache[pageUuid] ?: ClimbDetailState(isLoading = true)
                }

                ClimbDetailPageContent(
                    state = pageState,
                    isSharingEnabled = isSharingEnabled,
                    viewModel = viewModel,
                    onNavigateBack = onNavigateBack,
                    onNavigateToBugReport = onNavigateToBugReport
                )
            }
        } else {
            ClimbDetailPageContent(
                state = state,
                isSharingEnabled = isSharingEnabled,
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onNavigateToBugReport = onNavigateToBugReport,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/** Lightweight per-page content — no Scaffold, no TopAppBar, no banners, no BleVM. */
@Composable
private fun ClimbDetailPageContent(
    state: ClimbDetailState,
    isSharingEnabled: Boolean,
    viewModel: BoardClimbDetailViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    when {
        state.isLoading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
        }
        state.error != null -> {
            Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                com.cruxcoach.android.ui.common.ErrorCard(
                    error = state.error ?: stringResource(R.string.board_detail_error),
                    onDismiss = { viewModel.clearError() },
                    onReportBug = {
                        onNavigateToBugReport(
                            context.getString(R.string.error_bug_report_climb_title),
                            state.error ?: ""
                        )
                        viewModel.clearError()
                    }
                )
            }
        }
        state.climb != null -> {
            val climb = state.climb!!
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Grade + Stats card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(climb.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                climb.setterUsername?.let { setter ->
                                    Text(
                                        stringResource(R.string.board_detail_by_setter, setter),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = OrangeAccent,
                                        modifier = Modifier.clickable {
                                            viewModel.requestSetterFilter(setter)
                                            onNavigateBack()
                                        }
                                    )
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                climb.difficultyAverage?.let { diff ->
                                    val fbGrade = GradeDisplayHelper.formatDifficulty(diff, GradeScale.FRENCH)
                                    val vGrade = GradeDisplayHelper.formatDifficulty(diff, GradeScale.V_SCALE)
                                    Surface(
                                        color = zoneColorForDifficulty(diff, state.zones),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            "$fbGrade / $vGrade",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = DarkBackground
                                        )
                                    }
                                }
                                MatchBadge(isNomatch = climb.isNomatch)
                                if (climb.benchmarkDifficulty > 0.0) {
                                    BenchmarkBadge()
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AngleDropdownStatItem(
                                currentAngle = state.angle,
                                availableAngles = state.availableAngles,
                                gradeScale = state.gradeScale,
                                zones = state.zones,
                                onAngleSelected = viewModel::onAngleSelected
                            )
                            if (state.playback.isRoute) {
                                StatItem(stringResource(R.string.board_detail_frames), "${state.playback.totalFrames}")
                            } else {
                                StatItem(stringResource(R.string.board_moves), "${climb.moveCount}")
                            }
                            StatItem(stringResource(R.string.board_quality), climb.qualityAverage?.let { "%.1f".format(it) } ?: "--")
                            StatItem(stringResource(R.string.board_sends), "${climb.ascensionistCount ?: 0}")
                        }

                        // First ascent info
                        if (climb.faUsername != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val faDate = climb.faAt?.let { formatAscentDate(it) }
                            Text(
                                "FA: ${climb.faUsername}" + if (faDate != null) " ($faDate)" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Description
                        if (climb.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                climb.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Mirror toggle (centered icon-only)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { viewModel.toggleMirror() },
                        modifier = Modifier.testTag("boarddetail_mirror_toggle")
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = stringResource(if (state.isMirrored) R.string.cd_mirror_off else R.string.cd_mirror_on),
                            tint = if (state.isMirrored) OrangeAccent
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Board visualization (Climbdex-style) with countdown overlay
                Box(modifier = Modifier.fillMaxWidth()) {
                    KilterBoardVisualization(
                        holds = state.holds,
                        placements = state.placements,
                        boardSize = state.boardSize,
                        boardImages = state.boardImages,
                        ledColors = state.ledColors,
                        previewMode = state.playback.showPreview,
                        currentFrameHolds = if (state.playback.showPreview && state.playback.isRoute) {
                            state.playback.allFrames.getOrElse(state.playback.currentFrameIndex) { emptyList() }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("boarddetail_visualization")
                    )
                    // Countdown overlay
                    if (state.playback.countdownSeconds > 0) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${state.playback.countdownSeconds}",
                                style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent
                            )
                        }
                    }
                }

                // Route playback controls
                if (state.playback.isRoute) {
                    RoutePlaybackControls(state = state, viewModel = viewModel)
                }

                // Compact BLE send status
                if (state.ble.isSending || state.ble.success || state.ble.error != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            state.ble.isSending -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = OrangeAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.board_detail_sending), style = MaterialTheme.typography.bodySmall, color = OrangeAccent)
                            }
                            state.ble.success -> {
                                Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(14.dp), tint = SuccessGreen)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.board_detail_sent), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = SuccessGreen)
                            }
                            state.ble.error != null -> {
                                Text(
                                    state.ble.error ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ErrorRed,
                                    modifier = Modifier.clickable {
                                        onNavigateToBugReport(
                                            context.getString(R.string.error_bug_report_ble_title),
                                            state.ble.error ?: ""
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Climb sharing debug + indicator (only while connected)
                if (state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }) {
                    val sharingDebug = buildString {
                        append("S:")
                        append(if (isSharingEnabled) "ON" else "OFF")
                        append(" A:")
                        append(if (state.nearby.isAdvertising) "ON" else "OFF")
                        if (state.nearby.debugInfo.isNotEmpty()) {
                            append(" | ")
                            append(state.nearby.debugInfo)
                        }
                    }
                    Text(
                        sharingDebug,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (state.nearby.isAdvertising && state.ble.connectionState.let { it == ConnectionState.CONNECTED || it == ConnectionState.SENDING }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CellTower,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = OrangeAccent
                        )
                        Text(
                            stringResource(R.string.board_detail_climb_shared),
                            style = MaterialTheme.typography.bodySmall,
                            color = OrangeAccent
                        )
                    }
                }

                // User ascent history
                if (state.userAscents.isNotEmpty()) {
                    UserAscentHistory(
                        ascents = state.userAscents,
                        gradeScale = state.gradeScale,
                        onEdit = { viewModel.editAscent(it) },
                        onDelete = { viewModel.requestDeleteAscent(it.uuid) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AngleDropdownStatItem(
    currentAngle: Int,
    availableAngles: List<AngleOption>,
    gradeScale: GradeScale,
    zones: IntensityZones?,
    onAngleSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasMultipleAngles = availableAngles.size > 1

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .then(
                    if (hasMultipleAngles) Modifier.clickable { expanded = true }
                    else Modifier
                )
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${currentAngle}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (hasMultipleAngles) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.cd_change_angle),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.board_angle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 220.dp)
        ) {
            availableAngles.forEach { option ->
                val isSelected = option.angle == currentAngle
                val gradeText = option.difficultyAverage?.let { diff ->
                    GradeDisplayHelper.formatDifficulty(diff, gradeScale)
                } ?: "–"
                val zoneColor = option.difficultyAverage?.let { diff ->
                    zoneColorForDifficulty(diff, zones)
                }

                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${option.angle}°",
                                modifier = Modifier.width(40.dp),
                                fontWeight = if (isSelected) FontWeight.Bold
                                    else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                            )
                            if (zoneColor != null) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(zoneColor, CircleShape)
                                )
                            }
                            Text(
                                text = gradeText,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (isSelected) FontWeight.Bold
                                    else FontWeight.Normal
                            )
                            Text(
                                text = "${option.ascensionistCount ?: 0}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onAngleSelected(option.angle)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Match / No-match badge with Handshake icon (crossed out when no-match). */
@Composable
private fun MatchBadge(isNomatch: Boolean) {
    val color = if (isNomatch) ErrorRed else SuccessGreen
    val label = stringResource(if (isNomatch) R.string.board_detail_no_matching else R.string.board_detail_matching)
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MatchIcon(crossed = isNomatch, tint = color, size = 16)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun BenchmarkBadge() {
    Surface(
        color = OrangeAccent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Verified,
                contentDescription = stringResource(R.string.board_detail_benchmark),
                tint = OrangeAccent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                stringResource(R.string.board_detail_benchmark),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent
            )
        }
    }
}

/** Raised-hand icon with optional diagonal strike-through for no-match. */
@Composable
private fun MatchIcon(crossed: Boolean, tint: Color, size: Int = 16) {
    Box(modifier = Modifier.size(size.dp)) {
        Icon(
            Icons.Default.PanTool,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size.dp)
        )
        if (crossed) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = tint,
                    start = Offset(0f, this.size.height),
                    end = Offset(this.size.width, 0f),
                    strokeWidth = stroke
                )
            }
        }
    }
}
