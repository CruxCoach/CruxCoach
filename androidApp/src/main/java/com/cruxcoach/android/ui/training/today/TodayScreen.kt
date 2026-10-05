package com.cruxcoach.android.ui.training.today

import kotlinx.datetime.todayIn
import kotlinx.coroutines.launch
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
import com.cruxcoach.athlete.logic.BuiltinRoutines
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
    /** "Ausrüstung fehlt" from the another-suggestion sheet. */
    onOpenEquipment: () -> Unit = {},
    /** Board day: the playlist generator preset with a [com.cruxcoach.domain.playlist.GeneratorType] name and minutes. */
    onOpenPlaylistGenerator: (type: String, minutes: Int) -> Unit = { _, _ -> },
    /** Integration slot right under the week strip (coach setup progress). */
    coachCard: @Composable () -> Unit = {},
    /** "Klettertag eintragen": climbing outside the board app. */
    onLogClimbing: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askWhy by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.trsg_saved)
    val snackScope = rememberCoroutineScope()
    LaunchedEffect(state.suggestionSaved) {
        // Shown outside this effect: consuming the flag restarts the effect and would cancel it.
        if (state.suggestionSaved) { viewModel.consumeSuggestionSaved(); snackScope.launch { snackbar.showSnackbar(savedText) } }
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
            state.block?.let { block -> item(key = "block_chip") { BlockChip(block) } }
            item(key = "coach_card") { coachCard() }
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
                        onNext = { askWhy = true },
                        onEdit = { title -> viewModel.editTarget(title).let { (id, from) -> onOpenEditor(id, from) } },
                        onSave = viewModel::saveSuggestion,
                        onOpenPlaylistGenerator = onOpenPlaylistGenerator,
                    )
                }
                state.durationHint?.let { minutes ->
                    item(key = "duration_hint") { DurationHintCard(minutes, onApply = viewModel::applyDurationHint) }
                }
            }
            if (state.injuries.isNotEmpty()) item { InjuryCard(state, onOpenInjuries, onStart = { viewModel.startRoutine(BuiltinRoutines.INJURY_ONE_ARM) }) }
            item {
                TrainCard(state, onStartEmpty = viewModel::startEmptyWorkout, onOpenRoutines = onOpenRoutines,
                    onOpenExercises = onOpenExercises, onOpenHistory = onOpenHistory, onLogClimbing = onLogClimbing)
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
            if (state.profile.fuelEnabled) item { FuelCard(state, onOpenFuel, viewModel::addWater) }
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

    if (askWhy) {
        NextSuggestionSheet(
            state = state,
            onDismiss = { askWhy = false },
            onChoose = { feedback, slug ->
                askWhy = false
                viewModel.nextSuggestion(feedback, slug)
                when (feedback) {
                    com.cruxcoach.athlete.model.SuggestionFeedback.MISSING_EQUIPMENT -> onOpenEquipment()
                    com.cruxcoach.athlete.model.SuggestionFeedback.HURTS -> onOpenInjuries()
                    else -> Unit
                }
            },
        )
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
    onLogClimbing: () -> Unit = {},
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
                TextButton(onClick = onLogClimbing, modifier = Modifier.testTag("today_log_climbing")) {
                    Text(stringResource(R.string.tre_log_climbing))
                }
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
})

@Composable
private fun DailySuggestionCard(
    state: TodayState,
    onStart: (String) -> Unit,
    onNext: () -> Unit,
    onEdit: (String) -> Unit,
    onSave: (String) -> Unit,
    onOpenPlaylistGenerator: (type: String, minutes: Int) -> Unit = { _, _ -> },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false).testTag("today_suggestion_title"))
                Spacer(Modifier.width(8.dp))
                ConfidenceBadge(s.confidence)
            }
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
            val evidence = s.basedOn.take(4).map { evidenceText(it, state, language) }.filter { it.isNotBlank() }
            if (evidence.isNotEmpty()) {
                Text(stringResource(R.string.tre_based_on, evidence.joinToString(" · ")), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp).testTag("today_suggestion_based_on"))
            }
            s.boardPlan?.let { plan ->
                FilledTonalButton(onClick = { onOpenPlaylistGenerator(plan.type.name, plan.minutes) },
                    modifier = Modifier.padding(top = 8.dp).testTag("today_suggestion_board")) {
                    Icon(Icons.Default.Terrain, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tre_board_create) + " · " + stringResource(R.string.tre_board_plan, generatorLabel(plan.type), plan.minutes))
                }
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
                TextButton(onClick = { onEdit(title) }, enabled = s.routine.items.isNotEmpty(), modifier = Modifier.testTag("today_suggestion_edit")) {
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
                Progress(stringResource(R.string.trt_water), state.waterTodayMl.toDouble(), t.waterMl.toDouble(), "ml",
                    Modifier.weight(1f))
                FilledTonalButton(onClick = { onAddWater(250) }, modifier = Modifier.padding(start = 8.dp).testTag("today_water_add")) {
                    Text(stringResource(R.string.trt_water_add))
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
private fun BlockChip(block: com.cruxcoach.athlete.logic.BlockState) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("today_block")) {
        AssistChip(onClick = {}, label = { Text(blockLabel(block)) }, leadingIcon = { Icon(Icons.Default.DateRange, null) })
        InfoButton(stringResource(R.string.tre_block_info_title), stringResource(R.string.tre_block_info_text))
    }
}

@Composable
private fun ConfidenceBadge(c: com.cruxcoach.athlete.logic.Confidence) {
    val label = stringResource(when (c) {
        com.cruxcoach.athlete.logic.Confidence.LOW -> R.string.tre_conf_low
        com.cruxcoach.athlete.logic.Confidence.MEDIUM -> R.string.tre_conf_medium
        com.cruxcoach.athlete.logic.Confidence.HIGH -> R.string.tre_conf_high
    })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.testTag("today_suggestion_confidence")) { Text(label) }
        InfoButton(stringResource(R.string.tre_conf_info_title), stringResource(R.string.tre_conf_info_text))
    }
}

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

