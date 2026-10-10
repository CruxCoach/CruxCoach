package com.cruxcoach.android.ui.training.athlete

import com.cruxcoach.android.ui.training.body.shortLabel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.cruxcoach.android.foodvision.MicronutrientWeek
import com.cruxcoach.android.ui.training.fuel.MicroWeekCard
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.SetType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import javax.inject.Inject
import kotlin.math.roundToInt

data class WeeklyReviewState(
    val loading: Boolean = true,
    val weekOffset: Int = 0,
    val weekStart: LocalDate? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val trainingDays: Int = 0,
    val climbDays: Int = 0,
    val workoutDays: Int = 0,
    val boardMinutes: Int = 0,
    val workoutMinutes: Int = 0,
    val sets: Int = 0,
    val records: List<Pair<ExerciseDefinition, PersonalRecord>> = emptyList(),
    val setsByCategory: List<Pair<ExerciseCategoryV2, Int>> = emptyList(),
    val domainDays: List<Pair<LoadDomain, Int>> = emptyList(),
    val weightChangeKg: Double? = null,
    val proteinAvg: Double? = null,
    val proteinTarget: Int? = null,
    /** Days of the week with logged food. */
    val foodDays: Int = 0,
    val carbsAvg: Double? = null,
    /** Mean fat on the logged days and its share of the macro energy (the 20–35 % guideline). */
    val fatAvg: Double? = null,
    val fatShare: Double? = null,
    /** Average carbohydrate target of the logged days, from each day's training load. */
    val carbsTargetAvg: Int? = null,
    val micros: MicroWatch.Summary? = null,
    /** Mean energy eaten and mean estimated need on the logged days; need is null without weight or height. */
    val kcalAvg: Double? = null,
    val kcalNeedAvg: Int? = null,
    /** Energy guard with its numbers; only for the current week (it looks at the last days). */
    val energy: com.cruxcoach.athlete.logic.EnergyReport = com.cruxcoach.athlete.logic.EnergyReport(),
    val checkinAverages: List<Pair<Int, Double>> = emptyList(),
    val pausedDays: Int = 0,
)

/**
 * The weekly look back (MCI's weekly report, climber version): what happened,
 * which structures carried the load, where strength went — kept separate from
 * the fast logging screens on purpose.
 */
@HiltViewModel
class WeeklyReviewViewModel @Inject constructor(
    private val service: AthleteService,
    private val micronutrients: MicronutrientWeek,
) : ViewModel() {
    private val _state = MutableStateFlow(WeeklyReviewState())
    val state: StateFlow<WeeklyReviewState> = _state.asStateFlow()

    init { load(0) }

    fun previous() = load(_state.value.weekOffset + 1)
    fun next() = load((_state.value.weekOffset - 1).coerceAtLeast(0))

    private fun load(offset: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadNow(offset)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A week that fails to load shows as empty, never as an endless spinner.
                _state.value = _state.value.copy(loading = false, weekOffset = offset)
            }
        }
    }

    private suspend fun loadNow(offset: Int) {
        run {
            service.ensureReady()
            val repo = service.repo
            val profile = repo.profile()
            val today = service.today()
            val start = ConsistencyStreak.weekStart(today).minus(DatePeriod(days = offset * 7))
            val end = start.plus(DatePeriod(days = 6))
            val days = (0..6).map { start.plus(DatePeriod(days = it)).toString() }.toSet()
            val activities = service.activities(days = (offset + 1) * 7 + 7).filterKeys { it in days }
            val zone = TimeZone.currentSystemDefault()
            val fromMs = start.atStartOfDayIn(zone).toEpochMilliseconds()
            val toMs = end.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
            val weekSets = repo.completedSetsSince(fromMs).filter { (it.completedAt ?: 0) < toMs }
            val catalog = service.catalog

            val records = weekSets.sortedBy { it.completedAt }.mapNotNull { set ->
                val def = catalog.fallbackFor(set.exerciseSlug)
                val before = repo.history(set.exerciseSlug, 500).filter { (it.completedAt ?: 0) < (set.completedAt ?: 0) }
                PersonalRecords.detect(def, set, before)?.let { def to it }
            }.groupBy { it.first.slug }.map { (_, list) -> list.maxBy { it.second.value } }

            val climbDays = activities.values.filter { it.climbingMinutes > 0 || it.climbingEfforts > 0 }.map { it.day }.toSet()
            val workouts = repo.workoutsBetween(start.toString(), end.toString()).filter { !it.isOpen }
            val workoutDays = workouts.map { it.day }.toSet()
            val workoutDayById = workouts.associate { it.id to it.day }
            val domainDays = LoadDomain.entries.map { d ->
                val fromClimbing = if (d in LoadBalance.CLIMBING_DOMAINS) climbDays else emptySet()
                val fromSets = weekSets.filter { d in catalog.fallbackFor(it.exerciseSlug).domains }.mapNotNull { workoutDayById[it.workoutId] }
                d to (fromClimbing + fromSets).size
            }.filter { it.second > 0 }

            val trend = service.weightTrend()
            val startTrend = trend.lastOrNull { it.day <= start }?.trend
            val endTrend = trend.lastOrNull { it.day <= end }?.trend
            val food = repo.foodLogBetween(start.toString(), end.toString())
            val loggedDays = food.map { it.day }.toSet()
            // Carbohydrate targets follow each logged day's training load (FEAT-068).
            val carbTargets = endTrend?.let { w -> loggedDays.map { (profile.ownCarbsG ?: FuelTargets.compute(w, activities[it], profile.proteinPerKg).carbsG) } }.orEmpty()
            val micros = runCatching { micronutrients.upTo(end, profile.sex, profile.birthYear) }.getOrNull()
            val reds = if (offset == 0) runCatching { service.energyReport(profile, service.activities(7)) }.getOrNull() else null
            // Energy per logged day against that day's estimated need (training days need more).
            val kcalByDay = food.groupBy { it.day }.mapValues { (_, list) -> list.mapNotNull { MacroTotals.energy(it)?.kcal }.sum() }
            val height = service.heightCm()
            val needs = endTrend?.let { w -> loggedDays.mapNotNull { service.energyNeed(profile, activities[it], w, height)?.totalKcal } }.orEmpty()
            val checkins = repo.checkinsBetween(start.toString(), end.toString())
            fun avg(values: List<Int?>) = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

            _state.value = WeeklyReviewState(
                loading = false,
                weekOffset = offset,
                weekStart = start,
                profile = profile,
                trainingDays = (climbDays + workoutDays).size,
                climbDays = climbDays.size,
                workoutDays = workoutDays.size,
                boardMinutes = activities.values.sumOf { it.climbingMinutes },
                workoutMinutes = workouts.sumOf { it.durationMinutes ?: 0 },
                sets = weekSets.count { it.setType != SetType.WARMUP },
                records = records,
                // The same sets as the tile above: work sets, warm-ups not counted (it read "3 sets" over "9 + 2").
                setsByCategory = weekSets.filter { it.setType != SetType.WARMUP }.groupBy { catalog.fallbackFor(it.exerciseSlug).category }
                    .map { it.key to it.value.size }.sortedByDescending { it.second },
                domainDays = domainDays,
                weightChangeKg = if (startTrend != null && endTrend != null) endTrend - startTrend else null,
                proteinAvg = if (loggedDays.isNotEmpty()) food.sumOf { it.proteinG ?: 0.0 } / loggedDays.size else null,
                proteinTarget = profile.ownProteinG ?: endTrend?.let { (it * profile.proteinPerKg).roundToInt() },
                foodDays = loggedDays.size,
                carbsAvg = if (loggedDays.isNotEmpty()) food.sumOf { it.carbsG ?: 0.0 } / loggedDays.size else null,
                fatAvg = if (loggedDays.isNotEmpty()) food.sumOf { it.fatG ?: 0.0 } / loggedDays.size else null,
                fatShare = com.cruxcoach.athlete.logic.MacroTotals.of(food).fatEnergyShare,
                carbsTargetAvg = carbTargets.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                micros = micros,
                kcalAvg = kcalByDay.values.takeIf { it.isNotEmpty() }?.average(),
                kcalNeedAvg = needs.takeIf { it.isNotEmpty() && it.size == loggedDays.size }?.average()?.roundToInt(),
                energy = reds ?: com.cruxcoach.athlete.logic.EnergyReport(),
                checkinAverages = listOfNotNull(
                    avg(checkins.map { it.sleep })?.let { R.string.trt_q_sleep to it },
                    avg(checkins.map { it.energy })?.let { R.string.trt_q_energy to it },
                    avg(checkins.map { it.skin })?.let { R.string.trt_q_skin to it },
                    avg(checkins.map { it.fingers })?.let { R.string.trt_q_fingers to it },
                ),
                pausedDays = ConsistencyStreak.weeksFrom(emptySet(), repo.pauses(), end.coerceAtMost(today), 1).firstOrNull()?.pausedDays ?: 0,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeeklyReviewScreen(onBack: () -> Unit, viewModel: WeeklyReviewViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val lang = catalogLanguage()
    TrainingScaffold(title = stringResource(R.string.trt_weekly_review), onBack = onBack) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::previous, modifier = Modifier.testTag("review_prev")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.tra_review_prev))
                }
                Text(stringResource(R.string.tra_review_week_of, s.weekStart?.shortLabel().orEmpty()), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::next, enabled = s.weekOffset > 0, modifier = Modifier.testTag("review_next")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.tra_review_next))
                }
            }
            val goalReached = s.trainingDays >= s.profile.weeklyGoal
            Text(
                if (goalReached) stringResource(R.string.tra_review_goal_reached, s.trainingDays, s.profile.weeklyGoal)
                else stringResource(R.string.tra_review_goal_open, s.trainingDays, s.profile.weeklyGoal),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (s.pausedDays > 0) Text(pluralStringResource(R.plurals.tra_review_paused, s.pausedDays, s.pausedDays),
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.tra_review_climb_days), "${s.climbDays}", Modifier.weight(1f),
                    supporting = pluralStringResource(R.plurals.trt_minutes, s.boardMinutes, s.boardMinutes))
                StatTile(stringResource(R.string.tra_review_workout_days), "${s.workoutDays}", Modifier.weight(1f),
                    supporting = pluralStringResource(R.plurals.trt_minutes, s.workoutMinutes, s.workoutMinutes))
                StatTile(stringResource(R.string.tra_review_sets), "${s.sets}", Modifier.weight(1f))
            }
            SectionTitle(stringResource(R.string.tra_review_records))
            if (s.records.isEmpty()) Text(stringResource(R.string.tra_review_no_records), style = MaterialTheme.typography.bodySmall)
            s.records.forEach { (def, pr) ->
                ListItem(headlineContent = { Text(def.name(lang)) },
                    supportingContent = { Text(recordText(pr, s.profile)) })
            }
            if (s.domainDays.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tra_review_load))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.domainDays.forEach { (d, n) ->
                        AssistChip(onClick = {}, label = { Text(pluralStringResource(R.plurals.tra_review_domain_days, n, domainLabel(d), n)) })
                    }
                }
            }
            if (s.setsByCategory.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tra_review_categories))
                s.setsByCategory.forEach { (c, n) ->
                    Row { Text(categoryLabel(c), Modifier.weight(1f)); Text("$n") }
                }
            }
            if (s.profile.bodyEnabled && s.weightChangeKg != null) {
                SectionTitle(stringResource(R.string.tr_nav_body))
                Text(
                    if (s.profile.hideBodyNumbers) stringResource(when {
                        s.weightChangeKg!! > 0.15 -> R.string.tra_review_weight_up
                        s.weightChangeKg!! < -0.15 -> R.string.tra_review_weight_down
                        else -> R.string.tra_review_weight_stable
                    }) else stringResource(R.string.tra_review_weight_change, formatMass(s.weightChangeKg!!, s.profile.units, signed = true)),
                )
            }
            if (s.energy.signals.isNotEmpty()) EnergyCareCard(s.energy, s.profile, "review_reds", Modifier.padding(top = 12.dp))
            if (s.proteinAvg != null) {
                SectionTitle(stringResource(R.string.tr_nav_fuel))
                Text(pluralStringResource(R.plurals.tra_review_food_days, s.foodDays, s.foodDays), style = MaterialTheme.typography.bodySmall)
                // Without a body weight there is no target to name.
                Text(s.proteinTarget?.let { stringResource(R.string.tra_review_protein, s.proteinAvg!!.roundToInt(), it) }
                    ?: stringResource(R.string.tra_review_protein_no_target, s.proteinAvg!!.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium)
                if (s.carbsAvg != null && s.carbsTargetAvg != null) {
                    Text(stringResource(R.string.tra_review_carbs, s.carbsAvg!!.roundToInt(), s.carbsTargetAvg!!),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("review_carbs"))
                }
                // Fat like the fourth ring in Nutrition: grams and the share of energy the 20–35 % guideline talks about.
                if (s.fatAvg != null && s.fatShare != null) {
                    Text(stringResource(R.string.tra_review_fat, s.fatAvg!!.roundToInt(), (s.fatShare!! * 100).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("review_fat"))
                }
                if (s.profile.showCalories && s.kcalAvg != null) {
                    Text(s.kcalNeedAvg?.let { stringResource(R.string.trn_review_energy, s.kcalAvg!!.roundToInt(), it) }
                        ?: stringResource(R.string.trn_review_energy_no_need, s.kcalAvg!!.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("review_energy"))
                }
                s.micros?.let { MicroWeekCard(it, Modifier.padding(top = 8.dp)) }
            }
            if (s.checkinAverages.isNotEmpty()) {
                SectionTitle(stringResource(R.string.tra_review_checkins))
                s.checkinAverages.forEach { (label, value) ->
                    Row { Text(stringResource(label), Modifier.weight(1f)); Text(formatNumber(value)) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun recordText(pr: PersonalRecord, profile: AthleteProfile): String = when (pr.kind) {
    RecordKind.LOAD -> formatMass(pr.value, profile.units)
    RecordKind.ESTIMATED_MAX -> stringResource(R.string.tra_review_e1rm, formatMass(pr.value, profile.units))
    RecordKind.REPS -> pluralStringResource(R.plurals.tra_reps, pr.value.roundToInt(), pr.value.roundToInt())
    RecordKind.DURATION -> stringResource(R.string.tr_format_seconds, pr.value.roundToInt())
}
