package com.cruxcoach.android.ui.training.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.workout.HangTimerContent
import com.cruxcoach.android.ui.training.workout.workoutTitle
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt

/**
 * Guided training: one set at a time with a large target, a timer where the
 * exercise needs one, and a rest screen of its own between sets.
 */
@Composable
fun WorkoutPlayerScreen(
    onBack: () -> Unit,
    onOverview: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onFinished: (workoutId: String) -> Unit,
    viewModel: WorkoutPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timer by viewModel.restTimer.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    var finishing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlayerEvent.Finished -> onFinished(event.workoutId)
                PlayerEvent.Discarded -> onBack()
            }
        }
    }
    // The phone lies on the floor or the mat between sets: keep the screen on.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    BackHandler(enabled = state.phase == PlayerPhase.TIMER) { viewModel.cancelTimer() }

    if (state.phase == PlayerPhase.TIMER) {
        val current = state.current
        if (current != null) {
            TimerPhaseContent(current, state, onDone = viewModel::completeCurrent, onCancel = viewModel::cancelTimer)
            return
        }
    }

    val title = state.workout?.let { workoutTitle(it) } ?: stringResource(R.string.trp_title)
    TrainingScaffold(
        title = title,
        onBack = onBack,
        actions = {
            if (state.workout != null) {
                IconButton(onClick = onOverview, modifier = Modifier.testTag("player_overview")) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.trp_overview))
                }
                IconButton(onClick = { finishing = true }, modifier = Modifier.testTag("player_finish")) {
                    Icon(Icons.Default.Flag, contentDescription = stringResource(R.string.trw_finish))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.sets.isNotEmpty()) {
                val done = state.completedCount
                val total = state.sets.size
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).testTag("player_progress"),
                    color = CruxCoachDesign.colors.brandAccent,
                )
                Text(
                    stringResource(R.string.trp_progress, done, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (state.phase) {
                PlayerPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                PlayerPhase.NO_WORKOUT -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(stringResource(R.string.trw_no_open))
                    Button(onClick = onBack, modifier = Modifier.testTag("player_none_back")) { Text(stringResource(R.string.action_back)) }
                }
                PlayerPhase.SET, PlayerPhase.TIMER -> state.current?.let { current ->
                    SetView(
                        item = current,
                        state = state,
                        language = language,
                        onUpdate = viewModel::update,
                        onDone = viewModel::completeCurrent,
                        onStartTimer = viewModel::startTimer,
                        onSkip = viewModel::skip,
                        onOverview = onOverview,
                        onOpenExercise = onOpenExercise,
                    )
                }
                PlayerPhase.REST -> RestScreen(
                    timer = timer,
                    state = state,
                    language = language,
                    onPlus = { viewModel.adjustRest(15) },
                    onMinus = { viewModel.adjustRest(-15) },
                    onEnd = viewModel::endRest,
                    onReserve = viewModel::setReserve,
                )
                PlayerPhase.COMPLETE -> CompleteView(state, onFinish = { finishing = true }, onOverview = onOverview)
            }
        }
    }

    if (finishing) {
        PlayerFinishDialog(
            hasOpenSets = state.sets.any { !it.isCompleted },
            onDismiss = { finishing = false },
            onFinish = { rpe, notes -> finishing = false; viewModel.finish(rpe, notes) },
            onDiscard = { finishing = false; viewModel.discard() },
        )
    }
}

// ── Set view ─────────────────────────────────────────────────────────

@Composable
private fun SetView(
    item: PlayerItem,
    state: PlayerState,
    language: String,
    onUpdate: (ExerciseSet) -> Unit,
    onDone: () -> Unit,
    onStartTimer: () -> Unit,
    onSkip: () -> Unit,
    onOverview: () -> Unit,
    onOpenExercise: (String) -> Unit,
) {
    val set = item.set
    val def = item.def
    val units = state.profile.units
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("player_set_view"),
    ) {
        // Header: category, badge, name, set x of y, side.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(categoryLabel(def.category), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (set.setType != SetType.WORK) {
                AssistChip(onClick = {}, label = { Text(setTypeLabel(set.setType)) }, modifier = Modifier.testTag("player_badge"))
            }
        }
        Text(
            def.name(language),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(role = Role.Button) { onOpenExercise(def.slug) }.testTag("player_exercise_name"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.trp_set_of, item.position.setNumber, item.position.setsInBlock),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            set.side?.let { side ->
                Surface(color = CruxCoachDesign.colors.brandAccent, shape = MaterialTheme.shapes.small) {
                    Text(sideLabel(side).uppercase(), color = CruxCoachDesign.colors.onBrandAccent,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("player_side"))
                }
            }
        }

        // Target, big.
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                mainValue(set, def)?.let {
                    Text(it, fontSize = 48.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("player_target_main"))
                }
                loadText(set, def, units)?.let { load ->
                    Text(load, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("player_target_load"))
                }
                percentText(set, def, state.bodyweight)?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                edgeGripText(set)?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (def.kind == ExerciseKind.INTERVAL) {
                    Text(
                        stringResource(R.string.trw_interval_format,
                            (set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt(),
                            (set.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt(),
                            set.repsPerSet ?: def.defaults.repsPerSet ?: 6),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        // Quick adjust.
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
                val step = state.profile.smallestIncrementKg.coerceAtLeast(0.25)
                val minLoad = if (def.load == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
                val load = set.loadKg ?: 0.0
                PlayerStepper(
                    label = stringResource(R.string.trw_load_label),
                    value = loadText(set, def, units) ?: formatMass(load, units),
                    tag = "player_load",
                    onMinus = { onUpdate(set.copy(loadKg = (snap(load - step, step)).coerceAtLeast(minLoad))) },
                    onPlus = { onUpdate(set.copy(loadKg = (snap(load + step, step)).coerceAtMost(500.0))) },
                )
            }
            when (def.kind) {
                ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                    val reps = set.reps ?: 0
                    PlayerStepper(stringResource(R.string.trw_reps_label), reps.toString(), "player_reps",
                        onMinus = { onUpdate(set.copy(reps = (reps - 1).coerceAtLeast(0))) },
                        onPlus = { onUpdate(set.copy(reps = (reps + 1).coerceAtMost(999))) })
                }
                ExerciseKind.TIME, ExerciseKind.HANG -> {
                    val seconds = (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
                    val step = if (seconds >= 30) 5 else 1
                    PlayerStepper(stringResource(R.string.trw_seconds_label), stringResource(R.string.tr_format_seconds, seconds), "player_seconds",
                        onMinus = { onUpdate(set.copy(durationS = (seconds - step).coerceAtLeast(1).toDouble())) },
                        onPlus = { onUpdate(set.copy(durationS = (seconds + step).coerceAtMost(3600).toDouble())) })
                }
                ExerciseKind.CLIMB -> {
                    val rounds = set.reps ?: 0
                    PlayerStepper(stringResource(R.string.trw_rounds_label), rounds.toString(), "player_rounds",
                        onMinus = { onUpdate(set.copy(reps = (rounds - 1).coerceAtLeast(0))) },
                        onPlus = { onUpdate(set.copy(reps = (rounds + 1).coerceAtMost(999))) })
                }
                ExerciseKind.INTERVAL -> Unit
            }
            if (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) {
                set.edgeMm?.let { edge ->
                    val mm = edge.roundToInt()
                    PlayerStepper(stringResource(R.string.trw_edge_label), stringResource(R.string.tr_format_mm, mm.toString()), "player_edge",
                        onMinus = { onUpdate(set.copy(edgeMm = (mm - 1).coerceAtLeast(3).toDouble())) },
                        onPlus = { onUpdate(set.copy(edgeMm = (mm + 1).coerceAtMost(80).toDouble())) })
                }
            }
        }

        // Primary action.
        Spacer(Modifier.height(16.dp))
        val timed = def.kind == ExerciseKind.INTERVAL || def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.TIME
        if (timed) {
            Button(
                onClick = onStartTimer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("player_start_timer"),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trp_start_timer), style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player_done")) {
                Text(stringResource(R.string.trp_done_without_timer))
            }
        } else {
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = CruxCoachDesign.colors.positive),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("player_done"),
            ) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trp_done_set), style = MaterialTheme.typography.titleLarge)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSkip, enabled = state.upcoming != null, modifier = Modifier.heightIn(min = 48.dp).testTag("player_skip")) {
                Icon(Icons.Default.SkipNext, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.trp_skip))
            }
            TextButton(onClick = onOverview, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.trp_overview))
            }
        }

        // After this one.
        state.upcoming?.let { up ->
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            val parts = buildList {
                add(up.def.name(language))
                add(stringResource(R.string.trp_set_of, up.position.setNumber, up.position.setsInBlock))
                up.set.side?.let { add(sideLabel(it)) }
            }
            Text(stringResource(R.string.trp_next_after, parts.joinToString(" · ")), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("player_upcoming"))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayerStepper(label: String, value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(56.dp).testTag("${tag}_minus")) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trw_minus, label))
        }
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 8.dp).testTag("${tag}_value"))
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(56.dp).testTag("${tag}_plus")) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trw_plus, label))
        }
    }
}

private fun snap(value: Double, step: Double): Double = (value / step).roundToInt() * step

// ── Timer phase (full screen) ────────────────────────────────────────

@Composable
private fun TimerPhaseContent(item: PlayerItem, state: PlayerState, onDone: () -> Unit, onCancel: () -> Unit) {
    val set = item.set
    val def = item.def
    val profile = state.profile
    val interval = def.kind == ExerciseKind.INTERVAL
    val work = if (interval) (set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt()
        else (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
    val restBetween = if (interval) (set.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt() else 0
    val reps = if (interval) set.repsPerSet ?: def.defaults.repsPerSet ?: 6 else 1
    val sideLabels = set.side?.let { listOf(sideLabel(it)) }
    HangTimerContent(
        workS = work.coerceAtLeast(1),
        restBetweenS = restBetween,
        reps = reps,
        sets = 1,
        restBetweenSetsS = 0,
        sound = profile.timerSound,
        vibration = profile.timerVibration,
        voice = profile.timerVoice,
        sideLabels = sideLabels,
        onClose = onCancel,
        onSetFinished = {},
        onDone = onDone,
        finishAutomatically = true,
        modifier = Modifier.testTag("player_timer"),
    )
}

// ── Completion ───────────────────────────────────────────────────────

@Composable
private fun CompleteView(state: PlayerState, onFinish: () -> Unit, onOverview: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag("player_complete"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.trp_complete_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(pluralStringResource(R.plurals.trp_complete_text, state.completedCount, state.completedCount),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_complete_finish")) {
            Text(stringResource(R.string.trp_finish_training))
        }
        TextButton(onClick = onOverview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.trp_overview)) }
    }
}

// ── Finish dialog ────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerFinishDialog(
    hasOpenSets: Boolean,
    onDismiss: () -> Unit,
    onFinish: (Int?, String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("player_finish_dialog"),
        title = { Text(stringResource(R.string.trw_finish_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trw_rpe_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    InfoButton(stringResource(R.string.trw_rpe_info_title), stringResource(R.string.trw_rpe_info_text))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..10).forEach { v ->
                        FilterChip(selected = rpe == v, onClick = { rpe = if (rpe == v) null else v },
                            label = { Text(v.toString()) }, modifier = Modifier.testTag("player_rpe_$v"))
                    }
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(2000) },
                    label = { Text(stringResource(R.string.trw_notes_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("player_finish_notes"),
                )
                if (hasOpenSets) {
                    Text(stringResource(R.string.trw_unfinished_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onFinish(rpe, notes) }, modifier = Modifier.testTag("player_finish_confirm")) {
                Text(stringResource(R.string.trw_finish_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.testTag("player_finish_discard")) {
                    Text(stringResource(R.string.trw_discard), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) }
            }
        },
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.trw_discard_title)) },
            text = { Text(stringResource(R.string.trw_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }, modifier = Modifier.testTag("player_discard_confirm")) {
                    Text(stringResource(R.string.trw_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

// ── Target texts (shared with the rest screen) ───────────────────────

/** "8 reps", "10 s", "4 rounds" — the number the set is about. */
@Composable
internal fun mainValue(set: ExerciseSet, def: ExerciseDefinition): String? = when (def.kind) {
    ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> (set.reps ?: set.targetReps)?.let { pluralStringResource(R.plurals.trp_reps_big, it, it) }
    ExerciseKind.TIME, ExerciseKind.HANG ->
        (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble())?.roundToInt()?.let { stringResource(R.string.tr_format_seconds, it) }
    ExerciseKind.CLIMB -> set.reps?.let { pluralStringResource(R.plurals.trp_rounds, it, it) }
    ExerciseKind.INTERVAL -> null
}

/** "+12 kg", "15 kg assisted", "Bodyweight", "32 kg"; null without a load. */
@Composable
internal fun loadText(set: ExerciseSet, def: ExerciseDefinition, units: UnitSystem): String? {
    val load = set.loadKg
    return when (def.load) {
        LoadMode.BODYWEIGHT_PLUS -> when {
            load == null || load == 0.0 -> stringResource(R.string.tr_bodyweight)
            load < 0 -> stringResource(R.string.trw_load_assisted, formatMass(-load, units))
            else -> formatMass(load, units, signed = true)
        }
        LoadMode.EXTERNAL -> load?.let { formatMass(it, units) }
        else -> null
    }
}

/** Effective load in % body weight for loaded hangs and lifts. */
@Composable
internal fun percentText(set: ExerciseSet, def: ExerciseDefinition, bodyweight: Double?): String? {
    if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return null
    if (def.kind != ExerciseKind.HANG && def.kind != ExerciseKind.INTERVAL && def.kind != ExerciseKind.LOAD_REPS) return null
    val pct = StrengthMath.percentBodyweight(def.load, set.loadKg, set.bodyweightKg ?: bodyweight) ?: return null
    return stringResource(R.string.tr_format_percent_bw, pct.roundToInt())
}

@Composable
internal fun edgeGripText(set: ExerciseSet): String? {
    val parts = listOfNotNull(
        set.edgeMm?.let { stringResource(R.string.tr_format_mm, it.roundToInt().toString()) },
        set.grip?.let { gripLabel(it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** One line for previews: "8 reps · +12 kg · 20 mm". */
@Composable
internal fun targetSummary(set: ExerciseSet, def: ExerciseDefinition, bodyweight: Double?, units: UnitSystem): String? {
    val parts = listOfNotNull(
        mainValue(set, def),
        loadText(set, def, units)?.takeIf { def.load != LoadMode.BODYWEIGHT_PLUS || (set.loadKg ?: 0.0) != 0.0 },
        percentText(set, def, bodyweight),
        set.edgeMm?.let { stringResource(R.string.tr_format_mm, it.roundToInt().toString()) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
