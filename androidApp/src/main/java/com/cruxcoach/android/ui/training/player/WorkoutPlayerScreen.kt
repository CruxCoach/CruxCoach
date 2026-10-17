package com.cruxcoach.android.ui.training.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.workout.HangTimerContent
import com.cruxcoach.android.ui.training.workout.workoutTitle
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The training: one page per exercise (or circuit) with all its sets, a strip to move between
 * exercises, the main action at the bottom – or the rest bar while resting. The hang timer is the
 * only full screen (safety: a big countdown and "let go"). Free trainings open here too: the
 * separate list view is gone (owner 2026-10-17: maximally simple set views).
 */
@Composable
fun WorkoutPlayerScreen(
    onBack: () -> Unit,
    onAddExercise: (workoutId: String) -> Unit,
    onOpenExercise: (String) -> Unit,
    onFinished: (workoutId: String) -> Unit,
    viewModel: WorkoutPlayerViewModel = hiltViewModel(),
    /** The "still learning this value" line on work sets (a slot: it has a view model of its own). */
    learningBadge: @Composable (slug: String, modifier: Modifier) -> Unit = { slug, m ->
        com.cruxcoach.android.ui.training.coach.LearningBadge(slug, m)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    var finishing by rememberSaveable { mutableStateOf(false) }
    var reportingPain by rememberSaveable { mutableStateOf(false) }
    var swapping by rememberSaveable { mutableStateOf(false) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // A clear tick when a set is done; a longer buzz when it was a new best.
    val completeSet: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        viewModel.completeCurrent()
    }
    val completeAs: (Double) -> Unit = { value ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        viewModel.completeAs(value)
    }
    LaunchedEffect(state.feedback) {
        if (state.feedback?.record != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val snackbar = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    val resources = LocalResources.current
    val painResult = state.painResult
    val painText = painResult?.let { r ->
        val head = when (r.action) {
            com.cruxcoach.android.athlete.PainAction.SWAP ->
                stringResource(R.string.trp2_pain_done_swap, state.painNewDef?.name(language) ?: "")
            com.cruxcoach.android.athlete.PainAction.LIGHTER -> stringResource(R.string.trp2_pain_done_lighter)
            com.cruxcoach.android.athlete.PainAction.END_EXERCISE -> stringResource(R.string.trp2_pain_done_end)
        }
        if (r.injuryId != null) head + " " + stringResource(R.string.trp2_pain_saved) else head
    }
    LaunchedEffect(painResult) {
        val text = painText ?: return@LaunchedEffect
        val injuryId = painResult?.injuryId
        viewModel.consumePainResult()
        // Shown outside the effect: consuming the result restarts the effect and would cancel it.
        snackScope.launch {
            if (injuryId == null) { snackbar.showSnackbar(text); return@launch }
            // A saved injury shapes every later suggestion: one tap takes it back (audit W9).
            if (snackbar.showSnackbar(text, resources.getString(R.string.trpg_undo), duration = SnackbarDuration.Long) ==
                SnackbarResult.ActionPerformed) viewModel.forgetInjury(injuryId)
        }
    }
    // "Wie geplant" with Undo: today's trim comes back in one tap.
    val asPlannedUndo = state.asPlannedUndo
    LaunchedEffect(asPlannedUndo) {
        if (asPlannedUndo == null) return@LaunchedEffect
        val text = resources.getString(R.string.trpg_dayform_back)
        snackScope.launch {
            val result = snackbar.showSnackbar(text, resources.getString(R.string.trpg_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoAsPlanned() else viewModel.consumeAsPlannedUndo()
        }
    }
    // "Exercise ended" with Undo: one tap to end, one to take it back.
    val ended = state.endedUndo
    LaunchedEffect(ended) {
        val e = ended ?: return@LaunchedEffect
        val names = e.slugs.joinToString(", ") { state.def(it)?.name(language) ?: viewModel.catalog().fallbackFor(it).name(language) }
        // A circuit: "Circuit ended" (the list of four names filled three lines on the Nokia).
        val text = when (e.kind) {
            EndKind.SKIPPED -> resources.getString(R.string.trpg_skipped, names)
            EndKind.REMOVED -> resources.getString(R.string.trpg_removed, names)
            EndKind.ENDED -> if (e.circuit) resources.getString(R.string.trpg_circuit_ended) else resources.getString(R.string.trpg_ended, names)
        }
        snackScope.launch {
            val result = snackbar.showSnackbar(text, resources.getString(R.string.trpg_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoEnd() else viewModel.consumeEnded()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PlayerEvent.Finished -> onFinished(event.workoutId)
                PlayerEvent.Discarded -> onBack()
                is PlayerEvent.SkippedForInjury -> snackbar.showSnackbar(resources.getString(R.string.trw_typed_skipped,
                    event.slugs.joinToString(", ") { viewModel.catalog().fallbackFor(it).name(language) }))
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
            TimerPhaseContent(current, state, onDone = completeSet, onCancel = viewModel::cancelTimer,
                onReleased = { held ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    viewModel.completeCurrent(heldSeconds = held)
                })
            return
        }
    }

    val actions = remember(viewModel) {
        PageActions(
            onUpdate = viewModel::update,
            onFocus = viewModel::focus,
            onEdit = viewModel::edit,
            onReopen = { id -> viewModel.reopen(id) },
            onReserve = { id, rir -> viewModel.setReserve(id, rir) },
            onCompleteAs = completeAs,
            onOpenExercise = onOpenExercise,
            onPain = { reportingPain = true },
            onSwap = { swapping = true },
            onSwapTo = viewModel::swapExercise,
            onSkipExercise = viewModel::skipExercise,
            onEndExercise = viewModel::endExercise,
            onAddSet = viewModel::addSet,
            onAddWarmups = viewModel::addWarmups,
            onRemoveExercise = viewModel::removeExercise,
            onAsPlanned = viewModel::asPlanned,
        )
    }

    val title = state.workout?.let { workoutTitle(it) } ?: stringResource(R.string.trp_title)
    TrainingScaffold(
        title = title,
        onBack = onBack,
        showRestBanner = false,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (state.workout != null) {
                IconButton(onClick = { overview = true }, modifier = Modifier.testTag("player_overview")) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.trp_overview))
                }
                IconButton(onClick = { finishing = true }, modifier = Modifier.testTag("player_finish")) {
                    Icon(Icons.Default.Flag, contentDescription = stringResource(R.string.trw_finish))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                PlayerPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                PlayerPhase.NO_WORKOUT -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(stringResource(R.string.trw_no_open))
                    Button(onClick = onBack, modifier = Modifier.testTag("player_none_back")) { Text(stringResource(R.string.action_back)) }
                }
                PlayerPhase.EMPTY -> EmptyTraining(
                    onAdd = { state.workout?.let { onAddExercise(it.id) } },
                    onType = { typing = true },
                )
                PlayerPhase.COMPLETE -> if (state.browsing && state.page != null) {
                    PageWithStrip(state, language, actions, viewModel, learningBadge, Modifier.weight(1f))
                    BottomBar {
                        Button(onClick = viewModel::backToActive,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_all_done")) {
                            Text(stringResource(R.string.trpg_all_done), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else CompletePage(state, onFinish = viewModel::finish, onOverview = { overview = true })
                PlayerPhase.SET, PlayerPhase.REST, PlayerPhase.TIMER -> {
                    PageWithStrip(state, language, actions, viewModel, learningBadge, Modifier.weight(1f))
                    if (state.phase == PlayerPhase.REST) {
                        RestBar(viewModel.restTimer, state, language,
                            onPlus = { viewModel.adjustRest(15) }, onMinus = { viewModel.adjustRest(-15) }, onEnd = viewModel::endRest)
                    } else {
                        MainAction(state, onDone = completeSet, onStartTimer = viewModel::startTimer,
                            onShowActive = viewModel::backToActive)
                    }
                }
            }
        }
    }

    if (swapping) {
        val name = state.page?.sets?.firstOrNull { !it.isCompleted }?.let { state.def(it.exerciseSlug)?.name(language) }
        if (name == null) swapping = false
        else {
            val alternatives by produceState<List<com.cruxcoach.athlete.logic.SessionSuggester.Alternative>?>(null, state.page?.key) {
                value = viewModel.alternatives()
            }
            PlayerSwapSheet(name, alternatives, language,
                onDismiss = { swapping = false },
                onSwap = { slug -> swapping = false; viewModel.swapExercise(slug) })
        }
    }

    if (reportingPain) {
        val active = state.current?.takeIf { c -> state.page?.blockIndexes?.contains(c.set.blockIndex) == true }
            ?: state.page?.sets?.firstOrNull { !it.isCompleted }?.let { s -> state.def(s.exerciseSlug)?.let { d -> PlayerItem(s, d, com.cruxcoach.athlete.logic.PlayerQueue.position(state.sets, s)) } }
        if (active == null) reportingPain = false
        else PainSheet(
            exerciseName = active.def.name(language),
            setSide = active.set.side,
            onDismiss = { reportingPain = false },
            onConfirm = { region, side, severity, action, remember ->
                reportingPain = false
                viewModel.reportPain(region, side, severity, action, remember)
            },
        )
    }

    if (overview) {
        OverviewSheet(
            state = state,
            language = language,
            onDismiss = { overview = false },
            onShowPage = { i -> overview = false; viewModel.showPage(i) },
            onAdd = { overview = false; state.workout?.let { onAddExercise(it.id) } },
            onType = { overview = false; typing = true },
            onFinish = { overview = false; finishing = true },
        )
    }

    if (typing) {
        val catalog by produceState(com.cruxcoach.athlete.catalog.ExerciseCatalog.EMPTY) { value = viewModel.catalog() }
        com.cruxcoach.android.ui.training.workouts.WorkoutTextSheet(
            catalog = catalog,
            units = state.profile.units,
            title = stringResource(R.string.trw_typed_title),
            onDismiss = { typing = false },
            onApply = { result -> typing = false; viewModel.addTyped(result) },
        )
    }

    if (finishing && state.sets.none { it.isCompleted }) {
        com.cruxcoach.android.ui.training.workout.NothingDoneDialog(
            onKeep = { finishing = false }, onDiscard = { finishing = false; viewModel.discard() })
    } else if (finishing) {
        PlayerFinishDialog(
            hasOpenSets = state.sets.any { !it.isCompleted },
            onDismiss = { finishing = false },
            onFinish = { rpe, notes -> finishing = false; viewModel.finish(rpe, notes) },
            onDiscard = { finishing = false; viewModel.discard() },
        )
    }
}

/** The strip and the page on screen, cross-fading when the page changes. */
@Composable
private fun PageWithStrip(
    state: PlayerState,
    language: String,
    actions: PageActions,
    viewModel: WorkoutPlayerViewModel,
    learningBadge: @Composable (slug: String, modifier: Modifier) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        if (state.pages.size > 1) ExerciseStrip(state, language, onShow = viewModel::showPage)
        AnimatedContent(
            targetState = state.page?.key,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "player_page",
        ) { key ->
            val page = state.pages.firstOrNull { it.key == key }
            if (page != null) {
                ExercisePage(page, state, language, actions,
                    canSwap = viewModel.canSwap(), warmupAvailable = viewModel.warmupAvailable(), learningBadge = learningBadge)
            }
        }
    }
}

/**
 * One segment per exercise, filled by its done sets; the page on screen is marked. A tap shows
 * that exercise – looking is not skipping, nothing is logged.
 */
@Composable
private fun ExerciseStrip(state: PlayerState, language: String, onShow: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.pages.forEachIndexed { i, page ->
            val names = page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b } }
                .joinToString(", ") { state.def(it.exerciseSlug)?.name(language) ?: it.exerciseSlug }
            val description = stringResource(R.string.trpg_page_cd, i + 1, state.pages.size, names, page.done, page.total)
            val shown = i == state.pageIndex
            val fraction = if (page.total > 0) page.done.toFloat() / page.total else 0f
            val fill = when {
                !page.open -> CruxCoachDesign.colors.positive
                shown -> CruxCoachDesign.colors.brandAccent
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                Modifier.weight(1f).height(32.dp).clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Tab) { onShow(i) }
                    .semantics { contentDescription = description }
                    .testTag("player_strip_$i"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(if (shown) 8.dp else 5.dp).clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(if (page.open) fraction else 1f).fillMaxHeight().background(fill))
                }
            }
        }
    }
}

@Composable
private fun BottomBar(content: @Composable ColumnScope.() -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/**
 * The page's one main task: log the active set as planned ("8 reps done"), or start the hang
 * timer. When the active set is on another page, this leads there.
 */
@Composable
private fun MainAction(state: PlayerState, onDone: () -> Unit, onStartTimer: () -> Unit, onShowActive: () -> Unit) {
    val page = state.page
    val active = state.current?.takeIf { c -> !c.set.isCompleted && page != null && c.set.blockIndex in page.blockIndexes }
    BottomBar {
        when {
            active != null -> {
                val set = active.set
                val def = active.def
                val timed = def.kind == ExerciseKind.INTERVAL || def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.TIME
                when {
                    set.setType == SetType.TEST && !timed -> PrimaryButton(stringResource(R.string.trpg_log_test), "player_done", onDone)
                    timed -> {
                        val seconds = if (def.kind == ExerciseKind.INTERVAL) null
                            else (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble())?.roundToInt()
                        Button(onClick = onStartTimer, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_start_timer")) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(seconds?.let { stringResource(R.string.trpg_start_seconds, it) } ?: stringResource(R.string.trp_start_timer),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("player_done")) {
                            Text(stringResource(R.string.trp_done_without_timer))
                        }
                    }
                    else -> {
                        val label = mainValue(set, def)?.let { stringResource(R.string.trpg_done_planned, it) } ?: stringResource(R.string.trp_done_set)
                        PrimaryButton(label, "player_done", onDone)
                    }
                }
            }
            state.current != null -> Button(onClick = onShowActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_to_active")) {
                Text(stringResource(R.string.trpg_to_next), style = MaterialTheme.typography.titleMedium)
            }
            else -> Button(onClick = onShowActive, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_all_done")) {
                Text(stringResource(R.string.trpg_all_done), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CruxCoachDesign.colors.positive),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
    ) {
        Icon(Icons.Default.CheckCircle, null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A free training without exercises yet: add from the catalogue or type them. */
@Composable
private fun EmptyTraining(onAdd: () -> Unit, onType: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("player_empty"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.trpg_empty_hint), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("player_add_exercise")) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trw_add_exercise))
        }
        OutlinedButton(onClick = onType, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp).testTag("player_type")) {
            Icon(Icons.Default.EditNote, null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trwo_text_open))
        }
    }
}

/** Every exercise with its progress; a tap shows it. Adding, typing and finishing live here too. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewSheet(
    state: PlayerState,
    language: String,
    onDismiss: () -> Unit,
    onShowPage: (Int) -> Unit,
    onAdd: () -> Unit,
    onType: () -> Unit,
    onFinish: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("player_overview_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trp_overview), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp))
            state.pages.forEachIndexed { i, page ->
                val names = page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b } }
                    .joinToString(" → ") { state.def(it.exerciseSlug)?.name(language) ?: it.exerciseSlug }
                ListItem(
                    headlineContent = { Text(names, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (i == state.pageIndex) FontWeight.SemiBold else FontWeight.Normal) },
                    supportingContent = { Text(stringResource(R.string.trpg_page_progress, page.done, page.total)) },
                    trailingContent = { if (!page.open) Icon(Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onShowPage(i) }.testTag("player_overview_page_$i"),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                leadingContent = { Icon(Icons.Default.Add, null) },
                headlineContent = { Text(stringResource(R.string.trw_add_exercise)) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable(onClick = onAdd).testTag("player_overview_add"),
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.EditNote, null) },
                headlineContent = { Text(stringResource(R.string.trwo_text_open)) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable(onClick = onType).testTag("player_overview_type"),
            )
            ListItem(
                leadingContent = { Icon(Icons.Default.Flag, null) },
                headlineContent = { Text(stringResource(R.string.trw_finish)) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable(onClick = onFinish).testTag("player_overview_finish"),
            )
        }
    }
}

/** All sets done: how much it was, how hard it felt (right here, not in a dialog), finish. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompletePage(state: PlayerState, onFinish: (Int?, String?) -> Unit, onOverview: () -> Unit) {
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    val doneSets = state.sets.count { it.isCompleted && it.setType != SetType.WARMUP }
    val exercises = state.sets.filter { it.isCompleted }.map { it.blockIndex }.distinct().size
    val minutes = state.workout?.let { ((System.currentTimeMillis() - it.startedAt) / 60_000L).toInt().coerceAtLeast(0) } ?: 0
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("player_complete"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.trp_complete_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(listOf(pluralStringResource(R.plurals.trpg_sets_count, doneSets, doneSets),
            pluralStringResource(R.plurals.trpg_exercises_count, exercises, exercises),
            stringResource(R.string.trpg_minutes, minutes)).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp).testTag("player_complete_summary"))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.trw_rpe_label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            InfoButton(stringResource(R.string.trw_rpe_info_title), stringResource(R.string.trw_rpe_info_text))
        }
        // Two even rows of five (a wrapping row left 6 + 4).
        listOf(1..5, 6..10).forEach { range ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                range.forEach { v ->
                    NumberChip(v.toString(), "player_rpe_$v", Modifier.weight(1f), selected = rpe == v) { rpe = if (rpe == v) null else v }
                }
            }
        }
        OutlinedTextField(
            value = notes, onValueChange = { notes = it.take(2000) },
            label = { Text(stringResource(R.string.trw_notes_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("player_finish_notes"),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onFinish(rpe, notes.trim().ifBlank { null }) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("player_complete_finish")) {
            Text(stringResource(R.string.trp_finish_training))
        }
        TextButton(onClick = onOverview, modifier = Modifier.heightIn(min = 48.dp).testTag("player_complete_overview")) {
            Text(stringResource(R.string.trp_overview))
        }
    }
}


/** "Instead of X" during a training: the same alternatives as on today's card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSwapSheet(
    name: String,
    alternatives: List<com.cruxcoach.athlete.logic.SessionSuggester.Alternative>?,
    language: String,
    onDismiss: () -> Unit,
    onSwap: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("player_swap_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trt_swap_title, name), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            when {
                alternatives == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                alternatives.isEmpty() -> Text(stringResource(R.string.trt_swap_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                else -> alternatives.forEach { alt ->
                    val tag = when {
                        alt.easier -> stringResource(R.string.trt_swap_easier)
                        alt.harder -> stringResource(R.string.trt_swap_harder)
                        else -> null
                    }
                    ListItem(
                        leadingContent = { com.cruxcoach.android.ui.training.bodymap.ExerciseThumb(alt.def, Modifier.size(40.dp)) },
                        headlineContent = { Text(alt.def.name(language)) },
                        supportingContent = {
                            Text(listOfNotNull(tag, com.cruxcoach.android.ui.training.exercises.equipmentSummary(alt.def).takeIf { it.isNotBlank() })
                                .joinToString(" · "))
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onSwap(alt.def.slug) }.testTag("player_swap_to_${alt.def.slug}"),
                    )
                }
            }
        }
    }
}

// ── Values ─────────────────────────────────────────────────────────

/** The editable values of one set: load, reps or seconds, edge – on the active row and when correcting a done one. */
@Composable
internal fun SetSteppers(
    set: ExerciseSet,
    def: ExerciseDefinition,
    units: UnitSystem,
    incrementKg: Double,
    onUpdate: (ExerciseSet) -> Unit,
    modifier: Modifier = Modifier,
    /** Reps, seconds or rounds; off on the active row of rep work, where the result chips log them. */
    showMain: Boolean = true,
    belowLoad: @Composable () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
            val step = incrementKg.coerceAtLeast(0.25)
            val minLoad = if (def.load == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
            val load = set.loadKg ?: 0.0
            PlayerStepper(
                label = stringResource(R.string.trw_load_label),
                value = loadText(set, def, units) ?: formatMass(load, units),
                tag = "player_load",
                onMinus = { onUpdate(set.copy(loadKg = (snap(load - step, step)).coerceAtLeast(minLoad))) },
                onPlus = { onUpdate(set.copy(loadKg = (snap(load + step, step)).coerceAtMost(500.0))) },
                edit = StepperEdit(
                    title = stringResource(R.string.trw_load_input, com.cruxcoach.athlete.logic.Units.massUnit(units)),
                    initial = formatNumber(com.cruxcoach.athlete.logic.Units.massToDisplay(load, units), 2),
                    decimal = true, allowNegative = def.load == LoadMode.BODYWEIGHT_PLUS,
                ) { v -> onUpdate(set.copy(loadKg = com.cruxcoach.athlete.logic.Units.massFromDisplay(v, units).coerceIn(minLoad, 500.0))) },
            )
            belowLoad()
        }
        if (showMain) when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                val reps = set.reps ?: 0
                PlayerStepper(stringResource(R.string.trw_reps_label), reps.toString(), "player_reps",
                    onMinus = { onUpdate(set.copy(reps = (reps - 1).coerceAtLeast(0))) },
                    onPlus = { onUpdate(set.copy(reps = (reps + 1).coerceAtMost(999))) },
                    edit = StepperEdit(stringResource(R.string.trw_reps_label), reps.toString()) { v ->
                        onUpdate(set.copy(reps = v.roundToInt().coerceIn(0, 999)))
                    })
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> {
                val seconds = (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
                val step = if (seconds >= 30) 5 else 1
                PlayerStepper(stringResource(R.string.trw_seconds_label), stringResource(R.string.tr_format_seconds, seconds), "player_seconds",
                    onMinus = { onUpdate(set.copy(durationS = (seconds - step).coerceAtLeast(1).toDouble())) },
                    onPlus = { onUpdate(set.copy(durationS = (seconds + step).coerceAtMost(3600).toDouble())) },
                    edit = StepperEdit(stringResource(R.string.trw_seconds_label), seconds.toString()) { v ->
                        onUpdate(set.copy(durationS = v.roundToInt().coerceIn(1, 3600).toDouble()))
                    })
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
}

/** Tap on a stepper value to type it (big jumps like 0 → 30 kg without 30 taps). */
internal class StepperEdit(
    val title: String,
    val initial: String,
    val decimal: Boolean = false,
    val allowNegative: Boolean = false,
    val onValue: (Double) -> Unit,
)

@Composable
internal fun PlayerStepper(label: String, value: String, tag: String, onMinus: () -> Unit, onPlus: () -> Unit, edit: StepperEdit? = null) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(56.dp).testTag("${tag}_minus")) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trw_minus, label))
        }
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 8.dp)
                .then(if (edit != null) Modifier.clickable(role = Role.Button, onClickLabel = edit.title) { editing = true } else Modifier)
                .testTag("${tag}_value"))
        FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(56.dp).testTag("${tag}_plus")) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trw_plus, label))
        }
    }
    if (editing && edit != null) {
        var text by remember { mutableStateOf(edit.initial) }
        val parsed = parseDecimal(text)?.takeIf { edit.allowNegative || it >= 0 }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(edit.title) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(8) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (edit.decimal) androidx.compose.ui.text.input.KeyboardType.Decimal
                            else androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.testTag("${tag}_input"))
            },
            confirmButton = {
                TextButton(onClick = { parsed?.let(edit.onValue); editing = false }, enabled = parsed != null,
                    modifier = Modifier.testTag("${tag}_input_ok")) { Text(stringResource(R.string.tr_action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

internal fun snap(value: Double, step: Double): Double = (value / step).roundToInt() * step

// ── Timer phase (full screen) ────────────────────────────────────────

@Composable
private fun TimerPhaseContent(
    item: PlayerItem, state: PlayerState, onDone: () -> Unit, onCancel: () -> Unit,
    onReleased: (Double) -> Unit = {},
) {
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
        onReleasedEarly = if (interval) null else onReleased,
        modifier = Modifier.testTag("player_timer"),
    )
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
        // Always a line for loaded work, so the layout never jumps under the finger.
        LoadMode.EXTERNAL -> formatMass(load ?: 0.0, units)
        else -> null
    }
}

/** Two-arm pick-ups log the total of both hands; this says what goes on each block. */
@Composable
internal fun perBlockText(set: ExerciseSet, def: ExerciseDefinition, units: UnitSystem): String? {
    if (def.load != LoadMode.EXTERNAL || def.unilateral || EquipmentV2.PICKUP_BLOCK_PAIR !in def.equipment) return null
    val total = set.loadKg?.takeIf { it > 0 } ?: return null
    return stringResource(R.string.trw_load_per_block, formatMass(total / 2, units))
}

/** Effective load in % body weight for loaded hangs and lifts. */
@Composable
internal fun percentText(set: ExerciseSet, def: ExerciseDefinition, bodyweight: Double?): String? {
    if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return null
    if (def.kind != ExerciseKind.HANG && def.kind != ExerciseKind.INTERVAL && def.kind != ExerciseKind.LOAD_REPS) return null
    val pct = StrengthMath.percentBodyweight(def.load, set.loadKg ?: 0.0, set.bodyweightKg ?: bodyweight) ?: return null
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
