package com.cruxcoach.android.ui.playlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.BleConnectionViewModel
import com.cruxcoach.android.ui.board.ClimbRenderData
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardAssetState
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.QuantumLayerStatusStrip
import com.cruxcoach.android.ui.board.SessionQueueSheet
import com.cruxcoach.android.ui.board.SessionSummarySheet
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.LocalSessionQueueManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.settings.BoardPickerDialog
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.android.ui.theme.zoneColorForDifficulty
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerState

/** Preserve multi-layer overlays only for the board family that supports them. */
internal fun playlistProjectionLayers(
    brand: BoardBrand,
    state: BoardLayerState,
): List<BoardClimbLayer> = if (brand == BoardBrand.QUANTUM) state.layers else emptyList()

/**
 * The playlist player — the one place a running playlist lives. Board
 * render of the current climb, detail-style metadata and manual transport.
 * Rest remains the normal top banner, leaving the upcoming climb visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPlayerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (String, Int) -> Unit,
    onNavigateToSetter: (String) -> Unit = {},
    /** "+"-Button: zum Browser, wo Long-Press Climbs hinzufügt. */
    onNavigateToBrowser: () -> Unit = {},
    /** Opens the system's Bluetooth-enable dialog via the connect sheet's flow. */
    onEnableBluetooth: () -> Unit = {},
    /** Asks for BLUETOOTH_ADVERTISE when that is what blocks sharing. */
    onRequestSharingPermission: () -> Unit = {},
    viewModel: PlaylistPlayerViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boardLayers by viewModel.boardLayerState.collectAsStateWithLifecycle()
    var showQueueSheet by remember { mutableStateOf(false) }
    var showBleSheet by remember { mutableStateOf(false) }
    val bleConnectionViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnectionState by bleConnectionViewModel.state.collectAsStateWithLifecycle()
    val queueManager = LocalSessionQueueManager.current
    val queueState by queueManager.state.collectAsStateWithLifecycle()
    val isBleConnected =
        bleConnectionState.connectionState == ConnectionState.CONNECTED ||
            bleConnectionState.connectionState == ConnectionState.SENDING
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val loggedSendMsg = stringResource(R.string.playlist_logged_send)
    val loggedAttemptMsg = stringResource(R.string.playlist_logged_attempt)

    // Quick-log feedback: snackbar + reset the one-shot flag.
    LaunchedEffect(state.lastLogged) {
        state.lastLogged?.let { isSend ->
            snackbarHostState.showSnackbar(if (isSend) loggedSendMsg else loggedAttemptMsg)
            viewModel.consumeLogFeedback()
        }
    }

    // Playlist ended elsewhere (host stopped, migration failed) and no
    // summary pending → leave the player. Two subtleties: isConnecting
    // covers the join flow (player opens while GATT connects), and the
    // close only arms AFTER activity was observed once — the coordinator's
    // combined state can still be a pre-play snapshot on the first frame,
    // which used to bounce the player right back out of a freshly started
    // playlist.
    LaunchedEffect(Unit) {
        var sawActivity = false
        androidx.compose.runtime.snapshotFlow {
            Triple(playback.isActive, playback.isConnecting, state.finishedSession != null)
        }.collect { (active, connecting, summaryPending) ->
            if (active || connecting) {
                sawActivity = true
            } else if (sawActivity && !summaryPending) {
                onNavigateBack()
            }
        }
    }

    // Returning from a rest (skipped or ran out) → clear the lingering
    // "rest finished" banner state app-wide.
    LaunchedEffect(playback.isResting) {
        if (!playback.isResting) viewModel.playback.acknowledgeRestFinished()
    }

    if (showQueueSheet && playback.isActive) {
        SessionQueueSheet(
            onDismiss = { showQueueSheet = false },
            onNavigateToClimb = { uuid, angle -> onNavigateToClimb(uuid, angle) },
            canEdit = true,
            onSelectClimb = { index -> viewModel.playback.setCurrent(index) },
            // End from the sheet goes through the player's stop() so the
            // summary appears instead of a silent pop-out.
            onEndPlaylist = { viewModel.stop() },
        )
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            onNavigateToClimb = onNavigateToClimb,
            onBoardMismatchExit = onNavigateToBrowser,
            viewModel = bleConnectionViewModel,
        )
    }

    // The queue's send fence is transport-level and remains authoritative.
    // Surface its typed mismatch here as well as in the browser: a playlist
    // user should never get a silent no-op just because the browser is not in
    // the composition tree.
    queueState.boardMismatch?.let { mismatch ->
        BoardPickerDialog(
            prefill = mismatch.prefill,
            mismatch = mismatch,
            onSelected = {
                queueManager.clearBoardMismatch()
                onNavigateToBrowser()
            },
            onDismiss = {
                queueManager.clearBoardMismatch()
                onNavigateToBrowser()
            },
        )
    }

    if (state.showStopConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissStopConfirm() },
            title = {
                Text(
                    stringResource(R.string.playlist_stop_end_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = { Text(stringResource(R.string.playlist_stop_end_body)) },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.stop() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag("player_stop_confirm"),
                ) {
                    Text(stringResource(R.string.playlist_stop_end_confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.dismissStopConfirm() },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.finishedSession?.let { finished ->
        SessionSummarySheet(
            session = finished,
            summary = state.summary,
            zones = state.zones,
            onDismiss = {
                viewModel.consumeSummary()
                onNavigateBack()
            },
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                stringResource(R.string.playlist_player_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (playback.queue.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.playlist_player_progress,
                                        playback.currentIndex + 1,
                                        playback.queue.size,
                                        formatElapsed(playback.elapsedSeconds),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // Back = leave the SCREEN; the playlist keeps running
                        // (mini-player stays visible everywhere).
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
                            modifier = Modifier.testTag("player_ble_button"),
                        ) {
                            Icon(
                                if (isBleConnected) Icons.Default.BluetoothConnected
                                else Icons.Default.Bluetooth,
                                contentDescription = stringResource(
                                    if (isBleConnected) R.string.cd_board_connected
                                    else R.string.cd_board_connect
                                ),
                                tint = if (isBleConnected) SuccessGreen
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Central, always-visible stop — no more overflow hunt.
                        IconButton(
                            onClick = { viewModel.requestStop() },
                            modifier = Modifier.testTag("player_stop"),
                        ) {
                            // A stop sign for the one case that really stops.
                            Icon(
                                Icons.Default.StopCircle,
                                contentDescription = stringResource(R.string.playlist_stop_end_title),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                )
                // Spotify-style position track: how far through the playlist.
                if (playback.queue.isNotEmpty()) {
                    val progress by animateFloatAsState(
                        targetValue = (playback.currentIndex + 1f) / playback.queue.size,
                        animationSpec = tween(300),
                        label = "playlist_progress",
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        color = OrangeAccent,
                        trackColor = OrangeAccent.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
                // Asked to share and unable to: the session repairs itself once
                // Bluetooth returns, but until then nobody can join and nothing
                // would say why.
                if (playback.isPaused && !playback.isResting) {
                    Surface(
                        color = WarningYellow.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.playlist_paused_banner),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = WarningYellow,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                RestTimerBannerSlot()
            }
        },
        bottomBar = {
            PlayerControls(
                playback = playback,
                onPrevious = { viewModel.playback.previous() },
                onNext = { viewModel.playback.next() },
                onSendToBoard = { viewModel.playback.resendCurrentClimb() },
                onConnect = { showBleSheet = true },
                onOpenQueue = { showQueueSheet = true },
                onAddClimbs = onNavigateToBrowser,
            )
        },
    ) { padding ->
        // No scrolling: header, log buttons and transport are always on
        // screen; the board render shrinks into the remaining space.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.finishedSession != null) {
                // Summary sheet is up — the queue is already cleared, so the
                // content behind it would flash "empty playlist / unknown
                // climb". Keep the backdrop neutral instead.
                return@Column
            }
            if (playback.isConnecting && !playback.isActive) {
                // Join in progress: GATT is connecting to the host.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.board_session_connecting),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // A rest remains a compact banner above the climb. The queue is
            // already parked on the upcoming climb, so it can be inspected
            // while the countdown continues.
            AnimatedContent(
                modifier = Modifier.weight(1f),
                targetState = playback.currentIndex,
                transitionSpec = {
                    when {
                        targetState > initialState ->
                            (slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(200)))
                        else ->
                            (slideInHorizontally(tween(250)) { -it / 3 } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(200)))
                    }
                },
                label = "player_content",
            ) { targetIndex ->
                key(targetIndex) {
                    ClimbingContent(
                        state = state,
                        playback = playback,
                        onSwipeNext = { viewModel.playback.next() },
                        onSwipePrevious = { viewModel.playback.previous() },
                        onClimbTapped = { uuid, angle ->
                            viewModel.climbNavState.climbUuids =
                                playback.queue.map { it.climbUuid }.distinct()
                            viewModel.climbNavState.angle = angle
                            viewModel.climbNavState.source = ClimbNavigationSource.QUEUE
                            onNavigateToClimb(uuid, angle)
                        },
                        onQuickLog = { viewModel.quickLog(it) },
                        onNavigateToSetter = onNavigateToSetter,
                        boardLayers = boardLayers,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClimbingContent(
    state: PlaylistPlayerState,
    playback: PlaylistPlaybackState,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    onClimbTapped: (String, Int) -> Unit,
    onQuickLog: (Boolean) -> Unit,
    onNavigateToSetter: (String) -> Unit,
    boardLayers: com.cruxcoach.android.ble.BoardLayerState,
) {
    val render = state.render
    val density = LocalDensity.current
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val swipeThresholdPx = with(density) { 96.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            // Spotify-style horizontal swipe anywhere on the content.
            .pointerInput(playback.currentIndex) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragTotal += amount
                    },
                    onDragEnd = {
                        when {
                            dragTotal <= -swipeThresholdPx && playback.hasNext -> onSwipeNext()
                            dragTotal >= swipeThresholdPx && playback.hasPrevious -> onSwipePrevious()
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f },
                )
            },
    ) {
        Spacer(Modifier.height(8.dp))
        if (render != null && playback.currentClimb != null) {
            PlaylistClimbOverview(
                render = render,
                angle = playback.currentClimb.angle,
                zones = state.zones,
                attemptInfo = playback.attemptInfo,
                onNavigateToSetter = onNavigateToSetter,
            )
        } else {
            Text(
                if (playback.currentClimb == null) ""
                else playback.currentClimbName ?: stringResource(R.string.ble_unknown_climb),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("player_climb_name"),
            )
        }
        if (render?.climb?.brand == BoardBrand.QUANTUM) {
            Spacer(Modifier.height(8.dp))
            QuantumLayerStatusStrip(
                state = boardLayers,
                currentClimbUuid = render.climb.uuid,
                currentPlacements = render.holds.mapTo(HashSet()) { it.placementId },
                // The complete assign/replace/remove controls live on the
                // occurrence's detail page; the running list stays compact.
                onOpen = {
                    onClimbTapped(
                        render.climb.uuid,
                        playback.currentClimb?.angle ?: 40,
                    )
                },
                testTag = "playlist_quantum_layer_rack",
            )
        }
        Spacer(Modifier.height(if (render?.climb?.brand == BoardBrand.QUANTUM) 8.dp else 12.dp))

        // Board area flexes into whatever height remains — header, log
        // buttons and transport NEVER require scrolling. The viz sizes by
        // width + aspectRatio internally, so the width gets capped to
        // maxHeight * aspect when vertical space is the limiting factor.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                render != null -> {
                    // Resolve the MoonBoard asset here as well as in the
                    // renderer: its real image aspect is needed to cap the
                    // player's width against the available height. Using the
                    // generic fallback aspect after the asset had loaded made
                    // taller board images overflow this box on shorter phones;
                    // the bottom of the photo and its hold circles were clipped.
                    val moonBoardAsset = if (render.isMoonBoard) {
                        rememberMoonBoardAsset(render.climb.layoutId)
                    } else null
                    val aspect = if (render.isMoonBoard) {
                        (moonBoardAsset as? MoonBoardAssetState.Ready)
                            ?.asset
                            ?.imageAspect
                            ?: MOONBOARD_FALLBACK_ASPECT
                    } else {
                        render.boardSize?.let { s ->
                            val w = (s.edgeRight - s.edgeLeft).toFloat()
                            val h = (s.edgeTop - s.edgeBottom).toFloat()
                            if (w > 0f && h > 0f) w / h else 1f
                        } ?: 1f
                    }
                    val cappedWidth = minOf(maxWidth, maxHeight * aspect)
                    Box(
                        modifier = Modifier
                            .width(cappedWidth)
                            .clickable { onClimbTapped(render.climb.uuid, playback.currentClimb?.angle ?: 40) }
                            .testTag("player_board"),
                    ) {
                        if (render.isMoonBoard) {
                            MoonBoardVisualization(
                                frames = render.climb.frames,
                                assetState = moonBoardAsset ?: MoonBoardAssetState.Unavailable,
                                variant = MoonBoardVariant.fromLayoutId(render.climb.layoutId),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            KilterBoardVisualization(
                                holds = render.holds,
                                placements = render.placements,
                                boardSize = render.boardSize,
                                boardImages = render.boardImages,
                                ledColors = if (render.climb.brand == BoardBrand.KILTER) render.ledColors
                                            else LedHoldColors.standardFor(render.climb.brand),
                                projectionLayers = playlistProjectionLayers(
                                    render.climb.brand,
                                    boardLayers,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                state.renderLoading -> CircularProgressIndicator(color = OrangeAccent)
                else -> Text(
                    if (playback.queue.isEmpty()) stringResource(R.string.playlist_empty_message)
                    else stringResource(R.string.playlist_climb_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        // Quick-log: the training loop is climb → log → next. One tap
        // per outcome, right under the board.
        if (render != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onQuickLog(false) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag("player_log_attempt"),
                ) {
                    Icon(
                        Icons.Default.Replay, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.playlist_log_attempt))
                }
                androidx.compose.material3.Button(
                    onClick = { onQuickLog(true) },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = com.cruxcoach.android.ui.theme.SuccessGreen,
                    ),
                    modifier = Modifier.weight(1f).testTag("player_log_send"),
                ) {
                    Icon(
                        Icons.Default.Check, contentDescription = null,
                        tint = DarkBackground, modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.playlist_log_send), color = DarkBackground)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PlaylistClimbOverview(
    render: ClimbRenderData,
    angle: Int,
    zones: com.cruxcoach.domain.board.IntensityZones?,
    attemptInfo: Pair<Int, Int>?,
    onNavigateToSetter: (String) -> Unit,
) {
    val climb = render.climb
    Card(
        modifier = Modifier.fillMaxWidth().testTag("player_climb_overview"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    climb.name,
                    modifier = Modifier.weight(1f, fill = false).testTag("player_climb_name"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                climb.setterUsername?.takeIf(String::isNotBlank)?.let { setter ->
                    val pubkey = climb.createdByPubkey?.takeIf(String::isNotBlank)
                    val isClickable = climb.origin == "cruxcoach" && pubkey != null
                    Spacer(Modifier.width(7.dp))
                    Text(
                        setter,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDecoration = if (isClickable) TextDecoration.Underline else TextDecoration.None,
                        ),
                        color = if (isClickable) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 112.dp).then(
                            if (isClickable) Modifier.clickable {
                                onNavigateToSetter(requireNotNull(pubkey))
                            } else Modifier,
                        ),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                climb.difficultyAverage?.let { difficulty ->
                    val french = GradeDisplayHelper.formatDifficulty(
                        difficulty, com.cruxcoach.android.data.GradeScale.FRENCH,
                    )
                    val vScale = GradeDisplayHelper.formatDifficulty(
                        difficulty, com.cruxcoach.android.data.GradeScale.V_SCALE,
                    )
                    Surface(color = zoneColorForDifficulty(difficulty, zones), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "$french / $vScale",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkBackground,
                        )
                    }
                }
                Text("$angle°", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = OrangeAccent)
                Text(
                    if (climb.framesCount > 1) "${climb.framesCount}F" else "${climb.moveCount}M",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${climb.qualityAverage?.let { "%.1f".format(it) } ?: "–"}★",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (climb.benchmarkDifficulty > 0.0) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(R.string.board_detail_benchmark),
                        tint = OrangeAccent,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            attemptInfo?.let { (attempt, total) ->
                Spacer(Modifier.height(5.dp))
                Surface(color = OrangeAccent.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        stringResource(R.string.playlist_player_attempt, attempt, total),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("player_attempt_chip"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    playback: PlaylistPlaybackState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSendToBoard: () -> Unit,
    onConnect: () -> Unit,
    onOpenQueue: () -> Unit,
    onAddClimbs: () -> Unit,
) {
    // Tactile confirmation on transport actions — at the wall you tap
    // with chalked fingers and don't stare at the screen.
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun withHaptic(action: () -> Unit): () -> Unit = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        action()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onOpenQueue,
            modifier = Modifier.size(44.dp).testTag("player_queue"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = stringResource(R.string.board_queue_title),
                modifier = Modifier.size(28.dp),
            )
        }
        // Back and forward, the same size, always both. The centre used to
        // hold play/pause, and putting "next" there kept the media-player
        // silhouette: one big primary action with a small sibling. Moving
        // through a playlist has no primary direction — going back for another
        // go is as ordinary as moving on.
        //
        // No special case during a rest either. Both already mean the right
        // thing there: next ends the rest and leaves you on the upcoming
        // climb, previous ends it and steps back — and the rest screen carries
        // its own "skip the rest" button besides. Replacing the pair with one
        // button there only took away the way back.
        IconButton(
            onClick = withHaptic(onPrevious),
            enabled = playback.hasPrevious,
            modifier = Modifier.size(56.dp).testTag("player_prev"),
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.cd_previous),
                modifier = Modifier.size(36.dp),
            )
        }
        FilledIconButton(
            onClick = withHaptic(if (playback.boardConnected) onSendToBoard else onConnect),
            enabled = playback.currentClimb != null,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = OrangeAccent,
                contentColor = DarkBackground,
            ),
            modifier = Modifier.size(60.dp).testTag(
                if (playback.boardConnected) "player_lamp" else "player_connect",
            ),
        ) {
            Icon(
                if (playback.boardConnected) Icons.Default.Lightbulb else Icons.Default.Bluetooth,
                contentDescription = stringResource(
                    if (playback.boardConnected) R.string.playlist_send_to_board else R.string.cd_board_connect,
                ),
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(
            onClick = withHaptic(onNext),
            enabled = playback.hasNext,
            modifier = Modifier.size(56.dp).testTag("player_next"),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.cd_next),
                modifier = Modifier.size(36.dp),
            )
        }
        // Add climbs: to the browser, where long-press adds to the
        // running playlist — same flow for host and participants.
        IconButton(
            onClick = onAddClimbs,
            modifier = Modifier.size(44.dp).testTag("player_add"),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.playlist_player_add_climbs),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** MoonBoard viz fallback aspect (see MoonBoardVisualization). */
private const val MOONBOARD_FALLBACK_ASPECT = 0.65f

private fun formatElapsed(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
