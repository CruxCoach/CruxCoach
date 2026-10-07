package com.cruxcoach.android.ui.training.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.cruxcoach.android.ui.training.fuel.unitLabel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.ReadinessLevel
import com.cruxcoach.athlete.logic.ReadinessReason
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.logic.Units
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.trsg_saved)
    LaunchedEffect(state.suggestionSaved) {
        if (state.suggestionSaved) { viewModel.consumeSuggestionSaved(); snackbar.showSnackbar(savedText) }
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
            item { WeekHeader(state) }
            state.openWorkout?.let { item { OpenWorkoutCard(onOpenPlayer) } }
            item {
                ReadinessCard(state, onSave = viewModel::saveCheckin, onClear = viewModel::clearCheckin,
                    onEndPause = viewModel::endPause)
            }
            state.suggestion?.let { suggestion ->
                item(key = "daily_suggestion") {
                    DailySuggestionCard(
                        state = state,
                        onStart = viewModel::startSuggestion,
                        onNext = viewModel::nextSuggestion,
                        onEdit = { onOpenEditor(null, "ex:" + suggestion.routine.items.joinToString(",") { it.slug }) },
                        onSave = viewModel::saveSuggestion,
                    )
                }
            }
            if (state.injuries.isNotEmpty()) item { InjuryCard(state, onOpenInjuries, onStart = { viewModel.startRoutine(BuiltinRoutines.INJURY_ONE_ARM) }) }
            item {
                TrainCard(state, onStartEmpty = viewModel::startEmptyWorkout, onOpenRoutines = onOpenRoutines,
                    onOpenExercises = onOpenExercises, onOpenHistory = onOpenHistory)
            }
            state.suggestions.forEach { s ->
                item(key = s.name) {
                    SuggestionCard(s, onAction = {
                        when (s) {
                            TodaySuggestion.CONFIGURE_EQUIPMENT -> onOpenSettings()
                            TodaySuggestion.SET_BENCHMARKS -> onOpenBenchmarks()
                            TodaySuggestion.BASELINE_TEST -> viewModel.startRoutine(BuiltinRoutines.BASELINE_TESTS)
                            TodaySuggestion.LOG_WEIGHT -> onOpenBody()
                        }
                    })
                }
            }
            if (state.redsSignals.isNotEmpty()) item { RedsCard(state.redsSignals) }
            if (state.loadSpikes.isNotEmpty()) item { LoadSpikeCard(state) }
            if (state.profile.bodyEnabled) item { BodyCard(state, onOpenBody, viewModel::logWeight) }
            item { FuelCard(state, onOpenFuel, viewModel::addWater) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpenWeeklyReview, label = { Text(stringResource(R.string.trt_weekly_review)) },
                        leadingIcon = { Icon(Icons.Default.Insights, null) }, modifier = Modifier.testTag("today_weekly_review"))
                    if (state.injuries.isEmpty()) {
                        AssistChip(onClick = onOpenInjuries, label = { Text(stringResource(R.string.trt_report_injury)) },
                            leadingIcon = { Icon(Icons.Default.Healing, null) }, modifier = Modifier.testTag("today_report_injury"))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayArrow, null, tint = CruxCoachDesign.colors.onBrandAccent)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.trt_continue_workout), color = CruxCoachDesign.colors.onBrandAccent,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
    }
}

// ── Readiness check-in ──────────────────────────────────────────────

@Composable
private fun ReadinessCard(
    state: TodayState,
    onSave: (Int?, Int?, Int?, Int?, Boolean) -> Unit,
    onClear: () -> Unit,
    onEndPause: () -> Unit,
) {
    val checkin = state.checkin
    var editing by rememberSaveable(checkin?.createdAt) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("today_readiness")) {
        Column(Modifier.padding(16.dp)) {
            if (state.openPause != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bedtime, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trt_pause_active), modifier = Modifier.weight(1f))
                    TextButton(onClick = onEndPause, modifier = Modifier.testTag("today_end_pause")) {
                        Text(stringResource(R.string.trt_pause_end))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            if (checkin == null || editing) {
                if (!state.profile.checkinEnabled && checkin == null) {
                    ReadinessResult(state)
                    return@Column
                }
                CheckinForm(checkin?.sleep, checkin?.energy, checkin?.skin, checkin?.fingers, checkin?.sick ?: false) { s, e, sk, f, sick ->
                    onSave(s, e, sk, f, sick); editing = false
                }
            } else {
                ReadinessResult(state)
                Row {
                    TextButton(onClick = { editing = true }, modifier = Modifier.testTag("today_checkin_edit")) {
                        Text(stringResource(R.string.trt_checkin_change))
                    }
                    TextButton(onClick = onClear) { Text(stringResource(R.string.trt_checkin_reset)) }
                }
            }
        }
    }
}

@Composable
private fun ReadinessResult(state: TodayState) {
    val r = state.readiness
    val (icon, title) = when (r.level) {
        ReadinessLevel.GO -> Icons.Default.CheckCircle to stringResource(R.string.trt_ready_go)
        ReadinessLevel.ADAPT -> Icons.Default.Tune to stringResource(R.string.trt_ready_adapt)
        ReadinessLevel.REST -> Icons.Default.Bedtime to stringResource(R.string.trt_ready_rest)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = when (r.level) {
            ReadinessLevel.GO -> CruxCoachDesign.colors.positive
            ReadinessLevel.ADAPT -> CruxCoachDesign.colors.caution
            ReadinessLevel.REST -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        InfoButton(stringResource(R.string.trt_ready_info_title), stringResource(R.string.trt_ready_info_text))
    }
    Text(readinessReasonText(r.decidingFactor), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp).testTag("today_readiness_reason"))
    val more = r.reasons.drop(1).filter { it != ReadinessReason.ALL_GOOD }
    if (more.isNotEmpty()) {
        Text(more.map { readinessReasonShort(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
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

// ── Injury mode ─────────────────────────────────────────────────────

@Composable
private fun InjuryCard(state: TodayState, onManage: () -> Unit, onStart: () -> Unit) {
    val paused = state.injuries.any { it.climbingPaused }
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = Modifier.fillMaxWidth().testTag("today_injury"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Healing, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (paused) R.string.trt_injury_mode_paused else R.string.trt_injury_mode),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            val summary = state.injuries.map { i ->
                listOfNotNull(regionLabel(i.region), i.side?.let { injurySideLabel(it) }).joinToString(" ")
            }
            Text(summary.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.trt_injury_mode_hint), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, modifier = Modifier.testTag("today_injury_start")) {
                    Text(stringResource(R.string.trt_injury_start))
                }
                OutlinedButton(onClick = onManage) { Text(stringResource(R.string.trt_injury_manage)) }
            }
        }
    }
}

// ── Training of the day ─────────────────────────────────────────────

@Composable
private fun TrainCard(
    state: TodayState,
    onStartEmpty: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenExercises: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("today_train")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trt_today_training), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(dayLoadLabel(state.dayLoad), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val a = state.activity
            if (a != null && (a.climbingMinutes > 0 || a.climbingEfforts > 0)) {
                Text(
                    if (a.climbingMinutes > 0) pluralStringResource(R.plurals.trt_board_minutes, a.climbingMinutes, a.climbingMinutes)
                    else pluralStringResource(R.plurals.trt_board_efforts, a.climbingEfforts, a.climbingEfforts),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.todaysWorkouts.forEach { w ->
                Text("• " + com.cruxcoach.android.ui.training.workout.workoutTitle(w) +
                    (w.durationMinutes?.let { " · " + pluralStringResource(R.plurals.trt_minutes, it, it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if ((a == null || !a.trained) && state.todaysWorkouts.isEmpty()) {
                Text(stringResource(R.string.trt_nothing_yet), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartEmpty, enabled = state.openWorkout == null, modifier = Modifier.testTag("today_start_empty")) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.trt_start_training))
                }
                OutlinedButton(onClick = onOpenRoutines, modifier = Modifier.testTag("today_routines")) {
                    Text(stringResource(R.string.trt_routines))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenExercises, modifier = Modifier.testTag("today_exercises")) { Text(stringResource(R.string.tr_nav_exercises)) }
                TextButton(onClick = onOpenHistory, modifier = Modifier.testTag("today_history")) { Text(stringResource(R.string.trt_history)) }
            }
        }
    }
}

@Composable
private fun SuggestionCard(s: TodaySuggestion, onAction: () -> Unit) {
    val (title, text, action) = when (s) {
        TodaySuggestion.CONFIGURE_EQUIPMENT -> Triple(R.string.trt_sugg_equipment_title, R.string.trt_sugg_equipment_text, R.string.trt_sugg_equipment_action)
        TodaySuggestion.SET_BENCHMARKS -> Triple(R.string.trt_sugg_benchmarks_title, R.string.trt_sugg_benchmarks_text, R.string.trt_sugg_benchmarks_action)
        TodaySuggestion.BASELINE_TEST -> Triple(R.string.trt_sugg_test_title, R.string.trt_sugg_test_text, R.string.tr_action_start)
        TodaySuggestion.LOG_WEIGHT -> Triple(R.string.trt_sugg_weight_title, R.string.trt_sugg_weight_text, R.string.trt_sugg_weight_action)
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("today_suggestion_${s.name.lowercase()}")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAction) { Text(stringResource(action)) }
        }
    }
}

// ── Daily suggestion (MCI-style, climber version) ───────────────────

@Composable
private fun suggestionTitle(state: TodayState): String {
    val s = state.suggestion ?: return ""
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
    SuggestionReason.PULL_LONGER_AGO -> R.string.trsg_reason_pull_longer_ago
    SuggestionReason.LEGS_LONGER_AGO -> R.string.trsg_reason_legs_longer_ago
    SuggestionReason.LOW_ENERGY -> R.string.trsg_reason_low_energy
    SuggestionReason.GOAL_STRENGTH -> R.string.trsg_reason_goal_strength
    SuggestionReason.FAVORITES_USED -> R.string.trsg_reason_favorites
    SuggestionReason.SHORTENED_TO_TIME -> R.string.trsg_reason_shortened
})

@Composable
private fun DailySuggestionCard(
    state: TodayState,
    onStart: (String) -> Unit,
    onNext: () -> Unit,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
) {
    val s = state.suggestion ?: return
    val language = catalogLanguage()
    val title = suggestionTitle(state)
    val reasons = s.reasons.map { suggestionReasonText(it) }
    val main = s.routine.items.filter { !it.warmup }
    val names = main.take(5).map { item -> state.catalog.fallbackFor(item.slug).name(language) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("today_suggestion_card"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trsg_card_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trsg_why_title), reasons.joinToString("\n\n"))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("today_suggestion_title"))
            if (s.plannedEntry != null && (s.focus == SuggestionFocus.PLANNED || s.focus == SuggestionFocus.BOARD_DAY)) {
                Text(stringResource(R.string.trsg_planned_today), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                pluralStringResource(R.plurals.trsg_meta, s.routine.items.size, s.routine.items.size, s.estimatedMinutes),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
            )
            if (names.isNotEmpty()) {
                Text(names.joinToString(" · ") + if (main.size > names.size) " …" else "",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            reasons.firstOrNull()?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)
                    .testTag("today_suggestion_reason"))
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onStart(title) }, enabled = s.routine.items.isNotEmpty(),
                    modifier = Modifier.testTag("today_suggestion_start")) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.trsg_start))
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.testTag("today_suggestion_next")) {
                    Text(stringResource(R.string.trsg_next))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit, enabled = s.routine.items.isNotEmpty(), modifier = Modifier.testTag("today_suggestion_edit")) {
                    Text(stringResource(R.string.trsg_edit))
                }
                TextButton(onClick = { onSave(title) }, enabled = s.routine.items.isNotEmpty(),
                    modifier = Modifier.testTag("today_suggestion_save")) {
                    Text(stringResource(R.string.trsg_save))
                }
            }
        }
    }
}

// ── Safety cards ────────────────────────────────────────────────────

@Composable
private fun RedsCard(signals: List<RedsSignal>) {
    Card(Modifier.fillMaxWidth().testTag("today_reds")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = CruxCoachDesign.colors.caution)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_reds_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trt_reds_title), stringResource(R.string.trt_reds_info))
            }
            signals.filter { it != RedsSignal.LOSS_GOAL_PAUSED }.forEach { s ->
                Text("• " + stringResource(when (s) {
                    RedsSignal.LOW_BMI -> R.string.trt_reds_low_bmi
                    RedsSignal.RAPID_LOSS -> R.string.trt_reds_rapid_loss
                    RedsSignal.LOW_CARBS_ON_TRAINING_DAYS -> R.string.trt_reds_low_carbs
                    RedsSignal.LOSS_GOAL_PAUSED -> R.string.trt_reds_goal_paused
                }), style = MaterialTheme.typography.bodyMedium)
            }
            if (RedsSignal.LOSS_GOAL_PAUSED in signals) {
                Text(stringResource(R.string.trt_reds_goal_paused), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

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

// ── Body and fueling summaries ──────────────────────────────────────

@Composable
private fun BodyCard(state: TodayState, onOpen: () -> Unit, onLogWeight: (Double) -> Unit) {
    val units = state.profile.units
    var input by rememberSaveable { mutableStateOf("") }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("today_body")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tr_nav_body), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val rate = state.weeklyRateKg
                if (rate != null) {
                    val icon = when {
                        abs(rate) < 0.1 -> Icons.AutoMirrored.Filled.TrendingFlat
                        rate > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                        else -> Icons.AutoMirrored.Filled.TrendingDown
                    }
                    Icon(icon, contentDescription = stringResource(R.string.trt_trend_direction))
                }
            }
            val trend = state.trendKg
            if (trend != null && !state.profile.hideBodyNumbers) {
                Text(stringResource(R.string.trt_trend_weight, formatMass(trend, units)), style = MaterialTheme.typography.bodyLarge)
                state.weeklyRateKg?.let { Text(stringResource(R.string.trt_trend_rate, formatMass(it, units, signed = true)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (trend != null) {
                Text(stringResource(R.string.trt_numbers_hidden), style = MaterialTheme.typography.bodySmall)
            }
            if (state.lastWeighDay != state.today?.toString()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        label = { Text(stringResource(R.string.trt_weight_today, Units.massUnit(units))) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("today_weight_input"),
                    )
                    Spacer(Modifier.width(8.dp))
                    val parsed = parseDecimal(input)?.let { Units.massFromDisplay(it, units) }
                    FilledTonalButton(
                        onClick = { parsed?.let(onLogWeight); input = "" },
                        enabled = parsed != null && parsed in 20.0..300.0,
                        modifier = Modifier.testTag("today_weight_save"),
                    ) { Text(stringResource(R.string.tr_action_save)) }
                }
            }
        }
    }
}

@Composable
private fun FuelCard(state: TodayState, onOpen: () -> Unit, onAddWater: (Int) -> Unit) {
    val t = state.fuelTargets
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("today_fuel")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tr_nav_fuel), style = MaterialTheme.typography.titleMedium)
            if (t == null) {
                Text(stringResource(R.string.trt_fuel_needs_weight), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Progress(stringResource(R.string.trt_protein), state.proteinToday, t.proteinG.toDouble(), "g")
            Progress(stringResource(R.string.trt_carbs_for, dayLoadLabel(t.dayLoad)), state.carbsToday, t.carbsG.toDouble(), "g")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Stored in ml; fl oz in US units.
                val volume = FuelUnits.unitFor(state.profile.units, drink = true)
                val glass = FuelUnits.waterPresetsMl(state.profile.units).first()
                Progress(stringResource(R.string.trt_water), FuelUnits.fromBase(state.waterTodayMl.toDouble(), volume),
                    FuelUnits.fromBase(t.waterMl.toDouble(), volume), unitLabel(volume), Modifier.weight(1f))
                FilledTonalButton(onClick = { onAddWater(glass) }, modifier = Modifier.padding(start = 8.dp).testTag("today_water_add")) {
                    Text(stringResource(R.string.trf_water_add, amountText(glass.toDouble(), volume)))
                }
            }
        }
    }
}

@Composable
private fun Progress(label: String, value: Double, target: Double, unit: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} / ${target.roundToInt()} $unit", style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            // Reaching or passing a target is never shown as a warning: there is no "over budget".
            color = CruxCoachDesign.colors.positive,
        )
    }
}
