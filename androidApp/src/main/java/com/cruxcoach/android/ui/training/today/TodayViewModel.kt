package com.cruxcoach.android.ui.training.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import javax.inject.Inject

/** One day in the week strip. */
data class WeekDay(val date: LocalDate, val climbed: Boolean, val trained: Boolean, val paused: Boolean, val isToday: Boolean)

/**
 * Gentle nudges on the hub, each with one action. What to train today is the
 * daily suggestion's job ([SessionSuggester]); these only cover setup.
 */
enum class TodaySuggestion { CONFIGURE_EQUIPMENT, SET_BENCHMARKS, BASELINE_TEST, LOG_WEIGHT }

data class TodayState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val activity: DayActivity? = null,
    val dayLoad: DayLoad = DayLoad.REST,
    val checkin: Checkin? = null,
    val readiness: Readiness = ReadinessEvaluator.evaluate(null, emptyList()),
    val injuries: List<Injury> = emptyList(),
    val openPause: PausePeriod? = null,
    val openWorkout: Workout? = null,
    val todaysWorkouts: List<Workout> = emptyList(),
    val streak: StreakState? = null,
    val week: List<WeekDay> = emptyList(),
    val trendKg: Double? = null,
    val weeklyRateKg: Double? = null,
    val lastWeighDay: String? = null,
    val fuelTargets: FuelTargets.Targets? = null,
    val proteinToday: Double = 0.0,
    val carbsToday: Double = 0.0,
    val waterTodayMl: Int = 0,
    val redsSignals: List<RedsSignal> = emptyList(),
    val loadSpikes: List<LoadDomain> = emptyList(),
    val suggestions: List<TodaySuggestion> = emptyList(),
    val startedWorkout: Boolean = false,
    /** The started training should open in the guided player (routines), not the list. */
    val startedGuided: Boolean = false,
    /** Today's suggested training (MCI-style), null while a training is open. */
    val suggestion: SessionSuggestion? = null,
    /** Name of the athlete's own routine when the week plan picked it. */
    val plannedName: String? = null,
    val suggestionSaved: Boolean = false,
    /** For exercise names on the suggestion card. */
    val catalog: com.cruxcoach.athlete.catalog.ExerciseCatalog = com.cruxcoach.athlete.catalog.ExerciseCatalog.EMPTY,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            // The day the hub shows follows the clock, so a screen left open
            // over midnight switches to the new day's check-in, food and water.
            val days = flow { while (true) { emit(service.today().toString()); delay(60_000) } }.distinctUntilChanged()
            // Any change in these tables re-derives the whole hub; it is cheap.
            days.flatMapLatest { today -> combine(
                repo.observeProfile(),
                repo.observeCheckin(today),
                repo.observeActiveInjuries(),
                repo.observeOpenWorkout(),
                combine(
                    repo.observeRecentWorkouts(30),
                    repo.observeSeries(BodyMetric.WEIGHT.key),
                    repo.observeFoodLog(today),
                    repo.observeHydration(today),
                    repo.observePauses(),
                ) { _, _, food, water, pauses -> Triple(food, water, pauses) },
            ) { profile, checkin, injuries, open, extra -> Inputs(profile, checkin, injuries, open, extra.first, extra.second, extra.third) } }
                .collect { refresh(it) }
        }
    }

    private data class Inputs(
        val profile: AthleteProfile, val checkin: Checkin?, val injuries: List<Injury>, val open: Workout?,
        val food: List<FoodLogEntry>, val water: List<HydrationEntry>, val pauses: List<PausePeriod>,
    )

    private var lastInputs: Inputs? = null
    /** "Another suggestion" counter; resets with the day. */
    private var variant = 0
    private var variantDay: LocalDate? = null

    private fun refresh(i: Inputs) {
        lastInputs = i
        val today = service.today()
        if (variantDay != today) { variant = 0; variantDay = today }
        val activities = service.activities(days = 7 * 26)
        val activity = activities[today.toString()]
        val readiness = ReadinessEvaluator.evaluate(i.checkin, i.injuries)
        val trend = service.weightTrend()
        val streak = service.streak(i.profile, activities)
        val weekStart = ConsistencyStreak.weekStart(today)
        val openPause = i.pauses.firstOrNull { it.endDay == null }
        val week = (0 until 7).map { offset ->
            val date = weekStart.plus(DatePeriod(days = offset))
            val a = activities[date.toString()]
            WeekDay(
                date = date,
                climbed = a != null && (a.climbingMinutes > 0 || a.climbingEfforts > 0),
                trained = a != null && a.workoutMinutes > 0,
                paused = i.pauses.any { p -> covers(p, date, today) },
                isToday = date == today,
            )
        }
        val repo = service.repo
        val recentSets = repo.completedSetsSince(System.currentTimeMillis() - 28L * 24 * 3600 * 1000)
        val workoutDays = repo.workoutsBetween(today.minus(DatePeriod(days = 27)).toString(), today.toString())
            .associate { it.id to LocalDate.parse(it.day) }
        val climbingDays = activities.values.filter { it.climbingMinutes > 0 || it.climbingEfforts > 0 }
            .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet()
        val spikes = LoadBalance.compute(today, climbingDays,
            recentSets.mapNotNull { s -> workoutDays[s.workoutId]?.let { it to s } }, service.catalog)
            .filter { it.spike }.map { it.domain }

        val suggestions = buildList {
            if (!i.profile.equipmentConfigured) add(TodaySuggestion.CONFIGURE_EQUIPMENT)
            val benchmarks = repo.allBenchmarks()
            if (benchmarks.isEmpty()) add(TodaySuggestion.SET_BENCHMARKS)
            val lastTest = benchmarks.maxOfOrNull { it.measuredAt } ?: 0L
            val eightWeeks = 56L * 24 * 3600 * 1000
            if (benchmarks.isNotEmpty() && i.injuries.isEmpty() && System.currentTimeMillis() - lastTest > eightWeeks) add(TodaySuggestion.BASELINE_TEST)
            if (i.profile.bodyEnabled && trend.isEmpty()) add(TodaySuggestion.LOG_WEIGHT)
        }

        val (daily, plannedName) = if (i.open != null) null to null else suggest(i, readiness, activities)

        _state.update {
            it.copy(
                loading = false,
                suggestion = daily,
                plannedName = plannedName,
                catalog = service.catalog,
                profile = i.profile,
                today = today,
                activity = activity,
                dayLoad = FuelTargets.dayLoad(activity),
                checkin = i.checkin,
                readiness = readiness,
                injuries = i.injuries,
                openPause = openPause,
                openWorkout = i.open,
                todaysWorkouts = repo.workoutsBetween(today.toString(), today.toString()).filter { w -> !w.isOpen },
                streak = streak,
                week = week,
                trendKg = trend.lastOrNull()?.trend,
                weeklyRateKg = TrendWeight.weeklyRate(trend),
                lastWeighDay = trend.lastOrNull()?.day?.toString(),
                fuelTargets = service.fuelTargets(activity, i.profile),
                proteinToday = i.food.sumOf { e -> e.proteinG ?: 0.0 },
                carbsToday = i.food.sumOf { e -> e.carbsG ?: 0.0 },
                waterTodayMl = i.water.sumOf { w -> w.ml },
                redsSignals = service.redsSignals(i.profile, activities),
                loadSpikes = spikes,
                suggestions = suggestions,
            )
        }
    }

    /** Builds the engine input from the last two weeks; a failure only hides the card. */
    private fun suggest(i: Inputs, readiness: Readiness, activities: Map<String, DayActivity>): Pair<SessionSuggestion?, String?> =
        runCatching {
            val repo = service.repo
            val today = service.today()
            val zone = java.time.ZoneId.systemDefault()
            val workoutDays = repo.workoutsBetween(today.minus(DatePeriod(days = 8)).toString(), today.toString())
                .associate { it.id to LocalDate.parse(it.day) }
            val recentSets = repo.completedSetsSince(System.currentTimeMillis() - 9L * 24 * 3600 * 1000)
                .mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
            val lastTrained = repo.trainedExercises().mapNotNull { t ->
                t.lastAt?.let { ms -> t.slug to LocalDate.parse(java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()) }
            }.toMap()
            val routines = repo.routines()
            val input = SuggestionInput(
                catalog = service.catalog,
                profile = i.profile,
                today = today,
                readiness = readiness,
                injuries = i.injuries,
                activities = activities.mapNotNull { (day, a) -> runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to a } }.toMap(),
                recentSets = recentSets,
                favorites = repo.favorites(),
                benchmarkSlugs = repo.allBenchmarks().map { it.exerciseSlug }.toSet(),
                lastTrained = lastTrained,
                routines = routines,
                variant = variant,
            )
            val suggestion = SessionSuggester.suggest(input)
            val plannedName = suggestion.plannedEntry
                ?.takeIf { !it.startsWith("builtin:") && it != PLAN_BOARD && it != PLAN_REST }
                ?.let { id -> routines.firstOrNull { it.id == id }?.name }
            suggestion to plannedName
        }.getOrElse { null to null }

    /** "Another suggestion": same rules, different picks. */
    fun nextSuggestion() = io {
        variant++
        lastInputs?.let { refresh(it) }
    }

    /** Starts today's suggestion in the guided player; [title] is the localized focus title. */
    fun startSuggestion(title: String) = io {
        val suggestion = _state.value.suggestion ?: return@io
        service.startWorkout(suggestion.routine.copy(id = "suggestion:${service.today()}", name = title), title)
        _state.update { it.copy(startedWorkout = true, startedGuided = true) }
    }

    /** Keeps the suggestion as one of the athlete's own workouts. */
    fun saveSuggestion(title: String) = io {
        val suggestion = _state.value.suggestion ?: return@io
        val now = System.currentTimeMillis()
        service.repo.saveRoutine(suggestion.routine.copy(id = service.repo.newId(), name = title, builtinKey = null,
            createdAt = now, updatedAt = now))
        _state.update { it.copy(suggestionSaved = true) }
    }

    fun consumeSuggestionSaved() = _state.update { it.copy(suggestionSaved = false) }

    private fun covers(p: PausePeriod, day: LocalDate, today: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(p.startDay) }.getOrNull() ?: return false
        val end = p.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        return day in start..end
    }

    fun saveCheckin(sleep: Int?, energy: Int?, skin: Int?, fingers: Int?, sick: Boolean) = io {
        val day = service.today().toString()
        service.repo.saveCheckin(Checkin(day, System.currentTimeMillis(), sleep, energy, skin, fingers, sick = sick))
        if (sick && service.repo.openPause() == null) {
            service.repo.savePause(PausePeriod(service.repo.newId(), PauseReason.ILLNESS, day))
        }
    }

    fun clearCheckin() = io { service.repo.deleteCheckin(service.today().toString()) }

    fun endPause() = io {
        val open = service.repo.openPause() ?: return@io
        service.repo.savePause(open.copy(endDay = service.today().toString()))
    }

    fun startRoutine(key: String) = io {
        val routine = BuiltinRoutines.byKey(key) ?: return@io
        service.startWorkout(routine, null)
        _state.update { it.copy(startedWorkout = true, startedGuided = true) }
    }

    fun startEmptyWorkout() = io {
        service.startWorkout(null, null)
        _state.update { it.copy(startedWorkout = true, startedGuided = false) }
    }

    fun consumeStartedWorkout() = _state.update { it.copy(startedWorkout = false, startedGuided = false) }

    fun addWater(ml: Int) = io { service.repo.addHydration(service.today().toString(), ml) }

    fun logWeight(kg: Double) = io {
        val now = System.currentTimeMillis()
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", now))
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}
