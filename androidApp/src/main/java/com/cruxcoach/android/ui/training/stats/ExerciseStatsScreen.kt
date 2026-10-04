package com.cruxcoach.android.ui.training.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.android.ui.training.exercises.setSummary
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Side
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import javax.inject.Inject
import kotlin.math.roundToInt

data class ExerciseStatsState(
    val loading: Boolean = true,
    val def: ExerciseDefinition? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val range: StatsRange = StatsRange.SIX_MONTHS,
    val progress: ExerciseProgress? = null,
    /** Current performance value per side, as display value. */
    val levels: Map<Side?, Double> = emptyMap(),
    val bests: List<Pair<PersonalRecords.Key, Pair<RecordKind, Double>>> = emptyList(),
    val setsByWorkout: Map<String, List<ExerciseSet>> = emptyMap(),
)

/** Everything about one exercise over time: capacity, volume, sessions, bests. */
@HiltViewModel
class ExerciseStatsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseStatsState())
    val state: StateFlow<ExerciseStatsState> = _state.asStateFlow()
    private var loaded: String? = null

    fun load(slug: String) {
        if (loaded == slug) return
        loaded = slug
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val def = service.catalog.fallbackFor(slug)
            val bodyweight = service.currentBodyweight()
            val history = repo.history(slug, AthleteService.HISTORY_LIMIT)
            val progress = ProgressStats.exerciseProgress(def, history, TimeZone.currentSystemDefault(), bodyweight)
            val kind = BenchmarkMath.capacityKind(def)
            val levels = repo.benchmarks(slug).groupBy { it.side }.mapNotNull { (side, list) ->
                val newest = list.maxBy { it.measuredAt }
                BenchmarkMath.capacity(def, newest, bodyweight)?.let { cap ->
                    side to ProgressStats.displayValue(def, kind, cap.value, newest.bodyweightKg ?: bodyweight)
                }
            }.toMap()
            _state.update {
                it.copy(
                    loading = false,
                    def = def,
                    profile = repo.profile(),
                    today = service.today(),
                    progress = progress,
                    levels = levels,
                    bests = PersonalRecords.bests(def, history).entries.map { e -> e.key to e.value }
                        .sortedWith(compareBy({ it.first.side?.ordinal ?: -1 }, { it.first.edgeMm ?: 0 })),
                    setsByWorkout = history.filter { s -> s.isCompleted }.groupBy { s -> s.workoutId },
                )
            }
        }
    }

    fun setRange(range: StatsRange) = _state.update { it.copy(range = range) }
}

@Composable
fun ExerciseStatsScreen(
    slug: String,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    viewModel: ExerciseStatsViewModel = hiltViewModel(),
) {
    LaunchedEffect(slug) { viewModel.load(slug) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val lang = catalogLanguage()
    val colors = seriesColors()
    val def = s.def
    TrainingScaffold(title = def?.name(lang) ?: stringResource(R.string.tr_tab_stats), onBack = onBack) { padding ->
        val progress = s.progress
        if (s.loading || def == null || progress == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        val units = s.profile.units
        val kind = progress.kind
        val from = s.today?.minus(DatePeriod(days = s.range.days - 1))
        val sessions = progress.sessions.filter { from == null || it.day >= from }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("exercise_stats_list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                // The top bar carries the name; here only what the number means and a way to the exercise.
                Text(stringResource(R.string.trs_capacity_meaning, capacityLabel(kind)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenExercise(def.slug) }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.testTag("exercise_stats_name")) {
                    Text(stringResource(R.string.trs_open_exercise))
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsRange.entries.forEach { r ->
                        FilterChip(selected = s.range == r, onClick = { viewModel.setRange(r) }, label = { Text(rangeLabel(r)) })
                    }
                }
            }
            if (progress.sessions.isEmpty()) {
                item { EmptyHint(stringResource(R.string.trs_exercise_empty)) }
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.trs_tile_best), progress.best?.let { capacityText(def, kind, it, units) } ?: "–", Modifier.weight(1f))
                    StatTile(stringResource(R.string.trs_tile_last), progress.last?.let { capacityText(def, kind, it, units) } ?: "–", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(stringResource(R.string.trs_tile_change), progress.change28?.let { changeText(def, kind, it, units) } ?: "–", Modifier.weight(1f))
                    StatTile(stringResource(R.string.trs_tile_sessions), "${progress.sessionCount}", Modifier.weight(1f))
                    StatTile(stringResource(R.string.trs_tile_percent_bw), progress.percentBodyweight?.let { "${it.roundToInt()} %" } ?: "–", Modifier.weight(1f))
                }
            }

            // Capacity over time; one line per hand for one-sided work.
            item { SectionTitle(stringResource(R.string.trs_capacity_chart, capacityLabel(kind))) }
            item {
                val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
                val series = sides.mapIndexedNotNull { i, side ->
                    val points = sessions.mapNotNull { p ->
                        (if (side == null) p.bestDisplay else p.display[side])?.let { p.day to it }
                    }
                    if (points.isEmpty()) null else ChartSeries(side?.let { sideLabel(it) } ?: capacityLabel(kind), colors[i % colors.size], points)
                }
                val level = if (!def.unilateral) s.levels[null] else null
                if (series.isEmpty()) {
                    Text(stringResource(R.string.trs_no_value), style = MaterialTheme.typography.bodySmall)
                } else {
                    DateLineChart(series, { capacityText(def, kind, it, units) },
                        stringResource(R.string.trs_capacity_cd, def.name(lang), sessions.size),
                        Modifier.fillMaxWidth().testTag("exercise_stats_chart"), level = level)
                }
                if (s.levels.isNotEmpty()) {
                    // Labels resolved in composition first; joinToString's lambda is not composable.
                    val parts = s.levels.entries.sortedBy { it.key?.ordinal ?: -1 }.map { (side, v) ->
                        (side?.let { sideLabel(it) + ": " } ?: "") + capacityText(def, kind, v, units)
                    }
                    val text = parts.joinToString(" · ")
                    Text(stringResource(R.string.trs_current_value, text), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Volume per session.
            item { SectionTitle(stringResource(R.string.trs_volume_chart)) }
            item {
                val points = sessions.map { it.day to it.volume }.filter { it.second > 0 }
                if (points.isNotEmpty()) {
                    DateLineChart(listOf(ChartSeries(stringResource(R.string.trs_volume_chart), colors[2], points)),
                        { it.roundToInt().toString() }, stringResource(R.string.trs_volume_cd, points.size),
                        Modifier.fillMaxWidth().testTag("exercise_stats_volume"), height = 140.dp)
                }
                Text(stringResource(volumeHint(def)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Personal bests per edge, grip and side.
            if (s.bests.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trs_bests)) }
                items(s.bests, key = { "best-${it.first}" }) { (key, best) ->
                    val label = listOfNotNull(
                        key.side?.let { sideLabel(it) },
                        key.edgeMm?.let { stringResource(R.string.tr_format_mm, it.toString()) },
                        key.grip?.let { gripLabel(it) },
                    ).joinToString(" · ").ifBlank { capacityLabel(kind) }
                    ListItem(headlineContent = { Text(label) }, supportingContent = { Text(recordValue(best.first, best.second, units)) })
                }
            }

            // Sessions, newest first.
            item { SectionTitle(stringResource(R.string.trs_sessions)) }
            items(sessions.reversed().take(30), key = { "session-" + it.workoutId }) { p ->
                val sets = s.setsByWorkout[p.workoutId].orEmpty().sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
                Card(Modifier.fillMaxWidth().testTag("exercise_stats_session_${p.workoutId}")) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(p.day.shortLabel(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            p.bestDisplay?.let { Text(capacityText(def, kind, it, units), style = MaterialTheme.typography.titleSmall) }
                        }
                        Text(pluralStringResource(R.plurals.trs_sets, p.sets, p.sets), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sets.forEach { set ->
                            Text(setSummary(def, set, s.profile), style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (set.id == p.bestSet?.id) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun volumeHint(def: ExerciseDefinition): Int = when (def.kind) {
    com.cruxcoach.athlete.catalog.ExerciseKind.LOAD_REPS -> R.string.trs_volume_hint_load
    com.cruxcoach.athlete.catalog.ExerciseKind.REPS, com.cruxcoach.athlete.catalog.ExerciseKind.CLIMB -> R.string.trs_volume_hint_reps
    com.cruxcoach.athlete.catalog.ExerciseKind.TIME -> R.string.trs_volume_hint_time
    com.cruxcoach.athlete.catalog.ExerciseKind.HANG, com.cruxcoach.athlete.catalog.ExerciseKind.INTERVAL -> R.string.trs_volume_hint_hang
}

@Composable
private fun recordValue(kind: RecordKind, value: Double, units: com.cruxcoach.athlete.model.UnitSystem): String = when (kind) {
    RecordKind.LOAD -> stringResource(R.string.trs_best_load, formatMass(value, units))
    RecordKind.ESTIMATED_MAX -> stringResource(R.string.trs_best_e1rm, formatMass(value, units))
    RecordKind.REPS -> value.roundToInt().let { pluralStringResource(R.plurals.trs_reps, it, it) }
    RecordKind.DURATION -> stringResource(R.string.tr_format_seconds, value.roundToInt())
}
