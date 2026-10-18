// Port of androidApp/.../ui/training/athlete/WeeklyReviewScreen.kt's WeeklyReviewViewModel (0e1bad0c6)
// for the iOS app. Kept close to the Android file so later Android changes carry over by diff.
// Platform seams: an injected scope and IO dispatcher, check-in labels as string keys, and the
// micronutrient week (Android `MicronutrientWeek`) as an injected function, absent without food tables.
package com.cruxcoach.app.coach

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.ui.NutritionTexts
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.ConsistencyStreak
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.LoadBalance
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.PersonalRecord
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import kotlin.math.roundToInt

internal data class WeeklyReviewRunState(
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
    /** Mean calorie target of the logged days as the nutrition screen sets it: the need, less the deficit, or the own one. */
    val kcalTargetAvg: Int? = null,
    /** Energy guard with its numbers; only for the current week (it looks at the last days). */
    val energy: EnergyReport = EnergyReport(),
    /** Android string key of the check-in question (trt_q_*) and the week's mean answer. */
    val checkinAverages: List<Pair<String, Double>> = emptyList(),
    val pausedDays: Int = 0,
)

/**
 * The weekly look back (a weekly report, climber version): what happened,
 * which structures carried the load, where strength went — kept separate from
 * the fast logging screens on purpose.
 */
internal class WeeklyReviewPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Android `MicronutrientWeek.upTo`; null when the food tables are unavailable. */
    private val micronutrients: (suspend (LocalDate, Sex?, Int?) -> MicroWatch.Summary?)?,
) {
    private val _state = MutableStateFlow(WeeklyReviewRunState())
    val state: StateFlow<WeeklyReviewRunState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init { load(0) }

    fun previous() = load(_state.value.weekOffset + 1)
    fun next() = load((_state.value.weekOffset - 1).coerceAtLeast(0))

    fun close() = scope.cancel()

    private fun load(offset: Int) {
        // A quick double tap must not let an older week overwrite the newer one.
        loadJob?.cancel()
        loadJob = scope.launch(io) {
            try {
                loadNow(offset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A week that fails to load shows as empty, never as an endless spinner.
                _state.value = _state.value.copy(loading = false, weekOffset = offset)
            }
        }
    }

    private suspend fun loadNow(offset: Int) {
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
        // The targets the nutrition screen set for each logged day (holistic audit P2-18): protein and carbohydrate
        // from that day's training, own targets in their place, through the same service call.
        val dayTargets = loggedDays.mapNotNull { service.fuelTargets(activities[it], profile) }
        val carbTargets = dayTargets.map { it.carbsG }
        val micros = runCatching { micronutrients?.invoke(end, profile.sex, profile.birthYear) }.getOrNull()
        val reds = if (offset == 0) runCatching { service.energyReport(profile, service.activities(7)) }.getOrNull() else null
        // Energy per logged day against that day's estimated need (training days need more).
        val kcalByDay = food.groupBy { it.day }.mapValues { (_, list) -> list.mapNotNull { MacroTotals.energy(it)?.kcal }.sum() }
        val needs = loggedDays.mapNotNull { service.energyNeed(profile, activities[it])?.totalKcal }
        // The energy ring's target of each logged day: the need, less the day's deficit while losing weight, or the own one.
        val kcalTargets = loggedDays.mapNotNull { day ->
            val a = activities[day]
            NutritionTexts.energyTarget(service.energyNeed(profile, a), service.weightPlan(profile, a), profile)
        }
        val checkins = repo.checkinsBetween(start.toString(), end.toString())
        fun avg(values: List<Int?>) = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

        _state.value = WeeklyReviewRunState(
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
            proteinTarget = dayTargets.takeIf { it.isNotEmpty() }?.map { it.proteinG }?.average()?.roundToInt()
                ?: profile.ownProteinG ?: service.fuelTargets(null, profile)?.proteinG,
            foodDays = loggedDays.size,
            carbsAvg = if (loggedDays.isNotEmpty()) food.sumOf { it.carbsG ?: 0.0 } / loggedDays.size else null,
            fatAvg = if (loggedDays.isNotEmpty()) food.sumOf { it.fatG ?: 0.0 } / loggedDays.size else null,
            fatShare = MacroTotals.of(food).fatEnergyShare,
            carbsTargetAvg = carbTargets.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            micros = micros,
            kcalAvg = kcalByDay.values.takeIf { it.isNotEmpty() }?.average(),
            kcalNeedAvg = needs.takeIf { it.isNotEmpty() && it.size == loggedDays.size }?.average()?.roundToInt(),
            kcalTargetAvg = kcalTargets.takeIf { it.isNotEmpty() && it.size == loggedDays.size }?.average()?.roundToInt(),
            energy = reds ?: EnergyReport(),
            checkinAverages = listOfNotNull(
                avg(checkins.map { it.sleep })?.let { "trt_q_sleep" to it },
                avg(checkins.map { it.energy })?.let { "trt_q_energy" to it },
                avg(checkins.map { it.skin })?.let { "trt_q_skin" to it },
                avg(checkins.map { it.fingers })?.let { "trt_q_fingers" to it },
            ),
            pausedDays = ConsistencyStreak.weeksFrom(emptySet(), repo.pauses(), minOf(end, today), 1).firstOrNull()?.pausedDays ?: 0,
        )
    }
}
