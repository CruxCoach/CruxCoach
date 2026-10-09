package com.cruxcoach.android.ui.training.stats

import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.body.TrendChart
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.SetType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import javax.inject.Inject
import kotlin.math.roundToInt

/** One trained exercise in the stats list. */
data class ExerciseRow(
    val def: ExerciseDefinition,
    val kind: CapacityKind?,
    val last: Double?,
    val change: Double?,
    val spark: List<Double>,
    val lastAt: Long?,
)

data class StatsHubState(
    val loading: Boolean = true,
    val range: StatsRange = StatsRange.TWELVE_WEEKS,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val weeks: List<WeekAggregate> = emptyList(),
    val calendar: Map<LocalDate, Int> = emptyMap(),
    val domainWeeks: List<DomainWeek> = emptyList(),
    val daysThisWeek: Int = 0,
    val trainingDaysInRange: Int = 0,
    val recordsInRange: Int = 0,
    val weight: List<TrendWeight.Point> = emptyList(),
    /** % body weight per strength exercise and day. */
    val strength: Map<String, List<Pair<LocalDate, Double>>> = emptyMap(),
    val strengthDefs: Map<String, ExerciseDefinition> = emptyMap(),
    val exercises: List<ExerciseRow> = emptyList(),
    /** Teaser of the climber profile and the current load per structure. */
    val logbook: LogbookSummary = LogbookSummary(),
    val load: LoadStatus? = null,
    /** Latest trend weight, whatever the range; null before the first weigh-in. */
    val latestWeightKg: Double? = null,
    val weeklyRateKg: Double? = null,
    /** Start values the athlete has entered or tested. */
    val benchmarkCount: Int = 0,
) {
    val hasAnyData: Boolean get() = weeks.any { it.trainingDays > 0 } || exercises.isNotEmpty() || weight.isNotEmpty()
}

/** Exercises whose % body weight is the climber's strength-to-weight signal. */
val STRENGTH_TO_WEIGHT_SLUGS = listOf("finger.max_hang", "finger.one_arm_pickup", "pull.weighted_pull_up")

@HiltViewModel
class StatsHubViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(StatsHubState())
    val state: StateFlow<StatsHubState> = _state.asStateFlow()
    private var job: Job? = null

    init { load(StatsRange.TWELVE_WEEKS) }

    fun setRange(range: StatsRange) {
        if (range != _state.value.range) load(range)
    }

    /** The tab keeps this ViewModel; a return to it recomputes, so a just-finished training shows up. */
    fun reload() = load(_state.value.range)

    /** A weigh-in from the body tile, where the hub asks for it. */
    fun logWeight(kg: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
                com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
            reload()
        }
    }

    private fun load(range: StatsRange) {
        _state.value = _state.value.copy(range = range)
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val zone = TimeZone.currentSystemDefault()
            val today = service.today()
            val profile = repo.profile()
            val catalog = service.catalog
            val rangeStart = today.minus(DatePeriod(days = range.days - 1))
            val rangeStartMs = rangeStart.atStartOfDayIn(zone).toEpochMilliseconds()
            val calendarDays = 16 * 7
            val activities = service.activities(days = maxOf(range.weeks * 7 + 7, calendarDays))
            val setsInRange = repo.completedSetsSince(rangeStartMs - 7L * 86_400_000L)
                .filter { it.setType != SetType.WARMUP }
            val setsWithDay = setsInRange.mapNotNull { s -> s.completedAt?.let { ProgressStats.dayOf(it, zone) to s } }
            val weeks = ProgressStats.weekly(activities, setsWithDay.map { it.first }, today, range.weeks)
            val bodyweight = service.currentBodyweight()

            val trained = repo.trainedExercises().sortedByDescending { it.lastAt ?: 0L }
            var records = 0
            val rows = trained.mapNotNull { t ->
                val def = catalog[t.slug] ?: catalog.fallbackFor(t.slug)
                val history = repo.history(t.slug, AthleteService.HISTORY_LIMIT)
                // Warm-up-only exercises (arm circles before every session) are not progress.
                if (history.none { it.setType != SetType.WARMUP }) return@mapNotNull null
                // Personal bests set inside the range, each judged against what came before it.
                val chronological = history.sortedBy { it.completedAt ?: 0L }
                chronological.forEachIndexed { i, set ->
                    if ((set.completedAt ?: 0L) >= rangeStartMs &&
                        PersonalRecords.detect(def, set, chronological.subList(0, i)) != null) records++
                }
                val progress = ProgressStats.exerciseProgress(def, history, zone, bodyweight)
                ExerciseRow(
                    def = def,
                    kind = progress.kind,
                    last = progress.last,
                    change = progress.change28,
                    spark = progress.sessions.mapNotNull { it.bestDisplay }.takeLast(12),
                    lastAt = t.lastAt,
                )
            }
            val strength = STRENGTH_TO_WEIGHT_SLUGS.mapNotNull { slug ->
                val def = catalog[slug] ?: return@mapNotNull null
                val points = ProgressStats.exerciseProgress(def, repo.history(slug, AthleteService.HISTORY_LIMIT), zone, bodyweight)
                    .sessions.filter { it.day >= rangeStart }
                    .mapNotNull { s ->
                        val cap = s.bestCapacity ?: return@mapNotNull null
                        val bw = s.bodyweightKg?.takeIf { it > 0 } ?: return@mapNotNull null
                        s.day to cap / bw * 100.0
                    }
                if (points.isEmpty()) null else slug to points
            }.toMap()

            val logbook = service.logbookSummary()
            val load = runCatching { service.loadStatus() }.getOrNull()

            // A cancelled load keeps running its blocking queries; it must not overwrite a newer range.
            ensureActive()
            if (_state.value.range != range) return@launch
            _state.value = StatsHubState(
                loading = false,
                range = range,
                profile = profile,
                today = today,
                weeks = weeks,
                calendar = ProgressStats.calendar(activities, today, calendarDays),
                domainWeeks = ProgressStats.domainWeeks(activities, setsWithDay, catalog, today, range.weeks),
                daysThisWeek = weeks.lastOrNull()?.trainingDays ?: 0,
                trainingDaysInRange = activities.values.count { a ->
                    a.trained && runCatching { LocalDate.parse(a.day) }.getOrNull()?.let { it >= rangeStart } == true
                },
                recordsInRange = records,
                weight = service.weightTrend().filter { it.day >= rangeStart },
                latestWeightKg = service.weightTrend().lastOrNull()?.trend,
                benchmarkCount = repo.allBenchmarks().size,
                weeklyRateKg = com.cruxcoach.athlete.logic.TrendWeight.weeklyRate(service.weightTrend()),
                strength = strength,
                strengthDefs = strength.keys.mapNotNull { slug -> catalog[slug]?.let { slug to it } }.toMap(),
                exercises = rows,
                logbook = logbook,
                load = load,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsHubScreen(
    onBack: () -> Unit,
    onOpenExerciseStats: (String) -> Unit,
    onOpenBody: () -> Unit,
    onOpenWeeklyReview: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    viewModel: StatsHubViewModel = hiltViewModel(),
    tabBar: @Composable () -> Unit = {},
    onOpenClimberProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    /** The week's targets per area (integrator slot), right under the hub. */
    volumeCard: @Composable () -> Unit = {},
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var weightOpen by rememberSaveable { mutableStateOf(false) }
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (entered) viewModel.reload() else entered = true }
    val lang = catalogLanguage()
    val colors = seriesColors()
    val climbColor = colors[0]
    val offBoardColor = colors[1]
    TrainingScaffold(title = stringResource(R.string.tr_tab_progress), onBack = onBack, bottomBar = tabBar) { padding ->
        if (s.loading && s.today == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("stats_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Where to go from here: body, the week, start values, the log – each with its value.
            item(key = "hub") { ProgressHub(s, onOpenBody, onLogWeight = { weightOpen = true }, onOpenWeeklyReview, onOpenBenchmarks, onOpenHistory) }
            item(key = "volume") { volumeCard() }
            item {
                SectionTitle(stringResource(R.string.tru_progress_training))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsRange.entries.forEach { r ->
                        FilterChip(selected = s.range == r, onClick = { viewModel.setRange(r) }, label = { Text(rangeLabel(r)) },
                            modifier = Modifier.testTag("stats_range_${r.name.lowercase()}"))
                    }
                }
            }
            item { ClimberProfileCard(s, onOpenClimberProfile) }
            s.load?.let { load -> item { LoadChips(load, onOpenClimberProfile) } }
            if (!s.hasAnyData) {
                item { EmptyHint(stringResource(R.string.trs_empty)) }
            }

            // ── Training days and minutes per week ───────────────────
            item { SectionTitle(stringResource(R.string.trs_days_per_week)) }
            item {
                val bars = s.weeks.map { w ->
                    WeekBar(w.weekStart, listOf(climbColor to w.climbingDays.toDouble(),
                        offBoardColor to (w.trainingDays - w.climbingDays).coerceAtLeast(0).toDouble()))
                }
                WeeklyBarChart(bars, stringResource(R.string.trs_days_chart_cd, s.weeks.size, s.weeks.sumOf { it.trainingDays }),
                    Modifier.fillMaxWidth().testTag("stats_days_chart"))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(climbColor, stringResource(R.string.trs_legend_climbing))
                    Legend(offBoardColor, stringResource(R.string.trs_legend_off_board))
                }
            }
            item { SectionTitle(stringResource(R.string.trs_minutes_per_week)) }
            item {
                val bars = s.weeks.map { w ->
                    WeekBar(w.weekStart, listOf(climbColor to w.climbingMinutes.toDouble(), offBoardColor to w.workoutMinutes.toDouble()))
                }
                WeeklyBarChart(bars, stringResource(R.string.trs_minutes_chart_cd, s.weeks.sumOf { it.climbingMinutes + it.workoutMinutes }),
                    Modifier.fillMaxWidth().testTag("stats_minutes_chart"))
            }

            // ── Calendar ────────────────────────────────────────────
            item { SectionTitle(stringResource(R.string.trs_calendar)) }
            item {
                s.today?.let { today ->
                    CalendarHeatmap(s.calendar, today,
                        stringResource(R.string.trs_calendar_cd, s.calendar.count { it.value > 0 }),
                        Modifier.testTag("stats_calendar"))
                }
                Text(stringResource(R.string.trs_calendar_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Load per structure ──────────────────────────────────
            item { SectionTitle(stringResource(R.string.trs_load_title)) }
            item {
                Text(stringResource(R.string.trs_load_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val domains = listOf(LoadDomain.FINGER, LoadDomain.SHOULDER, LoadDomain.ELBOW, LoadDomain.SKIN)
            items(domains, key = { "dom-" + it.name }) { domain ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(domainLabel(domain), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                    val bars = s.domainWeeks.map { WeekBar(it.weekStart, listOf(climbColor to (it.days[domain] ?: 0).toDouble())) }
                    WeeklyBarChart(bars, stringResource(R.string.trs_load_cd, domainLabel(domain),
                        s.domainWeeks.lastOrNull()?.days?.get(domain) ?: 0), Modifier.weight(1f), height = 48.dp,
                        showDates = domain == domains.last())
                }
            }

            // ── Body ────────────────────────────────────────────────
            item {
                SectionTitle(stringResource(R.string.tr_nav_body)) {
                    TextButton(onClick = onOpenBody, modifier = Modifier.testTag("stats_open_body")) { Text(stringResource(R.string.trs_open_body)) }
                }
            }
            item {
                if (s.weight.isEmpty()) {
                    Text(stringResource(R.string.trs_body_empty), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { weightOpen = true }, modifier = Modifier.padding(top = 4.dp).testTag("stats_log_weight")) {
                        Text(stringResource(R.string.tru_enter_weight))
                    }
                } else {
                    TrendChart(s.weight, s.profile.hideBodyNumbers, s.profile.units,
                        stringResource(R.string.trs_weight_cd), Modifier.fillMaxWidth().clickable(onClick = onOpenBody))
                }
            }
            if (s.strength.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trs_strength_title)) }
                item {
                    val series = s.strength.entries.mapIndexed { i, (slug, points) ->
                        ChartSeries(label = s.strengthDefs[slug]?.name(lang) ?: slug,
                            color = colors[i % colors.size], points = points)
                    }
                    DateLineChart(series, { "${it.roundToInt()} %" }, stringResource(R.string.trs_strength_cd),
                        Modifier.fillMaxWidth().testTag("stats_strength_chart"))
                    Text(stringResource(R.string.trs_strength_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Exercises ───────────────────────────────────────────
            item { SectionTitle(stringResource(R.string.tr_tab_exercises)) }
            if (s.exercises.isEmpty()) {
                item { Text(stringResource(R.string.trs_exercises_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(s.exercises, key = { "ex-" + it.def.slug }) { row ->
                ExerciseStatsRow(row, lang, s.profile, climbColor) { onOpenExerciseStats(row.def.slug) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (weightOpen) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = s.profile.units, lastKg = s.latestWeightKg, hideNumbers = s.profile.hideBodyNumbers,
            onDismiss = { weightOpen = false },
            onSave = { kg -> viewModel.logWeight(kg); weightOpen = false },
        )
    }
}

/** The four ways into the details of Progress, each with its current value. */
@Composable
private fun ProgressHub(
    s: StatsHubState,
    onOpenBody: () -> Unit,
    onLogWeight: () -> Unit,
    onOpenWeeklyReview: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val w = s.latestWeightKg
            com.cruxcoach.android.ui.training.common.ValueTile(
                icon = Icons.Default.MonitorWeight, tint = MaterialTheme.colorScheme.tertiary,
                label = stringResource(R.string.tr_nav_body),
                value = when {
                    w == null -> stringResource(R.string.tru_tile_log)
                    s.profile.hideBodyNumbers -> stringResource(R.string.trt_numbers_hidden_short)
                    else -> formatMass(w, s.profile.units)
                },
                valueIsAction = w == null,
                supporting = stringResource(R.string.tru_hub_body),
                tag = "stats_open_body_tile", onClick = if (w == null) onLogWeight else onOpenBody,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            com.cruxcoach.android.ui.training.common.ValueTile(
                icon = Icons.Default.CalendarMonth, tint = CruxCoachDesign.colors.brandAccent,
                label = stringResource(R.string.trt_weekly_review),
                value = "${s.daysThisWeek} / ${s.profile.weeklyGoal}",
                supporting = stringResource(R.string.trs_tile_training_days),
                tag = "stats_weekly_review", onClick = onOpenWeeklyReview,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.cruxcoach.android.ui.training.common.ValueTile(
                icon = Icons.Default.Speed, tint = CruxCoachDesign.colors.positive,
                label = stringResource(R.string.trbm_title),
                value = if (s.benchmarkCount > 0) pluralStringResource(R.plurals.tru_hub_values, s.benchmarkCount, s.benchmarkCount)
                    else stringResource(R.string.tru_tile_log),
                valueIsAction = s.benchmarkCount == 0,
                supporting = stringResource(R.string.tru_hub_benchmarks),
                tag = "stats_benchmarks", onClick = onOpenBenchmarks,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            com.cruxcoach.android.ui.training.common.ValueTile(
                icon = Icons.Default.History, tint = MaterialTheme.colorScheme.secondary,
                label = stringResource(R.string.trt_done_hero_history),
                value = "${s.trainingDaysInRange}",
                supporting = stringResource(R.string.tru_hub_history, rangeLabel(s.range)) + " · " +
                    pluralStringResource(R.plurals.tru_hub_records, s.recordsInRange, s.recordsInRange),
                tag = "stats_history", onClick = onOpenHistory,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ExerciseStatsRow(row: ExerciseRow, lang: String, profile: AthleteProfile, color: Color, onClick: () -> Unit) {
    val units = profile.units
    val supporting = listOfNotNull(
        row.last?.let { capacityLabel(row.kind) + ": " + capacityText(row.def, row.kind, it, units) },
        row.change?.let { stringResource(R.string.trs_change_28, changeText(row.def, row.kind, it, units)) },
    ).joinToString(" · ").ifBlank { stringResource(R.string.trs_no_value) }
    ListItem(
        headlineContent = { Text(row.def.name(lang)) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkline(row.spark, color)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick).testTag("stats_exercise_${row.def.slug}"),
    )
}

/** Entry to the climber profile: grade, finger strength, and how the two relate. */
@Composable
private fun ClimberProfileCard(s: StatsHubState, onOpen: () -> Unit) {
    val finger = s.strength["finger.max_hang"]?.lastOrNull()?.second
    val parts = listOfNotNull(
        s.logbook.workingDifficulty?.let { stringResource(R.string.trl_card_grade, fontLabel(it)) },
        finger?.let { stringResource(R.string.trl_card_finger, "${it.roundToInt()} %") },
    )
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("stats_climber_profile")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Insights, contentDescription = null,
                tint = CruxCoachDesign.colors.brandAccent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trl_profile_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (parts.isEmpty()) stringResource(R.string.trl_card_empty) else parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.trl_card_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Current load per structure in one line; details live in the climber profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadChips(load: LoadStatus, onOpen: () -> Unit) {
    Column(Modifier.testTag("stats_load")) {
        Text(stringResource(R.string.trl_load_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadStructure.entries.forEach { st ->
                val trend = load.structures[st]?.trend ?: LoadTrend.LOW
                AssistChip(
                    onClick = onOpen,
                    label = { Text(structureLabel(st) + " · " + trendLabel(trend)) },
                    colors = when (trend) {
                        LoadTrend.SPIKE -> AssistChipDefaults.assistChipColors(containerColor = CruxCoachDesign.colors.cautionContainer,
                            labelColor = CruxCoachDesign.colors.onCautionContainer)
                        LoadTrend.RISING -> AssistChipDefaults.assistChipColors(labelColor = CruxCoachDesign.colors.caution)
                        else -> AssistChipDefaults.assistChipColors()
                    },
                    modifier = Modifier.testTag("stats_load_${st.name.lowercase()}"),
                )
            }
        }
    }
}

