package com.cruxcoach.android.ui.training.today

import kotlinx.datetime.todayIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.fuel.amountText
import com.cruxcoach.android.ui.training.fuel.inputText
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.ReadinessLevel
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.logic.ReadinessReason
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.logic.Units
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    onBack: () -> Unit,
    onOpenExercises: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenWorkout: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBody: () -> Unit,
    onOpenFuel: () -> Unit,
    onOpenInjuries: () -> Unit,
    onOpenWeeklyReview: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
    onOpenPlayer: () -> Unit = onOpenWorkout,
    onOpenBenchmarks: () -> Unit = {},
    tabBar: @Composable () -> Unit = {},
    /** Opens the workout editor: an own routine id, or `from` = "ex:<slug>,<slug>" to start a new one. */
    onOpenEditor: (routineId: String?, from: String?) -> Unit = { _, _ -> },
    /** Board day: the playlist generator preset with a [com.cruxcoach.domain.playlist.GeneratorType] name and minutes. */
    onOpenPlaylistGenerator: (type: String, minutes: Int) -> Unit = { _, _ -> },
    /** Goal and training week: the coach setup (first step of the checklist). */
    onOpenCoachSetup: () -> Unit = {},
    /** Start values estimated from a few questions (FEAT-071). */
    onOpenEstimate: () -> Unit = {},
    /** "Klettertag eintragen": climbing outside the board app. */
    onLogClimbing: () -> Unit = {},
    /** The filled climbing tile: today's one climbing day to edit (device test D30) … */
    onEditClimbing: (entryId: String) -> Unit = {},
    /** … or the list of climbing days when today has several. */
    onOpenClimbingDays: () -> Unit = {},
    /** What the coach learned from the athlete's choices (settings page, forgettable). */
    onOpenLearned: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askWhy by rememberSaveable { mutableStateOf(false) }
    var showWhy by rememberSaveable { mutableStateOf(false) }
    // After a finished training the hero says so; another session only on request.
    var moreToday by rememberSaveable(state.today?.toString()) { mutableStateOf(false) }
    var showWeight by rememberSaveable { mutableStateOf(false) }
    var showEquipment by rememberSaveable { mutableStateOf(false) }
    var showGoal by rememberSaveable { mutableStateOf(false) }
    var showCheckin by rememberSaveable { mutableStateOf(false) }
    /** An exercise of today's card the athlete tapped to swap. */
    var swapFor by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // The checklist's last tick is celebrated once in place before the card goes.
    val setupJustDone = state.setupJustDone
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.trsg_saved)
    val weightSavedText = stringResource(R.string.tru_weight_saved)
    val undoText = stringResource(R.string.trf_undo)
    val neverDoneText = stringResource(R.string.trt_never_done)
    val swappedText = stringResource(R.string.trt_swapped_undo)
    val droppedText = stringResource(R.string.trt_dropped_undo)
    val screenLanguage = catalogLanguage()
    val glass = FuelUnits.waterPresetsMl(state.profile.units).first()
    val waterAddedText = stringResource(R.string.tru_water_added, amountText(glass.toDouble(), FuelUnits.unitFor(state.profile.units, drink = true)))
    val snackScope = rememberCoroutineScope()
    fun tell(text: String) { snackScope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    LaunchedEffect(state.suggestionSaved) {
        // Shown outside this effect: consuming the flag restarts the effect and would cancel it.
        if (state.suggestionSaved) { viewModel.consumeSuggestionSaved(); tell(savedText) }
    }
    LaunchedEffect(state.startedWorkout) {
        if (state.startedWorkout) {
            val guided = state.startedGuided
            viewModel.consumeStartedWorkout()
            if (guided) onOpenPlayer() else onOpenWorkout()
        }
    }
    TrainingScaffold(
        title = stringResource(R.string.tr_nav_today),
        onBack = onBack,
        bottomBar = tabBar,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("today_settings")) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.tr_action_settings))
            }
            // Seldom used: in the menu, not as another row of buttons on the screen.
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("today_menu")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trt_more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.trt_weekly_review)) }, leadingIcon = { Icon(Icons.Default.Insights, null) },
                        onClick = { menu = false; onOpenWeeklyReview() }, modifier = Modifier.testTag("today_weekly_review"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.trt_done_hero_history)) }, leadingIcon = { Icon(Icons.Default.History, null) },
                        onClick = { menu = false; onOpenHistory() }, modifier = Modifier.testTag("today_history"))
                    if (state.openWorkout == null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.trt_quick_free)) }, leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { menu = false; viewModel.startEmptyWorkout() }, modifier = Modifier.testTag("today_start_empty"))
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.trt_report_injury)) }, leadingIcon = { Icon(Icons.Default.Healing, null) },
                        onClick = { menu = false; onOpenInjuries() }, modifier = Modifier.testTag("today_report_injury"))
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("today_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // One glance: where the week stands, what is still missing, today's training, then the day.
            item { WeekHeader(state) }
            state.openPause?.let { item(key = "pause") { PauseBanner(onEndPause = viewModel::endPause) } }
            if (state.injuries.isNotEmpty()) item(key = "injury") { InjuryBanner(state, onOpenInjuries) }
            val steps = setupSteps(state)
            if (steps.any { !it.done } || setupJustDone) {
                item(key = "setup") {
                    SetupChecklistCard(steps, justDone = setupJustDone, onDismissDone = viewModel::dismissSetupDone, onOpen = { step ->
                        when (step.kind) {
                            SetupKind.COACH -> onOpenCoachSetup()
                            SetupKind.EQUIPMENT -> showEquipment = true
                            SetupKind.WEIGHT -> showWeight = true
                        }
                    })
                }
            }
            if (state.openWorkout != null) {
                item(key = "open_workout") { OpenWorkoutCard(onOpenPlayer) }
            } else if (state.todaysWorkouts.isNotEmpty() && !moreToday) {
                item(key = "done_hero") { DoneHeroCard(state, onMore = { moreToday = true }, onOpenHistory = onOpenHistory) }
            } else state.suggestion?.let {
                item(key = "daily_suggestion") {
                    DailySuggestionCard(
                        state = state,
                        onStart = viewModel::startSuggestion,
                        onNext = { askWhy = true },
                        onWhy = { showWhy = true },
                        onEdit = { title -> viewModel.editTarget(title).let { (id, from) -> onOpenEditor(id, from) } },
                        onSave = viewModel::saveSuggestion,
                        onOpenPlaylistGenerator = onOpenPlaylistGenerator,
                        checkin = { CheckinRow(state, onOpen = { showCheckin = true }) },
                        onPickPlace = viewModel::pickPlace,
                        onQuickCheckin = viewModel::saveQuickCheckin,
                        onMoreCheckin = { showCheckin = true },
                        onChooseMinutes = viewModel::chooseMinutes,
                        onExercise = { swapFor = it },
                        onChooseCircuit = viewModel::chooseCircuit,
                        onSetCircuit = viewModel::setCircuit,
                        onLighter = { viewModel.chooseLevel(-1) },
                    )
                }
                state.durationHint?.let { minutes ->
                    item(key = "duration_hint") { DurationHintCard(minutes, onApply = viewModel::applyDurationHint) }
                }
            }
            if (state.energy.signals.isNotEmpty()) item { EnergyCareCard(state.energy, state.profile, "today_reds", onAdjustGoal = { showGoal = true }) }
            if (state.loadSpikes.isNotEmpty()) item { LoadSpikeCard(state) }
            item(key = "day") {
                DayGrid(
                    state = state,
                    // Today's entry rather than a new one that would log the day twice (device test D30).
                    onLogClimbing = {
                        val entries = state.climbingEntriesToday
                        when (entries.size) {
                            0 -> onLogClimbing()
                            1 -> onEditClimbing(entries.single())
                            else -> onOpenClimbingDays()
                        }
                    },
                    onLogWeight = { showWeight = true },
                    onOpenBody = onOpenBody,
                    onOpenFuel = onOpenFuel,
                    onAddWater = {
                        viewModel.addWater(glass)
                        snackScope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            if (snackbar.showSnackbar(waterAddedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                viewModel.removeLastWater()
                            }
                        }
                    },
                )
            }
            // After the checklist: at most one gentle next step (start values, a re-test).
            if (steps.all { it.done }) {
                state.suggestions.firstOrNull { it != TodaySuggestion.CONFIGURE_EQUIPMENT && it != TodaySuggestion.LOG_WEIGHT }?.let { s ->
                    item(key = s.name) {
                        SuggestionCard(s, onAction = {
                            when (s) {
                                TodaySuggestion.SET_BENCHMARKS -> onOpenEstimate()
                                TodaySuggestion.BASELINE_TEST -> viewModel.startRoutine(BuiltinRoutines.BASELINE_TESTS)
                                TodaySuggestion.CONFIGURE_EQUIPMENT -> showEquipment = true
                                TodaySuggestion.LOG_WEIGHT -> showWeight = true
                            }
                        }, onSecondary = if (s == TodaySuggestion.SET_BENCHMARKS) onOpenBenchmarks else null)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showWhy) WhySheet(state, onDismiss = { showWhy = false }, onOpenLearned = { showWhy = false; onOpenLearned() })
    val pendingStart by viewModel.pendingStart.collectAsStateWithLifecycle()
    pendingStart?.let { com.cruxcoach.android.ui.training.workout.OpenTrainingDialog(it, viewModel::resolveStart) }
    if (showCheckin) {
        CheckinSheet(state, onDismiss = { showCheckin = false },
            onSave = { s, e, sk, f, sick, sore -> viewModel.saveCheckin(s, e, sk, f, sick, sore); showCheckin = false },
            onClear = { viewModel.clearCheckin(); showCheckin = false })
    }
    if (showWeight) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = state.profile.units, lastKg = state.trendKg, hideNumbers = state.profile.hideBodyNumbers,
            reason = stringResource(R.string.tru_weight_reason_setup),
            onDismiss = { showWeight = false },
            onSave = { kg -> viewModel.logWeight(kg); showWeight = false; tell(weightSavedText) },
        )
    }
    if (showGoal) {
        com.cruxcoach.android.ui.training.common.WeightGoalSheet(
            profile = state.profile, weightKg = state.trendKg, heightCm = state.heightCm, need = state.need,
            onDismiss = { showGoal = false },
            onSave = viewModel::saveWeightGoal,
            onLogWeight = viewModel::logWeight, onLogHeight = viewModel::logHeight,
            weekNeeds = state.weekNeeds,
        )
    }
    if (showEquipment) {
        com.cruxcoach.android.ui.training.common.EquipmentSheet(
            initial = state.profile.equipment.takeIf { state.profile.equipmentConfigured } ?: emptySet(),
            onDismiss = { showEquipment = false },
            onSave = viewModel::saveEquipment,
            placeName = com.cruxcoach.android.ui.training.common.todaysPlaceName(state.profile),
        )
    }
    if (askWhy) {
        NextSuggestionSheet(
            state = state,
            onDismiss = { askWhy = false },
            onShuffle = { askWhy = false; viewModel.nextSuggestion() },
            onFocus = { focus -> askWhy = false; viewModel.chooseFocus(focus) },
            onLevel = { shift -> askWhy = false; viewModel.chooseLevel(shift) },
            // What hurts today is spared today (check-in), not turned into an injury; equipment is answered right here.
            onHurts = { askWhy = false; showCheckin = true },
            onEquipment = { askWhy = false; showEquipment = true },
            onPlace = { id -> askWhy = false; viewModel.pickPlace(id) },
        )
    }
    swapFor?.let { slug ->
        val alternatives = remember(slug, state.suggestion) { viewModel.alternatives(slug) }
        SwapSheet(
            state = state, slug = slug, alternatives = alternatives,
            onDismiss = { swapFor = null },
            onSwap = { to ->
                swapFor = null
                viewModel.swapExercise(slug, to)
                val text = swappedText.format(state.catalog.fallbackFor(to).name(screenLanguage), state.catalog.fallbackFor(slug).name(screenLanguage))
                snackScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(text, undoText, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.undoCardChange()
                }
            },
            onDrop = {
                swapFor = null
                viewModel.dropExercise(slug)
                val text = droppedText.format(state.catalog.fallbackFor(slug).name(screenLanguage))
                snackScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(text, undoText, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.undoCardChange()
                }
            },
            onExclude = {
                swapFor = null
                viewModel.excludeExercise(slug)
                // Never final by accident (comparison review #8): one tap takes it back.
                snackScope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(neverDoneText.format(state.catalog.fallbackFor(slug).name(screenLanguage)), undoText,
                            duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) viewModel.includeExercise(slug)
                }
            },
        )
    }
    // Turned down for the third time: ask once instead of resting it again and again.
    state.neverOffer?.let { slug ->
        val name = state.catalog.fallbackFor(slug).name(catalogLanguage())
        val text = stringResource(R.string.trt_never_offer, name)
        val yes = stringResource(R.string.trt_never_offer_yes)
        LaunchedEffect(slug) {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, yes, withDismissAction = true, duration = SnackbarDuration.Long)
            viewModel.answerNeverOffer(result == SnackbarResult.ActionPerformed)
        }
    }
}

// ── "Startklar": the minimum, as a checklist ───────────────────────

internal enum class SetupKind { COACH, EQUIPMENT, WEIGHT }

internal data class SetupStep(val kind: SetupKind, val done: Boolean)

/**
 * What a new athlete has to do at least: goal and week (coach), equipment and
 * body weight. A coach setup the athlete declined counts as done – the
 * checklist never nags about something they said no to.
 */
internal fun setupSteps(state: TodayState): List<SetupStep> {
    val coach = state.profile.coach.setupState
    return listOf(
        SetupStep(SetupKind.COACH, coach == com.cruxcoach.athlete.model.SetupState.DONE || coach == com.cruxcoach.athlete.model.SetupState.DISMISSED),
        SetupStep(SetupKind.EQUIPMENT, state.profile.equipmentConfigured),
        SetupStep(SetupKind.WEIGHT, state.trendKg != null),
    )
}

@Composable
private fun SetupChecklistCard(steps: List<SetupStep>, justDone: Boolean, onDismissDone: () -> Unit, onOpen: (SetupStep) -> Unit) {
    val done = steps.count { it.done }
    if (justDone && done == steps.size) {
        // All three ticked: a short "ready", then the card is gone for good.
        Card(
            colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.positiveContainer,
                contentColor = CruxCoachDesign.colors.onPositiveContainer),
            modifier = Modifier.fillMaxWidth().testTag("today_setup_done"),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tru_setup_ready_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.tru_setup_ready_text), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismissDone) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tru_done)) }
            }
        }
        return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("today_setup"),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RocketLaunch, null, tint = CruxCoachDesign.colors.brandAccent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.tru_setup_title), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.tru_setup_progress, done, steps.size), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { done.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                color = CruxCoachDesign.colors.brandAccent, drawStopIndicator = {},
            )
            steps.forEachIndexed { i, step ->
                val (title, text) = when (step.kind) {
                    SetupKind.COACH -> R.string.tru_setup_coach to R.string.tru_setup_coach_text
                    SetupKind.EQUIPMENT -> R.string.tru_setup_equipment to R.string.tru_setup_equipment_text
                    SetupKind.WEIGHT -> R.string.tru_setup_weight to R.string.tru_setup_weight_text
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(enabled = !step.done) { onOpen(step) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("today_setup_${step.kind.name.lowercase()}"),
                ) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(if (step.done) CruxCoachDesign.colors.positive else MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (step.done) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.tru_setup_done),
                            tint = CruxCoachDesign.colors.positiveContainer, modifier = Modifier.size(20.dp))
                        else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (step.done) FontWeight.Normal else FontWeight.SemiBold,
                            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(if (step.done) R.string.tru_setup_done else text), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!step.done) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}

// ── Header: week strip and consistency ──────────────────────────────

@Composable
private fun WeekHeader(state: TodayState) {
    val streak = state.streak
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (streak != null) {
                    Text(
                        pluralStringResource(R.plurals.trt_week_progress, streak.goal, streak.currentWeekDays, streak.goal),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    val sub = when {
                        streak.currentWeekPaused -> stringResource(R.string.trt_streak_paused)
                        streak.weeks > 0 -> pluralStringResource(R.plurals.trt_streak_weeks, streak.weeks, streak.weeks)
                        else -> stringResource(R.string.trt_streak_start)
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (streak != null && streak.jokers > 0) {
                AssistChip(onClick = {}, label = { Text(pluralStringResource(R.plurals.trt_jokers, streak.jokers, streak.jokers)) },
                    leadingIcon = { Icon(Icons.Default.Shield, null) })
            }
            InfoButton(stringResource(R.string.trt_streak_info_title), stringResource(R.string.trt_streak_info_text))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val locale = Locale.getDefault()
            state.week.forEach { day ->
                val label = java.time.DayOfWeek.of(day.date.dayOfWeek.ordinal + 1).getDisplayName(TextStyle.NARROW, locale)
                val filled = day.climbed || day.trained
                val desc = stringResource(when {
                    day.climbed && day.trained -> R.string.trt_day_both
                    day.climbed -> R.string.trt_day_climbed
                    day.trained -> R.string.trt_day_trained
                    day.paused -> R.string.trt_day_paused
                    else -> R.string.trt_day_rest
                })
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics { contentDescription = "$label: $desc" }) {
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal)
                    Box(
                        Modifier.size(28.dp).clip(CircleShape)
                            .background(if (filled) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.surfaceVariant)
                            .border(if (day.isToday) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            day.climbed && day.trained -> Icon(Icons.Default.DoneAll, null, Modifier.size(16.dp), tint = CruxCoachDesign.colors.onBrandAccent)
                            day.climbed -> Icon(Icons.Default.Terrain, null, Modifier.size(16.dp), tint = CruxCoachDesign.colors.onBrandAccent)
                            day.trained -> Icon(Icons.Default.FitnessCenter, null, Modifier.size(16.dp), tint = CruxCoachDesign.colors.onBrandAccent)
                            day.paused -> Icon(Icons.Default.Bedtime, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenWorkoutCard(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.brandAccent),
        modifier = Modifier.fillMaxWidth().testTag("today_open_workout"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, null, tint = CruxCoachDesign.colors.onBrandAccent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.trt_continue_workout), color = CruxCoachDesign.colors.onBrandAccent,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

/** Today's training is done: say so, and offer more only on request. */
@Composable
private fun DoneHeroCard(state: TodayState, onMore: () -> Unit, onOpenHistory: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.positiveContainer,
            contentColor = CruxCoachDesign.colors.onPositiveContainer),
        modifier = Modifier.fillMaxWidth().testTag("today_done_hero"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_done_hero_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val minutes = state.todaysWorkouts.sumOf { it.durationMinutes ?: 0 }
            // Titles resolved in composition first; joinToString's lambda is not composable.
            val titles = state.todaysWorkouts.map { com.cruxcoach.android.ui.training.workout.workoutTitle(it) }
            val minutesText = if (minutes > 0) " · " + pluralStringResource(R.plurals.trt_minutes, minutes, minutes) else ""
            Text(
                titles.joinToString(" · ") + minutesText,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
            Text(stringResource(R.string.trt_done_hero_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMore, modifier = Modifier.testTag("today_done_more")) { Text(stringResource(R.string.trt_done_hero_more)) }
                TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.trt_done_hero_history)) }
            }
        }
    }
}

/** Thin status line: an open pause, with the way out right there. */
@Composable
private fun PauseBanner(onEndPause: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("today_pause")) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bedtime, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.trt_pause_short), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onEndPause, modifier = Modifier.testTag("today_end_pause")) { Text(stringResource(R.string.trt_pause_end)) }
        }
    }
}

/** Thin status line for injury mode; the suggestion below already trains around it. */
@Composable
private fun InjuryBanner(state: TodayState, onManage: () -> Unit) {
    val paused = state.injuries.any { it.climbingPaused }
    val summary = state.injuries.map { i -> listOfNotNull(regionLabel(i.region), i.side?.let { injurySideLabel(it) }).joinToString(" ") }
    Surface(
        onClick = onManage,
        color = CruxCoachDesign.colors.cautionContainer, contentColor = CruxCoachDesign.colors.onCautionContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("today_injury"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Healing, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (paused) R.string.trt_injury_mode_paused else R.string.trt_injury_mode),
                    style = MaterialTheme.typography.labelLarge)
                Text(summary.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.trt_injury_manage))
        }
    }
}

// ── Readiness check-in ──────────────────────────────────────────────

/**
 * The check-in as one line inside today's training: a prompt until answered,
 * afterwards the day's verdict in a few characters. The questions open in a sheet.
 */
@Composable
private fun CheckinRow(state: TodayState, onOpen: () -> Unit) {
    val checkin = state.checkin
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("today_readiness"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            if (checkin == null && state.profile.checkinEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mood, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trt_checkin_title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpen, modifier = Modifier.testTag("today_checkin_open")) { Text(stringResource(R.string.tru_checkin_action)) }
                }
            } else {
                ReadinessResult(state, onEdit = if (state.profile.checkinEnabled) onOpen else null)
            }
        }
    }
}

/** The four questions in a sheet; tapping the selected value clears it (every question is optional). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckinSheet(
    state: TodayState,
    onDismiss: () -> Unit,
    onSave: (Int?, Int?, Int?, Int?, Boolean, Set<com.cruxcoach.athlete.model.InjuryRegion>) -> Unit,
    onClear: () -> Unit,
) {
    val checkin = state.checkin
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("today_checkin_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            CheckinForm(checkin?.sleep, checkin?.energy, checkin?.skin, checkin?.fingers, checkin?.sick ?: false, checkin?.sore.orEmpty(), onSave)
            if (checkin != null) {
                TextButton(onClick = onClear, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.trt_checkin_reset)) }
            }
        }
    }
}

@Composable
private fun ReadinessResult(state: TodayState, onEdit: (() -> Unit)?) {
    val r = state.readiness
    val c = state.checkin
    val (icon, title) = when (r.level) {
        ReadinessLevel.GO -> Icons.Default.CheckCircle to stringResource(R.string.trt_ready_go)
        ReadinessLevel.ADAPT -> Icons.Default.Tune to stringResource(R.string.trt_ready_adapt)
        ReadinessLevel.REST -> Icons.Default.Bedtime to stringResource(R.string.trt_ready_rest)
    }
    // Answers in a few characters ("Schlaf 4 · Haut 2"); the injury has its own banner above.
    val answers = listOfNotNull(
        c?.sleep?.let { stringResource(R.string.trt_q_sleep) + " " + it },
        c?.energy?.let { stringResource(R.string.trt_q_energy) + " " + it },
        c?.skin?.let { stringResource(R.string.trt_q_skin) + " " + it },
        c?.fingers?.let { stringResource(R.string.trt_q_fingers) + " " + it },
        if (c?.sick == true) stringResource(R.string.trt_short_sick) else null,
        c?.sore?.takeIf { it.isNotEmpty() }?.let { set -> stringResource(R.string.trt_short_sore, set.map { regionLabel(it) }.joinToString(", ")) },
    )
    val factor = r.reasons.firstOrNull { it != ReadinessReason.INJURY_CLIMBING_PAUSED && it != ReadinessReason.INJURY_ACTIVE && it != ReadinessReason.ALL_GOOD }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = when (r.level) {
            ReadinessLevel.GO -> CruxCoachDesign.colors.positive
            ReadinessLevel.ADAPT -> CruxCoachDesign.colors.caution
            ReadinessLevel.REST -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (answers.isNotEmpty()) {
                Text(answers.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_checkin_summary"))
            }
        }
        if (onEdit != null) TextButton(onClick = onEdit, modifier = Modifier.testTag("today_checkin_edit")) {
            Text(stringResource(R.string.trt_checkin_change))
        }
    }
    factor?.let {
        Text(readinessReasonText(it), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp).testTag("today_readiness_reason"))
    }
}

@Composable
private fun readinessReasonText(reason: ReadinessReason): String = stringResource(when (reason) {
    ReadinessReason.SICK -> R.string.trt_reason_sick
    ReadinessReason.INJURY_CLIMBING_PAUSED -> R.string.trt_reason_injury_paused
    ReadinessReason.INJURY_ACTIVE -> R.string.trt_reason_injury
    ReadinessReason.FINGERS_TIRED -> R.string.trt_reason_fingers
    ReadinessReason.SKIN_LOW -> R.string.trt_reason_skin
    ReadinessReason.LOW_SLEEP -> R.string.trt_reason_sleep
    ReadinessReason.LOW_ENERGY -> R.string.trt_reason_energy
    ReadinessReason.LOW_MOTIVATION -> R.string.trt_reason_motivation
    ReadinessReason.ALL_GOOD -> R.string.trt_reason_all_good
})

@Composable
internal fun readinessReasonShort(reason: ReadinessReason): String = stringResource(when (reason) {
    ReadinessReason.SICK -> R.string.trt_short_sick
    ReadinessReason.INJURY_CLIMBING_PAUSED, ReadinessReason.INJURY_ACTIVE -> R.string.trt_short_injury
    ReadinessReason.FINGERS_TIRED -> R.string.trt_q_fingers
    ReadinessReason.SKIN_LOW -> R.string.trt_q_skin
    ReadinessReason.LOW_SLEEP -> R.string.trt_q_sleep
    ReadinessReason.LOW_ENERGY -> R.string.trt_q_energy
    ReadinessReason.LOW_MOTIVATION -> R.string.trt_short_motivation
    ReadinessReason.ALL_GOOD -> R.string.trt_reason_all_good
})

@Composable
private fun CheckinForm(
    sleep0: Int?, energy0: Int?, skin0: Int?, fingers0: Int?, sick0: Boolean,
    sore0: Set<com.cruxcoach.athlete.model.InjuryRegion>,
    onSave: (Int?, Int?, Int?, Int?, Boolean, Set<com.cruxcoach.athlete.model.InjuryRegion>) -> Unit,
) {
    var sore by rememberSaveable { mutableStateOf(sore0.map { it.name }) }
    var sleep by rememberSaveable { mutableStateOf(sleep0) }
    var energy by rememberSaveable { mutableStateOf(energy0) }
    var skin by rememberSaveable { mutableStateOf(skin0) }
    var fingers by rememberSaveable { mutableStateOf(fingers0) }
    var sick by rememberSaveable { mutableStateOf(sick0) }
    Text(stringResource(R.string.trt_checkin_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.trt_checkin_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    ScaleRow(stringResource(R.string.trt_q_sleep), sleep, "sleep") { sleep = it }
    // Health Connect (opt-in): last night's sleep as a suggestion; the athlete taps to take it, nothing is saved by itself.
    if (sleep == null) {
        com.cruxcoach.android.athlete.health.rememberSleepHint(kotlin.time.Clock.System.todayIn(kotlinx.datetime.TimeZone.currentSystemDefault()))
            ?.let { hint -> com.cruxcoach.android.athlete.health.SleepHintChip(hint, onApply = { sleep = it }) }
    }
    ScaleRow(stringResource(R.string.trt_q_energy), energy, "energy") { energy = it }
    ScaleRow(stringResource(R.string.trt_q_skin), skin, "skin") { skin = it }
    ScaleRow(stringResource(R.string.trt_q_fingers), fingers, "fingers") { fingers = it }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { sick = !sick }) {
        Checkbox(checked = sick, onCheckedChange = { sick = it }, modifier = Modifier.testTag("checkin_sick"))
        Text(stringResource(R.string.trt_q_sick))
    }
    Text(stringResource(R.string.trt_q_sore), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    SoreChips(sore.mapNotNull { n -> com.cruxcoach.athlete.model.InjuryRegion.entries.firstOrNull { it.name == n } }.toSet(), accent = false) { set ->
        sore = set.map { it.name }
    }
    val soreSet = sore.mapNotNull { n -> com.cruxcoach.athlete.model.InjuryRegion.entries.firstOrNull { it.name == n } }.toSet()
    Button(
        onClick = { onSave(sleep, energy, skin, fingers, sick, soreSet) },
        enabled = sick || soreSet.isNotEmpty() || listOf(sleep, energy, skin, fingers).any { it != null },
        modifier = Modifier.fillMaxWidth().testTag("checkin_save"),
    ) { Text(stringResource(R.string.tr_action_save)) }
}

/**
 * Before today's suggestion: how strong the athlete feels and whether anything
 * hurts – and what. Both are optional; "Show suggestion" works with or
 * without answers. What hurts is spared today without becoming an injury.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickCheckin(onSave: (Int?, Set<com.cruxcoach.athlete.model.InjuryRegion>) -> Unit, onMore: () -> Unit) {
    val chipColors = answerChipColors()
    var strength by rememberSaveable { mutableStateOf<Int?>(null) }
    var hurts by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var sore by rememberSaveable { mutableStateOf(listOf<String>()) }
    val soreSet = sore.mapNotNull { n -> com.cruxcoach.athlete.model.InjuryRegion.entries.firstOrNull { it.name == n } }.toSet()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("today_quick_checkin")) {
        Text(stringResource(R.string.trt_quick_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.trt_quick_text), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.trt_quick_strength), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            (1..5).forEach { v ->
                FilterChip(
                    selected = strength == v,
                    onClick = { strength = if (strength == v) null else v },
                    label = { Text("$v", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                    colors = chipColors,
                    modifier = Modifier.weight(1f).testTag("today_quick_strength_$v"),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.trt_quick_weak), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.trt_quick_strong), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.trt_quick_hurts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = hurts == false, onClick = { hurts = false; sore = emptyList() },
                label = { Text(stringResource(R.string.trt_quick_no)) }, colors = chipColors, modifier = Modifier.testTag("today_quick_hurts_no"))
            FilterChip(selected = hurts == true, onClick = { hurts = true },
                label = { Text(stringResource(R.string.trt_quick_yes)) }, colors = chipColors, modifier = Modifier.testTag("today_quick_hurts_yes"))
        }
        if (hurts == true) {
            Text(stringResource(R.string.trt_quick_where), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            SoreChips(soreSet) { set -> sore = set.map { it.name } }
        }
        Button(
            onClick = { onSave(strength, if (hurts == true) soreSet else emptySet()) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp).testTag("today_quick_show"),
        ) { Text(stringResource(R.string.trt_quick_show)) }
        TextButton(onClick = onMore, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("today_quick_more")) {
            Text(stringResource(R.string.trt_quick_more))
        }
    }
}

/** An answer stands out on the suggestion card, whose colour the default selected chip shares. */
@Composable
private fun answerChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = CruxCoachDesign.colors.brandAccent,
    selectedLabelColor = CruxCoachDesign.colors.onBrandAccent,
)

/** Body areas as chips, several at once; the same ones as for an injury. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SoreChips(
    selected: Set<com.cruxcoach.athlete.model.InjuryRegion>,
    /** On the suggestion card the default selected colour is the card's own; the sheet keeps the default. */
    accent: Boolean = true,
    onChange: (Set<com.cruxcoach.athlete.model.InjuryRegion>) -> Unit,
) {
    val colors = if (accent) answerChipColors() else FilterChipDefaults.filterChipColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        com.cruxcoach.athlete.model.InjuryRegion.entries.forEach { r ->
            FilterChip(
                selected = r in selected,
                onClick = { onChange(if (r in selected) selected - r else selected + r) },
                label = { Text(regionLabel(r)) },
                colors = colors,
                modifier = Modifier.testTag("today_sore_${r.name.lowercase()}"),
            )
        }
    }
}

/** 1–5 as five tappable dots; tapping the selected value clears it (every question is optional). */
@Composable
private fun ScaleRow(label: String, value: Int?, tag: String, onChange: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        (1..5).forEach { v ->
            val selected = value == v
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) null else v) },
                label = { Text("$v") },
                modifier = Modifier.padding(start = 4.dp).testTag("checkin_${tag}_$v"),
            )
        }
    }
}

// ── "Dein Tag": what is logged often, with today's value ──────────

/**
 * Climbing, weight, food and water as four tiles, each with today's value and
 * one tap to log: the values and the quick actions are the same thing.
 */
@Composable
private fun DayGrid(
    state: TodayState,
    onLogClimbing: () -> Unit,
    onLogWeight: () -> Unit,
    onOpenBody: () -> Unit,
    onOpenFuel: () -> Unit,
    onAddWater: () -> Unit,
) {
    val units = state.profile.units
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        add { m -> ClimbingTile(state, onLogClimbing, m) }
        if (state.profile.bodyEnabled) add { m -> WeightTile(state, onLogWeight, onOpenBody, m) }
        add { m -> FoodTile(state, onOpenFuel, m) }
        add { m -> WaterTile(state, units, onAddWater, m) }
    }
    Column(Modifier.fillMaxWidth().testTag("today_quick"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tru_day_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp))
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ClimbingTile(state: TodayState, onLog: () -> Unit, modifier: Modifier) {
    val a = state.activity
    val climbed = a != null && (a.climbingMinutes > 0 || a.climbingEfforts > 0)
    com.cruxcoach.android.ui.training.common.ValueTile(
        icon = Icons.Default.Terrain, tint = CruxCoachDesign.colors.brandAccent,
        label = stringResource(R.string.tru_tile_climbing),
        value = when {
            !climbed || a == null -> stringResource(R.string.tru_tile_log)
            a.climbingMinutes > 0 -> pluralStringResource(R.plurals.trt_minutes, a.climbingMinutes, a.climbingMinutes)
            else -> pluralStringResource(R.plurals.tru_tile_efforts, a.climbingEfforts, a.climbingEfforts)
        },
        valueIsAction = !climbed,
        supporting = if (climbed) dayLoadLabel(state.dayLoad) else stringResource(R.string.tru_tile_climbing_hint),
        tag = "today_log_climbing", onClick = onLog, modifier = modifier,
    )
}

@Composable
private fun WeightTile(state: TodayState, onLog: () -> Unit, onOpenBody: () -> Unit, modifier: Modifier) {
    val trend = state.trendKg
    val loggedToday = state.lastWeighDay != null && state.lastWeighDay == state.today?.toString()
    val rate = state.weeklyRateKg
    com.cruxcoach.android.ui.training.common.ValueTile(
        icon = Icons.Default.MonitorWeight, tint = MaterialTheme.colorScheme.tertiary,
        label = stringResource(R.string.tru_tile_weight),
        value = when {
            trend == null -> stringResource(R.string.tru_tile_log)
            state.profile.hideBodyNumbers -> stringResource(R.string.trt_numbers_hidden_short)
            else -> formatMass(trend, state.profile.units)
        },
        valueIsAction = trend == null,
        supporting = when {
            loggedToday -> stringResource(R.string.tru_tile_weight_today)
            trend != null && rate != null && !state.profile.hideBodyNumbers ->
                stringResource(R.string.tru_tile_weight_rate, formatMass(rate, state.profile.units, signed = true))
            else -> stringResource(R.string.tru_tile_weight_tap)
        },
        tag = "today_weight", onClick = onLog, modifier = modifier,
        trailing = if (trend != null) ({
            IconButton(onClick = onOpenBody, modifier = Modifier.size(36.dp).testTag("today_body")) {
                Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = stringResource(R.string.tru_open_body), modifier = Modifier.size(20.dp))
            }
        }) else null,
    )
}

@Composable
private fun FoodTile(state: TodayState, onOpen: () -> Unit, modifier: Modifier) {
    val t = state.fuelTargets
    val energyGoal = com.cruxcoach.android.ui.training.common.energyTarget(state.need, state.fuelPlan ?: state.energy.plan, state.profile)
    val protein = state.proteinToday.roundToInt()
    com.cruxcoach.android.ui.training.common.ValueTile(
        icon = Icons.Default.Restaurant, tint = CruxCoachDesign.colors.positive,
        // The grams are protein: the label says so (device test 2026-10-10: "0 / 111 g" of what?).
        label = stringResource(R.string.tru_tile_food_protein),
        value = if (t != null) stringResource(R.string.trf_progress_g, protein, t.proteinG) else stringResource(R.string.trf_value_g, protein),
        progress = t?.let { if (it.proteinG > 0) protein.toFloat() / it.proteinG else 0f },
        supporting = when {
            t == null -> stringResource(R.string.tru_tile_food_no_target)
            // Energy in the open next to protein: eaten of the need, or of the calorie target while losing weight.
            state.profile.showCalories && energyGoal != null ->
                stringResource(R.string.trn_tile_kcal, (state.kcalToday ?: 0.0).roundToInt(), energyGoal)
            protein >= t.proteinG -> stringResource(R.string.tru_tile_food_reached)
            else -> stringResource(R.string.tru_tile_food_left, t.proteinG - protein)
        },
        tag = "today_fuel", onClick = onOpen, modifier = modifier,
    )
}

@Composable
private fun WaterTile(state: TodayState, units: com.cruxcoach.athlete.model.UnitSystem, onAdd: () -> Unit, modifier: Modifier) {
    val volume = FuelUnits.unitFor(units, drink = true)
    val target = state.fuelTargets?.waterMl
    val glass = FuelUnits.waterPresetsMl(units).first()
    com.cruxcoach.android.ui.training.common.ValueTile(
        icon = Icons.Default.WaterDrop, tint = WaterBlue,
        label = stringResource(R.string.tru_tile_water),
        value = if (target != null) stringResource(R.string.trf_progress_volume, inputText(state.waterTodayMl.toDouble(), volume),
            amountText(target.toDouble(), volume)) else amountText(state.waterTodayMl.toDouble(), volume),
        progress = target?.let { state.waterTodayMl.toFloat() / it },
        supporting = stringResource(R.string.tru_tile_water_tap, amountText(glass.toDouble(), volume)),
        tag = "today_water_add", onClick = onAdd, modifier = modifier,
        trailing = { Icon(Icons.Default.AddCircle, null, tint = WaterBlue, modifier = Modifier.padding(end = 8.dp).size(24.dp)) },
    )
}

/** Water's own colour, the same on Today and in the nutrition log. */
internal val WaterBlue = androidx.compose.ui.graphics.Color(0xFF4FA3F7)

@Composable
private fun SuggestionCard(s: TodaySuggestion, onAction: () -> Unit, onSecondary: (() -> Unit)? = null) {
    val (title, text, action) = when (s) {
        TodaySuggestion.CONFIGURE_EQUIPMENT -> Triple(R.string.trt_sugg_equipment_title, R.string.trt_sugg_equipment_text, R.string.trt_sugg_equipment_action)
        TodaySuggestion.SET_BENCHMARKS -> Triple(R.string.trt_sugg_benchmarks_title, R.string.trt_sugg_benchmarks_text, R.string.tru_estimate)
        TodaySuggestion.BASELINE_TEST -> Triple(R.string.trt_sugg_test_title, R.string.trt_sugg_test_text, R.string.tr_action_start)
        TodaySuggestion.LOG_WEIGHT -> Triple(R.string.trt_sugg_weight_title, R.string.trt_sugg_weight_text, R.string.trt_sugg_weight_action)
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("today_suggestion_${s.name.lowercase()}")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                onSecondary?.let { TextButton(onClick = it) { Text(stringResource(R.string.tru_enter_exact)) } }
                FilledTonalButton(onClick = onAction, modifier = Modifier.testTag("today_suggestion_action")) { Text(stringResource(action)) }
            }
        }
    }
}

// ── Daily suggestion (climber version) ──────────────────────────────

@Composable
private fun suggestionTitle(state: TodayState): String {
    val s = state.suggestion ?: return ""
    if (s.addOn) return stringResource(R.string.tre_focus_addon)
    if (s.focus == SuggestionFocus.PLANNED) {
        state.plannedName?.let { return it }
        s.plannedEntry?.takeIf { it.startsWith("builtin:") }
            ?.let { BuiltinRoutines.byKey(it.removePrefix("builtin:")) }
            ?.let { return routineName(it) }
    }
    return stringResource(when (s.focus) {
        SuggestionFocus.PLANNED -> R.string.trsg_focus_planned
        SuggestionFocus.BOARD_DAY -> R.string.trsg_focus_board
        SuggestionFocus.FINGER_STRENGTH -> R.string.trsg_focus_finger
        SuggestionFocus.PULL_PUSH -> R.string.trsg_focus_pull_push
        SuggestionFocus.LEGS_CORE -> R.string.trsg_focus_legs_core
        SuggestionFocus.MOBILITY_RECOVERY -> R.string.trsg_focus_mobility
        SuggestionFocus.INJURY_SAFE -> R.string.trsg_focus_injury
        SuggestionFocus.REST -> R.string.trsg_focus_rest
    })
}

@Composable
private fun suggestionReasonText(reason: SuggestionReason): String = stringResource(when (reason) {
    SuggestionReason.SICK -> R.string.trsg_reason_sick
    SuggestionReason.REST_READINESS -> R.string.trsg_reason_rest_readiness
    SuggestionReason.WEEK_PLAN -> R.string.trsg_reason_week_plan
    SuggestionReason.WEEK_PLAN_BOARD -> R.string.trsg_reason_week_plan_board
    SuggestionReason.WEEK_PLAN_REST -> R.string.trsg_reason_week_plan_rest
    SuggestionReason.PLAN_FILTERED_FOR_INJURY -> R.string.trsg_reason_plan_filtered
    SuggestionReason.PLAN_REPLACED_FOR_INJURY -> R.string.trsg_reason_plan_replaced
    SuggestionReason.BOARD_SKIPPED_TODAY -> R.string.trsg_reason_board_skipped
    SuggestionReason.BOARD_LIGHT -> R.string.trsg_reason_board_light
    SuggestionReason.HARD_DAY_TOMORROW -> R.string.trsg_reason_hard_tomorrow
    SuggestionReason.INJURY_CLIMBING_PAUSED -> R.string.trsg_reason_injury
    SuggestionReason.FINGERS_LOADED_RECENTLY -> R.string.trsg_reason_fingers_loaded
    SuggestionReason.FINGERS_TIRED -> R.string.trsg_reason_fingers_tired
    SuggestionReason.SKIN_LOW -> R.string.trsg_reason_skin
    SuggestionReason.FINGERS_RESTED -> R.string.trsg_reason_fingers_rested
    SuggestionReason.NO_FINGER_EQUIPMENT -> R.string.trsg_reason_no_finger_equipment
    SuggestionReason.PULL_LONGER_AGO -> R.string.trsg_reason_pull_longer_ago
    SuggestionReason.LEGS_LONGER_AGO -> R.string.trsg_reason_legs_longer_ago
    SuggestionReason.LOW_ENERGY -> R.string.trsg_reason_low_energy
    SuggestionReason.GOAL_STRENGTH -> R.string.trsg_reason_goal_strength
    SuggestionReason.FAVORITES_USED -> R.string.trsg_reason_favorites
    SuggestionReason.SHORTENED_TO_TIME -> R.string.trsg_reason_shortened
    SuggestionReason.RECOVERY_AFTER_LIMIT -> R.string.tre_reason_recovery_limit
    SuggestionReason.RECOVERY_AFTER_HARD -> R.string.tre_reason_recovery_hard
    SuggestionReason.RECOVERY_AFTER_VOLUME -> R.string.tre_reason_recovery_volume
    SuggestionReason.FINGER_LOAD_RISING -> R.string.tre_reason_finger_rising
    SuggestionReason.SKIN_LOAD_RISING -> R.string.tre_reason_skin_rising
    SuggestionReason.GUARDRAIL_YOUTH -> R.string.tre_reason_guard_youth
    SuggestionReason.GUARDRAIL_NOVICE -> R.string.tre_reason_guard_novice
    SuggestionReason.GUARDRAIL_MASTERS -> R.string.tre_reason_guard_masters
    SuggestionReason.FINGER_PREFERENCE_NONE -> R.string.tre_reason_finger_none
    SuggestionReason.FOCUS_AREAS -> R.string.tre_reason_focus
    SuggestionReason.GOAL_PROJECT -> R.string.tre_reason_goal_project
    SuggestionReason.GOAL_HEALTHY -> R.string.tre_reason_goal_healthy
    SuggestionReason.GOAL_COMEBACK -> R.string.tre_reason_goal_comeback
    SuggestionReason.CLIMBING_DAY -> R.string.tre_reason_climbing_day
    SuggestionReason.CLIMBING_DAY_ADDON -> R.string.tre_reason_addon
    SuggestionReason.BLOCK_INTRO -> R.string.tre_reason_block_intro
    SuggestionReason.BLOCK_DELOAD -> R.string.tre_reason_block_deload
    SuggestionReason.BLOCK_TAPER -> R.string.tre_reason_block_taper
    SuggestionReason.BLOCK_EVENT -> R.string.tre_reason_block_event
    SuggestionReason.PREFERENCES_LEARNED -> R.string.tre_reason_learned
    SuggestionReason.NO_TIME_TODAY -> R.string.tre_reason_no_time
    SuggestionReason.LEVEL_EASIER -> R.string.tre_reason_level_easier
    SuggestionReason.LEVEL_HARDER -> R.string.tre_reason_level_harder
    SuggestionReason.RETURN_AFTER_BREAK -> R.string.tre_reason_return_break
    SuggestionReason.RETURN_FINGER -> R.string.tre_reason_return_finger
    SuggestionReason.WEEKLY_TARGET -> R.string.trv_reason_weekly
    SuggestionReason.CHOSEN_TODAY -> R.string.trt_reason_chosen
    SuggestionReason.SPARED_TODAY -> R.string.trt_reason_spared_generic
    SuggestionReason.NO_WALL_HERE -> R.string.trt_reason_no_wall
    SuggestionReason.MORE_TIME_TODAY -> R.string.trt_reason_more_time
    SuggestionReason.EXTENDED_TO_TIME -> R.string.trt_reason_extended
    SuggestionReason.KEPT_SHORT_THIS_WEEK -> R.string.trt_reason_kept_short
    SuggestionReason.LESS_THAN_CHOSEN -> R.string.trt_reason_less_than_chosen
    SuggestionReason.USUAL_KIND -> R.string.trt_reason_usual_kind_generic
    SuggestionReason.USUAL_MINUTES -> R.string.trt_reason_usual_minutes_generic
})

/** A reason with today's details where it has them ("Heute geschont: Schulter …", "Wie meist am Montag: …"). */
@Composable
private fun suggestionReasonText(reason: SuggestionReason, state: TodayState): String {
    val usual = state.usual
    val weekday = state.today?.let { java.time.DayOfWeek.of(it.dayOfWeek.ordinal + 1).getDisplayName(TextStyle.FULL, Locale.getDefault()) }
    val usualFocus = usual?.focus
    val usualMinutes = usual?.minutes
    if (reason == SuggestionReason.USUAL_KIND && usualFocus != null && weekday != null) {
        return stringResource(R.string.trt_reason_usual_kind, weekday, kindLabel(usualFocus))
    }
    if (reason == SuggestionReason.USUAL_MINUTES && usualMinutes != null && weekday != null) {
        return stringResource(R.string.trt_reason_usual_minutes, weekday, usualMinutes)
    }
    // The weekly plan names the area it means (device test 2026-10-17: "this area" on a finger day meant pull).
    if (reason == SuggestionReason.WEEKLY_TARGET) {
        state.suggestion?.basedOn?.filterIsInstance<com.cruxcoach.athlete.logic.Evidence.WeeklyTarget>()?.firstOrNull()?.let { w ->
            return stringResource(R.string.trv_reason_weekly_area, com.cruxcoach.android.ui.training.workouts.volumeAreaLabel(w.area))
        }
    }
    val sore = state.checkin?.sore.orEmpty()
    if (reason != SuggestionReason.SPARED_TODAY || sore.isEmpty()) return suggestionReasonText(reason)
    val areas = sore.map { regionLabel(it) }.joinToString(", ")
    return stringResource(R.string.trt_reason_spared, areas)
}

/**
 * Today's training as the one big thing on the screen: what, how long, the
 * first exercises, the check-in and one big button. Reasons, evidence and
 * confidence sit behind "Warum?"; another suggestion is the one small action
 * next to it, adapt and save are in the menu.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DailySuggestionCard(
    state: TodayState,
    onStart: (String) -> Unit,
    onNext: () -> Unit,
    onWhy: () -> Unit,
    onEdit: (String) -> Unit,
    onSave: (String) -> Unit,
    onOpenPlaylistGenerator: (type: String, minutes: Int) -> Unit = { _, _ -> },
    checkin: @Composable () -> Unit = {},
    onPickPlace: (String) -> Unit = {},
    onQuickCheckin: (Int?, Set<com.cruxcoach.athlete.model.InjuryRegion>) -> Unit = { _, _ -> },
    onMoreCheckin: () -> Unit = {},
    onChooseMinutes: (Int?) -> Unit = {},
    /** A tap on an exercise: swap it, leave it out today or never suggest it. */
    onExercise: (String) -> Unit = {},
    /** "Als Zirkel" for today (owner 2026-10-17), and its rounds and rests. */
    onChooseCircuit: (Boolean) -> Unit = {},
    onSetCircuit: (Int?, Int?, Int?) -> Unit = { _, _, _ -> },
    /** "Leichter" from the energy hint (holistic audit P2-17): today's easier level, as in "another suggestion". */
    onLighter: () -> Unit = {},
) {
    val s = state.suggestion ?: return
    var circuitSheet by remember { mutableStateOf(false) }
    if (circuitSheet) CircuitSheet(s.routine.items, onDismiss = { circuitSheet = false }, onSet = onSetCircuit,
        onOff = { circuitSheet = false; onChooseCircuit(false) })
    val language = catalogLanguage()
    val title = suggestionTitle(state)
    val main = s.routine.items.filter { !it.warmup }
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("today_suggestion_card"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.trt_today_hero), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                // "Einstiegswoche" explains itself like every value: a tap opens the "Why?" sheet with the block.
                state.block?.let {
                    Text(blockLabel(it), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(CruxCoachDesign.shapes.small)
                            .clickable(onClickLabel = stringResource(R.string.tru_why), onClick = onWhy)
                            .padding(horizontal = 4.dp, vertical = 2.dp).testTag("today_block"))
                }
            }
            if (state.asksBeforeSuggestion) {
                // Two questions first, then the suggestion that fits them (owner 2026-10-15).
                QuickCheckin(onSave = onQuickCheckin, onMore = onMoreCheckin)
                return@Column
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).testTag("today_suggestion_title"))
            // How long and where, in one row: one tap on the time or the place changes it for today
            // (owner 2026-10-16); with one place there is still "no equipment today".
            val day = state.today?.toString() ?: java.time.LocalDate.now().toString()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // The count matches the list below: the warm-up is part of the time, not of the list.
                Text(pluralStringResource(R.plurals.trsg_meta_count, main.size, main.size),
                    style = MaterialTheme.typography.bodyMedium)
                if (s.timeAdjustable) DurationChip(s.totalMinutes, state.minutesToday, state.profile.sessionMinutes, onChooseMinutes)
                else Text(stringResource(R.string.trt_duration_value, s.totalMinutes), style = MaterialTheme.typography.bodyMedium)
                com.cruxcoach.android.ui.training.common.PlacePicker(
                    places = TrainingPlaces.of(state.profile),
                    currentId = TrainingPlaces.current(state.profile, day)?.id,
                    defaultId = TrainingPlaces.default(state.profile)?.id,
                    onPick = onPickPlace,
                )
                // One set of each in turn instead of all sets of one exercise first – one tap, today only.
                if (state.circuitPossible) {
                    // Clearly on or off (render review 2026-10-17: the selected chip looked like the other one).
                    FilterChip(
                        selected = state.circuitToday,
                        onClick = { onChooseCircuit(!state.circuitToday) },
                        label = { Text(stringResource(R.string.trcirc_today_chip)) },
                        leadingIcon = { Icon(if (state.circuitToday) Icons.Default.Check else Icons.Default.Repeat, null, Modifier.size(18.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CruxCoachDesign.colors.brandAccent,
                            selectedLabelColor = CruxCoachDesign.colors.onBrandAccent,
                            selectedLeadingIconColor = CruxCoachDesign.colors.onBrandAccent,
                        ),
                        modifier = Modifier.testTag("today_circuit"),
                    )
                }
            }
            if (main.isNotEmpty()) {
                // Each exercise with its sets; a tap swaps it (owner 2026-10-16: overridable with little effort).
                var expanded by rememberSaveable(s.routine.items.size) { mutableStateOf(false) }
                val shown = if (expanded) main else main.take(4)
                Column(Modifier.padding(top = 4.dp).testTag("today_suggestion_items")) {
                    shown.forEachIndexed { index, item ->
                        // A circuit's members under one header: rounds and rests, a tap changes them.
                        val circuit = item.circuit
                        if (circuit != null && shown.getOrNull(index - 1)?.circuit != circuit) {
                            CircuitGroupHeader(s.routine.items, circuit, onClick = { circuitSheet = true })
                        }
                        val def = state.catalog.fallbackFor(item.slug)
                        val name = def.name(language)
                        val swap = stringResource(R.string.trt_swap_action)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(CruxCoachDesign.shapes.small)
                                .clickable(onClickLabel = swap) { onExercise(item.slug) }
                                .testTag("today_item_${item.slug}"),
                        ) {
                            if (item.slug in state.favorites) {
                                // A favourite carries its star, so it is clear why it keeps coming back.
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.trt_favorite), Modifier.size(16.dp)
                                    .testTag("today_favorite_${item.slug}"), tint = CruxCoachDesign.colors.brandAccent)
                            } else {
                                Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp),
                                    color = if (item.circuit != null) CruxCoachDesign.colors.brandAccent else androidx.compose.ui.graphics.Color.Unspecified,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            // The sets the training will plan: today's form may take one off (audit W7: card ≠ player).
                            val planned = state.dayForm?.let { com.cruxcoach.athlete.logic.ReadinessModifiers.applyToItem(item, def, it) } ?: item
                            Text(com.cruxcoach.android.ui.training.workouts.routineItemSummary(planned, def.kind, def.load, state.profile.units, short = true),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, Modifier.padding(start = 6.dp).size(18.dp))
                        }
                    }
                    if (main.size > 4) {
                        Text(
                            if (expanded) stringResource(R.string.trt_show_less) else stringResource(R.string.trt_more_exercises, main.size - 4),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = CruxCoachDesign.colors.brandAccent,
                            modifier = Modifier.clip(CruxCoachDesign.shapes.small).clickable { expanded = !expanded }
                                .padding(vertical = 8.dp, horizontal = 4.dp).testTag("today_items_more"),
                        )
                    }
                }
            }
            s.reasons.firstOrNull()?.let {
                // The reason with "Warum?" right behind it: the details are one tap away, not another row of icons.
                val why = stringResource(R.string.tru_why)
                val accent = CruxCoachDesign.colors.brandAccent
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(suggestionReasonText(it, state))
                        append("  ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
                        append(why)
                        pop()
                    },
                    style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClickLabel = why, onClick = onWhy).testTag("today_suggestion_reason"),
                )
            }
            // Little energy lately before maximal finger work: one supportive line and "lighter" as one tap (P2-17).
            val energyHint = remember(state.energy, s, state.catalog) {
                com.cruxcoach.athlete.logic.EnergyBeforeTraining.hint(state.energy, s, state.catalog)
            }
            energyHint?.let { EnergyHintLine(it, state.profile, lighterChosen = state.levelShift < 0, onLighter = onLighter) }
            checkin()
            val plan = s.boardPlan
            if (plan != null) {
                // Board day: the session on the board is the main thing, the warm-up comes with it.
                Button(onClick = { onOpenPlaylistGenerator(plan.type.name, plan.minutes) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp).testTag("today_suggestion_board")) {
                    Icon(Icons.Default.Terrain, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tre_board_plan, generatorLabel(plan.type), plan.minutes))
                }
                OutlinedButton(onClick = { onStart(title) }, enabled = s.routine.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("today_suggestion_start")) {
                    Text(stringResource(R.string.trt_warmup_start))
                }
            } else {
                Button(onClick = { onStart(title) }, enabled = s.routine.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp).testTag("today_suggestion_start")) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.trsg_start))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onNext, modifier = Modifier.testTag("today_suggestion_next")) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trsg_next))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("today_suggestion_more")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trt_more_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trsg_why_title)) },
                            onClick = { menu = false; onWhy() },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            modifier = Modifier.testTag("today_suggestion_why"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trsg_edit)) },
                            onClick = { menu = false; onEdit(title) },
                            enabled = s.routine.items.isNotEmpty(),
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            modifier = Modifier.testTag("today_suggestion_edit"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trsg_save)) },
                            onClick = { menu = false; onSave(title) },
                            enabled = s.routine.items.isNotEmpty(),
                            leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                            modifier = Modifier.testTag("today_suggestion_save"),
                        )
                    }
                }
            }
        }
    }
}

/** The kinds of training the athlete can choose, in their words. */
@Composable
private fun kindLabel(focus: SuggestionFocus): String = stringResource(when (focus) {
    SuggestionFocus.FINGER_STRENGTH -> R.string.trt_kind_finger
    SuggestionFocus.PULL_PUSH -> R.string.trt_kind_pull_push
    SuggestionFocus.LEGS_CORE -> R.string.trt_kind_legs_core
    SuggestionFocus.BOARD_DAY -> R.string.trt_kind_board
    else -> R.string.trt_kind_mobility
})

/** Today's duration: the estimate, and a menu of durations for today only. */
@Composable
private fun DurationChip(minutes: Int, chosen: Int?, usual: Int, onChoose: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val content = LocalContentColor.current
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(R.string.trt_duration_value, minutes)) },
            leadingIcon = { Icon(Icons.Default.Timer, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = content, leadingIconContentColor = content,
                trailingIconContentColor = content),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = content.copy(alpha = 0.4f)),
            modifier = Modifier.testTag("today_duration"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("today_duration_menu")) {
            Text(stringResource(R.string.trt_duration_title), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TodayViewModel.DURATION_OPTIONS.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trt_duration_value, m)) },
                    onClick = { open = false; onChoose(if (m == chosen) null else m) },
                    trailingIcon = if (m == chosen) { { Icon(Icons.Default.Check, null) } } else null,
                    modifier = Modifier.testTag("today_duration_$m"),
                )
            }
            if (chosen != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trt_duration_usual, usual)) },
                    onClick = { open = false; onChoose(null) },
                    modifier = Modifier.testTag("today_duration_usual"),
                )
            }
        }
    }
}

/** "Warum?": every reason, what the suggestion was based on, and how sure it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhySheet(state: TodayState, onDismiss: () -> Unit, onOpenLearned: () -> Unit = {}) {
    val s = state.suggestion ?: return
    val language = catalogLanguage()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("today_why_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.trsg_why_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // The reasons first; what they rest on in one line; how well the coach knows you and the block as
            // headings whose long explanations sit behind their info icons (device test 2026-10-10: the sheet
            // said "Einstiegswoche" three times and "Basis – Basis").
            s.reasons.forEach { Text("• " + suggestionReasonText(it, state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            val block = state.block?.let { blockLabel(it) }
            val evidence = s.basedOn.map { evidenceText(it, state, language) }.filter { it.isNotBlank() && it != block }
            if (evidence.isNotEmpty()) {
                Text(stringResource(R.string.trt_based_on_title) + ": " + evidence.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).testTag("today_why_evidence"))
            }
            val confidenceTitle = stringResource(R.string.tre_conf_info_title)
            SectionTitle(confidenceTitle + ": " + confidenceLabel(s.confidence)) {
                InfoButton(confidenceTitle, stringResource(R.string.tre_conf_info_text))
            }
            if (s.confidence == com.cruxcoach.athlete.logic.Confidence.LOW) {
                Text(stringResource(R.string.trt_low_data), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("today_suggestion_confidence"))
            }
            // Intro, deload, taper and event weeks already have their own reason line above.
            val blockNamed = s.reasons.any { it.name.startsWith("BLOCK_") }
            if (block != null && !blockNamed) {
                SectionTitle(block) { InfoButton(block, stringResource(R.string.tre_block_info_text)) }
            }
            // What the coach learned from the athlete's choices: visible and forgettable in one place.
            TextButton(onClick = onOpenLearned, modifier = Modifier.padding(top = 8.dp).testTag("today_why_learned")) {
                Icon(Icons.Default.Psychology, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trlr_title))
            }
        }
    }
}

// ── Safety cards ────────────────────────────────────────────────────

@Composable
private fun LoadSpikeCard(state: TodayState) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("today_load_spike")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, null, tint = CruxCoachDesign.colors.caution)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trt_spike_title, state.loadSpikes.map { domainLabel(it) }.joinToString(", ")),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.trt_spike_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ── FEAT-071: block, confidence, evidence, feedback ─────────────────

@Composable
private fun blockLabel(b: com.cruxcoach.athlete.logic.BlockState): String = when (b.phase) {
    com.cruxcoach.athlete.logic.BlockPhase.INTRO -> stringResource(R.string.tre_block_intro)
    com.cruxcoach.athlete.logic.BlockPhase.BUILD -> stringResource(R.string.tre_block_build, b.weekInBlock, b.weeksInBlock)
    com.cruxcoach.athlete.logic.BlockPhase.DELOAD -> stringResource(R.string.tre_block_deload)
    com.cruxcoach.athlete.logic.BlockPhase.TAPER -> stringResource(R.string.tre_block_taper, b.daysToEvent ?: 0)
    com.cruxcoach.athlete.logic.BlockPhase.EVENT -> stringResource(R.string.tre_block_event)
}

@Composable
private fun confidenceLabel(c: com.cruxcoach.athlete.logic.Confidence): String = stringResource(when (c) {
    com.cruxcoach.athlete.logic.Confidence.LOW -> R.string.tre_conf_low
    com.cruxcoach.athlete.logic.Confidence.MEDIUM -> R.string.tre_conf_medium
    com.cruxcoach.athlete.logic.Confidence.HIGH -> R.string.tre_conf_high
})

@Composable
private fun generatorLabel(t: com.cruxcoach.domain.playlist.GeneratorType): String = stringResource(when (t) {
    com.cruxcoach.domain.playlist.GeneratorType.PYRAMID -> R.string.tre_gen_pyramid
    com.cruxcoach.domain.playlist.GeneratorType.POWER_ENDURANCE -> R.string.tre_gen_pe
    com.cruxcoach.domain.playlist.GeneratorType.VOLUME -> R.string.tre_gen_volume
    com.cruxcoach.domain.playlist.GeneratorType.LIMIT -> R.string.tre_gen_limit
    com.cruxcoach.domain.playlist.GeneratorType.PROJECTING -> R.string.tre_gen_projecting
    com.cruxcoach.domain.playlist.GeneratorType.MANUAL -> R.string.tre_gen_manual
})

@Composable
private fun intensityLabel(i: com.cruxcoach.athlete.model.ClimbIntensity?): String = stringResource(when (i) {
    com.cruxcoach.athlete.model.ClimbIntensity.LIGHT -> R.string.tre_int_light
    com.cruxcoach.athlete.model.ClimbIntensity.VOLUME -> R.string.tre_int_volume
    com.cruxcoach.athlete.model.ClimbIntensity.HARD -> R.string.tre_int_hard
    com.cruxcoach.athlete.model.ClimbIntensity.LIMIT -> R.string.tre_int_limit
    null -> R.string.tre_int_unknown
})

@Composable
private fun structureLabel(s: com.cruxcoach.athlete.logic.LoadStructure): String = stringResource(when (s) {
    com.cruxcoach.athlete.logic.LoadStructure.FINGER -> R.string.tre_struct_finger
    com.cruxcoach.athlete.logic.LoadStructure.SKIN -> R.string.tre_struct_skin
    com.cruxcoach.athlete.logic.LoadStructure.SHOULDER -> R.string.tre_struct_shoulder
})

@Composable
private fun trendLabel(t: com.cruxcoach.athlete.logic.LoadTrend): String = stringResource(when (t) {
    com.cruxcoach.athlete.logic.LoadTrend.LOW -> R.string.tre_trend_low
    com.cruxcoach.athlete.logic.LoadTrend.NORMAL -> R.string.tre_trend_normal
    com.cruxcoach.athlete.logic.LoadTrend.RISING -> R.string.tre_trend_rising
    com.cruxcoach.athlete.logic.LoadTrend.SPIKE -> R.string.tre_trend_spike
})

@Composable
internal fun coachGoalLabel(g: com.cruxcoach.athlete.model.CoachGoal): String = stringResource(when (g) {
    com.cruxcoach.athlete.model.CoachGoal.CLIMB_HARDER -> R.string.tre_goal_climb_harder
    com.cruxcoach.athlete.model.CoachGoal.PROJECT -> R.string.tre_goal_project
    com.cruxcoach.athlete.model.CoachGoal.BUILD_STRENGTH -> R.string.tre_goal_strength
    com.cruxcoach.athlete.model.CoachGoal.STAY_HEALTHY -> R.string.tre_goal_healthy
    com.cruxcoach.athlete.model.CoachGoal.COMEBACK -> R.string.tre_goal_comeback
    com.cruxcoach.athlete.model.CoachGoal.EVENT -> R.string.tre_goal_event
})

@Composable
internal fun focusAreaLabel(f: com.cruxcoach.athlete.model.FocusArea): String = stringResource(when (f) {
    com.cruxcoach.athlete.model.FocusArea.FINGER_STRENGTH -> R.string.tre_focus_finger
    com.cruxcoach.athlete.model.FocusArea.PULL_STRENGTH -> R.string.tre_focus_pull
    com.cruxcoach.athlete.model.FocusArea.POWER -> R.string.tre_focus_power
    com.cruxcoach.athlete.model.FocusArea.POWER_ENDURANCE -> R.string.tre_focus_pe
    com.cruxcoach.athlete.model.FocusArea.CORE -> R.string.tre_focus_core
    com.cruxcoach.athlete.model.FocusArea.MOBILITY -> R.string.tre_focus_mobility
    com.cruxcoach.athlete.model.FocusArea.PREVENTION -> R.string.tre_focus_prevention
})

/** One piece of "Basierend auf …", in the athlete's words. */
@Composable
private fun evidenceText(e: com.cruxcoach.athlete.logic.Evidence, state: TodayState, language: String): String = when (e) {
    is com.cruxcoach.athlete.logic.Evidence.Climbing -> {
        val detail = if (e.efforts > 0) stringResource(R.string.tre_ev_climb_detail, intensityLabel(e.intensity), e.efforts)
            else intensityLabel(e.intensity)
        when (e.daysAgo) {
            0 -> stringResource(R.string.tre_ev_climb_today, detail)
            1 -> stringResource(R.string.tre_ev_climb_yesterday, detail)
            else -> stringResource(R.string.tre_ev_climb_days, e.daysAgo, detail)
        }
    }
    is com.cruxcoach.athlete.logic.Evidence.WeekPlan -> stringResource(R.string.tre_ev_week_plan)
    is com.cruxcoach.athlete.logic.Evidence.CheckIn -> stringResource(R.string.tre_ev_checkin, readinessReasonShort(e.reason))
    is com.cruxcoach.athlete.logic.Evidence.SoreToday -> stringResource(R.string.trt_ev_sore, regionLabel(e.region))
    is com.cruxcoach.athlete.logic.Evidence.InjuryActive -> stringResource(R.string.tre_ev_injury,
        regionLabel(e.region) + (e.side?.let { " " + injurySideLabel(it) } ?: ""))
    is com.cruxcoach.athlete.logic.Evidence.LoadTrendNote -> stringResource(R.string.tre_ev_trend, structureLabel(e.structure), trendLabel(e.trend))
    is com.cruxcoach.athlete.logic.Evidence.PerformanceValue -> {
        val def = state.catalog.fallbackFor(e.benchmark.exerciseSlug)
        stringResource(R.string.tre_ev_value, def.name(language),
            com.cruxcoach.android.ui.training.benchmarks.benchmarkSummary(def, e.benchmark, state.profile, state.bodyweightKg))
    }
    is com.cruxcoach.athlete.logic.Evidence.BlockNote -> blockLabel(e.state)
    is com.cruxcoach.athlete.logic.Evidence.Favorites -> stringResource(R.string.tre_ev_favorites)
    is com.cruxcoach.athlete.logic.Evidence.Affinity -> stringResource(R.string.tre_ev_affinity,
        e.slugs.take(3).joinToString(", ") { state.catalog.fallbackFor(it).name(language) })
    is com.cruxcoach.athlete.logic.Evidence.GoalNote -> stringResource(R.string.tre_ev_goal, coachGoalLabel(e.goal))
    is com.cruxcoach.athlete.logic.Evidence.FocusNote -> stringResource(R.string.tre_ev_focus, e.areas.map { focusAreaLabel(it) }.joinToString(", "))
    is com.cruxcoach.athlete.logic.Evidence.Guardrail -> stringResource(when (e.kind) {
        com.cruxcoach.athlete.logic.GuardrailKind.YOUTH -> R.string.tre_ev_guard_youth
        com.cruxcoach.athlete.logic.GuardrailKind.NOVICE -> R.string.tre_ev_guard_novice
        com.cruxcoach.athlete.logic.GuardrailKind.MASTERS -> R.string.tre_ev_guard_masters
    })
    // "1 Woche Verlauf" – real plurals, and a part that is 0 is left out ("0 Sends" said nothing).
    is com.cruxcoach.athlete.logic.Evidence.History -> listOfNotNull(
        e.weeks.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tre_ev_history_weeks, it, it) },
        e.logbookSends.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.tre_ev_sends, it, it) },
    ).joinToString(" · ")
    is com.cruxcoach.athlete.logic.Evidence.Return -> stringResource(R.string.tre_ev_return, e.state.daysOff, e.state.week, e.state.weeksTotal)
    is com.cruxcoach.athlete.logic.Evidence.WeeklyTarget -> stringResource(
        if (e.area == com.cruxcoach.athlete.logic.VolumeArea.FINGER) R.string.trv_ev_sessions else R.string.trv_ev_sets,
        com.cruxcoach.android.ui.training.workouts.volumeAreaLabel(e.area), e.done, e.target)
    is com.cruxcoach.athlete.logic.Evidence.TimeToday -> stringResource(R.string.trt_ev_time_today, e.minutes, e.usual)
}

@Composable
private fun DurationHintCard(minutes: Int, onApply: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("today_duration_hint")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tre_duration_hint, minutes), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onApply) { Text(stringResource(R.string.tre_duration_apply)) }
        }
    }
}

/**
 * "Anderer Vorschlag": what would suit today better – the kind of training, a lighter or harder day,
 * or simply another mix. Time, place and single exercises are changed on the card itself; what hurts
 * goes to today's check-in (spared today), missing equipment to the equipment sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NextSuggestionSheet(
    state: TodayState,
    onDismiss: () -> Unit,
    onShuffle: () -> Unit,
    onFocus: (SuggestionFocus?) -> Unit,
    onLevel: (Int) -> Unit,
    onHurts: () -> Unit,
    onEquipment: () -> Unit,
    onPlace: (String) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("today_next_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            // What the athlete feels like today (owner 2026-10-15: "rather push and pull than fingers").
            Text(stringResource(R.string.trt_next_kind), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(SuggestionFocus.FINGER_STRENGTH, SuggestionFocus.PULL_PUSH, SuggestionFocus.LEGS_CORE,
                    SuggestionFocus.BOARD_DAY, SuggestionFocus.MOBILITY_RECOVERY).forEach { f ->
                    FilterChip(
                        selected = state.wish == f,
                        onClick = { onFocus(if (state.wish == f) null else f) },
                        label = { Text(kindLabel(f)) },
                        modifier = Modifier.testTag("today_kind_${f.name.lowercase()}"),
                    )
                }
                if (state.wish != null) {
                    SuggestionChip(onClick = { onFocus(null) }, label = { Text(stringResource(R.string.trt_kind_coach)) },
                        modifier = Modifier.testTag("today_kind_coach"))
                }
            }
            // Where, right with the kind (owner 2026-10-15: no hangboard there, no finger day); also on the card.
            val day = state.today?.toString() ?: java.time.LocalDate.now().toString()
            val currentPlace = TrainingPlaces.current(state.profile, day)?.id
            Text(stringResource(R.string.trt_next_place), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (TrainingPlaces.of(state.profile) + TrainingPlaces.NO_GEAR).forEach { place ->
                    FilterChip(
                        selected = place.id == currentPlace,
                        onClick = { if (place.id != currentPlace) onPlace(place.id) },
                        label = { Text(com.cruxcoach.android.ui.training.common.placeLabel(place)) },
                        leadingIcon = { Icon(com.cruxcoach.android.ui.training.common.placeIcon(place.kind), null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("today_next_place_${place.id}"),
                    )
                }
            }
            Text(stringResource(R.string.trt_next_level), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.levelShift < 0, onClick = { onLevel(if (state.levelShift < 0) 0 else -1) },
                    label = { Text(stringResource(R.string.trt_level_easier)) }, modifier = Modifier.testTag("today_level_easier"))
                FilterChip(selected = state.levelShift > 0, onClick = { onLevel(if (state.levelShift > 0) 0 else 1) },
                    label = { Text(stringResource(R.string.trt_level_harder)) }, modifier = Modifier.testTag("today_level_harder"))
            }
            Button(onClick = onShuffle, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp).testTag("today_next_shuffle")) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.trt_next_shuffle))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onHurts, modifier = Modifier.testTag("today_next_hurts")) {
                    Icon(Icons.Default.Healing, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trt_next_hurts))
                }
                TextButton(onClick = onEquipment, modifier = Modifier.testTag("today_next_equipment")) {
                    Icon(Icons.Default.FitnessCenter, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trt_next_equipment))
                }
            }
        }
    }
}

/**
 * A tap on an exercise of today's card: what could take its place (its easier and harder variant
 * first), or leave it out today, or never suggest it again. Every swap teaches the coach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapSheet(
    state: TodayState,
    slug: String,
    alternatives: List<com.cruxcoach.athlete.logic.SessionSuggester.Alternative>,
    onDismiss: () -> Unit,
    onSwap: (String) -> Unit,
    onDrop: () -> Unit,
    onExclude: () -> Unit,
) {
    val language = catalogLanguage()
    val name = state.catalog.fallbackFor(slug).name(language)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("today_swap_sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trt_swap_title, name), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            if (alternatives.isEmpty()) {
                Text(stringResource(R.string.trt_swap_none), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            alternatives.forEach { alt ->
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
                    modifier = Modifier.clickable { onSwap(alt.def.slug) }.testTag("today_swap_to_${alt.def.slug}"),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onDrop, modifier = Modifier.padding(horizontal = 8.dp).testTag("today_swap_drop")) {
                Icon(Icons.Default.RemoveCircleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_swap_drop))
            }
            TextButton(onClick = onExclude, modifier = Modifier.padding(horizontal = 8.dp).testTag("today_swap_exclude")) {
                Icon(Icons.Default.Block, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_swap_exclude))
            }
        }
    }
}


/** "Circuit · 3 rounds · 0:15 / 2:00" above today's circuit; a tap changes rounds and rests. */
@Composable
private fun CircuitGroupHeader(items: List<com.cruxcoach.athlete.model.RoutineItem>, circuit: Int, onClick: () -> Unit) {
    val first = items.firstOrNull { it.circuit == circuit } ?: return
    val rounds = com.cruxcoach.athlete.logic.Circuits.rounds(items, circuit)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(CruxCoachDesign.shapes.small).clickable(onClick = onClick)
            .padding(horizontal = 4.dp).testTag("today_circuit_group"),
    ) {
        Icon(Icons.Default.Repeat, null, Modifier.size(16.dp), tint = CruxCoachDesign.colors.brandAccent)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.trcirc_group, pluralStringResource(R.plurals.trcirc_rounds, rounds, rounds),
            formatClock(first.circuitRestS ?: com.cruxcoach.athlete.logic.Circuits.DEFAULT_CIRCUIT_REST_S), formatClock(first.roundRestS ?: 120)),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = CruxCoachDesign.colors.brandAccent,
            modifier = Modifier.weight(1f))
        Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
    }
}

/** Today's circuit: rounds, rest between the exercises and after each round – or no circuit after all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircuitSheet(
    items: List<com.cruxcoach.athlete.model.RoutineItem>,
    onDismiss: () -> Unit,
    onSet: (Int?, Int?, Int?) -> Unit,
    onOff: () -> Unit,
) {
    val key = items.firstOrNull { it.circuit != null }?.circuit ?: return onDismiss()
    val first = items.first { it.circuit == key }
    val rounds = com.cruxcoach.athlete.logic.Circuits.rounds(items, key)
    val between = first.circuitRestS ?: com.cruxcoach.athlete.logic.Circuits.DEFAULT_CIRCUIT_REST_S
    val after = first.roundRestS ?: 120
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("today_circuit_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.trcirc_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.trcirc_explain), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.cruxcoach.android.ui.training.workouts.EditorStepper(stringResource(R.string.trwo_field_rounds), rounds, 1, 1, 10, "circuit_rounds") { v ->
                onSet(v, between, after)
            }
            com.cruxcoach.android.ui.training.workouts.EditorStepper(stringResource(R.string.trcirc_rest_between), between, 5, 0, 120, "circuit_rest",
                format = { formatClock(it) }) { v -> onSet(rounds, v, after) }
            com.cruxcoach.android.ui.training.workouts.EditorStepper(stringResource(R.string.trcirc_rest_round), after, 15, 0, 600, "circuit_round_rest",
                format = { formatClock(it) }) { v -> onSet(rounds, between, v) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onOff, modifier = Modifier.heightIn(min = 48.dp).testTag("today_circuit_off")) {
                    Text(stringResource(R.string.trcirc_dissolve))
                }
                Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("today_circuit_done")) {
                    Text(stringResource(R.string.tr_action_done))
                }
            }
        }
    }
}
