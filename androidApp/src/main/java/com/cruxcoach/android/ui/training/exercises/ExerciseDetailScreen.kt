package com.cruxcoach.android.ui.training.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.BodyRegion
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlin.math.roundToInt

/** One past training of the exercise: its completed sets in order. */
data class HistorySession(val workoutId: String, val completedAt: Long, val sets: List<ExerciseSet>)

/** Best result for one comparable key (edge, grip, side), with the set that produced it. */
data class BestEntry(val key: PersonalRecords.Key, val kind: RecordKind, val value: Double, val set: ExerciseSet)

data class ExerciseDetailState(
    val loading: Boolean = true,
    val definition: ExerciseDefinition? = null,
    val chain: List<ExerciseDefinition> = emptyList(),
    val advice: InjuryAdvice = InjuryAdvice(InjuryVerdict.OK),
    val favorite: Boolean = false,
    val openWorkout: Workout? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val sessions: List<HistorySession> = emptyList(),
    val bests: List<BestEntry> = emptyList(),
    val navigateToWorkout: Boolean = false,
    val addRejected: Boolean = false,
    /** "Nie vorschlagen": never part of a suggestion (FEAT-071). */
    val excluded: Boolean = false,
)

@HiltViewModel
class ExerciseDetailViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseDetailState())
    val state: StateFlow<ExerciseDetailState> = _state.asStateFlow()
    private var loadedSlug: String? = null
    private var job: Job? = null

    fun load(slug: String) {
        if (loadedSlug == slug) return
        loadedSlug = slug
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            combine(
                service.catalogStore.catalog,
                repo.observeProfile(),
                repo.observeActiveInjuries(),
                repo.observeFavorites(),
                repo.observeOpenWorkout(),
            ) { catalog, profile, injuries, favorites, open ->
                val def = catalog[slug]
                val history = repo.history(slug, 200)
                ExerciseDetailState(
                    loading = false,
                    definition = def,
                    chain = if (def != null) catalog.chainOf(def.slug) else emptyList(),
                    advice = if (def != null) InjuryAdvisor.assess(def, injuries) else InjuryAdvice(InjuryVerdict.OK),
                    favorite = slug in favorites,
                    excluded = slug in profile.excludedExercises,
                    openWorkout = open,
                    profile = profile,
                    sessions = sessionsOf(history),
                    bests = if (def != null) bestsOf(def, history) else emptyList(),
                )
            }.collect { fresh ->
                _state.update { old -> fresh.copy(navigateToWorkout = old.navigateToWorkout, addRejected = old.addRejected) }
            }
        }
    }

    private fun sessionsOf(history: List<ExerciseSet>): List<HistorySession> =
        history.filter { it.isCompleted }
            .groupBy { it.workoutId }
            .map { (id, sets) ->
                HistorySession(id, sets.maxOf { it.completedAt ?: 0L },
                    sets.sortedWith(compareBy({ it.blockIndex }, { it.setIndex }, { it.side?.ordinal ?: -1 })))
            }
            .sortedByDescending { it.completedAt }
            .take(5)

    private fun bestsOf(def: ExerciseDefinition, history: List<ExerciseSet>): List<BestEntry> =
        history.mapNotNull { set -> PersonalRecords.score(def, set)?.let { (kind, value) -> BestEntry(PersonalRecords.keyOf(set), kind, value, set) } }
            .groupBy { it.key }
            .map { (_, entries) -> entries.maxBy { it.value } }
            .sortedWith(compareBy<BestEntry>({ it.key.edgeMm ?: Int.MAX_VALUE }, { it.key.grip?.ordinal ?: -1 }, { it.key.side?.ordinal ?: -1 }))

    fun toggleFavorite() {
        val slug = loadedSlug ?: return
        val favorite = !_state.value.favorite
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.repo.setFavorite(slug, favorite) }
    }

    fun toggleExcluded() {
        val slug = loadedSlug ?: return
        val excluded = !_state.value.excluded
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.setExcluded(slug, excluded) }
    }

    /** Adds the exercise to the running training, or starts one with it. */
    fun addToTraining(title: String) {
        val slug = loadedSlug ?: return
        if (_state.value.advice.verdict == InjuryVerdict.AVOID) {
            _state.update { it.copy(addRejected = true) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val workoutId = service.repo.openWorkout()?.id ?: service.startWorkout(null, title)
            val ok = service.addExercise(workoutId, slug)
            _state.update { if (ok) it.copy(navigateToWorkout = true) else it.copy(addRejected = true) }
        }
    }

    fun consumeEvents() = _state.update { it.copy(navigateToWorkout = false, addRejected = false) }
}

@Composable
fun ExerciseDetailScreen(
    slug: String,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onOpenWorkout: () -> Unit,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
    onOpenStats: (String) -> Unit = {},
    onCreateRoutine: (from: String) -> Unit = {},
    onOpenForceGauge: () -> Unit = {},
) {
    LaunchedEffect(slug) { viewModel.load(slug) }
    var addSheet by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    val snackbar = remember { SnackbarHostState() }
    val rejectedText = stringResource(R.string.trx_pick_rejected)

    LaunchedEffect(state.navigateToWorkout, state.addRejected) {
        when {
            state.navigateToWorkout -> { viewModel.consumeEvents(); onOpenWorkout() }
            state.addRejected -> { viewModel.consumeEvents(); snackbar.showSnackbar(rejectedText) }
        }
    }

    val def = state.definition
    TrainingScaffold(
        title = def?.name(language) ?: stringResource(R.string.tr_nav_exercises),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            if (def != null) {
                IconButton(onClick = viewModel::toggleFavorite, modifier = Modifier.testTag("exercise_detail_favorite")) {
                    Icon(
                        if (state.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(if (state.favorite) R.string.trx_favorite_remove else R.string.trx_favorite_add),
                        tint = if (state.favorite) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("exercise_detail_more")) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.tre_more_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (state.excluded) R.string.tre_include else R.string.tre_exclude)) },
                            leadingIcon = { Icon(if (state.excluded) Icons.Default.Visibility else Icons.Default.Block, null) },
                            onClick = { menu = false; viewModel.toggleExcluded() },
                            modifier = Modifier.testTag("exercise_detail_exclude"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            def == null -> EmptyHint(stringResource(R.string.trx_not_found), Modifier.padding(padding))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("exercise_detail_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    item { Header(def, language) }
                    item { VisualHeader(def) }
                    if (state.excluded) item {
                        AssistChip(onClick = viewModel::toggleExcluded, label = { Text(stringResource(R.string.tre_excluded_info)) },
                            leadingIcon = { Icon(Icons.Default.Block, null) }, modifier = Modifier.testTag("exercise_detail_excluded"))
                    }
                    if (state.advice.verdict != InjuryVerdict.OK) item { InjuryBanner(state.advice) }
                    item { TextSections(def, language) }
                    item { Facts(def) }
                    if (state.chain.size > 1) item { ChainStepper(state.chain, def.slug, language, onOpenExercise) }
                    item { com.cruxcoach.android.ui.training.benchmarks.BenchmarkCard(def.slug, onTestStarted = onOpenWorkout, onOpenForceGauge = onOpenForceGauge) }
                    item {
                        Spacer(Modifier.height(8.dp))
                        com.cruxcoach.android.ui.training.stats.ExerciseProgressCard(def.slug, onOpenStats = { onOpenStats(def.slug) })
                    }
                    if (state.bests.isNotEmpty()) item { Bests(def, state.bests, state.profile) }
                    item { History(def, state.sessions, state.profile) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
                val title = def.name(language)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { addSheet = true },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("exercise_detail_add_workout"),
                    ) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trx_to_workout))
                    }
                    Button(
                        onClick = { viewModel.addToTraining(title) },
                        enabled = state.advice.verdict != InjuryVerdict.AVOID,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("exercise_detail_add"),
                    ) {
                        Icon(if (state.openWorkout != null) Icons.Default.Add else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.openWorkout != null) R.string.trx_add_to_running else R.string.trx_start_with), maxLines = 1)
                    }
                }
                if (addSheet) {
                    com.cruxcoach.android.ui.training.workouts.AddToWorkoutSheet(
                        slug = def.slug,
                        onDismiss = { addSheet = false },
                        onTrainingStarted = { addSheet = false; onOpenWorkout() },
                        onCreateRoutine = { from -> addSheet = false; onCreateRoutine(from) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(def: ExerciseDefinition, language: String) {
    val text = def.text(language)
    Column(Modifier.fillMaxWidth()) {
        if (text.aliases.isNotEmpty()) {
            Text(text.aliases.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(categoryLabel(def.category) + " · ", style = MaterialTheme.typography.bodyMedium)
            DifficultyDots(def.difficulty)
        }
    }
}

/**
 * What the exercise trains, as a picture: the body map (front and back,
 * tap an area for its name) and, for finger work, the grip on the edge.
 */
@Composable
private fun VisualHeader(def: ExerciseDefinition) {
    val areas = remember(def.slug) { com.cruxcoach.android.ui.training.bodymap.ExerciseBodyAreas.of(def) }
    val grip = remember(def.slug) {
        com.cruxcoach.android.ui.training.bodymap.gripFor(def, null)
            ?.takeIf { def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.FINGER || def.kind == com.cruxcoach.athlete.catalog.ExerciseKind.HANG }
    }
    var tapped by remember(def.slug) { mutableStateOf<com.cruxcoach.android.ui.training.bodymap.BodyArea?>(null) }
    Card(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("exercise_detail_bodymap")) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.trb_detail_title), style = MaterialTheme.typography.titleSmall)
            com.cruxcoach.android.ui.training.bodymap.BodyMap(
                highlights = areas,
                onAreaClick = { tapped = it },
                modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                LegendSwatch(com.cruxcoach.android.ui.training.bodymap.bodyMapColor(1f))
                Text(stringResource(R.string.trb_legend_main), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp, end = 16.dp))
                LegendSwatch(com.cruxcoach.android.ui.training.bodymap.bodyMapColor(0.5f))
                Text(stringResource(R.string.trb_legend_involved), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
            val t = tapped
            Text(
                when {
                    t == null -> stringResource(R.string.trb_detail_tap)
                    (areas[t] ?: 0f) >= 1f -> stringResource(R.string.trb_selected_main, com.cruxcoach.android.ui.training.bodymap.bodyAreaLabel(t))
                    (areas[t] ?: 0f) > 0f -> stringResource(R.string.trb_selected_involved, com.cruxcoach.android.ui.training.bodymap.bodyAreaLabel(t))
                    else -> stringResource(R.string.trb_selected_none, com.cruxcoach.android.ui.training.bodymap.bodyAreaLabel(t))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag("exercise_detail_bodymap_tapped"),
            )
            if (grip != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.cruxcoach.android.ui.training.bodymap.GripPictogram(grip, def.defaults.edgeMm?.toDouble(),
                        Modifier.width(110.dp).height(76.dp).testTag("exercise_detail_grip"))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.trb_grip_title), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(com.cruxcoach.android.ui.training.bodymap.gripTypeLabel(grip), style = MaterialTheme.typography.titleSmall)
                        def.defaults.edgeMm?.let {
                            Text(stringResource(R.string.trb_edge_mm, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(12.dp).background(color, androidx.compose.foundation.shape.CircleShape))
}

@Composable
private fun InjuryBanner(advice: InjuryAdvice) {
    val injury = advice.injury
    val where = injury?.let { listOfNotNull(regionLabel(it.region), it.side?.let { s -> injurySideLabel(s) }).joinToString(" ") } ?: ""
    val message = when (advice.verdict) {
        InjuryVerdict.AVOID -> stringResource(R.string.trx_injury_avoid, where)
        InjuryVerdict.CAUTION -> stringResource(R.string.trx_injury_caution, where)
        InjuryVerdict.ONE_SIDE_ONLY -> stringResource(R.string.trx_injury_one_side, where,
            stringResource(if (advice.allowedSide == Side.LEFT) R.string.trx_side_left_lower else R.string.trx_side_right_lower))
        InjuryVerdict.OK -> return
    }
    val avoid = advice.verdict == InjuryVerdict.AVOID
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (avoid) MaterialTheme.colorScheme.errorContainer else CruxCoachDesign.colors.cautionContainer,
            contentColor = if (avoid) MaterialTheme.colorScheme.onErrorContainer else CruxCoachDesign.colors.onCautionContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("exercise_detail_injury"),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun TextSections(def: ExerciseDefinition, language: String) {
    val text = def.text(language)
    Column {
        if (text.why.isNotBlank()) {
            SectionTitle(stringResource(R.string.trx_why))
            Text(text.why, style = MaterialTheme.typography.bodyMedium)
        }
        if (text.steps.isNotEmpty()) {
            SectionTitle(stringResource(R.string.trx_steps))
            text.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(24.dp))
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (text.cues.isNotEmpty()) {
            SectionTitle(stringResource(R.string.trx_cues))
            text.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (text.mistakes.isNotEmpty()) {
            SectionTitle(stringResource(R.string.trx_mistakes))
            text.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(def: ExerciseDefinition) {
    Column {
        SectionTitle(stringResource(R.string.trx_equipment))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val needed = def.equipment.filterNot { it == EquipmentV2.NONE || it == EquipmentV2.MAT }
            (needed.ifEmpty { listOf(EquipmentV2.NONE) }).forEach { e ->
                AssistChip(onClick = {}, label = { Text(equipmentLabel(e)) })
            }
            if (def.unilateral) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.trx_unilateral)) },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) })
            }
        }
        if (def.domains.isNotEmpty()) {
            SectionTitle(stringResource(R.string.trx_loads))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                def.domains.forEach { d -> SuggestionChip(onClick = {}, label = { Text(domainLabel(d)) }) }
            }
        }
        if (def.contraindications.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.trx_contraindications, def.contraindications.map { bodyRegionLabel(it) }.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = CruxCoachDesign.colors.caution,
                    modifier = Modifier.weight(1f),
                )
                InfoButton(stringResource(R.string.trx_safety_title), stringResource(R.string.tr_not_medical_advice))
            }
        }
    }
}

@Composable
private fun bodyRegionLabel(r: BodyRegion): String = stringResource(when (r) {
    BodyRegion.FINGER -> R.string.trx_region_finger
    BodyRegion.PULLEY -> R.string.trx_region_pulley
    BodyRegion.WRIST -> R.string.tr_region_wrist
    BodyRegion.ELBOW -> R.string.tr_region_elbow
    BodyRegion.SHOULDER -> R.string.tr_region_shoulder
    BodyRegion.BACK -> R.string.tr_region_back
    BodyRegion.HIP -> R.string.tr_region_hip
    BodyRegion.KNEE -> R.string.tr_region_knee
    BodyRegion.ANKLE -> R.string.tr_region_ankle
})

@Composable
private fun ChainStepper(chain: List<ExerciseDefinition>, current: String, language: String, onOpen: (String) -> Unit) {
    Column {
        SectionTitle(stringResource(R.string.trx_progression))
        Text(stringResource(R.string.trx_progression_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentLabel = stringResource(R.string.trx_current)
            chain.forEachIndexed { i, step ->
                if (i > 0) Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                    modifier = Modifier.padding(horizontal = 4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                val isCurrent = step.slug == current
                FilterChip(
                    selected = isCurrent,
                    onClick = { if (!isCurrent) onOpen(step.slug) },
                    label = { Text(if (isCurrent) "${step.name(language)} ($currentLabel)" else step.name(language)) },
                    modifier = Modifier.testTag("exercise_chain_${step.slug}"),
                )
            }
        }
    }
}

@Composable
private fun keyLabel(key: PersonalRecords.Key): String? {
    val parts = listOfNotNull(
        key.edgeMm?.let { stringResource(R.string.tr_format_mm, it.toString()) },
        key.grip?.let { gripLabel(it) },
        key.side?.let { sideLabel(it) },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun Bests(def: ExerciseDefinition, bests: List<BestEntry>, profile: AthleteProfile) {
    Column(Modifier.testTag("exercise_detail_bests")) {
        SectionTitle(stringResource(R.string.trx_bests))
        bests.forEach { best ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(keyLabel(best.key) ?: stringResource(R.string.trx_best_any), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text(bestValue(def, best, profile), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun bestValue(def: ExerciseDefinition, best: BestEntry, profile: AthleteProfile): String = when (best.kind) {
    RecordKind.LOAD -> {
        val pct = StrengthMath.percentBodyweight(def.load, best.set.loadKg, best.set.bodyweightKg)
        formatMass(best.value, profile.units) +
            (pct?.let { " · " + stringResource(R.string.tr_format_percent_bw, it.roundToInt()) } ?: "")
    }
    RecordKind.ESTIMATED_MAX -> stringResource(R.string.trx_e1rm, formatMass(best.value, profile.units))
    RecordKind.REPS -> pluralStringResource(R.plurals.trx_reps, best.value.roundToInt(), best.value.roundToInt())
    RecordKind.DURATION -> stringResource(R.string.tr_format_seconds, best.value.roundToInt())
}

@Composable
private fun History(def: ExerciseDefinition, sessions: List<HistorySession>, profile: AthleteProfile) {
    // Nothing logged yet: the progress card above already says so; no second empty section.
    if (sessions.isEmpty()) return
    Column(Modifier.testTag("exercise_detail_history")) {
        SectionTitle(stringResource(R.string.trx_history))
        val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
        sessions.forEach { session ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        Instant.ofEpochMilli(session.completedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(formatter),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    session.sets.forEach { set ->
                        Text(setSummary(def, set, profile), style = MaterialTheme.typography.bodySmall,
                            color = if (set.setType == SetType.WARMUP) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** "L · 8× · +10 kg · 20 mm · Half crimp" — only the parts the set has. */
@Composable
internal fun setSummary(def: ExerciseDefinition, set: ExerciseSet, profile: AthleteProfile): String {
    val parts = mutableListOf<String>()
    if (set.setType != SetType.WORK) parts += setTypeLabel(set.setType)
    set.side?.let { parts += sideLabel(it) }
    set.reps?.let { parts += stringResource(R.string.trx_reps_short, it) }
    (set.durationS ?: set.workS)?.let { parts += stringResource(R.string.tr_format_seconds, it.roundToInt()) }
    set.repsPerSet?.let { parts += stringResource(R.string.trx_reps_short, it) }
    when (def.load) {
        LoadMode.BODYWEIGHT_PLUS -> parts += set.loadKg?.takeIf { it != 0.0 }?.let { formatMass(it, profile.units, signed = true) }
            ?: stringResource(R.string.tr_bodyweight)
        LoadMode.EXTERNAL -> set.loadKg?.let { parts += formatMass(it, profile.units) }
        else -> Unit
    }
    set.edgeMm?.let { parts += stringResource(R.string.tr_format_mm, formatNumber(it)) }
    set.grip?.let { parts += gripLabel(it) }
    set.rir?.let { parts += "RIR $it" }
    return parts.joinToString(" · ")
}
