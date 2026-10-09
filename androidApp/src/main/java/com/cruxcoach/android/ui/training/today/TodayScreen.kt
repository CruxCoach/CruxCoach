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
    var menu by remember { mutableStateOf(false) }
    // The checklist's last tick is celebrated once in place before the card goes.
    val setupJustDone = state.setupJustDone
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.trsg_saved)
    val weightSavedText = stringResource(R.string.tru_weight_saved)
    val equipmentSavedText = stringResource(R.string.tru_equipment_saved)
    val undoText = stringResource(R.string.trf_undo)
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
                    onLogClimbing = onLogClimbing,
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

    if (showWhy) WhySheet(state, onDismiss = { showWhy = false })
    if (showCheckin) {
        CheckinSheet(state, onDismiss = { showCheckin = false },
            onSave = { s, e, sk, f, sick -> viewModel.saveCheckin(s, e, sk, f, sick); showCheckin = false },
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
            onSave = { lose, target, pace -> viewModel.saveWeightGoal(lose, target, pace); showGoal = false },
            onLogWeight = viewModel::logWeight, onLogHeight = viewModel::logHeight,
        )
    }
    if (showEquipment) {
        com.cruxcoach.android.ui.training.common.EquipmentSheet(
            initial = state.profile.equipment.takeIf { state.profile.equipmentConfigured } ?: emptySet(),
            onDismiss = { showEquipment = false },
            onSave = { set -> viewModel.saveEquipment(set); showEquipment = false; tell(equipmentSavedText) },
        )
    }
    if (askWhy) {
        NextSuggestionSheet(
            state = state,
            onDismiss = { askWhy = false },
            onChoose = { feedback, slug ->
                askWhy = false
                viewModel.nextSuggestion(feedback, slug)
                when (feedback) {
                    // Answered right here: the sheet with the equipment, not a trip to the settings.
                    com.cruxcoach.athlete.model.SuggestionFeedback.MISSING_EQUIPMENT -> showEquipment = true
                    com.cruxcoach.athlete.model.SuggestionFeedback.HURTS -> onOpenInjuries()
                    else -> Unit
                }
            },
        )
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
    onSave: (Int?, Int?, Int?, Int?, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val checkin = state.checkin
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("today_checkin_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            CheckinForm(checkin?.sleep, checkin?.energy, checkin?.skin, checkin?.fingers, checkin?.sick ?: false, onSave)
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
private fun readinessReasonShort(reason: ReadinessReason): String = stringResource(when (reason) {
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
    onSave: (Int?, Int?, Int?, Int?, Boolean) -> Unit,
) {
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
    Button(
        onClick = { onSave(sleep, energy, skin, fingers, sick) },
        enabled = sick || listOf(sleep, energy, skin, fingers).any { it != null },
        modifier = Modifier.fillMaxWidth().testTag("checkin_save"),
    ) { Text(stringResource(R.string.tr_action_save)) }
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
    val energyGoal = com.cruxcoach.android.ui.training.common.energyTarget(state.need, state.energy.plan)
    val protein = state.proteinToday.roundToInt()
    com.cruxcoach.android.ui.training.common.ValueTile(
        icon = Icons.Default.Restaurant, tint = CruxCoachDesign.colors.positive,
        label = stringResource(R.string.tru_tile_food),
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

// ── Daily suggestion (MCI-style, climber version) ───────────────────

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
})

/**
 * Today's training as the one big thing on the screen: what, how long, the
 * first exercises, the check-in and one big button. Reasons, evidence and
 * confidence sit behind "Warum?"; another suggestion is the one small action
 * next to it, adapt and save are in the menu.
 */
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
) {
    val s = state.suggestion ?: return
    val language = catalogLanguage()
    val title = suggestionTitle(state)
    val main = s.routine.items.filter { !it.warmup }
    val shown = main.take(4)
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
                state.block?.let {
                    Text(blockLabel(it), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("today_block"))
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp).testTag("today_suggestion_title"))
            Text(pluralStringResource(R.plurals.trsg_meta, s.routine.items.size, s.routine.items.size, s.estimatedMinutes),
                style = MaterialTheme.typography.bodyMedium)
            if (shown.isNotEmpty()) {
                Column(Modifier.padding(top = 8.dp)) {
                    shown.forEach { item ->
                        Text("• " + state.catalog.fallbackFor(item.slug).name(language), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    if (main.size > shown.size) {
                        Text(stringResource(R.string.trt_more_exercises, main.size - shown.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            s.reasons.firstOrNull()?.let {
                // The reason with "Warum?" right behind it: the details are one tap away, not another row of icons.
                val why = stringResource(R.string.tru_why)
                val accent = CruxCoachDesign.colors.brandAccent
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(suggestionReasonText(it))
                        append("  ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
                        append(why)
                        pop()
                    },
                    style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClickLabel = why, onClick = onWhy).testTag("today_suggestion_reason"),
                )
            }
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

/** "Warum?": every reason, what the suggestion was based on, and how sure it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhySheet(state: TodayState, onDismiss: () -> Unit) {
    val s = state.suggestion ?: return
    val language = catalogLanguage()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("today_why_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.trsg_why_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            s.reasons.forEach { Text("• " + suggestionReasonText(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            val evidence = s.basedOn.map { evidenceText(it, state, language) }.filter { it.isNotBlank() }
            if (evidence.isNotEmpty()) {
                Text(stringResource(R.string.trt_based_on_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }
            Text(stringResource(R.string.tre_conf_info_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(confidenceLabel(s.confidence) + " – " + stringResource(R.string.tre_conf_info_text), style = MaterialTheme.typography.bodySmall)
            if (s.confidence == com.cruxcoach.athlete.logic.Confidence.LOW) {
                Text(stringResource(R.string.trt_low_data), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp).testTag("today_suggestion_confidence"))
            }
            state.block?.let {
                Text(blockLabel(it), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.tre_block_info_text), style = MaterialTheme.typography.bodySmall)
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
    is com.cruxcoach.athlete.logic.Evidence.Affinity -> stringResource(R.string.tre_ev_affinity)
    is com.cruxcoach.athlete.logic.Evidence.GoalNote -> stringResource(R.string.tre_ev_goal, coachGoalLabel(e.goal))
    is com.cruxcoach.athlete.logic.Evidence.FocusNote -> stringResource(R.string.tre_ev_focus, e.areas.map { focusAreaLabel(it) }.joinToString(", "))
    is com.cruxcoach.athlete.logic.Evidence.Guardrail -> stringResource(when (e.kind) {
        com.cruxcoach.athlete.logic.GuardrailKind.YOUTH -> R.string.tre_ev_guard_youth
        com.cruxcoach.athlete.logic.GuardrailKind.NOVICE -> R.string.tre_ev_guard_novice
        com.cruxcoach.athlete.logic.GuardrailKind.MASTERS -> R.string.tre_ev_guard_masters
    })
    is com.cruxcoach.athlete.logic.Evidence.History -> stringResource(R.string.tre_ev_history, e.weeks, e.logbookSends)
    is com.cruxcoach.athlete.logic.Evidence.Return -> stringResource(R.string.tre_ev_return, e.state.daysOff, e.state.week, e.state.weeksTotal)
    is com.cruxcoach.athlete.logic.Evidence.WeeklyTarget -> stringResource(
        if (e.area == com.cruxcoach.athlete.logic.VolumeArea.FINGER) R.string.trv_ev_sessions else R.string.trv_ev_sets,
        com.cruxcoach.android.ui.training.workouts.volumeAreaLabel(e.area), e.done, e.target)
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

/** "Anderer Vorschlag" with an optional reason that changes today's suggestions at once. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NextSuggestionSheet(
    state: TodayState,
    onDismiss: () -> Unit,
    onChoose: (com.cruxcoach.athlete.model.SuggestionFeedback?, String?) -> Unit,
) {
    val language = catalogLanguage()
    var pickDislike by remember { mutableStateOf(false) }
    val main = state.suggestion?.routine?.items?.filter { !it.warmup }.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("today_next_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.tre_next_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tre_next_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (!pickDislike) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val fb = com.cruxcoach.athlete.model.SuggestionFeedback.entries
                    fb.forEach { f ->
                        val label = stringResource(when (f) {
                            com.cruxcoach.athlete.model.SuggestionFeedback.NO_TIME -> R.string.tre_fb_no_time
                            com.cruxcoach.athlete.model.SuggestionFeedback.DISLIKE_EXERCISE -> R.string.tre_fb_dislike
                            com.cruxcoach.athlete.model.SuggestionFeedback.MISSING_EQUIPMENT -> R.string.tre_fb_equipment
                            com.cruxcoach.athlete.model.SuggestionFeedback.HURTS -> R.string.tre_fb_hurts
                            com.cruxcoach.athlete.model.SuggestionFeedback.TOO_EASY -> R.string.tre_fb_too_easy
                            com.cruxcoach.athlete.model.SuggestionFeedback.TOO_HARD -> R.string.tre_fb_too_hard
                            com.cruxcoach.athlete.model.SuggestionFeedback.OTHER -> R.string.tre_fb_other
                        })
                        if (f == com.cruxcoach.athlete.model.SuggestionFeedback.DISLIKE_EXERCISE && main.isEmpty()) return@forEach
                        SuggestionChip(
                            onClick = {
                                if (f == com.cruxcoach.athlete.model.SuggestionFeedback.DISLIKE_EXERCISE) pickDislike = true
                                else onChoose(f.takeIf { it != com.cruxcoach.athlete.model.SuggestionFeedback.OTHER }, null)
                            },
                            label = { Text(label) },
                            modifier = Modifier.testTag("today_next_${f.name.lowercase()}"),
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.tre_fb_dislike_pick), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    main.forEach { item ->
                        SuggestionChip(
                            onClick = { onChoose(com.cruxcoach.athlete.model.SuggestionFeedback.DISLIKE_EXERCISE, item.slug) },
                            label = { Text(state.catalog.fallbackFor(item.slug).name(language)) },
                            modifier = Modifier.testTag("today_next_dislike_${item.slug}"),
                        )
                    }
                }
            }
        }
    }
}

