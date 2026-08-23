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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Groups
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.PlaybackPhase
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.data.SessionVisibility
import com.cruxcoach.android.ui.board.QueueDeliveryPolicy
import com.cruxcoach.android.ui.board.BoardActionVisual
import com.cruxcoach.android.ui.board.BoardActionVisualPolicy
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.BleConnectionViewModel
import com.cruxcoach.android.ui.board.ClimbRenderData
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardAssetState
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.SessionQueueSheet
import com.cruxcoach.android.ui.board.SessionSummarySheet
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.navigation.ClimbNavigationSource
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.android.ui.theme.zoneColorForDifficulty
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import android.bluetooth.BluetoothManager
import androidx.compose.ui.platform.LocalContext
import com.cruxcoach.android.ble.BlePermissionHelper
import com.cruxcoach.android.ble.ConnectionState
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.ui.res.pluralStringResource

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
    onNavigateToSetter: (String) -> Unit = {},
    /** "+"-Button: zum Browser, wo Long-Press Climbs hinzufügt. */
    onNavigateToBrowser: () -> Unit = {},
    /**
     * Opens the board's shared list — the layer this player sits on top of.
     *
     * A private local playlist has no such screen and keeps its own sheet, so
     * the list button means "show me the list" in both cases without the
     * player having to know which kind it is beyond this one branch.
     */
    onOpenBoardPlaylist: () -> Unit = {},
    /** Opens the system's Bluetooth-enable dialog via the connect sheet's flow. */
    onEnableBluetooth: () -> Unit = {},
    /** Asks for BLUETOOTH_ADVERTISE when that is what blocks sharing. */
    onRequestSharingPermission: () -> Unit = {},
    viewModel: PlaylistPlayerViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A row opens this player on one occurrence without moving shared state.
    // Present that local focus as the player cursor; canonical current remains
    // in mesh.currentEntryId and is changed only by the lamp.
    val displayedPlayback = if (playback.isCanonicalPlaylist &&
        state.focusedIndex in playback.queue.indices
    ) {
        val item = playback.queue[state.focusedIndex]
        playback.copy(
            currentIndex = state.focusedIndex,
            currentClimb = item,
            currentClimbName = state.render?.climb?.name,
            currentClimbDifficulty = state.render?.climb?.difficultyAverage,
            mesh = playback.mesh?.copy(
                selectionOnBoard = state.focusedEntryId == playback.mesh?.currentEntryId &&
                    playback.mesh?.confirmedProjection?.let {
                        it.climbUuid.equals(item.climbUuid, ignoreCase = true) &&
                            it.angle == item.angle
                    } == true,
            ),
        )
    } else playback
    var showQueueSheet by remember { mutableStateOf(false) }
    var showBleSheet by remember { mutableStateOf(false) }
    val bleConnectionViewModel: BleConnectionViewModel = hiltViewModel()
    val bleConnectionState by bleConnectionViewModel.state.collectAsStateWithLifecycle()
    val isBleConnected =
        bleConnectionState.connectionState == ConnectionState.CONNECTED ||
            bleConnectionState.connectionState == ConnectionState.SENDING ||
            bleConnectionState.activeBoardCellId != null
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val loggedSendMsg = stringResource(R.string.playlist_logged_send)
    val loggedAttemptMsg = stringResource(R.string.playlist_logged_attempt)
    val noRandomMatchMsg = stringResource(R.string.board_playlist_random_unavailable)

    // Quick-log feedback: snackbar + reset the one-shot flag.
    LaunchedEffect(state.lastLogged) {
        state.lastLogged?.let { isSend ->
            snackbarHostState.showSnackbar(if (isSend) loggedSendMsg else loggedAttemptMsg)
            viewModel.consumeLogFeedback()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.randomAddUnavailable.collect {
            snackbarHostState.showSnackbar(noRandomMatchMsg)
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

    if (showQueueSheet && playback.isActive && !playback.isCanonicalPlaylist) {
        SessionQueueSheet(
            onDismiss = { showQueueSheet = false },
            onNavigateToClimb = { uuid, angle -> onNavigateToClimb(uuid, angle) },
            canEdit = true,
            // End from the sheet goes through the player's stop() so the
            // summary appears instead of a silent pop-out.
            onEndPlaylist = { viewModel.stop() },
        )
    }

    if (showBleSheet) {
        BleConnectionSheet(
            onDismiss = { showBleSheet = false },
            onNavigateToClimb = onNavigateToClimb,
            viewModel = bleConnectionViewModel,
        )
    }

    if (state.showStopConfirm) {
        // Three different things happened behind one red stop icon. A host with
        // participants does not end anything: stopSharing() sends the sentinel
        // that starts host migration, so the group climbs on and the person who
        // just pressed "End session" was never told. Say which of the three it
        // is, and stop promising termination when a handover is what follows.
        val boardPlaylist = playback.isCanonicalPlaylist
        val othersStay = !boardPlaylist && playback.isHost && playback.participantCount > 1
        val remaining = (playback.participantCount - 1).coerceAtLeast(1)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissStopConfirm() },
            title = {
                Text(
                    stringResource(
                        when {
                            boardPlaylist -> R.string.playlist_stop_board_title
                            !playback.isHost -> R.string.playlist_stop_leave_title
                            othersStay -> R.string.playlist_stop_handover_title
                            else -> R.string.playlist_stop_end_title
                        }
                    ),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    when {
                        boardPlaylist -> stringResource(R.string.playlist_stop_board_body)
                        !playback.isHost -> stringResource(R.string.playlist_stop_leave_body)
                        othersStay -> pluralStringResource(
                            R.plurals.playlist_stop_handover_body, remaining, remaining,
                        )
                        else -> stringResource(R.string.playlist_stop_end_body)
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.stop() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        // Only a real ending is destructive. Leaving and handing
                        // over are not, and red made them look like one.
                        // Closing a board playlist is not destructive: the group's
                        // list is untouched and this device stays in it.
                        containerColor = if (!boardPlaylist && playback.isHost && !othersStay) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.testTag("player_stop_confirm"),
                ) {
                    Text(
                        stringResource(
                            when {
                                boardPlaylist -> R.string.playlist_stop_board_confirm
                                !playback.isHost -> R.string.playlist_stop_leave_confirm
                                othersStay -> R.string.playlist_stop_handover_confirm
                                else -> R.string.playlist_stop_end_confirm
                            }
                        )
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Handing over is the default; ending it for the whole
                    // group is the deliberate second choice, not the silent
                    // one it used to be.
                    if (othersStay) {
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.stop(endForEveryone = true) },
                            modifier = Modifier.testTag("player_stop_end_for_all"),
                        ) {
                            Text(
                                stringResource(R.string.playlist_stop_end_for_all),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.dismissStopConfirm() },
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
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
                            if (displayedPlayback.queue.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.playlist_player_progress,
                                        displayedPlayback.currentIndex + 1,
                                        displayedPlayback.queue.size,
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
                            // A stop sign for the one case that really stops.
                            val reallyEnds = playback.isCanonicalPlaylist ||
                                playback.isHost && playback.participantCount <= 1
                            Icon(
                                if (reallyEnds) Icons.Default.StopCircle
                                else Icons.AutoMirrored.Filled.Logout,
                                contentDescription = stringResource(
                                    when {
                                        playback.isCanonicalPlaylist -> R.string.playlist_stop_end_title
                                        !playback.isHost -> R.string.playlist_stop_leave_title
                                        reallyEnds -> R.string.playlist_stop_end_title
                                        else -> R.string.playlist_stop_handover_title
                                    }
                                ),
                                tint = if (reallyEnds) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    },
                )
                // Spotify-style position track: how far through the playlist.
                if (displayedPlayback.queue.isNotEmpty()) {
                    val progress by animateFloatAsState(
                        targetValue = (displayedPlayback.currentIndex + 1f) /
                            displayedPlayback.queue.size,
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
                // The board's list is a group's list: what this device has
                // selected and what the wall is actually showing are two
                // facts, and the player says both rather than letting the big
                // climb render imply the second one. The lamp below is what
                // closes the gap.
                if (playback.isCanonicalPlaylist) {
                    BoardStatusLine(displayedPlayback)
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
                if (playback.sharingBlocked) {
                    // sharingBlocked is true for three different reasons and
                    // carries none of them, so the banner used to name the
                    // most common one and be wrong for the other two: a
                    // climber who had refused the advertise permission read
                    // "Bluetooth is off" with Bluetooth on. Ask the platform
                    // here instead — it is the same question, answered where
                    // the answer exists.
                    val context = LocalContext.current
                    // Read platform state on every recomposition. Keying these
                    // checks only on sharingBlocked kept the pre-dialog result
                    // cached after a permission grant, so the banner continued
                    // to claim that permission was missing.
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    val bluetoothOff = adapter != null && !adapter.isEnabled
                    val advertisePermissionMissing =
                        !BlePermissionHelper.hasAdvertisingPermission(context)
                    Surface(
                        color = WarningYellow.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        bluetoothOff -> R.string.ble_sharing_blocked
                                        advertisePermissionMissing ->
                                            R.string.ble_sharing_blocked_permission
                                        else -> R.string.ble_sharing_blocked_other
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = WarningYellow,
                                modifier = Modifier.weight(1f),
                            )
                            // Only offer an action that leads somewhere. The
                            // third case — the GATT server itself refused —
                            // has nothing for the user to do but retry.
                            if (bluetoothOff) {
                                TextButton(onClick = onEnableBluetooth) {
                                    Text(stringResource(R.string.ble_sharing_blocked_action))
                                }
                            } else if (advertisePermissionMissing) {
                                TextButton(onClick = onRequestSharingPermission) {
                                    Text(stringResource(R.string.ble_sharing_blocked_permission_action))
                                }
                            }
                        }
                    }
                }
                if (!playback.isCanonicalPlaylist && playback.isHost && !playback.sharingBlocked) {
                    val isJoinable = playback.visibility == SessionVisibility.JOINABLE
                    Surface(
                        color = if (isJoinable) {
                            OrangeAccent.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (isJoinable) {
                                    R.string.ble_session_visibility_status_joinable
                                } else {
                                    R.string.ble_session_visibility_status_local
                                },
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isJoinable) {
                                OrangeAccent
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        },
        bottomBar = {
            PlayerControls(
                playback = displayedPlayback,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onLamp = viewModel::resendFocused,
                onConnect = { showBleSheet = true },
                onOpenQueue = {
                    if (playback.isCanonicalPlaylist) onOpenBoardPlaylist()
                    else showQueueSheet = true
                },
                onAddClimbs = onNavigateToBrowser,
                onAddRandom = viewModel::addRandomClimb,
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

            // Animated phase/climb transitions: advancing slides left,
            // going back slides right, entering/leaving a rest crossfades.
            AnimatedContent(
                modifier = Modifier.weight(1f),
                targetState = PlayerContentKey(
                    displayedPlayback.currentIndex,
                    displayedPlayback.isResting,
                ),
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
                        playback = displayedPlayback,
                        gradeScale = state.gradeScale,
                        onSkip = { viewModel.playback.next() },
                    )
                } else {
                    ClimbingContent(
                        state = state,
                        playback = displayedPlayback,
                        onSwipeNext = viewModel::next,
                        onSwipePrevious = viewModel::previous,
                        onClimbTapped = { uuid, angle ->
                            viewModel.climbNavState.climbUuids =
                                playback.queue.map { it.climbUuid }.distinct()
                            viewModel.climbNavState.angle = angle
                            viewModel.climbNavState.source = ClimbNavigationSource.QUEUE
                            viewModel.climbNavState.boardPlaylistEntryId =
                                state.focusedEntryId.takeIf { playback.isCanonicalPlaylist }
                            viewModel.climbNavState.boardPlaylistEntryClimbUuid =
                                uuid.takeIf { playback.isCanonicalPlaylist }
                            onNavigateToClimb(uuid, angle)
                        },
                        onQuickLog = { viewModel.quickLog(it) },
                        onNavigateToSetter = onNavigateToSetter,
                    )
                }
            }
        }
    }
}

/**
 * Selected versus confirmed, in one line.
 *
 * Deliberately without a climb name for what the wall is showing: naming it
 * would mean resolving a second climb on every snapshot, and the honest thing
 * the player has to convey is only whether the group's selection is up there.
 * The list screen, where somebody is actually looking at what is queued,
 * names it.
 */
@Composable
private fun BoardStatusLine(playback: PlaylistPlaybackState) {
    val mesh = playback.mesh ?: return
    val pending = playback.pendingProjection
    val (text, color) = when {
        // Unnamed on purpose, as everywhere in the player: resolving a climb
        // name here would mean a lookup on every snapshot, and the list screen
        // — where somebody is actually looking at the occurrences — names it.
        pending != null -> stringResource(
            when (pending.reason) {
                com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
                    .BOARD_WRITE_FAILED -> R.string.board_playlist_send_write_failed
                com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
                    .CLIMB_UNAVAILABLE -> R.string.board_playlist_send_unavailable
            },
            stringResource(R.string.board_playlist_failure_this_climb),
        ) to MaterialTheme.colorScheme.error
        mesh.selectionOnBoard ->
            stringResource(R.string.board_playlist_on_board) to SuccessGreen
        !mesh.projectionKnown ->
            stringResource(R.string.board_playlist_board_unknown) to WarningYellow
        else -> stringResource(R.string.board_playlist_not_on_board) to OrangeAccent
    }
    Surface(color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                modifier = Modifier.testTag("player_board_status"),
            )
            // Having a copy of the list is not being up to date with the group.
            if (!mesh.synchronized) {
                Text(
                    stringResource(R.string.board_playlist_out_of_sync),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
    onNavigateToSetter: (String) -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Empty queue: no title — the empty-state message below
                    // explains the situation; "Unbekannter Climb" would
                    // contradict the banner the user just tapped.
                    when {
                        playback.currentClimb == null -> ""
                        else -> playback.currentClimbName ?: render?.climb?.name
                            ?: stringResource(R.string.ble_unknown_climb)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).testTag("player_climb_name"),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

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
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // The lamp used to live up here as well as in the
                        // transport row. Two controls that light the wall is
                        // one more than there can be: at the board it has to
                        // be true that exactly one thing projects, in exactly
                        // one place, on every screen. It is now between
                        // Previous and Next, which is where the decision it
                        // belongs to is made.
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

/**
 * The playlist player's climb identity uses the same compact information
 * hierarchy as the climb detail screen: name and setter first, then the
 * scannable grade/angle/moves/rating/status row. Playlist transport remains
 * outside this card because local focus and board projection are distinct.
 */
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag("player_climb_overview"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = climb.name,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("player_climb_name"),
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
                        text = setter,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium.copy(
                            textDecoration = if (isClickable) TextDecoration.Underline
                            else TextDecoration.None,
                        ),
                        color = if (isClickable) OrangeAccent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .widthIn(max = 112.dp)
                            .then(
                                if (isClickable) Modifier.clickable {
                                    onNavigateToSetter(pubkey)
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
                        difficulty,
                        com.cruxcoach.android.data.GradeScale.FRENCH,
                    )
                    val vScale = GradeDisplayHelper.formatDifficulty(
                        difficulty,
                        com.cruxcoach.android.data.GradeScale.V_SCALE,
                    )
                    Surface(
                        color = zoneColorForDifficulty(difficulty, zones),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "$french / $vScale",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = DarkBackground,
                        )
                    }
                }
                Text(
                    "$angle°",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent,
                )
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Default.Groups,
                        contentDescription = stringResource(R.string.board_sends),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        "${climb.ascensionistCount ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            attemptInfo?.let { (attempt, total) ->
                Spacer(Modifier.height(5.dp))
                Surface(
                    color = OrangeAccent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        stringResource(R.string.playlist_player_attempt, attempt, total),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("player_attempt_chip"),
                    )
                }
            }
        }
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
            .fillMaxSize()
            // Safety valve for very small screens — normally everything fits.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
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
    onLamp: () -> Unit,
    onConnect: () -> Unit,
    onOpenQueue: () -> Unit,
    onAddClimbs: () -> Unit,
    onAddRandom: () -> Unit,
) {
    val sendCapable = when {
        playback.isCanonicalPlaylist -> playback.mesh?.boardReady == true
        playback.isParticipant -> true
        else -> QueueDeliveryPolicy.canSend(playback.isHost, playback.boardConnected)
    }
    val boardVisual = BoardActionVisualPolicy.resolve(
        sendCapable = sendCapable,
        connecting = playback.boardConnecting,
    )
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
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (playback.isCanonicalPlaylist) {
            // The board list is the layer below this focused view, so Back is
            // its single, predictable way out. The left edge is the useful
            // one-tap add action instead of a second route to the same list.
            IconButton(
                onClick = onAddRandom,
                modifier = Modifier.size(44.dp).testTag("player_add_random"),
            ) {
                Icon(
                    Icons.Default.Casino,
                    contentDescription = stringResource(R.string.board_playlist_add_random),
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            // Legacy local playback still owns its queue sheet; unlike the
            // board playlist there is no list destination underneath it.
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
        // The physical-board action, between the two arrows and nowhere else.
        //
        // Previous and Next move what the group is looking at and deliberately
        // leave the wall alone — somebody flipping ahead through the list must
        // not take the board from whoever is on it. This is the whole of "and
        // now put it up there", and having it between them is what makes the
        // difference legible without a word of explanation.
        // "The wall is not showing this" covers both kinds of playlist: the
        // shared list sets it whenever the selection is not the confirmed
        // climb, and the explicit send mode sets it on a private one.
        FilledIconButton(
            onClick = withHaptic(
                if (boardVisual == BoardActionVisual.LAMP) onLamp else onConnect,
            ),
            enabled = playback.currentClimb != null,
            // Successful projection never disables a resend. The lamp is an
            // action, not a status light, so it stays visually active while a
            // climb can be sent and only greys out when sending is impossible.
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = OrangeAccent,
                contentColor = DarkBackground,
            ),
            modifier = Modifier.size(60.dp).testTag(
                if (boardVisual == BoardActionVisual.LAMP) "player_lamp" else "player_connect",
            ),
        ) {
            Icon(
                when (boardVisual) {
                    BoardActionVisual.LAMP -> Icons.Default.Lightbulb
                    BoardActionVisual.CONNECTING ->
                        Icons.AutoMirrored.Filled.BluetoothSearching
                    BoardActionVisual.CONNECT -> Icons.Default.Bluetooth
                },
                contentDescription = stringResource(
                    when (boardVisual) {
                        BoardActionVisual.LAMP -> R.string.board_playlist_lamp
                        BoardActionVisual.CONNECTING -> R.string.cd_board_dock_connecting
                        BoardActionVisual.CONNECT -> R.string.cd_board_connect
                    },
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

private fun formatCountdown(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatElapsed(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
