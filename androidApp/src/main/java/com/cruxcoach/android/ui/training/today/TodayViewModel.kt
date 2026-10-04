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

/** Gentle nudges on the hub, each with one action. */
enum class TodaySuggestion { CONFIGURE_EQUIPMENT, SET_BENCHMARKS, ANTAGONIST_AFTER_BOARD, INJURY_ROUTINE, BASELINE_TEST, LOG_WEIGHT }

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

    private fun refresh(i: Inputs) {
        val today = service.today()
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
            if (i.injuries.any { it.climbingPaused } || readiness.avoidClimbing && i.injuries.isNotEmpty()) add(TodaySuggestion.INJURY_ROUTINE)
            val boardToday = activity != null && (activity.climbingMinutes > 0 || activity.climbingEfforts > 0)
            if (boardToday && (activity?.workoutMinutes ?: 0) == 0 && i.injuries.isEmpty()) add(TodaySuggestion.ANTAGONIST_AFTER_BOARD)
            val benchmarks = repo.allBenchmarks()
            if (benchmarks.isEmpty()) add(TodaySuggestion.SET_BENCHMARKS)
            val lastTest = benchmarks.maxOfOrNull { it.measuredAt } ?: 0L
            val eightWeeks = 56L * 24 * 3600 * 1000
            if (benchmarks.isNotEmpty() && i.injuries.isEmpty() && System.currentTimeMillis() - lastTest > eightWeeks) add(TodaySuggestion.BASELINE_TEST)
            if (i.profile.bodyEnabled && trend.isEmpty()) add(TodaySuggestion.LOG_WEIGHT)
        }

        _state.update {
            it.copy(
                loading = false,
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
