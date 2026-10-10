package com.cruxcoach.android.ui.training.workout

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.athlete.model.Workout
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun WorkoutScreen(
    onBack: () -> Unit,
    onAddExercise: (workoutId: String) -> Unit,
    onOpenExercise: (String) -> Unit,
    onFinished: (workoutId: String) -> Unit,
    viewModel: WorkoutViewModel = hiltViewModel(),
    onOpenPlayer: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val language = catalogLanguage()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkoutEvent.Record -> snackbar.showSnackbar(
                    resources.getString(
                        R.string.trw_record_snack,
                        event.def.name(language),
                        recordValueText(resources, event.def, event.record, state.profile.units),
                    ),
                )
                is WorkoutEvent.Finished -> onFinished(event.workoutId)
                WorkoutEvent.Discarded -> onBack()
            }
        }
    }

    var showFinish by rememberSaveable { mutableStateOf(false) }
    var timerFor by remember { mutableStateOf<Pair<ExerciseSet, ExerciseDefinition>?>(null) }
    val workout = state.workout

    TrainingScaffold(
        title = workout?.let { workoutTitle(it) } ?: stringResource(R.string.trw_title_workout),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (workout != null && state.blocks.isNotEmpty()) {
                IconButton(onClick = onOpenPlayer, modifier = Modifier.testTag("workout_open_player")) {
                    Icon(androidx.compose.material.icons.Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.trw_open_player))
                }
            }
            if (workout != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(workout.id) {
                    while (true) { now = System.currentTimeMillis(); delay(1000) }
                }
                val seconds = ((now - workout.startedAt) / 1000).toInt()
                Text(
                    formatClock(seconds),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 16.dp).testTag("workout_clock")
                        .semantics { contentDescription = resources.getString(R.string.trw_elapsed_cd, formatClock(seconds)) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading || state.closing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            workout == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyHint(stringResource(R.string.trw_no_open))
                Button(onClick = onBack, modifier = Modifier.testTag("workout_none_back")) { Text(stringResource(R.string.action_back)) }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("workout_list"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.blocks.isEmpty()) item { EmptyHint(stringResource(R.string.trw_empty_workout)) }
                    items(state.blocks, key = { it.index }) { block ->
                        BlockCard(
                            block = block,
                            profile = state.profile,
                            bodyweight = state.bodyweight,
                            language = language,
                            onOpenExercise = onOpenExercise,
                            viewModel = viewModel,
                            onTimer = { set -> timerFor = set to block.def },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onAddExercise(workout.id) },
                            modifier = Modifier.weight(1f).testTag("workout_add_exercise"),
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trw_add_exercise), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { showFinish = true },
                            modifier = Modifier.weight(1f).testTag("workout_finish"),
                        ) {
                            Icon(Icons.Default.Flag, null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trw_finish), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showFinish && workout != null) {
        FinishDialog(
            hasOpenSets = state.blocks.any { b -> b.sets.any { !it.isCompleted } },
            onDismiss = { showFinish = false },
            onFinish = { rpe, notes -> showFinish = false; viewModel.finish(rpe, notes) },
            onDiscard = { showFinish = false; viewModel.discard() },
        )
    }

    timerFor?.let { (set, def) ->
        val profile = state.profile
        val interval = def.kind == ExerciseKind.INTERVAL
        val work = if (interval) (set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt()
            else (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
        val sideLabels = set.side?.let { listOf(sideLabel(it)) }
        HangTimerDialog(
            workS = work,
            restBetweenS = if (interval) (set.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt() else 0,
            reps = if (interval) set.repsPerSet ?: def.defaults.repsPerSet ?: 6 else 1,
            sets = 1,
            restBetweenSetsS = 0,
            sound = profile.timerSound,
            vibration = profile.timerVibration,
            voice = profile.timerVoice,
            sideLabels = sideLabels,
            onDismiss = { timerFor = null },
            onSetFinished = {
                val doneSet = if (interval) set else set.copy(durationS = work.toDouble())
                if (!set.isCompleted) viewModel.complete(doneSet)
            },
        )
    }
}

// ── Block ────────────────────────────────────────────────────────────

@Composable
private fun BlockCard(
    block: WorkoutBlock,
    profile: AthleteProfile,
    bodyweight: Double?,
    language: String,
    onOpenExercise: (String) -> Unit,
    viewModel: WorkoutViewModel,
    onTimer: (ExerciseSet) -> Unit,
) {
    val def = block.def
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("workout_block_${block.index}")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable { onOpenExercise(def.slug) }.padding(vertical = 4.dp),
                ) {
                    Text(def.name(language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(categoryLabel(def.category), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("workout_block_menu_${block.index}")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trw_menu_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        block.easier?.let { easier ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trw_easier, easier.name(language))) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                onClick = { menu = false; viewModel.swap(block.index, easier.slug) },
                            )
                        }
                        block.harder?.let { harder ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trw_harder, harder.name(language))) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                onClick = { menu = false; viewModel.swap(block.index, harder.slug) },
                            )
                        }
                        if (block.warmupAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trw_add_warmup)) },
                                leadingIcon = { Icon(Icons.Default.Whatshot, null) },
                                onClick = { menu = false; viewModel.addWarmups(block) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trw_remove_exercise)) },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmRemove = true },
                        )
                    }
                }
            }
            if (def.kind == ExerciseKind.INTERVAL) {
                val first = block.sets.firstOrNull()
                Text(
                    stringResource(
                        R.string.trw_interval_format,
                        (first?.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt(),
                        (first?.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt(),
                        first?.repsPerSet ?: def.defaults.repsPerSet ?: 6,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val workIndexes = block.sets.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
            block.sets.forEach { set ->
                val number = if (set.setType == SetType.WARMUP) null else workIndexes.indexOf(set.setIndex) + 1
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                SetRow(set, number, def, profile, bodyweight, viewModel, onTimer)
            }
            TextButton(onClick = { viewModel.addSet(block.index) }, modifier = Modifier.testTag("workout_add_set_${block.index}")) {
                Text(stringResource(R.string.trw_add_set))
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.trw_remove_exercise)) },
            text = { Text(stringResource(R.string.trw_remove_exercise_confirm, def.name(language))) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; viewModel.removeBlock(block.index) }) {
                    Text(stringResource(R.string.tr_action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

// ── Set row ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetRow(
    set: ExerciseSet,
    number: Int?,
    def: ExerciseDefinition,
    profile: AthleteProfile,
    bodyweight: Double?,
    viewModel: WorkoutViewModel,
    onTimer: (ExerciseSet) -> Unit,
) {
    val done = set.isCompleted
    val units = profile.units
    Column(Modifier.fillMaxWidth().testTag("set_row_${set.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SetLabel(set, number)
            FlowRow(
                Modifier.weight(1f).alpha(if (done) 0.6f else 1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (def.kind) {
                    ExerciseKind.REPS -> RepsStepper(set, viewModel, !done)
                    ExerciseKind.LOAD_REPS -> {
                        LoadStepper(set, def, profile, viewModel, !done)
                        RepsStepper(set, viewModel, !done)
                    }
                    ExerciseKind.TIME -> SecondsStepper(set, viewModel, !done)
                    ExerciseKind.HANG -> {
                        SecondsStepper(set, viewModel, !done)
                        if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
                            LoadStepper(set, def, profile, viewModel, !done)
                        }
                        EdgeStepper(set, viewModel, !done)
                        GripPicker(set, viewModel, !done)
                    }
                    ExerciseKind.INTERVAL -> {
                        if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
                            LoadStepper(set, def, profile, viewModel, !done)
                        }
                        EdgeStepper(set, viewModel, !done)
                        GripPicker(set, viewModel, !done)
                    }
                    ExerciseKind.CLIMB -> {
                        RoundsStepper(set, viewModel, !done)
                        NoteButton(set, viewModel)
                    }
                }
            }
            val doneDesc = stringResource(if (done) R.string.trw_reopen_set else R.string.trw_done_set)
            FilledIconToggleButton(
                checked = done,
                onCheckedChange = { checked -> if (checked) viewModel.complete(set) else viewModel.reopen(set) },
                modifier = Modifier.size(48.dp).testTag("set_done_${set.id}").semantics { contentDescription = doneDesc },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
            val hints = buildList {
                set.targetReps?.takeIf { def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.LOAD_REPS }
                    ?.let { add(stringResource(R.string.trw_target_reps, it)) }
                set.targetDurationS?.takeIf { def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG }
                    ?.let { add(stringResource(R.string.trw_target_seconds, it.roundToInt())) }
                com.cruxcoach.android.ui.training.player.perBlockText(set, def, profile.units)?.let(::add)
                if (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL || def.kind == ExerciseKind.LOAD_REPS) {
                    StrengthMath.percentBodyweight(def.load, set.loadKg, set.bodyweightKg ?: bodyweight)
                        ?.takeIf { def.load != LoadMode.NONE }
                        ?.let { add(stringResource(R.string.tr_format_percent_bw, it.roundToInt())) }
                }
            }
            Text(hints.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (def.kind in setOf(ExerciseKind.HANG, ExerciseKind.INTERVAL, ExerciseKind.TIME) && !done) {
                IconButton(onClick = { onTimer(set) }, modifier = Modifier.testTag("set_timer_${set.id}")) {
                    Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.trw_timer_open))
                }
            }
            IconButton(
                onClick = { viewModel.startRest(set.restS ?: def.defaults.restS ?: 120) },
                modifier = Modifier.testTag("set_rest_${set.id}"),
            ) {
                Icon(Icons.Default.HourglassTop, contentDescription = stringResource(R.string.trw_rest_start))
            }
        }
        if (done && def.kind in setOf(ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.HANG)) {
            RirRow(set, viewModel)
        }
    }
}

@Composable
private fun SetLabel(set: ExerciseSet, number: Int?) {
    val typeLabel = setTypeLabel(set.setType)
    val text = when (set.setType) {
        SetType.WARMUP -> stringResource(R.string.trw_warmup_short)
        SetType.TEST -> stringResource(R.string.trw_test_short) + (number ?: "")
        SetType.WORK -> (number ?: 0).toString()
    }
    val sideText = set.side?.let { stringResource(if (it == Side.LEFT) R.string.trw_side_short_left else R.string.trw_side_short_right) }
    val sideFull = set.side?.let { sideLabel(it) }
    Column(
        Modifier.width(36.dp).semantics {
            contentDescription = listOfNotNull(typeLabel, number?.toString(), sideFull).joinToString(" ")
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = if (set.setType == SetType.WARMUP) CruxCoachDesign.colors.caution else MaterialTheme.colorScheme.onSurface)
        if (sideText != null) Text(sideText, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RirRow(set: ExerciseSet, viewModel: WorkoutViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
        Text(stringResource(R.string.trw_rir_label), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
        listOf(0, 1, 2, 3).forEach { v ->
            val selected = set.rir == v || (v == 3 && (set.rir ?: -1) > 3)
            FilterChip(
                selected = selected,
                onClick = { viewModel.update(set.copy(rir = if (selected) null else v)) },
                label = { Text(if (v == 3) stringResource(R.string.trw_rir_3plus) else v.toString()) },
                modifier = Modifier.padding(end = 4.dp).testTag("set_rir_${set.id}_$v"),
            )
        }
    }
}

// ── Inputs ───────────────────────────────────────────────────────────

/** − value + with a tap on the value for direct entry. */
@Composable
private fun Stepper(
    label: String,
    valueText: String,
    enabled: Boolean,
    tag: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus, enabled = enabled, modifier = Modifier.size(36.dp).testTag("${tag}_minus")) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trw_minus, label))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(min = 44.dp)
                .then(if (enabled && onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier)
                .testTag("${tag}_value"),
        ) {
            Text(valueText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(36.dp).testTag("${tag}_plus")) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trw_plus, label))
        }
    }
}

@Composable
private fun RepsStepper(set: ExerciseSet, viewModel: WorkoutViewModel, enabled: Boolean) {
    var edit by remember { mutableStateOf(false) }
    val reps = set.reps ?: 0
    Stepper(
        label = stringResource(R.string.trw_reps_label), valueText = reps.toString(), enabled = enabled, tag = "reps_${set.id}",
        onMinus = { viewModel.update(set.copy(reps = (reps - 1).coerceAtLeast(0))) },
        onPlus = { viewModel.update(set.copy(reps = (reps + 1).coerceAtMost(999))) },
        onEdit = { edit = true },
    )
    if (edit) NumberDialog(stringResource(R.string.trw_reps_label), reps.toString(), decimal = false,
        onDismiss = { edit = false }) { v -> viewModel.update(set.copy(reps = v.roundToInt().coerceIn(0, 999))) }
}

@Composable
private fun RoundsStepper(set: ExerciseSet, viewModel: WorkoutViewModel, enabled: Boolean) {
    val rounds = set.reps ?: 0
    Stepper(
        label = stringResource(R.string.trw_rounds_label), valueText = rounds.toString(), enabled = enabled, tag = "rounds_${set.id}",
        onMinus = { viewModel.update(set.copy(reps = (rounds - 1).coerceAtLeast(0))) },
        onPlus = { viewModel.update(set.copy(reps = (rounds + 1).coerceAtMost(999))) },
    )
}

@Composable
private fun SecondsStepper(set: ExerciseSet, viewModel: WorkoutViewModel, enabled: Boolean) {
    var edit by remember { mutableStateOf(false) }
    val seconds = (set.durationS ?: 0.0).roundToInt()
    val step = if (seconds <= 20) 1 else 5
    Stepper(
        label = stringResource(R.string.trw_seconds_label), valueText = seconds.toString(), enabled = enabled, tag = "secs_${set.id}",
        onMinus = { viewModel.update(set.copy(durationS = (seconds - step).coerceAtLeast(0).toDouble())) },
        onPlus = { viewModel.update(set.copy(durationS = (seconds + step).coerceAtMost(3600).toDouble())) },
        onEdit = { edit = true },
    )
    if (edit) NumberDialog(stringResource(R.string.trw_seconds_label), seconds.toString(), decimal = false,
        onDismiss = { edit = false }) { v -> viewModel.update(set.copy(durationS = v.coerceIn(0.0, 3600.0))) }
}

@Composable
private fun EdgeStepper(set: ExerciseSet, viewModel: WorkoutViewModel, enabled: Boolean) {
    var edit by remember { mutableStateOf(false) }
    val edge = (set.edgeMm ?: 20.0).roundToInt()
    Stepper(
        label = stringResource(R.string.trw_edge_label), valueText = stringResource(R.string.tr_format_mm, edge.toString()),
        enabled = enabled, tag = "edge_${set.id}",
        onMinus = { viewModel.update(set.copy(edgeMm = (edge - 1).coerceAtLeast(3).toDouble())) },
        onPlus = { viewModel.update(set.copy(edgeMm = (edge + 1).coerceAtMost(80).toDouble())) },
        onEdit = { edit = true },
    )
    if (edit) NumberDialog(stringResource(R.string.trw_edge_label), edge.toString(), decimal = false,
        onDismiss = { edit = false }) { v -> viewModel.update(set.copy(edgeMm = v.coerceIn(3.0, 80.0))) }
}

@Composable
private fun LoadStepper(set: ExerciseSet, def: ExerciseDefinition, profile: AthleteProfile, viewModel: WorkoutViewModel, enabled: Boolean) {
    var edit by remember { mutableStateOf(false) }
    val units = profile.units
    val load = set.loadKg ?: 0.0
    val step = profile.smallestIncrementKg.coerceAtLeast(0.25)
    val minLoad = if (def.load == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
    val text = when {
        def.load == LoadMode.BODYWEIGHT_PLUS && load == 0.0 -> stringResource(R.string.tr_bodyweight)
        def.load == LoadMode.BODYWEIGHT_PLUS && load < 0 -> stringResource(R.string.trw_load_assisted, formatMass(-load, units))
        def.load == LoadMode.BODYWEIGHT_PLUS -> formatMass(load, units, signed = true)
        else -> formatMass(load, units)
    }
    Stepper(
        label = stringResource(R.string.trw_load_label), valueText = text, enabled = enabled, tag = "load_${set.id}",
        onMinus = { viewModel.update(set.copy(loadKg = roundStep(load - step, step).coerceAtLeast(minLoad))) },
        onPlus = { viewModel.update(set.copy(loadKg = roundStep(load + step, step).coerceAtMost(500.0))) },
        onEdit = { edit = true },
    )
    if (edit) {
        val shown = formatNumber(Units.massToDisplay(load, units))
        NumberDialog(
            stringResource(R.string.trw_load_input, Units.massUnit(units)), shown, decimal = true,
            allowNegative = def.load == LoadMode.BODYWEIGHT_PLUS,
            hint = if (def.load == LoadMode.BODYWEIGHT_PLUS) stringResource(R.string.trw_load_input_hint) else null,
            onDismiss = { edit = false },
        ) { v -> viewModel.update(set.copy(loadKg = Units.massFromDisplay(v, units).coerceIn(minLoad, 500.0))) }
    }
}

private fun roundStep(value: Double, step: Double): Double = (value / step).roundToInt() * step

@Composable
private fun GripPicker(set: ExerciseSet, viewModel: WorkoutViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { if (enabled) open = true },
            enabled = enabled,
            label = { Text(set.grip?.let { gripLabel(it) } ?: stringResource(R.string.trw_grip_none), maxLines = 1) },
            modifier = Modifier.testTag("grip_${set.id}"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Grip.entries.forEach { g ->
                DropdownMenuItem(text = { Text(gripLabel(g)) }, onClick = { open = false; viewModel.update(set.copy(grip = g)) })
            }
        }
    }
}

@Composable
private fun NoteButton(set: ExerciseSet, viewModel: WorkoutViewModel) {
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(set.note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.trw_note), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
        leadingIcon = { Icon(Icons.Default.EditNote, null) },
        modifier = Modifier.testTag("note_${set.id}"),
    )
    if (open) {
        var text by rememberSaveable { mutableStateOf(set.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.trw_note)) },
            text = {
                OutlinedTextField(text, { text = it.take(500) }, placeholder = { Text(stringResource(R.string.trw_note_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("note_input"))
            },
            confirmButton = {
                TextButton(onClick = { open = false; viewModel.update(set.copy(note = text.trim().ifBlank { null })) }) {
                    Text(stringResource(R.string.tr_action_save))
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun NumberDialog(
    title: String,
    initial: String,
    decimal: Boolean,
    allowNegative: Boolean = false,
    hint: String? = null,
    onDismiss: () -> Unit,
    onValue: (Double) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val parsed = parseDecimal(text)?.takeIf { allowNegative || it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(8) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (decimal || allowNegative) KeyboardType.Decimal else KeyboardType.Number),
                    isError = text.isNotBlank() && parsed == null,
                    modifier = Modifier.fillMaxWidth().testTag("number_dialog_input"),
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onValue); onDismiss() },
                modifier = Modifier.testTag("number_dialog_ok")) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

// ── Finish ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishDialog(
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
        modifier = Modifier.testTag("workout_finish_dialog"),
        title = { Text(stringResource(R.string.trw_finish_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trw_rpe_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    InfoButton(stringResource(R.string.trw_rpe_info_title), stringResource(R.string.trw_rpe_info_text))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..10).forEach { v ->
                        FilterChip(
                            selected = rpe == v,
                            onClick = { rpe = if (rpe == v) null else v },
                            label = { Text(v.toString()) },
                            modifier = Modifier.testTag("finish_rpe_$v"),
                        )
                    }
                }
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it.take(2000) },
                    label = { Text(stringResource(R.string.trw_notes_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("finish_notes"),
                )
                if (hasOpenSets) {
                    Text(stringResource(R.string.trw_unfinished_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onFinish(rpe, notes) }, modifier = Modifier.testTag("finish_confirm")) {
                Text(stringResource(R.string.trw_finish_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.testTag("finish_discard")) {
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
                TextButton(onClick = { confirmDiscard = false; onDiscard() }, modifier = Modifier.testTag("discard_confirm")) {
                    Text(stringResource(R.string.trw_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

// ── Text helpers shared with the summary and history ────────────────

/** Built-in routines are stored by key; show their localized name. */
@Composable
internal fun workoutTitle(workout: Workout): String {
    val builtin = workout.routineId?.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:")?.let { BuiltinRoutines.byKey(it) }
    return when {
        builtin != null -> routineName(builtin)
        !workout.title.isNullOrBlank() -> workout.title!!
        else -> stringResource(R.string.trw_workout_default)
    }
}

internal fun massText(resources: Resources, kg: Double, units: UnitSystem, signed: Boolean = false): String {
    val v = Units.massToDisplay(kg, units)
    val t = (if (signed && v > 0) "+" else "") + formatNumber(v)
    return resources.getString(if (units == UnitSystem.IMPERIAL) R.string.tr_format_lb else R.string.tr_format_kg, t)
}

internal fun recordValueText(resources: Resources, def: ExerciseDefinition, record: PersonalRecord, units: UnitSystem): String =
    when (record.kind) {
        RecordKind.LOAD ->
            if (def.load == LoadMode.BODYWEIGHT_PLUS) resources.getString(R.string.trw_record_total, massText(resources, record.value, units))
            else massText(resources, record.value, units)
        RecordKind.ESTIMATED_MAX -> resources.getString(R.string.trw_record_e1rm, massText(resources, record.value, units))
        RecordKind.REPS -> record.value.roundToInt().let { resources.getQuantityString(R.plurals.trw_reps, it, it) }
        RecordKind.DURATION -> resources.getString(R.string.tr_format_seconds, record.value.roundToInt())
    }
