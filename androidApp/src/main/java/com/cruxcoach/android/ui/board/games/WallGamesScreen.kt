package com.cruxcoach.android.ui.board.games

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.games.EnduranceChallenge
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.GAME_LEVELS
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.TraverseGame
import kotlin.math.roundToInt

/** Wall games on the active board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallGamesScreen(
    onNavigateBack: () -> Unit,
    viewModel: WallGamesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var showClimbPicker by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn(state.running)

    if (showConnection) BleConnectionSheet(onDismiss = { showConnection = false })
    if (showClimbPicker) {
        ClimbPickerSheet(
            recent = state.recentClimbs,
            onPick = { climb ->
                showClimbPicker = false
                viewModel.chooseClimb(climb.uuid, climb.angle)
            },
            onDismiss = { showClimbPicker = false },
        )
    }

    val connected = connection == ConnectionState.CONNECTED || connection == ConnectionState.SENDING
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wall_games_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setSoundOn(!state.soundOn) }, modifier = Modifier.testTag("wall_games_sound")) {
                        Icon(
                            if (state.soundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(
                                if (state.soundOn) R.string.wall_games_sound_turn_off else R.string.wall_games_sound_turn_on,
                            ),
                        )
                    }
                    IconButton(onClick = { showConnection = true }, modifier = Modifier.testTag("wall_games_connect")) {
                        Icon(
                            if (connected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                            contentDescription = stringResource(R.string.board_browser_connect),
                            tint = if (connected) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.board != null) {
                GameActionBar(
                    state = state,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onReset = viewModel::reset,
                )
            }
        },
    ) { padding ->
        val board = state.board
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
            board == null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        if (state.unavailable == WallGameBoardLoad.NoHolds) R.string.wall_games_no_holds
                        else R.string.wall_games_unsupported,
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("wall_games_unavailable"),
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GamePicker(selected = state.game, enabled = !state.running, onSelect = viewModel::selectGame)
                Text(
                    stringResource(state.game.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.run.phase == GamePhase.IDLE) {
                    GameSettings(state = state, viewModel = viewModel, onChooseClimb = { showClimbPicker = true })
                } else {
                    RunPanel(state)
                }
                WallStatus(state.sendProblem, connected, onConnect = { showConnection = true })
                GameBoardPreview(state)
            }
        }
    }
}

private val WallGame.titleRes: Int
    @StringRes get() = when (this) {
        WallGame.LAVA -> R.string.wall_games_lava
        WallGame.ENDURANCE -> R.string.wall_games_endurance
        WallGame.THIEF -> R.string.wall_games_thief
        WallGame.TRAVERSE -> R.string.wall_games_traverse
    }

private val WallGame.descriptionRes: Int
    @StringRes get() = when (this) {
        WallGame.LAVA -> R.string.wall_games_lava_desc
        WallGame.ENDURANCE -> R.string.wall_games_endurance_desc
        WallGame.THIEF -> R.string.wall_games_thief_desc
        WallGame.TRAVERSE -> R.string.wall_games_traverse_desc
    }

@StringRes
private fun levelRes(level: Int): Int = when (level) {
    1 -> R.string.wall_games_level_1
    2 -> R.string.wall_games_level_2
    3 -> R.string.wall_games_level_3
    4 -> R.string.wall_games_level_4
    else -> R.string.wall_games_level_5
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamePicker(selected: WallGame, enabled: Boolean, onSelect: (WallGame) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WallGame.entries.forEach { game ->
            FilterChip(
                selected = game == selected,
                onClick = { onSelect(game) },
                enabled = enabled,
                label = { Text(stringResource(game.titleRes)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("wall_game_${game.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun GameSettings(state: WallGamesState, viewModel: WallGamesViewModel, onChooseClimb: () -> Unit) {
    when (state.game) {
        WallGame.LAVA -> LevelPicker(
            level = state.level,
            interval = stringResource(R.string.wall_games_lava_interval, WallGameFormat.seconds(FloorIsLava.intervalMs(state.level))),
            onLevel = viewModel::setLevel,
        )
        WallGame.ENDURANCE -> {
            ClimbCard(state, onChooseClimb)
            LevelPicker(
                level = state.level,
                interval = stringResource(
                    R.string.wall_games_endurance_interval,
                    WallGameFormat.seconds(EnduranceChallenge.intervalMs(state.level)),
                ),
                onLevel = viewModel::setLevel,
            )
        }
        WallGame.TRAVERSE -> {
            ClimbCard(state, onChooseClimb)
            TraverseTempo(state.traverseDifficulty, viewModel::setTraverseDifficulty)
        }
        WallGame.THIEF -> ThiefControls(state, viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelPicker(level: Int, interval: String, onLevel: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wall_games_level), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GAME_LEVELS.forEach { value ->
                FilterChip(
                    selected = value == level,
                    onClick = { onLevel(value) },
                    label = { Text(stringResource(levelRes(value))) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_level_$value"),
                )
            }
        }
        Text(interval, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TraverseTempo(difficulty: Double, onDifficulty: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wall_games_tempo), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = difficulty.toFloat(),
            onValueChange = { onDifficulty((it * 2).roundToInt() / 2.0) },
            valueRange = 0f..TraverseGame.MAX_DIFFICULTY.toFloat(),
            steps = (TraverseGame.MAX_DIFFICULTY * 2).toInt() - 1,
            modifier = Modifier.testTag("wall_games_tempo"),
        )
        Text(
            stringResource(R.string.wall_games_traverse_interval, WallGameFormat.seconds(TraverseGame.intervalMs(difficulty))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClimbCard(state: WallGamesState, onChooseClimb: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("wall_games_climb"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wall_games_climb), style = MaterialTheme.typography.labelMedium)
                    val climb = state.climb
                    Text(
                        if (climb != null) stringResource(R.string.wall_games_climb_at_angle, climb.name, climb.angle)
                        else stringResource(R.string.wall_games_no_climb),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (climb != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                TextButton(onClick = onChooseClimb, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (state.climb == null) R.string.wall_games_choose_climb else R.string.wall_games_change_climb))
                }
            }
            val problem = state.climbProblem
            when {
                problem != null -> Text(
                    stringResource(
                        when (problem) {
                            ClimbProblem.NOT_FOUND -> R.string.wall_games_climb_not_found
                            ClimbProblem.OTHER_BOARD -> R.string.wall_games_climb_other_board
                            ClimbProblem.NO_HOLDS -> R.string.wall_games_climb_no_holds
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                (state.climb?.offBoard ?: 0) > 0 -> Text(
                    pluralStringResource(R.plurals.wall_games_climb_off_board, state.climb!!.offBoard, state.climb.offBoard),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.climb == null -> Text(
                    stringResource(R.string.wall_games_climb_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThiefControls(state: WallGamesState, viewModel: WallGamesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wall_games_density), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldsThief.DENSITIES.forEach { density ->
                FilterChip(
                    selected = density == state.thiefDensity,
                    onClick = { viewModel.setThiefDensity(density) },
                    label = { Text(stringResource(R.string.wall_games_density_value, density)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_density_$density"),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                pluralStringResource(
                    R.plurals.wall_games_hands_left,
                    HoldsThief.handCount(state.thiefRoute),
                    HoldsThief.handCount(state.thiefRoute),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::newThiefClimb, modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_new_climb")) {
                Text(stringResource(R.string.wall_games_new_climb))
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.wall_games_steal_count), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = { viewModel.setStealCount(state.stealCount - 1) },
                enabled = state.stealCount > 1,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.wall_games_steal_less)) }
            Text(
                state.stealCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            IconButton(
                onClick = { viewModel.setStealCount(state.stealCount + 1) },
                enabled = state.stealCount < WallGamesState.MAX_STEAL,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wall_games_steal_more)) }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = viewModel::stealHolds,
                enabled = HoldsThief.handCount(state.thiefRoute) > 0,
                modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_steal"),
            ) {
                Text(pluralStringResource(R.plurals.wall_games_steal, state.stealCount, state.stealCount))
            }
        }
    }
}

@Composable
private fun RunPanel(state: WallGamesState) {
    val run = state.run
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("wall_games_run"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (run.phase) {
                GamePhase.COUNTDOWN -> Text(
                    run.countdown.toString(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent,
                    modifier = Modifier.testTag("wall_games_countdown"),
                )
                GamePhase.RUNNING -> {
                    Text(
                        if (run.elapsedMs < GO_SHOWN_MS) stringResource(R.string.wall_games_go)
                        else WallGameFormat.clock(run.elapsedMs),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("wall_games_clock"),
                    )
                    ProgressLine(state)
                    if (run.timelineDone && state.game.needsClimb) {
                        Text(stringResource(R.string.wall_games_all_lit), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                GamePhase.FINISHED -> {
                    if (state.game == WallGame.LAVA && run.timelineDone) {
                        Text(stringResource(R.string.wall_games_lava_top), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    Text(
                        stringResource(R.string.wall_games_your_time, WallGameFormat.clock(run.elapsedMs)),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("wall_games_result"),
                    )
                    ProgressLine(state)
                }
                GamePhase.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun ProgressLine(state: WallGamesState) {
    val run = state.run
    if (run.steps <= 0) return
    val res = when (state.game) {
        WallGame.LAVA -> R.string.wall_games_progress_rows
        WallGame.TRAVERSE -> R.string.wall_games_progress_moves
        else -> R.string.wall_games_progress_steps
    }
    Text(
        stringResource(res, run.step, run.steps),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WallStatus(problem: WallSendProblem?, connected: Boolean, onConnect: () -> Unit) {
    val text = when {
        !connected || problem == WallSendProblem.NOT_CONNECTED -> R.string.wall_games_not_connected
        problem == WallSendProblem.OTHER_BOARD -> R.string.wall_games_other_board
        problem == WallSendProblem.SESSION -> R.string.wall_games_session
        problem == WallSendProblem.CLEANING -> R.string.wall_games_cleaning
        problem == WallSendProblem.FAILED -> R.string.wall_games_send_failed
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("wall_games_status")) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem == WallSendProblem.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!connected) {
            TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.board_browser_connect))
            }
        }
    }
}

@Composable
private fun GameBoardPreview(state: WallGamesState) {
    val board = state.board ?: return
    val holds = state.shownHolds
    // A tall wall at full width would push everything else off screen.
    val heightCap = (LocalConfiguration.current.screenHeightDp * BOARD_MAX_HEIGHT_FRACTION).dp
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val aspect = board.boardSize?.let { size ->
            val w = (size.edgeRight - size.edgeLeft).toFloat()
            val h = (size.edgeTop - size.edgeBottom).toFloat()
            if (w > 0f && h > 0f) w / h else null
        } ?: MOONBOARD_ASPECT
        val width = minOf(maxWidth, heightCap * aspect)
        Box(Modifier.width(width).testTag("wall_games_board")) {
            if (board.brand == BoardBrand.MOONBOARD) {
                MoonBoardVisualization(
                    frames = WallGameHolds.moonBoardFrames(holds),
                    assetState = rememberMoonBoardAsset(board.layoutId),
                    variant = board.moonVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                KilterBoardVisualization(
                    holds = holds,
                    placements = board.placements,
                    boardSize = board.boardSize,
                    boardImages = board.boardImages,
                    ledColors = board.ledColors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun GameActionBar(
    state: WallGamesState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.run.phase) {
                GamePhase.COUNTDOWN, GamePhase.RUNNING -> Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("wall_games_stop"),
                ) { Text(stringResource(R.string.wall_games_stop)) }
                GamePhase.FINISHED -> {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("wall_games_done"),
                    ) { Text(stringResource(R.string.action_done)) }
                    Button(
                        onClick = onStart,
                        enabled = state.canStart,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("wall_games_again"),
                    ) { Text(stringResource(R.string.wall_games_play_again)) }
                }
                GamePhase.IDLE -> Button(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("wall_games_start"),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.game != WallGame.THIEF -> R.string.wall_games_start
                                state.thiefLive -> R.string.wall_games_light_again
                                else -> R.string.wall_games_light
                            },
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimbPickerSheet(recent: List<RecentClimb>, onPick: (RecentClimb) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wall_games_recent_title), style = MaterialTheme.typography.titleMedium)
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.wall_games_recent_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("wall_games_recent_empty"),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(recent, key = { "${it.uuid}@${it.angle}" }) { climb ->
                        Text(
                            stringResource(R.string.wall_games_climb_at_angle, climb.name, climb.angle),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onPick(climb) }
                                .padding(vertical = 12.dp)
                                .testTag("wall_games_recent_item"),
                        )
                    }
                }
            }
        }
    }
}

/** Keeps the display on while a game runs; the phone usually lies on the floor meanwhile. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

private const val GO_SHOWN_MS = 800L
private const val BOARD_MAX_HEIGHT_FRACTION = 0.6f
private const val MOONBOARD_ASPECT = 0.72f
