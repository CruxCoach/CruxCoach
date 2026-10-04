package com.cruxcoach.android.ui.training.exercises

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
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
) {
    LaunchedEffect(slug) { viewModel.load(slug) }
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
                    if (state.advice.verdict != InjuryVerdict.OK) item { InjuryBanner(state.advice) }
                    item { TextSections(def, language) }
                    item { Facts(def) }
                    if (state.chain.size > 1) item { ChainStepper(state.chain, def.slug, language, onOpenExercise) }
                    if (state.bests.isNotEmpty()) item { Bests(def, state.bests, state.profile) }
                    item { History(def, state.sessions, state.profile) }
                    item { Spacer(Modifier.height(16.dp)) }
                }
                val title = def.name(language)
                Button(
                    onClick = { viewModel.addToTraining(title) },
                    enabled = state.advice.verdict != InjuryVerdict.AVOID,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("exercise_detail_add"),
                ) {
                    Icon(if (state.openWorkout != null) Icons.Default.Add else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.openWorkout != null) R.string.trx_add_to_running else R.string.trx_start_with))
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
    Column(Modifier.testTag("exercise_detail_history")) {
        SectionTitle(stringResource(R.string.trx_history))
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.trx_history_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
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
