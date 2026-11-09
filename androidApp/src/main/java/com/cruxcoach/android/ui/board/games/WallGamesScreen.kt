package com.cruxcoach.android.ui.board.games

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import com.cruxcoach.android.ui.common.InfoButton
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.BleConnectionSheet
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.games.EnduranceChallenge
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.GAME_LEVELS
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.TraverseGame

/** What the wall games screen asks for: the view model, or a stand-in in the review renders. */
interface WallGamesActions {
    fun selectGame(game: WallGame)
    fun setLevel(level: Int)
    fun setTraverseDifficulty(difficulty: Double)
    fun setSoundOn(on: Boolean)
    fun setThiefDensity(density: Int)
    fun newThiefClimb()
    fun setStealCount(count: Int)
    fun stealHolds()
    fun start()
    fun stop()
    fun reset()
}

/** Wall games on the active board. */
@Composable
fun WallGamesScreen(
    onNavigateBack: () -> Unit,
    onOpenCatalogues: () -> Unit = {},
    viewModel: WallGamesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    var showClimbPicker by rememberSaveable { mutableStateOf(false) }

    KeepScreenOn(state.running)
    // Back from the catalogues (or anywhere) without a board: look again.
    LifecycleResumeEffect(Unit) {
        viewModel.reloadIfMissing()
        onPauseOrDispose {}
    }

    if (showConnection) BleConnectionSheet(onDismiss = { showConnection = false })
    if (showClimbPicker) {
        ClimbPickerSheet(
            recent = state.recentClimbs,
            onPick = { climb ->
                showClimbPicker = false
                viewModel.chooseClimb(climb.uuid, climb.angle)
            },
            onBrowse = {
                showClimbPicker = false
                onNavigateBack()
            },
            onDismiss = { showClimbPicker = false },
        )
    }

    WallGamesContent(
        state = state,
        connected = connection == ConnectionState.CONNECTED || connection == ConnectionState.SENDING,
        actions = viewModel,
        onNavigateBack = onNavigateBack,
        onConnect = { showConnection = true },
        onChooseClimb = { showClimbPicker = true },
        onOpenCatalogues = onOpenCatalogues,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallGamesContent(
    state: WallGamesState,
    connected: Boolean,
    actions: WallGamesActions,
    onNavigateBack: () -> Unit,
    onConnect: () -> Unit,
    onChooseClimb: () -> Unit,
    onOpenCatalogues: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                // At the largest font "Wand-Spiele" breaks after its hyphen, not inside a word.
                title = { Text(stringResource(R.string.wall_games_title), style = LocalTextStyle.current.copy(hyphens = Hyphens.Auto)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { actions.setSoundOn(!state.soundOn) }, modifier = Modifier.testTag("wall_games_sound")) {
                        Icon(
                            if (state.soundOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(
                                if (state.soundOn) R.string.wall_games_sound_turn_off else R.string.wall_games_sound_turn_on,
                            ),
                        )
                    }
                    IconButton(onClick = onConnect, modifier = Modifier.testTag("wall_games_connect")) {
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
                GameActionBar(state = state, connected = connected, actions = actions, onConnect = onConnect, onChooseClimb = onChooseClimb)
            }
        },
    ) { padding ->
        val board = state.board
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OrangeAccent)
            }
            board == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val noHolds = state.unavailable == WallGameBoardLoad.NoHolds
                Text(
                    stringResource(if (noHolds) R.string.wall_games_no_holds else R.string.wall_games_unsupported),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("wall_games_unavailable"),
                )
                // The hint comes with its action: the catalogues, where the board's download starts.
                if (noHolds) {
                    Button(onClick = onOpenCatalogues, modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_catalogues")) {
                        Text(stringResource(R.string.settings_group_board_catalogs))
                    }
                }
            }
            // A game in progress shows only its clock and the wall: the phone lies on the floor.
            state.run.phase != GamePhase.IDLE -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RunPanel(state)
                // The wall takes the rest of the screen, so every lit hold shows.
                GameBoardPreview(state, Modifier.weight(1f))
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GamePicker(selected = state.game, onSelect = actions::selectGame)
                GameSettings(state = state, actions = actions, onChooseClimb = onChooseClimb)
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

/** The app's own selected-chip look (filter screen): the quiet orange container with its label. */
@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = CruxCoachDesign.colors.brandAccentContainer,
    selectedLabelColor = CruxCoachDesign.colors.onBrandAccentContainer,
)

/** The games as chips under a heading whose info explains the chosen game's rules. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GamePicker(selected: WallGame, onSelect: (WallGame) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wall_games_game), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            InfoButton(stringResource(selected.titleRes), stringResource(selected.descriptionRes))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallGame.entries.forEach { game ->
                FilterChip(
                    selected = game == selected,
                    onClick = { onSelect(game) },
                    label = { Text(stringResource(game.titleRes)) },
                    colors = chipColors(),
                    modifier = Modifier.testTag("wall_game_${game.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun GameSettings(state: WallGamesState, actions: WallGamesActions, onChooseClimb: () -> Unit) {
    when (state.game) {
        WallGame.LAVA -> LevelPicker(
            level = state.level,
            interval = stringResource(R.string.wall_games_lava_interval, WallGameFormat.seconds(FloorIsLava.intervalMs(state.level))),
            onLevel = actions::setLevel,
        )
        WallGame.ENDURANCE -> {
            ClimbCard(state, onChooseClimb)
            LevelPicker(
                level = state.level,
                interval = stringResource(
                    R.string.wall_games_endurance_interval,
                    WallGameFormat.seconds(EnduranceChallenge.intervalMs(state.level)),
                ),
                onLevel = actions::setLevel,
            )
        }
        WallGame.TRAVERSE -> {
            ClimbCard(state, onChooseClimb)
            TraverseTempo(state.traverseDifficulty, actions::setTraverseDifficulty)
        }
        WallGame.THIEF -> ThiefControls(state, actions)
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
                    colors = chipColors(),
                    modifier = Modifier.testTag("wall_games_level_$value"),
                )
            }
        }
        Text(interval, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The traverse tempo in half steps, set with 48 dp step buttons like the other numbers; the time between moves is the value. */
@Composable
private fun TraverseTempo(difficulty: Double, onDifficulty: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.wall_games_tempo), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("wall_games_tempo")) {
            IconButton(
                onClick = { onDifficulty((difficulty - TEMPO_STEP).coerceAtLeast(0.0)) },
                enabled = difficulty > 0.0,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.wall_games_tempo_slower)) }
            Text(
                stringResource(R.string.wall_games_traverse_interval, WallGameFormat.seconds(TraverseGame.intervalMs(difficulty))),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onDifficulty((difficulty + TEMPO_STEP).coerceAtMost(TraverseGame.MAX_DIFFICULTY)) },
                enabled = difficulty < TraverseGame.MAX_DIFFICULTY,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wall_games_tempo_faster)) }
        }
    }
}

@Composable
private fun ClimbCard(state: WallGamesState, onChooseClimb: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("wall_games_climb"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val climb = state.climb
            val problem = state.climbProblem
            val name: @Composable () -> Unit = {
                Text(stringResource(R.string.wall_games_climb), style = MaterialTheme.typography.labelMedium)
                when {
                    climb != null -> Text(
                        stringResource(R.string.wall_games_climb_at_angle, climb.name, climb.angle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Why the climb just chosen can't be played, instead of "no climb".
                    problem != null -> Text(
                        stringResource(
                            when (problem) {
                                ClimbProblem.NOT_FOUND -> R.string.wall_games_climb_not_found
                                ClimbProblem.OTHER_BOARD -> R.string.wall_games_climb_other_board
                                ClimbProblem.NO_HOLDS -> R.string.wall_games_climb_no_holds
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(stringResource(R.string.wall_games_no_climb), style = MaterialTheme.typography.titleMedium)
                }
            }
            // Without a climb the bar's main button chooses one; here only changing it.
            val choose: @Composable () -> Unit = {
                if (climb != null) {
                    TextButton(onClick = onChooseClimb, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.wall_games_change_climb))
                    }
                }
            }
            // Narrow or large text: the button goes under the name, which keeps the card's width.
            if (compactLayout()) {
                name()
                choose()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { name() }
                    choose()
                }
            }
            when {
                (state.climb?.offBoard ?: 0) > 0 -> Text(
                    pluralStringResource(R.plurals.wall_games_climb_off_board, state.climb!!.offBoard, state.climb.offBoard),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThiefControls(state: WallGamesState, actions: WallGamesActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wall_games_density), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldsThief.DENSITIES.forEach { density ->
                FilterChip(
                    selected = density == state.thiefDensity,
                    onClick = { actions.setThiefDensity(density) },
                    label = { Text(stringResource(R.string.wall_games_density_value, density)) },
                    colors = chipColors(),
                    modifier = Modifier.testTag("wall_games_density_$density"),
                )
            }
        }
        val handsLeft: @Composable () -> Unit = {
            Text(
                pluralStringResource(
                    R.plurals.wall_games_hands_left,
                    HoldsThief.handCount(state.thiefRoute),
                    HoldsThief.handCount(state.thiefRoute),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val newClimb: @Composable () -> Unit = {
            OutlinedButton(onClick = actions::newThiefClimb, modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_new_climb")) {
                Text(stringResource(R.string.wall_games_new_climb))
            }
        }
        if (compactLayout()) {
            handsLeft()
            newClimb()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { handsLeft() }
                newClimb()
            }
        }
        Text(stringResource(R.string.wall_games_steal_count), style = MaterialTheme.typography.titleSmall)
        // The number of holds stands here once; "Steal" at the bottom takes them.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { actions.setStealCount(state.stealCount - 1) },
                enabled = state.stealCount > 1,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.wall_games_steal_less)) }
            Text(
                state.stealCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { actions.setStealCount(state.stealCount + 1) },
                enabled = state.stealCount < WallGamesState.MAX_STEAL,
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wall_games_steal_more)) }
        }
    }
}

/** The largest fonts put a row's action under its text, and the bar's buttons above each other. */
@Composable
private fun compactLayout(): Boolean = LocalDensity.current.fontScale > 1.3f

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (run.phase) {
                GamePhase.COUNTDOWN -> BigText(
                    run.countdown.toString(),
                    maxSize = 112.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("wall_games_countdown"),
                )
                GamePhase.RUNNING -> {
                    BigText(
                        if (run.elapsedMs < GO_SHOWN_MS) stringResource(R.string.wall_games_go)
                        else WallGameFormat.clock(run.elapsedMs),
                        maxSize = 88.dp,
                        modifier = Modifier.testTag("wall_games_clock"),
                    )
                    // Once the whole climb is lit, what to do next replaces the count.
                    if (run.timelineDone && state.game.needsClimb) {
                        Text(stringResource(R.string.wall_games_all_lit), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    } else {
                        ProgressLine(state)
                    }
                }
                GamePhase.FINISHED -> {
                    val top = state.game == WallGame.LAVA && run.timelineDone
                    if (top) {
                        Text(stringResource(R.string.wall_games_lava_top), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    BigText(
                        WallGameFormat.clock(run.elapsedMs),
                        maxSize = 64.dp,
                        modifier = Modifier.testTag("wall_games_result"),
                    )
                    // How far it went, unless the lava reached the top (said above).
                    if (!top) ProgressLine(state)
                }
                GamePhase.IDLE -> Unit
            }
        }
    }
}

/**
 * One line as large as the width allows, up to [maxSize]: readable from the floor, never cut off.
 * In dp, so the largest font does not push the wall off the screen; the clock is large already.
 */
@Composable
private fun BigText(text: String, maxSize: Dp, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val max = with(LocalDensity.current) { maxSize.toSp() }
    BasicText(
        text,
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.displayLarge.copy(
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = color.takeOrElse { MaterialTheme.colorScheme.onSurface },
        ),
        autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = max),
        modifier = modifier.fillMaxWidth(),
    )
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
    LinearProgressIndicator(
        progress = { run.step.toFloat() / run.steps },
        color = OrangeAccent,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(res, run.step, run.steps),
        style = MaterialTheme.typography.titleMedium,
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
    val message: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = if (problem == WallSendProblem.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
    val connect: @Composable () -> Unit = {
        if (!connected || problem == WallSendProblem.FAILED) {
            TextButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.board_browser_connect))
            }
        }
    }
    if (compactLayout()) {
        Column(Modifier.fillMaxWidth().testTag("wall_games_status")) {
            message(Modifier)
            connect()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("wall_games_status")) {
            message(Modifier.weight(1f))
            connect()
        }
    }
}

/** The wall with the shown holds; [modifier] with a weight lets it fill the height left. */
@Composable
private fun GameBoardPreview(state: WallGamesState, modifier: Modifier = Modifier) {
    val board = state.board ?: return
    val holds = state.shownHolds
    // A tall wall at full width would push everything else off screen.
    val screenCap = (LocalConfiguration.current.screenHeightDp * BOARD_MAX_HEIGHT_FRACTION).dp
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val heightCap = if (constraints.hasBoundedHeight) maxHeight else screenCap
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

/** The bottom bar: whether the wall follows, then the main action. */
@Composable
private fun GameActionBar(
    state: WallGamesState,
    connected: Boolean,
    actions: WallGamesActions,
    onConnect: () -> Unit,
    onChooseClimb: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            WallStatus(state.sendProblem, connected, onConnect = onConnect)
            // Two actions side by side; at the largest font above each other, so no label breaks.
            val buttons: @Composable (Modifier) -> Unit = { slot ->
                when (state.run.phase) {
                    GamePhase.COUNTDOWN, GamePhase.RUNNING -> Button(
                        onClick = actions::stop,
                        modifier = slot.heightIn(min = 48.dp).testTag("wall_games_stop"),
                    ) { ButtonLabel(R.string.wall_games_stop) }
                    GamePhase.FINISHED -> {
                        OutlinedButton(
                            onClick = actions::reset,
                            modifier = slot.heightIn(min = 48.dp).testTag("wall_games_done"),
                        ) { ButtonLabel(R.string.action_done) }
                        Button(
                            onClick = actions::start,
                            enabled = state.canStart,
                            modifier = slot.heightIn(min = 48.dp).testTag("wall_games_again"),
                        ) { ButtonLabel(R.string.wall_games_play_again) }
                    }
                    // Once the thief's climb is on the wall, stealing is the move; lighting it again is the rare one.
                    GamePhase.IDLE -> when {
                        state.game == WallGame.THIEF && state.thiefLive -> {
                            OutlinedButton(
                                onClick = actions::start,
                                modifier = slot.heightIn(min = 48.dp).testTag("wall_games_start"),
                            ) { ButtonLabel(R.string.wall_games_light_again) }
                            Button(
                                onClick = actions::stealHolds,
                                enabled = HoldsThief.handCount(state.thiefRoute) > 0,
                                modifier = slot.heightIn(min = 48.dp).testTag("wall_games_steal"),
                            ) { ButtonLabel(R.string.wall_games_steal_now) }
                        }
                        // The next step of a game that needs a climb is choosing one.
                        state.game.needsClimb && state.climb == null -> Button(
                            onClick = onChooseClimb,
                            modifier = slot.heightIn(min = 48.dp).testTag("wall_games_choose"),
                        ) { ButtonLabel(R.string.wall_games_choose_climb) }
                        else -> Button(
                            onClick = actions::start,
                            enabled = state.canStart,
                            modifier = slot.heightIn(min = 48.dp).testTag("wall_games_start"),
                        ) { ButtonLabel(if (state.game == WallGame.THIEF) R.string.wall_games_light else R.string.wall_games_start) }
                    }
                }
            }
            if (compactLayout()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.fillMaxWidth()) }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { buttons(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ButtonLabel(res: Int) = Text(stringResource(res), textAlign = TextAlign.Center)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClimbPickerSheet(
    recent: List<RecentClimb>,
    onPick: (RecentClimb) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) { ClimbPickerContent(recent, onPick, onBrowse) }
}

@Composable
internal fun ClimbPickerContent(recent: List<RecentClimb>, onPick: (RecentClimb) -> Unit, onBrowse: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.wall_games_recent_title), style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) {
            Text(
                stringResource(R.string.wall_games_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("wall_games_recent_empty"),
            )
            // The way it describes, one tap away: back to the climbs.
            OutlinedButton(onClick = onBrowse, modifier = Modifier.heightIn(min = 48.dp).testTag("wall_games_browse")) {
                Text(stringResource(R.string.wall_games_browse))
            }
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
private const val TEMPO_STEP = 0.5
private const val BOARD_MAX_HEIGHT_FRACTION = 0.6f
private const val MOONBOARD_ASPECT = 0.72f
