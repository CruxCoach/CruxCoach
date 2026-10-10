package com.cruxcoach.android.ui.training.benchmarks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Exercises whose starting values matter most for planning loads. */
val KEY_BENCHMARK_SLUGS = listOf(
    "finger.max_hang",
    "finger.one_arm_pickup",
    "finger.two_arm_pickup",
    "pull.weighted_pull_up",
    "pull.pull_up",
    "push.push_up",
    "core.hollow_hold",
)

data class BenchmarkRow(
    val def: ExerciseDefinition,
    /** Newest value per side (null key = two-handed). */
    val values: Map<Side?, Benchmark>,
    val injury: InjuryAdvice,
    /** Estimated / being learnt / known (FEAT-071). */
    val learning: LearningState.State = LearningState.State.None,
)

data class BenchmarksState(
    val loading: Boolean = true,
    val key: List<BenchmarkRow> = emptyList(),
    val others: List<BenchmarkRow> = emptyList(),
    val profile: AthleteProfile = AthleteProfile(),
    val bodyweight: Double? = null,
    /** Grade anchor for the plausibility hint: the athlete's grade or the logbook's working grade. */
    val workingDifficulty: Double? = null,
)

sealed interface BenchmarkEvent { data object TestStarted : BenchmarkEvent }

@HiltViewModel
class BenchmarksViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(BenchmarksState())
    val state: StateFlow<BenchmarksState> = _state.asStateFlow()
    private val _events = Channel<BenchmarkEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val logbook = runCatching { service.gradeSummary() }.getOrNull()
            combine(repo.observeAllBenchmarks(), repo.observeProfile(), repo.observeActiveInjuries()) { all, profile, injuries ->
                val catalog = service.catalog
                val bySlug = all.groupBy { it.exerciseSlug }
                fun row(slug: String): BenchmarkRow? {
                    val def = catalog[slug] ?: return null
                    val list = bySlug[slug].orEmpty()
                    return BenchmarkRow(def, latestPerSide(list), InjuryAdvisor.assess(def, injuries),
                        LearningState.of(slug, list, com.cruxcoach.android.ui.training.coach.workSessionCount(service, slug)))
                }
                BenchmarksState(
                    loading = false,
                    key = KEY_BENCHMARK_SLUGS.mapNotNull(::row),
                    others = bySlug.keys.filter { it !in KEY_BENCHMARK_SLUGS }.mapNotNull(::row)
                        .sortedBy { it.def.name("en") },
                    profile = profile,
                    bodyweight = service.currentBodyweight(),
                    workingDifficulty = workingDifficulty(profile, logbook),
                )
            }.collect { _state.value = it }
        }
    }

    fun save(b: Benchmark) = io { service.saveBenchmark(b) }

    /** Body weight from the hint on top: values with added weight and % body weight need it. */
    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
            com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
        _state.update { it.copy(bodyweight = service.currentBodyweight() ?: kg) }
    }
    fun delete(id: String) = io { service.repo.deleteBenchmark(id) }
    fun startTest(slug: String) = io { if (service.startTest(slug) != null) _events.send(BenchmarkEvent.TestStarted) }
    fun newId(): String = service.repo.newId()

    private fun io(block: suspend () -> Unit) { viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() } }
}

internal fun latestPerSide(list: List<Benchmark>): Map<Side?, Benchmark> =
    list.groupBy { it.side }.mapValues { (_, v) -> v.maxBy { it.measuredAt } }

/** The athlete's own grade first, then the logbook's working grade when it rests on enough sends. */
internal fun workingDifficulty(profile: AthleteProfile, logbook: LogbookSummary?): Double? =
    profile.coach.currentGrade?.let { LogbookSummaries.difficultyOf(it) } ?: logbook?.takeIf { it.hasGrades }?.workingDifficulty

/**
 * The value in % body weight on a scale [GradeStrengthNorms] knows (10-s
 * max on ~20 mm with both hands, pull-up 1RM), or null for other protocols.
 */
internal fun normValue(def: ExerciseDefinition, b: Benchmark, bodyweight: Double?): Pair<GradeStrengthNorms.Metric, Double>? {
    val bw = (b.bodyweightKg ?: bodyweight)?.takeIf { it > 0 } ?: return null
    val cap = BenchmarkMath.capacity(def, b, bw) ?: return null
    return when {
        def.slug == "finger.max_hang" && cap.kind == CapacityKind.TEN_SECOND_MAX && (b.edgeMm?.let { kotlin.math.abs(it - 20.0) <= 2.0 } ?: true) ->
            GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM to cap.value / bw * 100.0
        def.slug == "pull.weighted_pull_up" && cap.kind == CapacityKind.E1RM_TOTAL ->
            GradeStrengthNorms.Metric.PULL_UP_1RM to cap.value / bw * 100.0
        def.slug == "pull.pull_up" && cap.kind == CapacityKind.MAX_REPS ->
            GradeStrengthNorms.Metric.PULL_UP_1RM to StrengthMath.epley(bw, cap.value.roundToInt()) / bw * 100.0
        else -> null
    }
}

@Composable
fun BenchmarksScreen(
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onTestStarted: () -> Unit,
    viewModel: BenchmarksViewModel = hiltViewModel(),
    onOpenForceGauge: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lang = catalogLanguage()
    var entry by remember { mutableStateOf<ExerciseDefinition?>(null) }
    var estimating by rememberSaveable { mutableStateOf(false) }
    var weightOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.events.collect { if (it is BenchmarkEvent.TestStarted) onTestStarted() } }

    TrainingScaffold(
        title = stringResource(R.string.trbm_title),
        onBack = onBack,
        actions = { InfoButton(stringResource(R.string.trbm_title), stringResource(R.string.trbm_info)) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("benchmarks_list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // What the page is for is behind the info icon in the app bar; here only what to do.
                if (state.bodyweight == null) {
                    com.cruxcoach.android.ui.training.common.NeedsDataCard(
                        icon = androidx.compose.material.icons.Icons.Default.MonitorWeight,
                        title = stringResource(R.string.tru_setup_weight),
                        text = stringResource(R.string.trbm_needs_weight),
                        action = stringResource(R.string.tru_enter_weight),
                        tag = "benchmarks_needs_weight",
                        onAction = { weightOpen = true },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedButton(onClick = { estimating = true }, modifier = Modifier.padding(top = 8.dp).testTag("benchmarks_quick_estimate")) {
                    Text(stringResource(R.string.trc_quick_estimate))
                }
                Text(stringResource(R.string.trc_quick_estimate_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SectionTitle(stringResource(R.string.trbm_key_title)) }
            items(state.key, key = { it.def.slug }) { row ->
                BenchmarkRowCard(row, lang, state.profile, state.bodyweight, state.workingDifficulty,
                    onOpen = { onOpenExercise(row.def.slug) }, onEnter = { entry = row.def },
                    onTest = { viewModel.startTest(row.def.slug) }, onForceGauge = onOpenForceGauge)
            }
            if (state.others.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trbm_other_title)) }
                items(state.others, key = { "o-" + it.def.slug }) { row ->
                    BenchmarkRowCard(row, lang, state.profile, state.bodyweight, state.workingDifficulty,
                        onOpen = { onOpenExercise(row.def.slug) }, onEnter = { entry = row.def },
                        onTest = { viewModel.startTest(row.def.slug) }, onForceGauge = onOpenForceGauge)
                }
            }
        }
    }
    entry?.let { def ->
        BenchmarkEntryDialog(def, state.profile, state.bodyweight,
            allowedSide = state.key.plus(state.others).firstOrNull { it.def.slug == def.slug }?.injury
                ?.takeIf { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }?.allowedSide,
            newId = viewModel::newId,
            onDismiss = { entry = null },
            onSave = { viewModel.save(it); entry = null },
            workingDifficulty = state.workingDifficulty)
    }
    if (estimating) com.cruxcoach.android.ui.training.coach.QuickEstimateSheet(onDismiss = { estimating = false })
    if (weightOpen) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = state.profile.units, lastKg = null, hideNumbers = state.profile.hideBodyNumbers,
            reason = stringResource(R.string.tru_weight_reason_setup),
            onDismiss = { weightOpen = false },
            onSave = { kg -> viewModel.logWeight(kg); weightOpen = false },
        )
    }
}

@Composable
private fun BenchmarkRowCard(
    row: BenchmarkRow,
    lang: String,
    profile: AthleteProfile,
    bodyweight: Double?,
    workingDifficulty: Double?,
    onOpen: () -> Unit,
    onEnter: () -> Unit,
    onTest: () -> Unit,
    onForceGauge: () -> Unit,
) {
    val paused = row.injury.verdict == InjuryVerdict.AVOID
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("benchmark_row_${row.def.slug}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.def.name(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                com.cruxcoach.android.ui.training.coach.learningLabel(row.learning)?.let { label ->
                    SuggestionChip(onClick = {}, label = { Text(label) }, modifier = Modifier.testTag("benchmark_learning_${row.def.slug}"))
                }
            }
            val sides: List<Side?> = if (row.def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
            sides.forEach { side ->
                val b = row.values[side]
                val prefix = side?.let { sideLabel(it) + ": " } ?: ""
                Text(
                    prefix + (b?.let { benchmarkSummary(row.def, it, profile, bodyweight) } ?: stringResource(R.string.trbm_none)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (b != null) {
                    Text(stringResource(R.string.trbm_meta, sourceLabel(b.source), formatDay(b.measuredAt)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    normValue(row.def, b, bodyweight)?.let { (metric, pct) ->
                        com.cruxcoach.android.ui.training.coach.PlausibilityHint(metric, pct, workingDifficulty)
                    }
                }
            }
            if (paused) {
                Text(stringResource(R.string.trbm_paused_injury), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEnter, modifier = Modifier.testTag("benchmark_enter_${row.def.slug}")) {
                    Text(stringResource(R.string.trbm_enter))
                }
                OutlinedButton(onClick = onTest, enabled = !paused, modifier = Modifier.testTag("benchmark_test_${row.def.slug}")) {
                    Icon(Icons.Default.Speed, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.trbm_test))
                }
            }
            if (profile.coach.forceGaugeEnabled && row.def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.FINGER && !paused) {
                TextButton(onClick = onForceGauge, modifier = Modifier.testTag("benchmark_force_${row.def.slug}")) {
                    Text(stringResource(R.string.trc_force_measure))
                }
            }
        }
    }
}

// ── Formatting ───────────────────────────────────────────────────────

@Composable
fun benchmarkSummary(def: ExerciseDefinition, b: Benchmark, profile: AthleteProfile, bodyweight: Double?): String {
    val units = profile.units
    val cap = BenchmarkMath.capacity(def, b, bodyweight)
    val pieces = mutableListOf<String>()
    when (BenchmarkMath.capacityKind(def)) {
        CapacityKind.E1RM_TOTAL -> {
            pieces += loadText(def, b.loadKg ?: 0.0, units) + " × " + (b.reps ?: 0)
            if (cap != null) {
                val bw = b.bodyweightKg ?: bodyweight
                val shown = if (def.load == LoadMode.BODYWEIGHT_PLUS && bw != null) cap.value - bw else cap.value
                pieces += stringResource(R.string.trbm_e1rm, if (def.load == LoadMode.BODYWEIGHT_PLUS) formatMass(shown, units, signed = true) else formatMass(shown, units))
            }
        }
        CapacityKind.TEN_SECOND_MAX -> {
            pieces += loadText(def, b.loadKg ?: 0.0, units) + " · " + stringResource(R.string.tr_format_seconds, (b.durationS ?: 10.0).roundToInt())
            b.edgeMm?.let { pieces += stringResource(R.string.tr_format_mm, formatNumber(it)) }
            b.grip?.let { pieces += gripLabel(it) }
            val bw = b.bodyweightKg ?: bodyweight
            if (cap != null && bw != null && bw > 0) {
                pieces += stringResource(R.string.trbm_ten_second_max, formatMass(cap.value, units), (cap.value / bw * 100).roundToInt())
            }
        }
        CapacityKind.MAX_REPS -> pieces += stringResource(R.string.trbm_max_reps, b.reps ?: 0)
        CapacityKind.MAX_SECONDS -> pieces += stringResource(R.string.trbm_max_seconds, (b.durationS ?: 0.0).roundToInt())
        null -> Unit
    }
    return pieces.joinToString(" · ")
}

@Composable
private fun loadText(def: ExerciseDefinition, loadKg: Double, units: UnitSystem): String = when {
    def.load == LoadMode.BODYWEIGHT_PLUS && loadKg == 0.0 -> stringResource(R.string.tr_bodyweight)
    def.load == LoadMode.BODYWEIGHT_PLUS && loadKg < 0 -> stringResource(R.string.trbm_assisted, formatMass(-loadKg, units))
    def.load == LoadMode.BODYWEIGHT_PLUS -> formatMass(loadKg, units, signed = true)
    else -> formatMass(loadKg, units)
}

@Composable
private fun sourceLabel(s: BenchmarkSource): String = stringResource(when (s) {
    BenchmarkSource.MANUAL -> R.string.trbm_source_manual
    BenchmarkSource.TEST -> R.string.trbm_source_test
    BenchmarkSource.AUTO -> R.string.trbm_source_auto
    BenchmarkSource.ESTIMATE -> R.string.trbm_source_estimate
})

private fun formatDay(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

// ── Entry dialog ─────────────────────────────────────────────────────

/**
 * "What can you do?" for one exercise, with a live preview of what it means
 * (e1RM, 10-s maximum, % body weight). One-sided exercises are entered per
 * hand; an open one-sided injury limits the choice to the healthy side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkEntryDialog(
    def: ExerciseDefinition,
    profile: AthleteProfile,
    bodyweight: Double?,
    allowedSide: Side?,
    newId: () -> String,
    onDismiss: () -> Unit,
    onSave: (Benchmark) -> Unit,
    /** Grade anchor for the plausibility hint; null shows none. */
    workingDifficulty: Double? = null,
) {
    val units = profile.units
    val kind = BenchmarkMath.capacityKind(def) ?: run { onDismiss(); return }
    var loadText by rememberSaveable { mutableStateOf("") }
    var repsText by rememberSaveable { mutableStateOf("") }
    var secondsText by rememberSaveable { mutableStateOf(if (kind == CapacityKind.TEN_SECOND_MAX) "10" else "") }
    var edgeText by rememberSaveable { mutableStateOf(def.defaults.edgeMm?.toString() ?: "") }
    var grip by rememberSaveable { mutableStateOf<Grip?>(if (kind == CapacityKind.TEN_SECOND_MAX && def.defaults.edgeMm != null) Grip.HALF_CRIMP else null) }
    var side by rememberSaveable { mutableStateOf(if (def.unilateral) (allowedSide ?: Side.RIGHT) else null) }

    val loadKg = parseDecimal(loadText)?.let { Units.massFromDisplay(it, units) }
    val reps = repsText.trim().toIntOrNull()
    val seconds = parseDecimal(secondsText)
    val edge = parseDecimal(edgeText)
    val needsLoad = kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX
    val draft = Benchmark(
        id = "", exerciseSlug = def.slug, side = side,
        edgeMm = edge.takeIf { kind == CapacityKind.TEN_SECOND_MAX },
        grip = grip.takeIf { kind == CapacityKind.TEN_SECOND_MAX },
        loadKg = if (needsLoad) (loadKg ?: if (def.load == LoadMode.BODYWEIGHT_PLUS) 0.0 else null) else null,
        reps = reps.takeIf { kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.MAX_REPS },
        durationS = seconds.takeIf { kind == CapacityKind.TEN_SECOND_MAX || kind == CapacityKind.MAX_SECONDS },
        bodyweightKg = bodyweight, source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis(),
    )
    val valid = when (kind) {
        CapacityKind.E1RM_TOTAL -> draft.loadKg != null && (reps ?: 0) in 1..30
        CapacityKind.TEN_SECOND_MAX -> draft.loadKg != null && (seconds ?: 0.0) in 3.0..60.0
        CapacityKind.MAX_REPS -> (reps ?: 0) in 1..500
        CapacityKind.MAX_SECONDS -> (seconds ?: 0.0) in 1.0..3600.0
    } && !(def.load == LoadMode.EXTERNAL && (draft.loadKg ?: 0.0) <= 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("benchmark_dialog"),
        title = { Text(def.name(catalogLanguage())) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(when (kind) {
                    CapacityKind.E1RM_TOTAL -> R.string.trbm_hint_e1rm
                    CapacityKind.TEN_SECOND_MAX -> R.string.trbm_hint_hang
                    CapacityKind.MAX_REPS -> R.string.trbm_hint_reps
                    CapacityKind.MAX_SECONDS -> R.string.trbm_hint_seconds
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (def.unilateral) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf(Side.LEFT, Side.RIGHT).forEach { s ->
                            FilterChip(selected = side == s, enabled = allowedSide == null || allowedSide == s,
                                onClick = { side = s }, label = { Text(sideLabel(s)) },
                                modifier = Modifier.testTag("benchmark_side_${s.name.lowercase()}"))
                        }
                    }
                }
                if (needsLoad) {
                    OutlinedTextField(
                        value = loadText, onValueChange = { loadText = it.take(8) }, singleLine = true,
                        label = { Text(stringResource(
                            if (def.load == LoadMode.BODYWEIGHT_PLUS) R.string.trbm_load_added else R.string.trbm_load_total,
                            Units.massUnit(units))) },
                        supportingText = if (def.load == LoadMode.BODYWEIGHT_PLUS) ({ Text(stringResource(R.string.trbm_load_added_hint)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("benchmark_load"),
                    )
                }
                if (kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.MAX_REPS) {
                    OutlinedTextField(value = repsText, onValueChange = { repsText = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        label = { Text(stringResource(R.string.trbm_reps)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("benchmark_reps"))
                }
                if (kind == CapacityKind.TEN_SECOND_MAX || kind == CapacityKind.MAX_SECONDS) {
                    OutlinedTextField(value = secondsText, onValueChange = { secondsText = it.take(5) }, singleLine = true,
                        label = { Text(stringResource(R.string.trbm_seconds)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("benchmark_seconds"))
                }
                if (kind == CapacityKind.TEN_SECOND_MAX && def.defaults.edgeMm != null) {
                    OutlinedTextField(value = edgeText, onValueChange = { edgeText = it.take(4) }, singleLine = true,
                        label = { Text(stringResource(R.string.trbm_edge)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        listOf(Grip.HALF_CRIMP, Grip.OPEN_HAND, Grip.FULL_CRIMP, Grip.THREE_FINGER_DRAG).forEach { g ->
                            FilterChip(selected = grip == g, onClick = { grip = if (grip == g) null else g }, label = { Text(gripLabel(g)) })
                        }
                    }
                }
                if (valid) {
                    Text(benchmarkSummary(def, draft, profile, bodyweight), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp).testTag("benchmark_preview"))
                    val target = LoadPrescriber.prescribe(def, WorkoutPlanner.itemFor(def),
                        BenchmarkMath.capacity(def, draft, bodyweight), bodyweight, profile.smallestIncrementKg)
                    if (target != null) {
                        Text(prescriptionText(def, target, units), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    normValue(def, draft, bodyweight)?.let { (metric, pct) ->
                        com.cruxcoach.android.ui.training.coach.PlausibilityHint(metric, pct, workingDifficulty)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(id = newId(), measuredAt = System.currentTimeMillis())) }, enabled = valid,
                modifier = Modifier.testTag("benchmark_save")) {
                Text(stringResource(R.string.tr_action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

/** "Planned: 5 × 10 s at +4 kg" — shows the athlete what the value turns into. */
@Composable
fun prescriptionText(def: ExerciseDefinition, t: LoadTarget, units: UnitSystem): String {
    val sets = def.defaults.sets ?: 3
    val reps = t.reps
    val duration = t.durationS
    val amount = when {
        reps != null -> "$sets × $reps"
        duration != null -> "$sets × " + stringResource(R.string.tr_format_seconds, duration)
        else -> "$sets ×"
    }
    val load = t.loadKg?.let { " · " + loadText(def, it, units) } ?: ""
    return stringResource(R.string.trbm_planned, amount + load)
}

// ── Card for the exercise detail page ────────────────────────────────

@HiltViewModel
class BenchmarkCardViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    data class CardState(
        val def: ExerciseDefinition? = null,
        val values: Map<Side?, Benchmark> = emptyMap(),
        val profile: AthleteProfile = AthleteProfile(),
        val bodyweight: Double? = null,
        val injury: InjuryAdvice = InjuryAdvice(InjuryVerdict.OK),
        val target: Map<Side?, LoadTarget> = emptyMap(),
        val learning: LearningState.State = LearningState.State.None,
        val workingDifficulty: Double? = null,
    )
    private val _state = MutableStateFlow(CardState())
    val state: StateFlow<CardState> = _state.asStateFlow()
    private val _events = Channel<BenchmarkEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var loadedSlug: String? = null

    fun load(slug: String) {
        if (loadedSlug == slug) return
        loadedSlug = slug
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val logbook = runCatching { service.gradeSummary() }.getOrNull()
            combine(repo.observeBenchmarks(slug), repo.observeProfile(), repo.observeActiveInjuries()) { list, profile, injuries ->
                val def = service.catalog[slug]
                val bw = service.currentBodyweight()
                val values = latestPerSide(list)
                val targets = if (def == null) emptyMap() else {
                    val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
                    sides.mapNotNull { side ->
                        val cap = service.capacityFor(def, side, def.defaults.edgeMm?.toDouble(), null, bw)
                        LoadPrescriber.prescribe(def, WorkoutPlanner.itemFor(def), cap, bw, profile.smallestIncrementKg)?.let { side to it }
                    }.toMap()
                }
                CardState(def, values, profile, bw, def?.let { InjuryAdvisor.assess(it, injuries) } ?: InjuryAdvice(InjuryVerdict.OK), targets,
                    learning = LearningState.of(slug, list, com.cruxcoach.android.ui.training.coach.workSessionCount(service, slug)),
                    workingDifficulty = workingDifficulty(profile, logbook))
            }.collect { _state.value = it }
        }
    }

    fun save(b: Benchmark) = viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.saveBenchmark(b) }
    fun startTest() {
        val slug = loadedSlug ?: return
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); if (service.startTest(slug) != null) _events.send(BenchmarkEvent.TestStarted) }
    }
    fun newId(): String = service.repo.newId()
}

/** Performance value of one exercise: what is known, what it plans, enter or test. */
@Composable
fun BenchmarkCard(
    slug: String,
    onTestStarted: () -> Unit,
    viewModel: BenchmarkCardViewModel = hiltViewModel(key = "benchmark-$slug"),
    /** Shown on finger exercises once a force gauge is enabled; null hides it. */
    onOpenForceGauge: (() -> Unit)? = null,
) {
    LaunchedEffect(slug) { viewModel.load(slug) }
    LaunchedEffect(Unit) { viewModel.events.collect { if (it is BenchmarkEvent.TestStarted) onTestStarted() } }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val def = s.def ?: return
    if (BenchmarkMath.capacityKind(def) == null) return
    var entering by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("exercise_benchmark_card")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trbm_card_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                com.cruxcoach.android.ui.training.coach.learningLabel(s.learning)?.let { label ->
                    SuggestionChip(onClick = {}, label = { Text(label) }, modifier = Modifier.testTag("exercise_benchmark_learning"))
                }
                InfoButton(stringResource(R.string.trbm_card_title), stringResource(R.string.trbm_info))
            }
            val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
            sides.forEach { side ->
                val prefix = side?.let { sideLabel(it) + ": " } ?: ""
                Text(prefix + (s.values[side]?.let { benchmarkSummary(def, it, s.profile, s.bodyweight) } ?: stringResource(R.string.trbm_none)),
                    style = MaterialTheme.typography.bodyMedium)
                s.target[side]?.let { t ->
                    val text = prescriptionText(def, t, s.profile.units) +
                        if (t.basis.fromOtherSide) " " + stringResource(R.string.trbm_from_other_side) else ""
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { entering = true }, modifier = Modifier.testTag("exercise_benchmark_enter")) {
                    Text(stringResource(R.string.trbm_enter))
                }
                OutlinedButton(onClick = viewModel::startTest, enabled = s.injury.verdict != InjuryVerdict.AVOID,
                    modifier = Modifier.testTag("exercise_benchmark_test")) { Text(stringResource(R.string.trbm_test)) }
            }
            if (onOpenForceGauge != null && s.profile.coach.forceGaugeEnabled &&
                def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.FINGER && s.injury.verdict != InjuryVerdict.AVOID) {
                TextButton(onClick = onOpenForceGauge, modifier = Modifier.testTag("exercise_benchmark_force")) {
                    Text(stringResource(R.string.trc_force_measure))
                }
            }
        }
    }
    if (entering) {
        BenchmarkEntryDialog(def, s.profile, s.bodyweight,
            allowedSide = s.injury.takeIf { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }?.allowedSide,
            newId = viewModel::newId, onDismiss = { entering = false }, onSave = { viewModel.save(it); entering = false },
            workingDifficulty = s.workingDifficulty)
    }
}
