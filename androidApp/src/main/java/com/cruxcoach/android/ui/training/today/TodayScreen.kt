package com.cruxcoach.android.ui.training.today

import kotlinx.datetime.todayIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
    var showWhy by rememberSaveable { mutableStateOf(false) }
    // After a finished training the hero says so; another session only on request.
    var moreToday by rememberSaveable(state.today?.toString()) { mutableStateOf(false) }
    var showWeight by rememberSaveable { mutableStateOf(false) }
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
            // One glance: where the week stands, what needs attention, then today's training.
            item { WeekHeader(state) }
            state.openPause?.let { item(key = "pause") { PauseBanner(onEndPause = viewModel::endPause) } }
            if (state.injuries.isNotEmpty()) item(key = "injury") { InjuryBanner(state, onOpenInjuries) }
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
                    )
                }
                state.durationHint?.let { minutes ->
                    item(key = "duration_hint") { DurationHintCard(minutes, onApply = viewModel::applyDurationHint) }
                }
            }
            item(key = "checkin") {
                CheckinCard(state, onSave = viewModel::saveCheckin, onClear = viewModel::clearCheckin)
            }
            item(key = "quick") {
                QuickActions(
                    state = state,
                    onLogClimbing = onLogClimbing,
                    onLogWeight = { showWeight = true },
                    onAddWater = { viewModel.addWater(250) },
                    onOpenFuel = onOpenFuel,
                    onStartEmpty = viewModel::startEmptyWorkout,
                    onOpenRoutines = onOpenRoutines,
                )
            }
            item(key = "coach_card") { coachCard() }
            // Setup hints one at a time: the next useful step, not a to-do list.
            state.suggestions.firstOrNull()?.let { s ->
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
            val doneSomething = state.todaysWorkouts.isNotEmpty() ||
                state.activity?.let { it.climbingMinutes > 0 || it.climbingEfforts > 0 } == true
            if (doneSomething) item(key = "done_today") { DoneTodayCard(state, onOpenHistory) }
            if (state.profile.bodyEnabled || state.profile.fuelEnabled) {
                item(key = "tiles") { SummaryTiles(state, onOpenBody, onOpenFuel) }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpenWeeklyReview, label = { Text(stringResource(R.string.trt_weekly_review)) },
                        leadingIcon = { Icon(Icons.Default.Insights, null) }, modifier = Modifier.testTag("today_weekly_review"))
                    AssistChip(onClick = onOpenExercises, label = { Text(stringResource(R.string.tr_nav_exercises)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }, modifier = Modifier.testTag("today_exercises"))
                    if (state.injuries.isEmpty()) {
                        AssistChip(onClick = onOpenInjuries, label = { Text(stringResource(R.string.trt_report_injury)) },
                            leadingIcon = { Icon(Icons.Default.Healing, null) }, modifier = Modifier.testTag("today_report_injury"))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showWhy) WhySheet(state, onDismiss = { showWhy = false })
    if (showWeight) WeightDialog(state, onDismiss = { showWeight = false }, onSave = { kg -> viewModel.logWeight(kg); showWeight = false })
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
 * The check-in as one line: a prompt until answered (it opens in place),
 * afterwards the day's verdict with the answers in a few characters.
 */
@Composable
private fun CheckinCard(
    state: TodayState,
    onSave: (Int?, Int?, Int?, Int?, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val checkin = state.checkin
    var expanded by rememberSaveable(checkin?.createdAt) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("today_readiness")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            when {
                expanded -> {
                    CheckinForm(checkin?.sleep, checkin?.energy, checkin?.skin, checkin?.fingers, checkin?.sick ?: false) { s, e, sk, f, sick ->
                        onSave(s, e, sk, f, sick); expanded = false
                    }
                    Row {
                        TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.tr_action_cancel)) }
                        if (checkin != null) TextButton(onClick = { onClear(); expanded = false }) { Text(stringResource(R.string.trt_checkin_reset)) }
                    }
                }
                checkin == null && state.profile.checkinEnabled -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true }.testTag("today_checkin_open"),
                ) {
                    Icon(Icons.Default.Mood, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.trt_checkin_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.trt_checkin_prompt_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.trt_checkin_title))
                }
                else -> ReadinessResult(state, onEdit = if (state.profile.checkinEnabled) ({ expanded = true }) else null)
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

// ── Quick actions and today's log ───────────────────────────────────

/** Everything logged often, always in the same place. */
@Composable
private fun QuickActions(
    state: TodayState,
    onLogClimbing: () -> Unit,
    onLogWeight: () -> Unit,
    onAddWater: () -> Unit,
    onOpenFuel: () -> Unit,
    onStartEmpty: () -> Unit,
    onOpenRoutines: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("today_quick"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(Icons.Default.Terrain, stringResource(R.string.trt_quick_climbing), "today_log_climbing", onLogClimbing)
        if (state.profile.bodyEnabled) QuickAction(Icons.Default.MonitorWeight, stringResource(R.string.trt_quick_weight), "today_weight", onLogWeight)
        if (state.profile.fuelEnabled) {
            QuickAction(Icons.Default.WaterDrop, stringResource(R.string.trt_water_add), "today_water_add", onAddWater)
            QuickAction(Icons.Default.Restaurant, stringResource(R.string.trt_quick_food), "today_food", onOpenFuel)
        }
        if (state.openWorkout == null) QuickAction(Icons.Default.Add, stringResource(R.string.trt_quick_free), "today_start_empty", onStartEmpty)
        QuickAction(Icons.AutoMirrored.Filled.List, stringResource(R.string.trt_routines), "today_routines", onOpenRoutines)
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

/** What is already done today — only shown once there is something. */
@Composable
private fun DoneTodayCard(state: TodayState, onOpenHistory: () -> Unit) {
    val a = state.activity
    val climbed = a != null && (a.climbingMinutes > 0 || a.climbingEfforts > 0)
    if (!climbed && state.todaysWorkouts.isEmpty()) return
    OutlinedCard(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth().testTag("today_train")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trt_done_today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(dayLoadLabel(state.dayLoad), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (climbed && a != null) {
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
    SuggestionReason.RETURN_AFTER_BREAK -> R.string.tre_reason_return_break
    SuggestionReason.RETURN_FINGER -> R.string.tre_reason_return_finger
    SuggestionReason.WEEKLY_TARGET -> R.string.trv_reason_weekly
})

/**
 * Today's training as the one big thing on the screen: what, how long, the
 * first exercises and one big button. Reasons, evidence and confidence sit
 * behind "Warum?"; another suggestion, adapt and save are small actions.
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
        Column(Modifier.padding(16.dp)) {
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
                Text(suggestionReasonText(it), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    modifier = Modifier.padding(top = 8.dp).testTag("today_suggestion_reason"))
            }
            if (s.confidence == com.cruxcoach.athlete.logic.Confidence.LOW) {
                Text(stringResource(R.string.trt_low_data), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onWhy).testTag("today_suggestion_confidence"))
            }
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
                TextButton(onClick = onNext, modifier = Modifier.testTag("today_suggestion_next")) { Text(stringResource(R.string.trsg_next)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onEdit(title) }, enabled = s.routine.items.isNotEmpty(), modifier = Modifier.testTag("today_suggestion_edit")) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trsg_edit))
                }
                IconButton(onClick = onWhy, modifier = Modifier.testTag("today_suggestion_why")) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.trsg_why_title))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("today_suggestion_more")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trt_more_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
            state.block?.let {
                Text(blockLabel(it), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.tre_block_info_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeightDialog(state: TodayState, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    val units = state.profile.units
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = parseDecimal(input)?.let { Units.massFromDisplay(it, units) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trt_weight_today, Units.massUnit(units))) },
        text = {
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = state.trendKg?.takeIf { !state.profile.hideBodyNumbers }?.let { t ->
                    { Text(stringResource(R.string.trt_trend_weight, formatMass(t, units))) }
                },
                modifier = Modifier.testTag("today_weight_input"))
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed in 20.0..300.0,
                modifier = Modifier.testTag("today_weight_save")) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
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

/** Body and fueling as two small tiles; the screens behind them hold the details. */
@Composable
private fun SummaryTiles(state: TodayState, onOpenBody: () -> Unit, onOpenFuel: () -> Unit) {
    val units = state.profile.units
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.profile.bodyEnabled) {
            Card(onClick = onOpenBody, modifier = Modifier.weight(1f).testTag("today_body")) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tr_nav_body), style = MaterialTheme.typography.labelLarge)
                    val trend = state.trendKg
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                trend == null -> "–"
                                state.profile.hideBodyNumbers -> stringResource(R.string.trt_numbers_hidden_short)
                                else -> formatMass(trend, units)
                            },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        state.weeklyRateKg?.let { rate ->
                            Spacer(Modifier.width(6.dp))
                            Icon(when {
                                abs(rate) < 0.1 -> Icons.AutoMirrored.Filled.TrendingFlat
                                rate > 0 -> Icons.AutoMirrored.Filled.TrendingUp
                                else -> Icons.AutoMirrored.Filled.TrendingDown
                            }, contentDescription = stringResource(R.string.trt_trend_direction), modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(stringResource(R.string.trt_tile_trend), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.profile.fuelEnabled) {
            val t = state.fuelTargets
            Card(onClick = onOpenFuel, modifier = Modifier.weight(1f).testTag("today_fuel")) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.tr_nav_fuel), style = MaterialTheme.typography.labelLarge)
                    if (t == null) {
                        Text(stringResource(R.string.trt_fuel_needs_weight), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${state.proteinToday.roundToInt()} / ${t.proteinG} g", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.trt_protein), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(
                            progress = { if (t.proteinG > 0) (state.proteinToday / t.proteinG).toFloat().coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = CruxCoachDesign.colors.positive,
                        )
                    }
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

