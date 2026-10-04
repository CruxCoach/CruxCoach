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
            _state.update {
                it.copy(
                    loading = false, workout = workout, blocks = blocks, catalog = catalog,
                    summary = if (workout != null) service.summarize(workoutId) else null,
                    units = repo.profile().units,
                )
            }
        }
    }

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
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.trw_summary_saved), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        workoutTitle(workout) + " · " + formatDay(workout.day),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.trw_summary_sets), summary.completedSets.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.trw_summary_exercises), summary.exercises.toString(), Modifier.weight(1f))
                    StatTile(
                        stringResource(R.string.trw_summary_duration),
                        summary.durationMinutes?.let { pluralStringResource(R.plurals.trw_minutes, it, it) } ?: "–",
                        Modifier.weight(1f),
                    )
                }
            }
            if (summary.records.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trw_records_title)) }
                summary.records.forEach { (slug, record) ->
                    item(key = "pr_$slug") {
                        val def = state.catalog.fallbackFor(slug)
                        ListItem(
                            headlineContent = { Text(def.name(language)) },
                            supportingContent = { Text(recordValueText(resources, def, record, state.units)) },
                            leadingContent = { Icon(Icons.Default.EmojiEvents, null, tint = CruxCoachDesign.colors.brandAccent) },
                            modifier = Modifier.testTag("summary_record_$slug"),
                        )
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
