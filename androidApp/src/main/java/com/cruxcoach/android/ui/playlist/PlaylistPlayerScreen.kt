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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.PlaybackPhase
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.SessionQueueSheet
import com.cruxcoach.android.ui.board.SessionSummarySheet
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * The playlist player — the one place a running playlist lives. Board
 * render of the current climb, big transport controls, the rest block as
 * a full countdown ring with up-next preview, swipe navigation, attempt
 * indicator, participants and the summary sheet on stop. Hosts and
 * participants get the same surface (the coordinator routes role-aware);
 * next/prev are phase-aware: during a rest they skip/undo the pause
 * instead of jumping past the upcoming climb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPlayerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (String, Int) -> Unit,
    /** "+"-Button: zum Browser, wo Long-Press Climbs hinzufügt. */
    onNavigateToBrowser: () -> Unit = {},
    viewModel: PlaylistPlayerViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
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
            // End from the sheet goes through the player's stop() so the
            // summary appears instead of a silent pop-out.
            onEndPlaylist = { viewModel.stop() },
        )
    }

    if (state.showStopConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissStopConfirm() },
            title = {
                Text(
                    stringResource(R.string.board_session_end_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.stop() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag("player_stop_confirm"),
                ) {
                    Text(
                        if (playback.isHost) stringResource(R.string.ble_end_session)
                        else stringResource(R.string.ble_leave_session),
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissStopConfirm() }) {
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
                                if (playback.isParticipant && playback.hostName.isNotBlank()) {
                                    stringResource(R.string.playlist_player_hosted_by, playback.hostName)
                                } else {
                                    stringResource(R.string.playlist_player_title)
                                },
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
                        if (playback.participantCount > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.People, contentDescription = null,
                                    tint = OrangeAccent, modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "${playback.participantCount}",
                                    color = OrangeAccent,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        // Central, always-visible stop — no more overflow hunt.
                        IconButton(
                            onClick = { viewModel.requestStop() },
                            modifier = Modifier.testTag("player_stop"),
                        ) {
                            Icon(
                                Icons.Default.StopCircle,
                                contentDescription = if (playback.isHost) {
                                    stringResource(R.string.ble_end_session)
                                } else {
                                    stringResource(R.string.ble_leave_session)
                                },
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        if (playback.isHost) {
                            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("player_menu")) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ble_queue_resend)) },
                                    leadingIcon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.playback.resendCurrentClimb()
                                    },
                                    modifier = Modifier.testTag("player_resend"),
                                )
                            }
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
            }
        },
        bottomBar = {
            PlayerControls(
                playback = playback,
                onPrevious = { viewModel.playback.previous() },
                onNext = { viewModel.playback.next() },
                onTogglePause = { viewModel.playback.togglePause() },
                onOpenQueue = { showQueueSheet = true },
                onAddClimbs = onNavigateToBrowser,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
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

            // Animated phase/climb transitions: advancing slides left,
            // going back slides right, entering/leaving a rest crossfades.
            AnimatedContent(
                targetState = PlayerContentKey(playback.currentIndex, playback.isResting),
                transitionSpec = {
                    when {
                        initialState.resting != targetState.resting ->
                            fadeIn(tween(250)) togetherWith fadeOut(tween(200))
                        targetState.index > initialState.index ->
                            (slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(200)))
                        else ->
                            (slideInHorizontally(tween(250)) { -it / 3 } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(200)))
                    }
                },
                label = "player_content",
            ) { key ->
                if (key.resting) {
                    RestingContent(
                        playback = playback,
                        gradeScale = state.gradeScale,
                        onSkip = { viewModel.playback.next() },
                    )
                } else {
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
                    )
                }
            }
        }
    }
}

/** AnimatedContent key: which climb + which phase is on screen. */
private data class PlayerContentKey(val index: Int, val resting: Boolean)

@Composable
private fun ClimbingContent(
    state: PlaylistPlayerState,
    playback: PlaylistPlaybackState,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    onClimbTapped: (String, Int) -> Unit,
    onQuickLog: (Boolean) -> Unit,
) {
    val render = state.render
    val density = LocalDensity.current
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val swipeThresholdPx = with(density) { 96.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playback.currentClimbName ?: render?.climb?.name
                        ?: stringResource(R.string.ble_unknown_climb),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("player_climb_name"),
                )
                val subtitle = buildString {
                    (playback.currentClimbDifficulty ?: render?.climb?.difficultyAverage)?.let {
                        append(GradeDisplayHelper.formatDifficulty(it, state.gradeScale))
                    }
                    playback.currentClimb?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${it.angle}°")
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Attempt chip for limit/projecting runs ("Versuch 2 von 5").
            playback.attemptInfo?.let { (attempt, total) ->
                Surface(
                    color = OrangeAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.playlist_player_attempt, attempt, total),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("player_attempt_chip"),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            render != null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClimbTapped(render.climb.uuid, playback.currentClimb?.angle ?: 40) }
                    .testTag("player_board"),
            ) {
                if (render.isMoonBoard) {
                    MoonBoardVisualization(
                        frames = render.climb.frames,
                        assetState = rememberMoonBoardAsset(render.climb.layoutId),
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.renderLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = OrangeAccent) }
            else -> Box(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
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

/**
 * Rest phase: countdown ring front and center; the up-next card shows the
 * climb the queue is ALREADY parked on (it advanced when the rest was
 * armed) so the climber knows what's lit on the board.
 */
@Composable
private fun RestingContent(
    playback: PlaylistPlaybackState,
    gradeScale: com.cruxcoach.android.data.GradeScale,
    onSkip: () -> Unit,
) {
    val phase = playback.phase as? PlaybackPhase.Resting ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.playlist_player_rest_title),
            style = MaterialTheme.typography.titleMedium,
            color = InfoBlue,
        )
        Spacer(Modifier.height(20.dp))
        // Countdown ring — animated smoothly toward the next tick.
        val progress by animateFloatAsState(
            targetValue = if (phase.totalSeconds <= 0) 0f
                          else phase.secondsRemaining.toFloat() / phase.totalSeconds,
            animationSpec = tween(500),
            label = "rest_ring",
        )
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { 1f },
                color = InfoBlue.copy(alpha = 0.12f),
                strokeWidth = 10.dp,
                modifier = Modifier.size(220.dp),
            )
            CircularProgressIndicator(
                progress = { progress },
                color = InfoBlue,
                strokeWidth = 10.dp,
                modifier = Modifier.size(220.dp),
            )
            Text(
                formatCountdown(phase.secondsRemaining),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("player_rest_countdown"),
            )
        }
        Spacer(Modifier.height(28.dp))
        // Up next = the CURRENT queue item (already lit on the board).
        val upNextName = playback.currentClimbName
        if (upNextName != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.playlist_player_up_next),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(upNextName)
                            playback.currentClimbDifficulty?.let {
                                append("  ")
                                append(GradeDisplayHelper.formatDifficulty(it, gradeScale))
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("player_up_next"),
                    )
                    playback.attemptInfo?.let { (attempt, total) ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.playlist_player_attempt, attempt, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = OrangeAccent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        OutlinedButton(
            onClick = onSkip,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("player_skip_rest"),
        ) {
            Text(stringResource(R.string.playlist_player_skip_rest))
        }
    }
}

@Composable
private fun PlayerControls(
    playback: PlaylistPlaybackState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePause: () -> Unit,
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
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onOpenQueue,
            modifier = Modifier.size(48.dp).testTag("player_queue"),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = stringResource(R.string.board_queue_title),
                modifier = Modifier.size(28.dp),
            )
        }
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
            onClick = withHaptic(onTogglePause),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = OrangeAccent),
            modifier = Modifier.size(64.dp).testTag("player_pause"),
        ) {
            Icon(
                if (playback.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (playback.isPaused) stringResource(R.string.cd_resume)
                                     else stringResource(R.string.cd_pause),
                tint = DarkBackground,
                modifier = Modifier.size(32.dp),
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
            modifier = Modifier.size(48.dp).testTag("player_add"),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.playlist_player_add_climbs),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun formatCountdown(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatElapsed(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
