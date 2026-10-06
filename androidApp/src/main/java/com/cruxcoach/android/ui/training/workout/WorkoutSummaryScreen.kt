package com.cruxcoach.android.ui.training.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ProgressionSuggestion
import kotlinx.datetime.minus
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.logic.TrainingBlocks
import com.cruxcoach.athlete.logic.TrendKind
import com.cruxcoach.athlete.logic.ProgressionTrend
import com.cruxcoach.athlete.logic.ProgressionAdvisor
import com.cruxcoach.athlete.logic.ProgressionVerdict
import com.cruxcoach.athlete.logic.WorkoutSummary
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.athlete.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

data class SummaryState(
    val loading: Boolean = true,
    val workout: Workout? = null,
    val blocks: List<Pair<ExerciseDefinition, List<ExerciseSet>>> = emptyList(),
    val summary: WorkoutSummary? = null,
    val units: UnitSystem = UnitSystem.METRIC,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val routineSaved: Boolean = false,
    /** Patterns across sessions per exercise (FEAT-071): stall, decline, twice too easy/hard. */
    val trends: List<Pair<String, ProgressionTrend>> = emptyList(),
    /** Own workouts containing each exercise, for the swap prompt. */
    val routineCounts: Map<String, Int> = emptyMap(),
    /** One-shot message: swapped in n workouts (≥ 0), deload started (-1). */
    val message: Int? = null,
    /** Total load moved in weighted rep work (kg × reps) and time under load on holds, for the reward header. */
    val volumeKg: Double = 0.0,
    val hangSeconds: Int = 0,
    val streak: com.cruxcoach.athlete.logic.StreakState? = null,
)

@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state.asStateFlow()

    fun load(workoutId: String) {
        if (_state.value.workout?.id == workoutId) return
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val workout = repo.workout(workoutId)
            val catalog = service.catalog
            val sets = repo.setsFor(workoutId).filter { it.isCompleted }
            val blocks = sets.groupBy { it.blockIndex }.toSortedMap().values.map { rows ->
                catalog.fallbackFor(rows.first().exerciseSlug) to rows.sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
            }
            val profile = repo.profile()
            val routines = repo.routines()
            val slugs = blocks.map { it.first.slug }.distinct()
            val trends = slugs.mapNotNull { slug ->
                val def = catalog[slug] ?: return@mapNotNull null
                // History is newest first; one list per training.
                val sessions = repo.history(slug, 200).filter { it.isCompleted }
                    .groupBy { it.workoutId }.values.take(6).map { it.toList() }
                val trend = ProgressionAdvisor.trend(def, sessions, profile.smallestIncrementKg)
                trend.takeIf { it.kind != TrendKind.NONE && it.kind != TrendKind.PROGRESSING }?.let { slug to it }
            }
            val work = sets.filter { it.setType == SetType.WORK }
            val volume = work.sumOf { s ->
                val def = catalog.fallbackFor(s.exerciseSlug)
                val loaded = def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS
                if ((def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.LOAD_REPS) && loaded)
                    (com.cruxcoach.athlete.logic.StrengthMath.effectiveLoad(def.load, s.loadKg, s.bodyweightKg) ?: 0.0) * (s.reps ?: 0)
                else 0.0
            }
            val hang = work.sumOf { s ->
                val def = catalog.fallbackFor(s.exerciseSlug)
                when (def.kind) {
                    ExerciseKind.HANG -> s.durationS ?: 0.0
                    ExerciseKind.INTERVAL -> (s.workS ?: def.defaults.workS?.toDouble() ?: 7.0) * (s.repsPerSet ?: def.defaults.repsPerSet ?: 6)
                    else -> 0.0
                }
            }
            val streak = runCatching { service.streak(profile, service.activities(7 * 26)) }.getOrNull()
            _state.update {
                it.copy(
                    loading = false, workout = workout, blocks = blocks, catalog = catalog,
                    volumeKg = volume, hangSeconds = hang.roundToInt(), streak = streak,
                    summary = if (workout != null) service.summarize(workoutId) else null,
                    units = profile.units,
                    trends = trends,
                    routineCounts = slugs.associateWith { s -> routines.count { r -> r.items.any { i -> i.slug == s } } },
                )
            }
            if (workout != null) logCompletion(workout, sets)
        }
    }

    /**
     * A training started from today's suggestion tells the coach how much of
     * it was done (the player logs this on finish; this covers the list view).
     */
    private fun logCompletion(workout: Workout, completed: List<ExerciseSet>) {
        if (workout.routineId?.startsWith("suggestion") != true) return
        val events = service.repo.suggestionEventsForDay(workout.day)
        if (events.any { it.kind == SuggestionEventKind.COMPLETED && it.workoutId == workout.id }) return
        val started = events.firstOrNull { it.kind == SuggestionEventKind.STARTED && it.workoutId == workout.id }
        val done = completed.count { it.setType == SetType.WORK }
        val planned = started?.value?.takeIf { it > 0 } ?: done.toDouble()
        val share = if (planned > 0) (done / planned).coerceIn(0.0, 1.0) else 0.0
        service.logSuggestion(SuggestionEventKind.COMPLETED, started?.focus, completed.map { it.exerciseSlug }.distinct(),
            workoutId = workout.id, value = share)
        workout.sessionRpe?.let { rpe ->
            service.logSuggestion(SuggestionEventKind.FEEDBACK, "session_rpe", emptyList(), workoutId = workout.id, value = rpe.toDouble())
        }
    }

    /** Replaces [from] with [to] in every own workout that contains it, keeping sets, rest and sides. */
    fun swapInRoutines(from: String, to: String) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val target = service.catalog[to] ?: return@launch
            var count = 0
            repo.routines().filter { r -> r.items.any { it.slug == from } }.forEach { r ->
                val items = r.items.map { item ->
                    if (item.slug != from) item
                    else WorkoutPlanner.itemFor(target).copy(sets = item.sets, restS = item.restS, sides = item.sides, warmup = item.warmup)
                }
                repo.saveRoutine(r.copy(items = items, updatedAt = System.currentTimeMillis()))
                count++
            }
            service.logSuggestion(SuggestionEventKind.SWAPPED, null, listOf(from, to))
            _state.update { s -> s.copy(message = count, routineCounts = s.routineCounts - from) }
        }
    }

    /** Starts a lighter week now: the training block jumps to its deload week. */
    fun startDeload() {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val start = service.today().minus(kotlinx.datetime.DatePeriod(days = 7 * (TrainingBlocks.INTRO_WEEKS + TrainingBlocks.BUILD_WEEKS)))
            service.repo.updateProfile { p -> p.copy(coach = p.coach.copy(blockStartDay = start.toString())) }
            _state.update { it.copy(message = -1) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun saveRoutine(name: String) {
        val id = _state.value.workout?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.saveRoutineFromWorkout(id, name.trim())
            _state.update { it.copy(routineSaved = true) }
        }
    }
}

@Composable
fun WorkoutSummaryScreen(
    workoutId: String,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    viewModel: WorkoutSummaryViewModel = hiltViewModel(),
) {
    LaunchedEffect(workoutId) { viewModel.load(workoutId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var askName by rememberSaveable { mutableStateOf(false) }
    val savedText = stringResource(R.string.trw_routine_saved)
    LaunchedEffect(state.routineSaved) { if (state.routineSaved) snackbar.showSnackbar(savedText) }
    val swapText = stringResource(R.string.tre_swap_done, state.message ?: 0)
    val deloadText = stringResource(R.string.tre_deload_started)
    val snackScope = rememberCoroutineScope()
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        // Shown outside the effect so consuming the message does not cancel it.
        snackScope.launch { snackbar.showSnackbar(if (m < 0) deloadText else swapText) }
    }
    var confirmSwap by remember { mutableStateOf<Pair<String, String>?>(null) }

    TrainingScaffold(
        title = stringResource(R.string.trw_summary_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val workout = state.workout
        val summary = state.summary
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        if (workout == null || summary == null) {
            EmptyHint(stringResource(R.string.trw_no_sets), Modifier.padding(padding))
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("summary_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RewardHeader(workout, summary, state) }
            if (summary.records.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trw_records_title)) }
                summary.records.forEach { (slug, record) ->
                    item(key = "pr_$slug") {
                        val def = state.catalog.fallbackFor(slug)
                        val now = recordValueText(resources, def, record, state.units)
                        val before = if (record.previous > 0) recordValueText(resources, def, record.copy(value = record.previous), state.units) else null
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.positiveContainer,
                                contentColor = CruxCoachDesign.colors.onPositiveContainer),
                            modifier = Modifier.fillMaxWidth().testTag("summary_record_$slug"),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.EmojiEvents, null, tint = CruxCoachDesign.colors.brandAccent, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(def.name(language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (before != null) stringResource(R.string.trp2_record_from_to, before, now)
                                        else stringResource(R.string.trp2_record_first, now),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (summary.suggestions.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trw_suggestions_title)) }
                summary.suggestions.forEach { (slug, suggestion) ->
                    item(key = "sugg_$slug") {
                        SuggestionRow(state.catalog.fallbackFor(slug), suggestion, state, language, onOpenExercise)
                    }
                }
            }
            if (state.trends.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.tre_trend_title)) }
                state.trends.forEach { (slug, trend) ->
                    item(key = "trend_$slug") {
                        TrendRow(state.catalog.fallbackFor(slug), trend, state, language, onOpenExercise,
                            onSwap = { to -> confirmSwap = slug to to }, onDeload = viewModel::startDeload)
                    }
                }
            }
            sideBalance(summary)?.let { (left, right) ->
                item {
                    Column(Modifier.testTag("summary_side_balance")) {
                        SectionTitle(stringResource(R.string.trw_side_title))
                        Text(stringResource(R.string.trw_side_values, left, right), style = MaterialTheme.typography.bodyMedium)
                        if (abs(left - right) > 10) {
                            val weaker = sideLabel(if (left < right) Side.LEFT else Side.RIGHT)
                            Text(stringResource(R.string.trw_side_diff, weaker, abs(left - right)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.trw_sets_title)) }
            if (state.blocks.isEmpty()) item { EmptyHint(stringResource(R.string.trw_no_sets)) }
            state.blocks.forEachIndexed { i, (def, sets) ->
                item(key = "block_$i") {
                    Card(onClick = { onOpenExercise(def.slug) }, modifier = Modifier.fillMaxWidth().testTag("summary_block_$i")) {
                        Column(Modifier.padding(12.dp)) {
                            Text(def.name(language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            sets.forEach { s -> Text(setLine(def, s, state.units), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { askName = true }, modifier = Modifier.fillMaxWidth().testTag("summary_save_routine"),
                    enabled = !state.routineSaved && state.blocks.isNotEmpty()) {
                    Icon(Icons.Default.BookmarkAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trw_save_routine))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    confirmSwap?.let { (from, to) ->
        val count = state.routineCounts[from] ?: 0
        AlertDialog(
            onDismissRequest = { confirmSwap = null },
            title = { Text(stringResource(R.string.tre_swap_confirm_title)) },
            text = {
                Text(if (count > 0) stringResource(R.string.tre_swap_confirm_text, state.catalog.fallbackFor(to).name(language),
                    state.catalog.fallbackFor(from).name(language), count) else stringResource(R.string.tre_swap_none))
            },
            confirmButton = {
                if (count > 0) TextButton(onClick = { confirmSwap = null; viewModel.swapInRoutines(from, to) },
                    modifier = Modifier.testTag("summary_swap_confirm")) { Text(stringResource(R.string.tre_swap_action)) }
                else TextButton(onClick = { confirmSwap = null; onOpenExercise(to) }) { Text(stringResource(R.string.tre_menu_details)) }
            },
            dismissButton = { TextButton(onClick = { confirmSwap = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }

    if (askName) {
        val default = state.workout?.let { workoutTitle(it) }.orEmpty()
        var name by rememberSaveable { mutableStateOf(default) }
        AlertDialog(
            onDismissRequest = { askName = false },
            title = { Text(stringResource(R.string.trw_save_routine)) },
            text = {
                OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.trw_routine_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("summary_routine_name"))
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { askName = false; viewModel.saveRoutine(name) }) {
                    Text(stringResource(R.string.tr_action_save))
                }
            },
            dismissButton = { TextButton(onClick = { askName = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun SuggestionRow(
    def: ExerciseDefinition,
    s: ProgressionSuggestion,
    state: SummaryState,
    language: String,
    onOpenExercise: (String) -> Unit,
) {
    val signed = def.load == LoadMode.BODYWEIGHT_PLUS
    Card(Modifier.fillMaxWidth().testTag("summary_suggestion_${def.slug}")) {
        Column(Modifier.padding(12.dp)) {
            Text(def.name(language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (s.verdict == ProgressionVerdict.TOO_EASY) R.string.trw_too_easy else R.string.trw_too_hard),
                style = MaterialTheme.typography.bodySmall)
            s.nextLoadKg?.let { next ->
                val text = if (signed && next == 0.0) stringResource(R.string.tr_bodyweight)
                    else if (signed && next < 0) stringResource(R.string.trw_load_assisted, formatMass(-next, state.units))
                    else formatMass(next, state.units, signed = signed)
                Text(stringResource(if (s.verdict == ProgressionVerdict.TOO_EASY) R.string.trw_sugg_more_load else R.string.trw_sugg_less_load, text),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            val variant = if (s.verdict == ProgressionVerdict.TOO_EASY) s.harderSlug else s.easierSlug
            variant?.let { slug ->
                val other = state.catalog.fallbackFor(slug)
                TextButton(onClick = { onOpenExercise(slug) }) {
                    Text(stringResource(if (s.verdict == ProgressionVerdict.TOO_EASY) R.string.trw_sugg_try else R.string.trw_sugg_easier_variant,
                        other.name(language)))
                }
            }
        }
    }
}

/** Patterns across sessions: stall → variation, decline → lighter week, twice too easy/hard → chain partner. */
@Composable
private fun TrendRow(
    def: ExerciseDefinition,
    t: ProgressionTrend,
    state: SummaryState,
    language: String,
    onOpenExercise: (String) -> Unit,
    onSwap: (String) -> Unit,
    onDeload: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("summary_trend_${def.slug}")) {
        Column(Modifier.padding(12.dp)) {
            Text(def.name(language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when (t.kind) {
                TrendKind.STALL -> {
                    Text(stringResource(R.string.tre_trend_stall, t.sessions.coerceAtMost(6)), style = MaterialTheme.typography.bodySmall)
                    val variation = t.variationSlug
                    if (variation != null) {
                        TextButton(onClick = { onOpenExercise(variation) }) {
                            Text(stringResource(R.string.tre_trend_stall_try, state.catalog.fallbackFor(variation).name(language)))
                        }
                    } else {
                        Text(stringResource(R.string.tre_trend_stall_generic), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TrendKind.DECLINE -> {
                    Text(stringResource(R.string.tre_trend_decline), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDeload, modifier = Modifier.testTag("summary_deload")) { Text(stringResource(R.string.tre_deload_action)) }
                }
                TrendKind.TOO_EASY_TWICE -> {
                    Text(stringResource(R.string.tre_trend_easy), style = MaterialTheme.typography.bodySmall)
                    t.harderSlug?.let { to ->
                        TextButton(onClick = { onSwap(to) }, modifier = Modifier.testTag("summary_swap_${def.slug}")) {
                            Text(stringResource(R.string.tre_swap_harder, state.catalog.fallbackFor(to).name(language)))
                        }
                    }
                }
                TrendKind.TOO_HARD_TWICE -> {
                    Text(stringResource(R.string.tre_trend_hard), style = MaterialTheme.typography.bodySmall)
                    t.easierSlug?.let { to ->
                        TextButton(onClick = { onSwap(to) }, modifier = Modifier.testTag("summary_swap_${def.slug}")) {
                            Text(stringResource(R.string.tre_swap_easier, state.catalog.fallbackFor(to).name(language)))
                        }
                    }
                }
                TrendKind.NONE, TrendKind.PROGRESSING -> Unit
            }
        }
    }
}

/** Left/right as % of the stronger side, or null without both sides. */
private fun sideBalance(summary: WorkoutSummary): Pair<Int, Int>? {
    val left = summary.sideLoad[Side.LEFT] ?: return null
    val right = summary.sideLoad[Side.RIGHT] ?: return null
    val max = maxOf(left, right).takeIf { it > 0 } ?: return null
    return (left / max * 100).roundToInt() to (right / max * 100).roundToInt()
}

@Composable
private fun setLine(def: ExerciseDefinition, s: ExerciseSet, units: UnitSystem): String {
    val parts = mutableListOf<String>()
    parts += when (s.setType) {
        SetType.WARMUP -> setTypeLabel(SetType.WARMUP)
        SetType.TEST -> setTypeLabel(SetType.TEST)
        SetType.WORK -> stringResource(R.string.trw_set_cd, s.setIndex + 1)
    }
    s.side?.let { parts += sideLabel(it) }
    when (def.kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.CLIMB ->
            s.reps?.let { parts += pluralStringResource(R.plurals.trw_reps, it, it) }
        ExerciseKind.TIME, ExerciseKind.HANG ->
            s.durationS?.let { parts += stringResource(R.string.tr_format_seconds, it.roundToInt()) }
        ExerciseKind.INTERVAL -> parts += stringResource(R.string.trw_interval_format,
            (s.workS ?: 0.0).roundToInt(), (s.restBetweenS ?: 0.0).roundToInt(), s.repsPerSet ?: 0)
    }
    if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
        s.loadKg?.let { load ->
            parts += when {
                def.load == LoadMode.BODYWEIGHT_PLUS && load == 0.0 -> stringResource(R.string.tr_bodyweight)
                def.load == LoadMode.BODYWEIGHT_PLUS && load < 0 -> stringResource(R.string.trw_load_assisted, formatMass(-load, units))
                else -> formatMass(load, units, signed = def.load == LoadMode.BODYWEIGHT_PLUS)
            }
        }
    }
    s.edgeMm?.let { parts += stringResource(R.string.tr_format_mm, formatNumber(it)) }
    s.grip?.let { parts += gripLabel(it) }
    s.rir?.let { parts += stringResource(R.string.trw_rir_short, it) }
    s.note?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}

internal fun formatDay(day: String): String = runCatching {
    java.time.LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(day)

/**
 * The end of a training as a small celebration (MCI-style reward screen):
 * a check that pops in, "done", the key numbers, and where the week stands.
 */
@Composable
private fun RewardHeader(workout: Workout, summary: WorkoutSummary, state: SummaryState) {
    val scale = remember { androidx.compose.animation.core.Animatable(0.4f) }
    LaunchedEffect(workout.id) {
        scale.animateTo(1f, androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
        ))
    }
    Column(Modifier.fillMaxWidth().testTag("summary_reward"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive,
            modifier = Modifier.size(88.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        Text(stringResource(R.string.trp2_reward_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp))
        Text(workoutTitle(workout) + " · " + formatDay(workout.day), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.trw_summary_duration),
                summary.durationMinutes?.let { pluralStringResource(R.plurals.trw_minutes, it, it) } ?: "–",
                Modifier.weight(1f),
            )
            StatTile(stringResource(R.string.trw_summary_sets), summary.completedSets.toString(), Modifier.weight(1f))
            when {
                state.volumeKg > 0 -> StatTile(stringResource(R.string.trp2_tile_volume),
                    formatMass(state.volumeKg, state.units), Modifier.weight(1f))
                state.hangSeconds > 0 -> StatTile(stringResource(R.string.trp2_tile_hang),
                    formatClock(state.hangSeconds), Modifier.weight(1f))
                else -> StatTile(stringResource(R.string.trw_summary_exercises), summary.exercises.toString(), Modifier.weight(1f))
            }
        }
        state.streak?.let { st ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth().testTag("summary_week")) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (st.currentWeekDone) stringResource(R.string.trp2_week_goal_reached)
                        else stringResource(R.string.trp2_week_goal, st.currentWeekDays, st.goal),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LinearProgressIndicator(
                        progress = { if (st.goal > 0) (st.currentWeekDays.toFloat() / st.goal).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = CruxCoachDesign.colors.positive,
                    )
                    if (st.weeks > 0) {
                        Text(pluralStringResource(R.plurals.trp2_streak_weeks, st.weeks, st.weeks), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
