package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The playlist player — the one place a running playlist lives. Shows the
 * current climb on the board, big transport controls, the rest phase as a
 * full countdown with up-next preview, participants, and the end-of-
 * playlist summary. Works identically for hosts and participants (the
 * coordinator routes controls role-aware).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPlayerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClimb: (String, Int) -> Unit,
    viewModel: PlaylistPlayerViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    // Playlist ended elsewhere (host stopped, migration failed) and no
    // summary pending → leave the player. isConnecting covers the join
    // flow: the player opens while GATT is still connecting.
    LaunchedEffect(playback.isActive, playback.isConnecting, state.finishedSession) {
        if (!playback.isActive && !playback.isConnecting && state.finishedSession == null) {
            onNavigateBack()
        }
    }

    if (showQueueSheet && playback.isActive) {
        SessionQueueSheet(
            onDismiss = { showQueueSheet = false },
            onNavigateToClimb = { uuid, angle -> onNavigateToClimb(uuid, angle) },
            canEdit = true,
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
        topBar = {
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
                    // Down-chevron semantics: the playlist keeps running.
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
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
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.testTag("player_menu")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (playback.isHost) {
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
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (playback.isHost) stringResource(R.string.ble_end_session)
                                    else stringResource(R.string.ble_leave_session),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                viewModel.stop()
                            },
                            modifier = Modifier.testTag("player_stop"),
                        )
                    }
                },
            )
        },
        bottomBar = {
            PlayerControls(
                playback = playback,
                onPrevious = { viewModel.playback.previous() },
                onNext = { viewModel.playback.next() },
                onTogglePause = { viewModel.playback.togglePause() },
                onOpenQueue = { showQueueSheet = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
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
            when (val phase = playback.phase) {
                is PlaybackPhase.Resting -> RestingContent(
                    phase = phase,
                    upNextName = playback.upNext?.let { next ->
                        // The queue only knows the uuid; the sheet resolves
                        // names lazily — show the plain position fallback.
                        stringResource(
                            R.string.playlist_player_up_next_position,
                            playback.currentIndex + 2, playback.queue.size,
                        )
                    },
                    onSkip = { viewModel.playback.skipRest() },
                )
                is PlaybackPhase.Climbing -> ClimbingContent(
                    state = state,
                    playback = playback,
                    onClimbTapped = { uuid, angle ->
                        viewModel.climbNavState.climbUuids = playback.queue.map { it.climbUuid }
                        viewModel.climbNavState.angle = angle
                        viewModel.climbNavState.source = ClimbNavigationSource.QUEUE
                        onNavigateToClimb(uuid, angle)
                    },
                )
            }
        }
    }
}

@Composable
private fun ClimbingContent(
    state: PlaylistPlayerState,
    playback: com.cruxcoach.android.data.PlaylistPlaybackState,
    onClimbTapped: (String, Int) -> Unit,
) {
    val render = state.render
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        // Climb header
        Text(
            playback.currentClimbName ?: state.render?.climb?.name
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
        Spacer(Modifier.height(12.dp))
    }
}

/** Rest phase: the countdown IS the screen — big timer, progress, up-next. */
@Composable
private fun RestingContent(
    phase: PlaybackPhase.Resting,
    upNextName: String?,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.HourglassBottom,
            contentDescription = null,
            tint = InfoBlue,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.playlist_player_rest_title),
            style = MaterialTheme.typography.titleMedium,
            color = InfoBlue,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            formatCountdown(phase.secondsRemaining),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("player_rest_countdown"),
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = {
                if (phase.totalSeconds <= 0) 0f
                else 1f - phase.secondsRemaining.toFloat() / phase.totalSeconds
            },
            color = InfoBlue,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        if (upNextName != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
                shape = RoundedCornerShape(12.dp),
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
                    Text(
                        upNextName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
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
    playback: com.cruxcoach.android.data.PlaylistPlaybackState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenQueue: () -> Unit,
) {
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
            onClick = onPrevious,
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
            onClick = onTogglePause,
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
            onClick = onNext,
            enabled = playback.hasNext,
            modifier = Modifier.size(56.dp).testTag("player_next"),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.cd_next),
                modifier = Modifier.size(36.dp),
            )
        }
        // Symmetry spacer opposite the queue button.
        Spacer(Modifier.size(48.dp))
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
