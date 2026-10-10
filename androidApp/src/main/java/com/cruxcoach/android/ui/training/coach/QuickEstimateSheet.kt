package com.cruxcoach.android.ui.training.coach

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Answer to "how many pull-ups in a row?". */
enum class PullAnswer(val reps: Int?) { NONE(0), FEW(2), SOME(5), MANY(10), LOTS(15), WEIGHTED(null) }

/** Answer to "10 s on a 20 mm edge, both hands?". */
enum class HangAnswer { CANNOT, BARELY, EASY, WEIGHTED }

data class QuickEstimateState(
    val loading: Boolean = true,
    val units: UnitSystem = UnitSystem.METRIC,
    val bodyweight: Double? = null,
    val hasPickup: Boolean = false,
    /** Two-hand hangs are off while a finger injury rules them out. */
    val hangAllowed: Boolean = true,
    /** Healthy side for one-arm pick-ups during a one-sided injury; null = both. */
    val pickupSide: Side? = null,
    val pickupAllowed: Boolean = true,
    /** Anchor for the plausibility hint: logbook working grade or the athlete's own grade. */
    val workingDifficulty: Double? = null,
    val increment: Double = 1.0,
)

sealed interface QuickEstimateEvent { data object Saved : QuickEstimateEvent }

/**
 * The 15-second self-estimate of the coach setup (FEAT-071 §4.2). Answers
 * become performance values marked ESTIMATE; the first logged set replaces
 * them either way. The plausibility check against the grade is a hint only.
 */
@HiltViewModel
class QuickEstimateViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(QuickEstimateState())
    val state: StateFlow<QuickEstimateState> = _state.asStateFlow()
    private val _events = Channel<QuickEstimateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val profile = repo.profile()
            val injuries = repo.activeInjuries()
            val catalog = service.catalog
            val hang = catalog["finger.max_hang"]?.let { InjuryAdvisor.assess(it, injuries) }
            val pickup = catalog["finger.one_arm_pickup"]?.let { InjuryAdvisor.assess(it, injuries) }
            val logbook = runCatching { service.gradeSummary() }.getOrNull()
            val working = profile.coach.currentGrade?.let { LogbookSummaries.difficultyOf(it) }
                ?: logbook?.takeIf { it.hasGrades }?.workingDifficulty
            _state.value = QuickEstimateState(
                loading = false,
                units = profile.units,
                bodyweight = service.currentBodyweight(),
                // A block at any place counts: values are entered once, wherever they were measured.
                hasPickup = EquipmentV2.satisfied(EquipmentV2.PICKUP_BLOCK, com.cruxcoach.athlete.logic.TrainingPlaces.allEquipment(profile)),
                hangAllowed = hang?.verdict != InjuryVerdict.AVOID,
                pickupSide = pickup?.takeIf { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }?.allowedSide,
                pickupAllowed = pickup?.verdict != InjuryVerdict.AVOID,
                workingDifficulty = working,
                increment = profile.smallestIncrementKg,
            )
        }
    }

    /** Body weight entered on the sheet itself: the percentages below need it. */
    fun logWeight(kg: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(service.today().toString(),
                com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, kg, "kg", System.currentTimeMillis()))
            _state.update { it.copy(bodyweight = kg) }
        }
    }

    fun save(
        pull: PullAnswer?, weightedKg: Double?, weightedReps: Int?,
        hang: HangAnswer?, hangKg: Double?,
        pickupLeftKg: Double?, pickupRightKg: Double?,
    ) {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val now = System.currentTimeMillis()
            val bw = s.bodyweight
            fun bench(slug: String, side: Side? = null, load: Double? = null, reps: Int? = null, duration: Double? = null, edge: Double? = null) =
                Benchmark(id = repo.newId(), exerciseSlug = slug, side = side, edgeMm = edge, grip = edge?.let { Grip.HALF_CRIMP },
                    loadKg = load, reps = reps, durationS = duration, bodyweightKg = bw, source = BenchmarkSource.ESTIMATE, measuredAt = now)
            val values = buildList {
                when {
                    pull == PullAnswer.WEIGHTED && weightedKg != null && (weightedReps ?: 0) > 0 ->
                        add(bench("pull.weighted_pull_up", load = weightedKg, reps = weightedReps))
                    pull != null && (pull.reps ?: 0) > 0 -> add(bench("pull.pull_up", reps = pull.reps))
                }
                if (s.hangAllowed) when (hang) {
                    HangAnswer.BARELY -> add(bench("finger.max_hang", load = 0.0, duration = 10.0, edge = 20.0))
                    // "Easy" for 10 s: a tenth of body weight added is the usual first guess.
                    HangAnswer.EASY -> add(bench("finger.max_hang", duration = 10.0, edge = 20.0,
                        load = StrengthMath.roundTo((bw ?: 70.0) * 0.1, s.increment.coerceAtLeast(0.5))))
                    HangAnswer.WEIGHTED -> hangKg?.let { add(bench("finger.max_hang", load = it, duration = 10.0, edge = 20.0)) }
                    else -> Unit
                }
                if (s.hasPickup && s.pickupAllowed) {
                    pickupLeftKg?.takeIf { it > 0 && s.pickupSide != Side.RIGHT }?.let {
                        add(bench("finger.one_arm_pickup", side = Side.LEFT, load = it, duration = 10.0, edge = 20.0))
                    }
                    pickupRightKg?.takeIf { it > 0 && s.pickupSide != Side.LEFT }?.let {
                        add(bench("finger.one_arm_pickup", side = Side.RIGHT, load = it, duration = 10.0, edge = 20.0))
                    }
                }
            }
            values.forEach { service.saveBenchmark(it) }
            _events.send(QuickEstimateEvent.Saved)
        }
    }

    companion object {
        /** Finger strength as total load in % body weight (10 s, 20 mm, both hands). */
        fun fingerPct(hang: HangAnswer?, hangKg: Double?, bw: Double?, increment: Double): Double? {
            bw ?: return null
            val added = when (hang) {
                HangAnswer.BARELY -> 0.0
                HangAnswer.EASY -> StrengthMath.roundTo(bw * 0.1, increment.coerceAtLeast(0.5))
                HangAnswer.WEIGHTED -> hangKg ?: return null
                else -> return null
            }
            return (bw + added) / bw * 100.0
        }

        /** Pull-up 1RM as total load in % body weight (Epley). */
        fun pullPct(pull: PullAnswer?, weightedKg: Double?, weightedReps: Int?, bw: Double?): Double? {
            bw ?: return null
            return when {
                pull == PullAnswer.WEIGHTED && weightedKg != null && (weightedReps ?: 0) > 0 ->
                    StrengthMath.epley(bw + weightedKg, weightedReps!!) / bw * 100.0
                pull != null && (pull.reps ?: 0) > 0 -> StrengthMath.epley(bw, pull.reps!!) / bw * 100.0
                else -> null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickEstimateSheet(onDismiss: () -> Unit) {
    val viewModel: QuickEstimateViewModel = hiltViewModel(key = "quick-estimate")
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.events.collect { if (it is QuickEstimateEvent.Saved) onDismiss() } }
    var pull by rememberSaveable { mutableStateOf<PullAnswer?>(null) }
    var weightedKg by rememberSaveable { mutableStateOf("") }
    var weightedReps by rememberSaveable { mutableStateOf("") }
    var hang by rememberSaveable { mutableStateOf<HangAnswer?>(null) }
    var hangKg by rememberSaveable { mutableStateOf("") }
    var leftKg by rememberSaveable { mutableStateOf("") }
    var rightKg by rememberSaveable { mutableStateOf("") }
    var bodyText by rememberSaveable { mutableStateOf("") }
    val units = s.units
    fun kg(text: String): Double? = parseDecimal(text)?.let { Units.massFromDisplay(it, units) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("quick_estimate_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trc_estimate_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.trc_estimate_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                return@Column
            }

            // 0. Body weight, when it is missing: asked right here instead of a hint to enter it elsewhere.
            if (s.bodyweight == null) {
                Text(stringResource(R.string.trc_estimate_no_weight), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(bodyText, { bodyText = it.take(6) }, singleLine = true,
                        label = { Text(stringResource(R.string.tru_weight_title) + " (" + Units.massUnit(units) + ")") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("estimate_bodyweight"))
                    Spacer(Modifier.width(8.dp))
                    val bodyKg = kg(bodyText)
                    FilledTonalButton(onClick = { bodyKg?.let(viewModel::logWeight) },
                        enabled = bodyKg != null && bodyKg in com.cruxcoach.android.ui.training.common.PLAUSIBLE_WEIGHT_KG,
                        modifier = Modifier.testTag("estimate_bodyweight_save")) { Text(stringResource(R.string.tr_action_save)) }
                }
            }

            // 1. Pull-ups
            Text(stringResource(R.string.trc_estimate_pull), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PullAnswer.entries.forEach { a ->
                    FilterChip(selected = pull == a, onClick = { pull = if (pull == a) null else a },
                        label = { Text(pullLabel(a)) }, modifier = Modifier.testTag("estimate_pull_${a.name.lowercase()}"))
                }
            }
            if (pull == PullAnswer.WEIGHTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(weightedKg, { weightedKg = it.take(6) }, singleLine = true,
                        label = { Text(stringResource(R.string.trc_estimate_added, Units.massUnit(units))) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("estimate_pull_kg"))
                    OutlinedTextField(weightedReps, { weightedReps = it.filter(Char::isDigit).take(2) }, singleLine = true,
                        label = { Text(stringResource(R.string.trc_estimate_reps)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("estimate_pull_reps"))
                }
            }
            PlausibilityHint(GradeStrengthNorms.Metric.PULL_UP_1RM,
                QuickEstimateViewModel.pullPct(pull, kg(weightedKg), weightedReps.toIntOrNull(), s.bodyweight), s.workingDifficulty)

            // 2. Two-hand hang
            Text(stringResource(R.string.trc_estimate_hang), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            if (!s.hangAllowed) {
                Text(stringResource(R.string.trc_estimate_injury_skip), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HangAnswer.entries.forEach { a ->
                        FilterChip(selected = hang == a, onClick = { hang = if (hang == a) null else a },
                            label = { Text(hangLabel(a)) }, modifier = Modifier.testTag("estimate_hang_${a.name.lowercase()}"))
                    }
                }
                if (hang == HangAnswer.WEIGHTED) {
                    OutlinedTextField(hangKg, { hangKg = it.take(6) }, singleLine = true,
                        label = { Text(stringResource(R.string.trc_estimate_added, Units.massUnit(units))) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("estimate_hang_kg"))
                }
                if (hang == HangAnswer.CANNOT) {
                    Text(stringResource(R.string.trc_estimate_hang_cannot), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlausibilityHint(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM,
                    QuickEstimateViewModel.fingerPct(hang, kg(hangKg), s.bodyweight, s.increment), s.workingDifficulty)
            }

            // 3. One-arm pick-up, per hand
            if (s.hasPickup && s.pickupAllowed) {
                Text(stringResource(R.string.trc_estimate_pickup), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(leftKg, { leftKg = it.take(6) }, singleLine = true, enabled = s.pickupSide != Side.RIGHT,
                        label = { Text(sideLabel(Side.LEFT) + " (" + Units.massUnit(units) + ")") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("estimate_pickup_left"))
                    OutlinedTextField(rightKg, { rightKg = it.take(6) }, singleLine = true, enabled = s.pickupSide != Side.LEFT,
                        label = { Text(sideLabel(Side.RIGHT) + " (" + Units.massUnit(units) + ")") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("estimate_pickup_right"))
                }
                if (s.pickupSide != null) {
                    Text(stringResource(R.string.trc_estimate_healthy_side), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text(stringResource(R.string.trc_estimate_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))

            val anything = (pull != null && pull != PullAnswer.NONE) || (hang != null && hang != HangAnswer.CANNOT) ||
                (kg(leftKg) ?: 0.0) > 0 || (kg(rightKg) ?: 0.0) > 0
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.save(pull, kg(weightedKg), weightedReps.toIntOrNull(), hang, kg(hangKg), kg(leftKg), kg(rightKg)) },
                    enabled = anything,
                    modifier = Modifier.testTag("estimate_save"),
                ) { Text(stringResource(R.string.tr_action_save)) }
            }
        }
    }
}

/**
 * Strength in % body weight, placed against the grade orientation band:
 * "typisch für etwa 7a", or "ungewöhnlich hoch – Eingabe prüfen?" — a hint,
 * never a block.
 */
@Composable
internal fun PlausibilityHint(metric: GradeStrengthNorms.Metric, pct: Double?, difficulty: Double?) {
    pct ?: return
    val text = buildString {
        append(stringResource(R.string.trc_estimate_pct, pct.roundToInt()))
        if (difficulty != null) {
            val verdict = GradeStrengthNorms.plausibility(metric, difficulty, pct)
            val grade = LogbookSummaries.fontOf(difficulty) ?: ""
            when (verdict) {
                GradeStrengthNorms.Plausibility.UNUSUALLY_HIGH -> append(" · " + stringResource(R.string.trc_estimate_high, grade))
                GradeStrengthNorms.Plausibility.UNUSUALLY_LOW -> append(" · " + stringResource(R.string.trc_estimate_low, grade))
                GradeStrengthNorms.Plausibility.PLAUSIBLE -> {
                    val typical = LogbookSummaries.fontOf(GradeStrengthNorms.typicalDifficulty(metric, pct))
                    if (typical != null) append(" · " + stringResource(R.string.trc_estimate_typical, typical))
                }
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp).testTag("estimate_hint_${metric.name.lowercase()}"))
}

@Composable
private fun pullLabel(a: PullAnswer): String = stringResource(when (a) {
    PullAnswer.NONE -> R.string.trc_estimate_pull_0
    PullAnswer.FEW -> R.string.trc_estimate_pull_1_3
    PullAnswer.SOME -> R.string.trc_estimate_pull_4_7
    PullAnswer.MANY -> R.string.trc_estimate_pull_8_12
    PullAnswer.LOTS -> R.string.trc_estimate_pull_13
    PullAnswer.WEIGHTED -> R.string.trc_estimate_pull_weighted
})

@Composable
private fun hangLabel(a: HangAnswer): String = stringResource(when (a) {
    HangAnswer.CANNOT -> R.string.trc_estimate_hang_no
    HangAnswer.BARELY -> R.string.trc_estimate_hang_barely
    HangAnswer.EASY -> R.string.trc_estimate_hang_easy
    HangAnswer.WEIGHTED -> R.string.trc_estimate_hang_weighted
})
