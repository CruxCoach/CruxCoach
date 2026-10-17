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
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import javax.inject.Inject

/** A level step kept from [offer]: [before] the kept step for the kind, [todayShift] today's choice, [eventIds] the ledger entries. */
data class LevelKept(val offer: PreferenceLearning.LevelOffer, val before: Int?, val todayShift: Int, val eventIds: List<String>)

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
    /** Energy eaten today (logged kcal, else from the macros); null without entries. */
    val kcalToday: Double? = null,
    /** Today's estimated need and, while losing weight, the plan behind the calorie target. */
    val need: com.cruxcoach.athlete.logic.EnergyBalance.Need? = null,
    /** Today's weight-loss plan as today's targets see it (planned training included, audit W4). */
    val fuelPlan: com.cruxcoach.athlete.logic.WeightPlan.Plan? = null,
    /** The last seven days' needs: the goal sheet says what the chosen pace really adds up to. */
    val weekNeeds: List<com.cruxcoach.athlete.logic.EnergyBalance.Need> = emptyList(),
    val heightCm: Double? = null,
    val energy: com.cruxcoach.athlete.logic.EnergyReport = com.cruxcoach.athlete.logic.EnergyReport(),
    val loadSpikes: List<LoadDomain> = emptyList(),
    val suggestions: List<TodaySuggestion> = emptyList(),
    val startedWorkout: Boolean = false,
    /** The started training should open in the guided player (routines), not the list. */
    val startedGuided: Boolean = false,
    /** Today's suggested training, null while a training is open. */
    val suggestion: SessionSuggestion? = null,
    /** Name of the athlete's own routine when the week plan picked it. */
    val plannedName: String? = null,
    val suggestionSaved: Boolean = false,
    /** For exercise names on the suggestion card. */
    val catalog: com.cruxcoach.athlete.catalog.ExerciseCatalog = com.cruxcoach.athlete.catalog.ExerciseCatalog.EMPTY,
    /** How today's form will change the suggestion when it starts (sets, load): the card shows the same sets. */
    val dayForm: com.cruxcoach.athlete.logic.ReadinessModifier? = null,
    /** Turned down for the third time: the card asks once whether to stop suggesting it (comparison review #8). */
    val neverOffer: String? = null,
    /** Position in the training cycle (FEAT-071), null without a block. */
    val block: BlockState? = null,
    /** The athlete's real training length when it differs clearly from the preferred one. */
    val durationHint: Int? = null,
    /** Body weight for the performance values in the evidence line. */
    val bodyweightKg: Double? = null,
    /** This week's volume per area (weekly plan), empty when it could not be computed. */
    val weekly: List<AreaProgress> = emptyList(),
    /** The checklist's last step was just done (seen open earlier in this view model's life). */
    val setupJustDone: Boolean = false,
    /** What the athlete chose to train today ("another suggestion"); null = the coach decides. */
    val wish: com.cruxcoach.athlete.logic.SuggestionFocus? = null,
    /** Minutes chosen for today on the card; null = the preferred duration. */
    val minutesToday: Int? = null,
    /** Favourite exercises, starred on the card. */
    val favorites: Set<String> = emptySet(),
    /** Today's "easier" (−1) / "harder" (+1) answer from the "another suggestion" sheet. */
    val levelShift: Int = 0,
    /** What the athlete usually chooses on today's weekday (kind, duration), learned from earlier choices. */
    val usual: PreferenceLearning.UsualChoice? = null,
    /** Today's suggestion as a circuit (owner 2026-10-17): one set of each in turn, round after round. */
    val circuitToday: Boolean = false,
    /** At least two of today's exercises can go into a circuit (pull, push, prehab, core, legs). */
    val circuitPossible: Boolean = false,
    /** Ids of today's climbing days logged by hand or from Health Connect: the filled tile opens them (device test D30). */
    val climbingEntriesToday: List<String> = emptyList(),
    /** "Easier"/"harder" twice for today's kind: the card asks once whether to keep the step (comparison review #10). */
    val levelOffer: PreferenceLearning.LevelOffer? = null,
    /** A level step just kept, for the snackbar and "Rückgängig". */
    val levelKept: LevelKept? = null,
) {
    /** The two questions come before today's suggestion until answered or skipped (owner 2026-10-15). */
    val asksBeforeSuggestion: Boolean get() = profile.checkinEnabled && checkin == null && openWorkout == null
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(TodayState())
    val state: StateFlow<TodayState> = _state.asStateFlow()

    /** Bumped to re-read the day now instead of at the next minute (device test 2026-10-16, 00:00). */
    private val dayCheck = MutableStateFlow(0)

    /**
     * The data collector. Started from the init block at the end of the class: a refresh on the IO thread uses
     * fields declared further down (the lock, today's choices), which are only set once the constructor got
     * there (CI 2026-10-17: "Cannot enter synchronized block because refreshLock is null" on a slow runner).
     */
    private fun startCollecting() {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            // The day the hub shows follows the clock, so a screen left open
            // over midnight switches to the new day's check-in, food and water.
            // A check-in saved just after midnight belongs to the new day: saving re-reads the day at once.
            val days = dayCheck.flatMapLatest { flow { while (true) { emit(service.today().toString()); delay(60_000) } } }.distinctUntilChanged()
            // Any change in these tables re-derives the whole hub; it is cheap.
            days.flatMapLatest { today -> combine(
                repo.observeProfile(),
                repo.observeCheckin(today),
                repo.observeActiveInjuries(),
                repo.observeOpenWorkout(),
                combine(
                    // A climbing day entered on the tile, a Health Connect sync or a board session re-derive the hub.
                    combine(repo.observeRecentWorkouts(30),
                        service.observeActivityChanges(LocalDate.parse(today).minus(DatePeriod(days = 60)).toString())) { w, _ -> w },
                    // Weight and height feed the targets and the energy estimate.
                    // Favourites only re-trigger the suggestion; it reads them itself.
                    combine(repo.observeSeries(BodyMetric.WEIGHT.key), repo.observeSeries(BodyMetric.HEIGHT.key), repo.observeFavorites()) { w, h, f -> Triple(w.size, h.size, f.size) },
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
    /** Open checklist steps at the previous refresh; null before the first. */
    private var lastOpenSteps: Int? = null
    /** "Another suggestion" counter; resets with the day. */
    private var variant = 0
    private var variantDay: LocalDate? = null

    /** Today's answers from the "another suggestion" sheet; reset with the day. */
    private var budgetFactor = 1.0
    private var levelShift = 0
    private var wish: com.cruxcoach.athlete.logic.SuggestionFocus? = null
    private var minutesToday: Int? = null
    /** "Als Zirkel" for today, with the athlete's rounds and rests if changed in the sheet. */
    private var circuitToday = false
    private var circuitRounds: Int? = null
    private var circuitRestS: Int? = null
    private var circuitRoundRestS: Int? = null
    private val dislikedToday = mutableSetOf<String>()
    /** Exercises the athlete swapped on the card today: suggested slug → own pick ("" = left out). */
    private val swapsToday = mutableMapOf<String, String>()
    /** The engine input of the shown suggestion, for the alternatives of a swap (read on the main thread). */
    @Volatile private var lastInput: SuggestionInput? = null
    /** Level steps to offer for good, from the ledger at the last suggestion (comparison review #10). */
    @Volatile private var lastLevelOffers: List<PreferenceLearning.LevelOffer> = emptyList()
    /** Today's weekday habit; "the coach decides" for today sets [ignoreUsualKind]. */
    private var lastUsual: PreferenceLearning.UsualChoice? = null
    private var ignoreUsualKind = false
    /** Today's plan as this screen last stored it for nutrition ([AthleteService.rememberPlannedToday]). */
    private var plannedWritten: String? = null
    /** Suggestions already in the ledger as SHOWN today (focus + slugs). */
    private val shownToday = mutableSetOf<String>()

    private val refreshLock = Any()

    /** One refresh at a time, so "another suggestion" and the data collector never overwrite each other with older inputs. */
    private fun refresh(i: Inputs) = synchronized(refreshLock) { refreshUnlocked(i) }

    private fun refreshUnlocked(input: Inputs) {
        val today = service.today()
        // The day's place decides the equipment: a place picked yesterday falls back to the default.
        val placed = TrainingPlaces.inUse(input.profile, today.toString())
        if (placed != input.profile) runCatching { service.usePlaceOfToday() }
        val i = input.copy(profile = placed)
        lastInputs = i
        if (variantDay != today) {
            variant = 0; variantDay = today
            budgetFactor = 1.0; levelShift = 0; wish = null; minutesToday = null; dislikedToday.clear(); shownToday.clear(); swapsToday.clear(); ignoreUsualKind = false
            circuitToday = false; circuitRounds = null; circuitRestS = null; circuitRoundRestS = null
            runCatching {
                val todays = service.repo.suggestionEventsForDay(today.toString()).sortedBy { it.createdAt }
                todays.filter { it.kind == SuggestionEventKind.SHOWN }.forEach { shownToday += signature(it.focus, it.slugs) }
                // After a restart, today's suggestion is the one the athlete shaped (device test 2026-10-17: the
                // chosen 90 min were gone after the app was restarted).
                restoreToday(todays)
            }
        }
        val activities = service.activities(days = 7 * 26)
        val activity = activities[today.toString()]
        // What hurts today (check-in) is spared like an injury, without becoming one.
        val injuriesToday = i.injuries + com.cruxcoach.athlete.logic.Soreness.injuries(i.checkin)
        val readiness = ReadinessEvaluator.evaluate(i.checkin, injuriesToday)
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

        val loadStatus = runCatching { service.loadStatus() }.getOrNull()
        val block = blockState(i, loadStatus, today)
        val weekly = runCatching { weeklyProgress(i, activities, block, today, weekStart) }.getOrDefault(emptyList())
        val (daily, plannedName) = if (i.open != null) null to null else suggest(i, readiness, activities, block, loadStatus, weekly, weekStart)
        daily?.let { s ->
            val sig = signature(s.focus.name, s.routine.items.map { it.slug })
            if (shownToday.add(sig)) service.logSuggestion(SuggestionEventKind.SHOWN, s.focus.name, s.routine.items.map { it.slug })
        }
        // Nutrition follows today's planned training until it is done (audit W4); an open training keeps the plan.
        daily?.let { s -> runCatching { plannedWritten = service.rememberPlannedToday(s, plannedWritten) } }
        val planned = runCatching { service.plannedForTargets(activity) }.getOrNull()
        val fuelActivity = planned?.activity ?: activity
        val durationHint = runCatching {
            PreferenceLearning.suggestedSessionMinutes(repo.workoutsBetween(today.minus(DatePeriod(days = 42)).toString(), today.toString()),
                i.profile.sessionMinutes, today)
        }.getOrNull()

        // Counted here, not in the screen: the coach setup opens before Today has drawn once.
        val coachDone = i.profile.coach.setupState == com.cruxcoach.athlete.model.SetupState.DONE ||
            i.profile.coach.setupState == com.cruxcoach.athlete.model.SetupState.DISMISSED
        val openSteps = listOf(coachDone, i.profile.equipmentConfigured, trend.isNotEmpty()).count { !it }
        val justDone = (lastOpenSteps ?: 0) > 0 && openSteps == 0
        lastOpenSteps = openSteps
        val dayForm = service.todaysModifier().let { m -> if (daily?.shortenedForFatigue == true) m.copy(setsDelta = 0) else m }
            .takeIf { !it.isNeutral }
        _state.update {
            it.copy(
                loading = false,
                setupJustDone = it.setupJustDone || justDone,
                suggestion = daily,
                dayForm = dayForm,
                plannedName = plannedName,
                block = block,
                durationHint = durationHint,
                bodyweightKg = service.currentBodyweight(),
                weekly = weekly,
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
                fuelTargets = service.fuelTargets(fuelActivity, i.profile),
                proteinToday = i.food.sumOf { e -> e.proteinG ?: 0.0 },
                carbsToday = i.food.sumOf { e -> e.carbsG ?: 0.0 },
                waterTodayMl = i.water.sumOf { w -> w.ml },
                kcalToday = i.food.mapNotNull { e -> com.cruxcoach.athlete.logic.MacroTotals.energy(e)?.kcal }.takeIf { it.isNotEmpty() }?.sum(),
                need = service.energyNeed(i.profile, fuelActivity),
                fuelPlan = planned?.let { runCatching { service.todayWeightPlan(i.profile, fuelActivity, activities) }.getOrNull() },
                weekNeeds = runCatching { service.weekNeeds(i.profile, if (planned != null) service.withToday(activities, fuelActivity) else activities) }
                    .getOrDefault(emptyList()),
                heightCm = service.heightCm(),
                energy = service.energyReport(i.profile, activities),
                loadSpikes = spikes,
                suggestions = suggestions,
                wish = wish,
                minutesToday = minutesToday,
                circuitToday = circuitToday && daily?.routine?.items?.any { it.circuit != null } == true,
                circuitPossible = daily?.let { circuitCandidates(it) } ?: false,
                favorites = runCatching { repo.favorites() }.getOrDefault(emptySet()),
                levelShift = levelShift,
                levelOffer = daily?.let { s -> lastLevelOffers.firstOrNull { it.focus == s.focus } },
                usual = lastUsual,
                climbingEntriesToday = runCatching { repo.climbingDaysSince(today.toString()) }.getOrDefault(emptyList())
                    .filter { e -> e.day == today.toString() }.map { e -> e.id },
            )
        }
    }

    /** Today's swaps, drops, level, kind, time and circuit from the ledger, in the order they were made. */
    private fun restoreToday(events: List<com.cruxcoach.athlete.model.SuggestionEvent>) {
        var chosenKinds = 0
        for (e in events) when (e.kind) {
            SuggestionEventKind.SWAPPED -> {
                val from = e.slugs.getOrNull(0)
                val to = e.slugs.getOrNull(1)
                if (from != null && to != null) {
                    val key = originalOf(from)
                    if (key == to) swapsToday.remove(key) else swapsToday[key] = to
                }
            }
            SuggestionEventKind.NEXT -> when (e.feedback) {
                // "Leave it out today" stays left out after a restart; "not today" picks something else.
                SuggestionFeedback.DISLIKE_EXERCISE -> e.slugs.singleOrNull()?.let {
                    if (e.value == PreferenceLearning.LEFT_OUT) swapsToday[originalOf(it)] = "" else dislikedToday += it
                }
                // The level chips set the level, they do not add up (lighter, then harder = harder).
                SuggestionFeedback.TOO_EASY -> levelShift = 1
                SuggestionFeedback.TOO_HARD -> levelShift = -1
                else -> Unit
            }
            SuggestionEventKind.FEEDBACK -> {
                val focus = e.focus.orEmpty()
                when {
                    focus.startsWith(PreferenceLearning.CHOSEN_LEVEL) -> levelShift = (e.value?.toInt() ?: 0).coerceIn(-1, 1)
                    focus == PreferenceLearning.CHOSEN_MINUTES -> minutesToday = e.value?.toInt()
                    focus.startsWith(PreferenceLearning.CHOSEN_KIND) -> {
                        chosenKinds++
                        val name = focus.removePrefix(PreferenceLearning.CHOSEN_KIND)
                        wish = name.takeIf { it.isNotEmpty() }?.let { runCatching { com.cruxcoach.athlete.logic.SuggestionFocus.valueOf(it) }.getOrNull() }
                        ignoreUsualKind = wish == null
                    }
                    focus.startsWith(PreferenceLearning.CHOSEN_FORMAT) ->
                        circuitToday = focus.removePrefix(PreferenceLearning.CHOSEN_FORMAT) == "circuit"
                    focus.startsWith(PreferenceLearning.CIRCUIT_SETUP) -> {
                        val f = focus.removePrefix(PreferenceLearning.CIRCUIT_SETUP).split(",")
                        circuitRounds = f.getOrNull(0)?.toIntOrNull()
                        circuitRestS = f.getOrNull(1)?.toIntOrNull()
                        circuitRoundRestS = f.getOrNull(2)?.toIntOrNull()
                    }
                }
            }
            else -> Unit
        }
        // "Another suggestion" taps: a kind choice also writes a NEXT, so it does not count as one, and neither does a level chip.
        variant = (events.count { it.kind == SuggestionEventKind.NEXT && it.feedback != SuggestionFeedback.DISLIKE_EXERCISE &&
            it.feedback != SuggestionFeedback.TOO_EASY && it.feedback != SuggestionFeedback.TOO_HARD } - chosenKinds).coerceAtLeast(0)
    }

    private fun signature(focus: String?, slugs: List<String>) = (focus ?: "") + "|" + slugs.joinToString(",")

    /**
     * The training cycle: starts on the first day the coach runs (so every
     * athlete gets intro → build → deload), a trip or competition date adds the
     * taper; tired check-ins and a finger-load spike can bring the deload forward.
     */
    private fun blockState(i: Inputs, loadStatus: LoadStatus?, today: LocalDate): BlockState? =
        runCatching {
            val coach = i.profile.coach
            val start = coach.blockStartDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: run {
                service.repo.updateProfile { p -> if (p.coach.blockStartDay != null) p else p.copy(coach = p.coach.copy(blockStartDay = today.toString())) }
                today
            }
            val target = coach.targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val checkins = service.repo.checkinsBetween(today.minus(DatePeriod(days = 6)).toString(), today.toString())
            var fatigue = checkins.count { c -> (c.sleep ?: 5) <= 2 || (c.energy ?: 5) <= 2 || (c.fingers ?: 5) <= 2 }
            fatigue += when (loadStatus?.structures?.get(LoadStructure.FINGER)?.trend) {
                LoadTrend.SPIKE -> 2
                LoadTrend.RISING -> 1
                else -> 0
            }
            TrainingBlocks.state(start, target, today, fatigue)
        }.getOrNull()

    /** Builds the engine input from all the coach knows; a failure only hides the card. */
    /**
     * The weekly volume plan: targets for this athlete and this block, and what
     * the week holds so far (logged sets of every training this week, board and
     * manual climbing days as credit).
     */
    private fun weeklyProgress(
        i: Inputs, activities: Map<String, DayActivity>, block: BlockState?, today: LocalDate, weekStart: LocalDate,
    ): List<AreaProgress> {
        val repo = service.repo
        val days = i.profile.coach.trainingDaysPerWeek ?: i.profile.weeklyGoal
        val targets = WeeklyVolume.targets(i.profile, block, i.injuries, days)
        val workoutDays = repo.workoutsBetween(weekStart.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
        val zone = java.time.ZoneId.systemDefault()
        val startMs = java.time.LocalDate.parse(weekStart.toString()).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekSets = repo.completedSetsSince(startMs).mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
        return WeeklyVolume.progress(weekSets, activities, service.catalog, weekStart, targets, today)
    }

    private fun suggest(
        i: Inputs, readiness: Readiness, activities: Map<String, DayActivity>, block: BlockState?, loadStatus: LoadStatus?,
        weekly: List<AreaProgress> = emptyList(), weekStart: LocalDate? = null,
    ): Pair<SessionSuggestion?, String?> =
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
            val sinceMs = System.currentTimeMillis() - PreferenceLearning.WINDOW_DAYS * 24L * 3600 * 1000
            val resets = i.profile.learningResets
            val events = repo.suggestionEventsSince(sinceMs)
            // Today's swaps are learned for the days to come; today's card keeps the rest of its picks
            // (device test 2026-10-17: one swap moved the other exercises around).
            // "Leave it out today" likewise (device test 2026-10-17: the dropped press made the slot pick push-ups instead).
            val stable = events.filterNot { e ->
                e.day == today.toString() && (e.kind == SuggestionEventKind.SWAPPED ||
                    (e.kind == SuggestionEventKind.NEXT && e.feedback == SuggestionFeedback.DISLIKE_EXERCISE && e.value == PreferenceLearning.LEFT_OUT))
            }
            val learned = PreferenceLearning.affinity(stable, repo.completedSetsSince(sinceMs), today, resets)
            val affinity = learned + dislikedToday.associateWith { -10.0 }
            // Turned down on earlier days: rests a few days (today's own swaps keep the card stable instead).
            // Kept after a training ("künftig so"): the chosen exercise leads its slot, the replaced one rests.
            val kept = PreferenceLearning.keptEffect(PreferenceLearning.kept(events, today, resets))
            val resting = PreferenceLearning.resting(stable.filterNot { it.day == today.toString() }, today, resets).keys + kept.resting.keys
            // The kind and time the athlete chose on this weekday before: today's default until they choose otherwise.
            lastLevelOffers = PreferenceLearning.levelOffers(events, today, i.profile.keptLevels, resets)
            val usual = PreferenceLearning.usualChoices(events, today, resets)[today.dayOfWeek.isoDayNumber]
            lastUsual = usual
            val usualWish = usual?.focus?.takeIf { wish == null && !ignoreUsualKind }
            val usualMinutes = usual?.minutes?.takeIf { minutesToday == null }
            val parsedActivities = activities.mapNotNull { (day, a) -> runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to a } }.toMap()
            val todayActivity = parsedActivities[today]
            val climbingToday = (todayActivity != null && (todayActivity.climbingMinutes > 0 || todayActivity.climbingEfforts > 0)) ||
                today.dayOfWeek.isoDayNumber in i.profile.coach.climbingDays
            val benchmarks = repo.allBenchmarks().groupBy { it.exerciseSlug }.mapNotNull { (_, list) -> list.maxByOrNull { it.measuredAt } }
            val historyWeeks = parsedActivities.filterValues { it.trained }.keys.map { ConsistencyStreak.weekStart(it) }.distinct().size
            // Back after a break or a healed finger injury: a conservative ramp on top of every other rule.
            val returnState = runCatching {
                val since = today.minus(DatePeriod(days = 120))
                val days120 = repo.workoutsBetween(since.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
                val sets120 = repo.completedSetsSince(System.currentTimeMillis() - 120L * 24 * 3600 * 1000)
                    .mapNotNull { set -> days120[set.workoutId]?.let { it to set } }
                ReturnToTraining.state(activities, repo.pauses(), repo.allInjuries(), today,
                    maxFingerBefore = ReturnToTraining.maxFingerBefore(sets120, service.catalog, today))
            }.getOrNull()
            val logbook = runCatching { service.logbookSummary() }.getOrNull()
            val input = SuggestionInput(
                catalog = service.catalog,
                profile = i.profile,
                today = today,
                readiness = readiness,
                injuries = i.injuries + com.cruxcoach.athlete.logic.Soreness.injuries(i.checkin),
                activities = parsedActivities,
                recentSets = recentSets,
                favorites = repo.favorites(),
                benchmarkSlugs = repo.allBenchmarks().map { it.exerciseSlug }.toSet(),
                lastTrained = lastTrained,
                routines = routines,
                variant = variant,
                coach = i.profile.coach,
                loadStatus = loadStatus,
                affinity = affinity,
                excluded = i.profile.excludedExercises,
                block = block,
                climbingToday = climbingToday,
                benchmarks = benchmarks,
                levelShift = levelShift,
                budgetFactor = budgetFactor,
                logbookSends = logbook?.sampleSize ?: 0,
                logbookDifficulty = logbook?.takeIf { it.hasGrades }?.workingDifficulty,
                resting = resting,
                historyWeeks = historyWeeks,
                weekly = weekly,
                weekStart = weekStart,
                returnState = returnState,
                wish = wish ?: usualWish,
                minutesToday = minutesToday ?: usualMinutes,
                wishLearned = wish == null && usualWish != null,
                minutesLearned = minutesToday == null && usualMinutes != null,
                kept = kept.preferred,
            )
            lastInput = input
            // The athlete's swaps on the card stay for the day, whatever else changes.
            val swapped = SessionSuggester.withSwaps(SessionSuggester.suggest(input), swapsToday.toMap(), service.catalog)
            val suggestion = if (circuitToday) asCircuit(swapped) else swapped
            val plannedName = suggestion.plannedEntry
                ?.takeIf { !it.startsWith("builtin:") && it != PLAN_BOARD && it != PLAN_REST }
                ?.let { id -> routines.firstOrNull { it.id == id }?.name }
            suggestion to plannedName
        }.getOrElse { null to null }

    /** "Another suggestion": same rules, different picks. */
    fun nextSuggestion() = nextSuggestion(null, null)

    /**
     * "Another suggestion" with an optional reason that works at once: less
     * time shortens today's budget, a disliked exercise stays out today (and
     * counts against it later), too easy / too hard shifts today's level.
     * Equipment and pain are fixed where they live (the screen navigates).
     */
    fun nextSuggestion(feedback: SuggestionFeedback?, slug: String?) = io {
        synchronized(refreshLock) {
            val s = _state.value.suggestion
            val slugs = if (feedback == SuggestionFeedback.DISLIKE_EXERCISE && slug != null) listOf(slug)
                else s?.routine?.items?.map { it.slug }.orEmpty()
            service.logSuggestion(SuggestionEventKind.NEXT, s?.focus?.name, slugs, feedback)
            when (feedback) {
                // "Keine Zeit": the next shorter duration, the same one the card's time menu offers.
                SuggestionFeedback.NO_TIME -> minutesToday = shorterThan(s?.totalMinutes ?: _state.value.profile.sessionMinutes)
                SuggestionFeedback.DISLIKE_EXERCISE -> slug?.let { dislikedToday += it }
                SuggestionFeedback.TOO_EASY -> levelShift = (levelShift + 1).coerceAtMost(1)
                SuggestionFeedback.TOO_HARD -> levelShift = (levelShift - 1).coerceAtLeast(-1)
                else -> Unit
            }
            variant++
            lastInputs?.let { refreshUnlocked(it) }
        }
    }

    /**
     * "What do you feel like today?" (owner 2026-10-15): the athlete picks the kind of
     * training; null goes back to the coach's choice. Safety rules still apply.
     */
    fun chooseFocus(focus: com.cruxcoach.athlete.logic.SuggestionFocus?) = io {
        synchronized(refreshLock) {
            val s = _state.value.suggestion
            service.logSuggestion(SuggestionEventKind.NEXT, s?.focus?.name, s?.routine?.items?.map { it.slug }.orEmpty(), null)
            // The coach learns the athlete's usual choice per weekday from this ([PreferenceLearning.usualChoices]).
            service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_KIND + (focus?.name ?: ""), emptyList())
            ignoreUsualKind = focus == null
            wish = focus
            lastInputs?.let { refreshUnlocked(it) }
        }
    }

    /**
     * "Wie lange heute?" on the card (owner 2026-10-16): today's suggestion is built for [minutes];
     * null goes back to the preferred duration. Only for today, like the chosen kind.
     */
    fun chooseMinutes(minutes: Int?) = io {
        synchronized(refreshLock) {
            service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_MINUTES, emptyList(), value = minutes?.toDouble())
            // "As usual" on a weekday with a habit of its own: the preferred duration, explicitly.
            minutesToday = minutes ?: _state.value.profile.sessionMinutes.takeIf { lastUsual?.minutes != null }
            lastInputs?.let { refreshUnlocked(it) }
        }
    }

    /**
     * "Als Zirkel" on the card (owner 2026-10-17): today's pull, push, prehab, core and leg work in one
     * circuit – one set of each in turn; finger and power work stay before it. Only for today; the choice
     * goes into the ledger, so a weekday habit can be learned later.
     */
    fun chooseCircuit(on: Boolean) = io {
        synchronized(refreshLock) {
            service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_FORMAT + (if (on) "circuit" else ""), emptyList())
            circuitToday = on
            lastInputs?.let { refreshUnlocked(it) }
        }
    }

    /** Rounds and rests of today's circuit, from its sheet; null keeps the coach's value. */
    fun setCircuit(rounds: Int?, circuitRestS: Int?, roundRestS: Int?) = io {
        synchronized(refreshLock) {
            circuitRounds = rounds
            this.circuitRestS = circuitRestS
            circuitRoundRestS = roundRestS
            lastInputs?.let { refreshUnlocked(it) }
        }
        // Survives a restart like the other choices of today (audit P2-13).
        service.logSuggestion(SuggestionEventKind.FEEDBACK,
            PreferenceLearning.CIRCUIT_SETUP + listOf(rounds, circuitRestS, roundRestS).joinToString(",") { it?.toString().orEmpty() }, emptyList())
    }

    private fun circuitCandidates(s: com.cruxcoach.athlete.logic.SessionSuggestion): Boolean =
        s.routine.items.count { !it.warmup && com.cruxcoach.athlete.logic.Circuits.eligibleToday(service.catalog[it.slug], it) } >= 2

    /** Today's suggestion with its suitable exercises in one circuit, the time estimated for that. */
    private fun asCircuit(s: com.cruxcoach.athlete.logic.SessionSuggestion): com.cruxcoach.athlete.logic.SessionSuggestion {
        val catalog = service.catalog
        var items = com.cruxcoach.athlete.logic.Circuits.asCircuitToday(s.routine.items, catalog)
        val key = items.firstOrNull { it.circuit != null }?.circuit ?: return s
        // A circuit saves the rests between sets: with the same sets it ended far below the chosen time
        // (60 min → 38). More rounds fill the time – only when the straight suggestion filled it too, so an
        // intentionally light week stays light – up to five rounds; the athlete's own round count wins.
        val target = minutesToday ?: (lastInputs?.profile?.sessionMinutes ?: s.estimatedMinutes)
        if (circuitRounds == null && s.estimatedMinutes >= target - CIRCUIT_TOLERANCE_MIN) {
            var rounds = com.cruxcoach.athlete.logic.Circuits.rounds(items, key)
            while (rounds < MAX_CIRCUIT_ROUNDS && SessionSuggester.estimateMinutes(items, catalog) < target - CIRCUIT_TOLERANCE_MIN) {
                rounds++
                items = com.cruxcoach.athlete.logic.Circuits.setRounds(items, key, rounds)
            }
        }
        circuitRounds?.let { items = com.cruxcoach.athlete.logic.Circuits.setRounds(items, key, it) }
        if (circuitRestS != null || circuitRoundRestS != null) {
            val first = items.first { it.circuit == key }
            items = com.cruxcoach.athlete.logic.Circuits.setRests(items, key, circuitRestS ?: first.circuitRestS, circuitRoundRestS ?: first.roundRestS)
        }
        return s.copy(routine = s.routine.copy(items = items), estimatedMinutes = SessionSuggester.estimateMinutes(items, catalog))
    }

    /** Today's "easier" (−1) or "harder" (+1), 0 back to the coach's level; the coach learns nothing from it beyond today. */
    fun chooseLevel(shift: Int) = io {
        synchronized(refreshLock) {
            val s = _state.value.suggestion
            val feedback = when { shift > 0 -> SuggestionFeedback.TOO_EASY; shift < 0 -> SuggestionFeedback.TOO_HARD; else -> null }
            if (feedback != null) service.logSuggestion(SuggestionEventKind.NEXT, s?.focus?.name, s?.routine?.items?.map { it.slug }.orEmpty(), feedback)
            // Back to the coach's level: noted too, so a restart and the "for good?" offer see it (comparison review #10).
            else if (levelShift != 0) service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_LEVEL + (s?.focus?.name ?: ""),
                emptyList(), value = 0.0)
            levelShift = shift.coerceIn(-1, 1)
            lastInputs?.let { refreshUnlocked(it) }
        }
    }

    /**
     * The answer to [TodayState.levelOffer]: [keep] takes the step for the kind of training for good (visible and
     * forgettable on "What the coach learned"; today's own chip goes back to the coach's level, which is now that
     * step); no asks nothing for [PreferenceLearning.LEVEL_WINDOW_DAYS] days.
     */
    fun answerLevelOffer(keep: Boolean) {
        val offer = _state.value.levelOffer ?: return
        lastLevelOffers = lastLevelOffers - offer
        _state.update { it.copy(levelOffer = null) }
        io {
            val kind = offer.focus.name
            val before = service.repo.profile().keptLevels[kind]
            val answer = service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.LEVEL_ANSWER + kind, emptyList(),
                value = if (keep) offer.direction.toDouble() else 0.0)
            if (!keep) {
                // Recomputed under the lock, so a refresh that ran meanwhile cannot bring the question back.
                synchronized(refreshLock) { lastInputs?.let { refreshUnlocked(it) } }
                return@io
            }
            val today = synchronized(refreshLock) {
                val shift = levelShift
                val back = if (shift != 0) service.logSuggestion(SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_LEVEL + kind,
                    emptyList(), value = 0.0) else null
                levelShift = 0
                shift to back
            }
            val now = ((before ?: 0) + offer.direction).coerceIn(-1, 1)
            service.repo.updateProfile { p -> p.copy(keptLevels = if (now == 0) p.keptLevels - kind else p.keptLevels + (kind to now)) }
            _state.update { it.copy(levelKept = LevelKept(offer, before, today.first, listOfNotNull(answer, today.second))) }
        }
    }

    /** "Rückgängig" after keeping a level step: the step, today's choice and the answer as before. */
    fun undoLevelKept(kept: LevelKept) {
        _state.update { it.copy(levelKept = null) }
        io {
            val kind = kept.offer.focus.name
            synchronized(refreshLock) {
                kept.eventIds.forEach(service::forgetSuggestion)
                levelShift = kept.todayShift
            }
            service.repo.updateProfile { p ->
                p.copy(keptLevels = if (kept.before == null) p.keptLevels - kind else p.keptLevels + (kind to kept.before))
            }
        }
    }

    fun consumeLevelKept() = _state.update { it.copy(levelKept = null) }

    /**
     * What could replace [slug] on today's card (owner 2026-10-16: "jede Empfehlung mit wenig Aufwand
     * überschreibbar"): the same kind of work through the same safety filters as the suggestion.
     */
    fun alternatives(slug: String): List<SessionSuggester.Alternative> {
        // No lock: a refresh holds it through its database reads, and this runs on the main thread; input and
        // state are immutable snapshots.
        val input = lastInput ?: return emptyList()
        val s = _state.value.suggestion ?: return emptyList()
        return runCatching { SessionSuggester.alternatives(input, s, slug) }.getOrDefault(emptyList())
    }

    /** The suggested slug [shown] stands for (itself, or the one it replaced earlier today). */
    private fun originalOf(shown: String): String = swapsToday.entries.firstOrNull { it.value == shown }?.key ?: shown

    /** The last swap or "leave out" on the card, for "Rückgängig": the card's entry before it and the ledger entry. */
    private class CardUndo(var key: String? = null, var previous: String? = null, var eventId: String? = null)
    @Volatile private var cardUndo: CardUndo? = null
    private var cardJob: kotlinx.coroutines.Job? = null

    /** The athlete's pick instead of [from] for today; the coach learns from it (−1 for [from], +1 for [to]). */
    fun swapExercise(from: String, to: String) {
        val undo = CardUndo().also { cardUndo = it }
        cardJob = viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            synchronized(refreshLock) {
                val key = originalOf(from)
                undo.key = key; undo.previous = swapsToday[key]
                if (key == to) swapsToday.remove(key) else swapsToday[key] = to
                undo.eventId = service.logSuggestion(SuggestionEventKind.SWAPPED, _state.value.suggestion?.focus?.name, listOf(from, to))
                lastInputs?.let { refreshUnlocked(it) }
            }
            offerNeverIfRepeated(from)
        }
    }

    /** "Heute weglassen": out of today's suggestion without a replacement; counts like "keine Lust". */
    fun dropExercise(slug: String) {
        val undo = CardUndo().also { cardUndo = it }
        cardJob = viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            synchronized(refreshLock) {
                val key = originalOf(slug)
                undo.key = key; undo.previous = swapsToday[key]
                swapsToday[key] = ""
                undo.eventId = service.logSuggestion(SuggestionEventKind.NEXT, _state.value.suggestion?.focus?.name, listOf(slug),
                    SuggestionFeedback.DISLIKE_EXERCISE, value = PreferenceLearning.LEFT_OUT)
                lastInputs?.let { refreshUnlocked(it) }
            }
            offerNeverIfRepeated(slug)
        }
    }

    /** "Rückgängig" after a swap or "leave out" on the card (audit P2-14): the card as before, nothing learned. */
    fun undoCardChange() {
        val undo = cardUndo ?: return
        cardUndo = null
        val job = cardJob
        io {
            job?.join()
            val key = undo.key ?: return@io
            synchronized(refreshLock) {
                val previous = undo.previous
                if (previous == null) swapsToday.remove(key) else swapsToday[key] = previous
                undo.eventId?.let(service::forgetSuggestion)
                lastInputs?.let { refreshUnlocked(it) }
            }
            if (_state.value.neverOffer != null) _state.update { it.copy(neverOffer = null) }
        }
    }

    /** "Nie vorschlagen" from the card; the profile change brings the new suggestion. */
    fun excludeExercise(slug: String) = io {
        synchronized(refreshLock) { swapsToday.entries.removeAll { it.value == slug } }
        service.setExcluded(slug, true)
    }

    /** "Rückgängig" after "Nie vorschlagen". */
    fun includeExercise(slug: String) = io { service.setExcluded(slug, false) }

    /**
     * After a rejection: on the third one the card asks once "stop suggesting it?" instead of only resting it
     * again and again; asked at most once per exercise (answer or dismiss).
     */
    private fun offerNeverIfRepeated(slug: String) {
        val repo = service.repo
        val key = "never.offered.$slug"
        if (repo.setting(key) != null || slug in repo.profile().excludedExercises) return
        val today = service.today()
        val sinceMs = System.currentTimeMillis() - PreferenceLearning.WINDOW_DAYS * 24L * 3600 * 1000
        val count = PreferenceLearning.rejections(repo.suggestionEventsSince(sinceMs), today, repo.profile().learningResets)[slug] ?: 0.0
        if (count >= PreferenceLearning.OFFER_NEVER_AFTER) _state.update { it.copy(neverOffer = slug) }
    }

    /** The answer to [TodayState.neverOffer]: never again, or keep it (it rests a while and may come back). */
    fun answerNeverOffer(never: Boolean) {
        val slug = _state.value.neverOffer ?: return
        _state.update { it.copy(neverOffer = null) }
        io {
            service.repo.putSetting("never.offered.$slug", if (never) "never" else "kept")
            if (never) excludeExercise(slug)
        }
    }

    /** Takes over the real training length as the preferred duration. */
    fun applyDurationHint() = io {
        val minutes = _state.value.durationHint ?: return@io
        service.repo.updateProfile { it.copy(sessionMinutes = minutes) }
    }

    /**
     * Where "adapt" opens the editor: the planned own workout itself when the
     * suggestion is that workout unchanged, otherwise a draft that keeps every
     * item detail (warm-up flags, sides, sets cut to the time budget).
     */
    fun editTarget(title: String): Pair<String?, String?> {
        val s = _state.value.suggestion ?: return null to null
        val planned = s.plannedEntry
        val changed = setOf(SuggestionReason.PLAN_FILTERED_FOR_INJURY, SuggestionReason.FINGERS_LOADED_RECENTLY,
            SuggestionReason.FINGERS_TIRED, SuggestionReason.SHORTENED_TO_TIME, SuggestionReason.RECOVERY_AFTER_LIMIT,
            SuggestionReason.RECOVERY_AFTER_HARD, SuggestionReason.RECOVERY_AFTER_VOLUME, SuggestionReason.FINGER_LOAD_RISING,
            SuggestionReason.SKIN_LOAD_RISING, SuggestionReason.GUARDRAIL_YOUTH, SuggestionReason.BLOCK_INTRO,
            SuggestionReason.BLOCK_DELOAD, SuggestionReason.BLOCK_TAPER)
        viewModelScope.launch(Dispatchers.IO) { service.logSuggestion(SuggestionEventKind.EDITED, s.focus.name, s.routine.items.map { it.slug }) }
        if (s.focus == SuggestionFocus.PLANNED && planned != null && !planned.startsWith("builtin:") && s.reasons.none { it in changed }) {
            return planned to null
        }
        service.offerEditorDraft(s.routine.copy(name = title, builtinKey = null))
        return null to "draft:" + s.routine.items.joinToString(",") { it.slug }
    }

    /** A start waiting for "continue the open training or end it". */
    val pendingStart = MutableStateFlow<com.cruxcoach.android.ui.training.workout.PendingStart?>(null)

    /** Runs [begin] at once, or after the athlete chose what happens to another open training. */
    private fun guardedStart(routineId: String?, title: String?, begin: suspend (replace: Boolean) -> Unit) = io {
        val conflict = service.openConflict(routineId)
        if (conflict != null) pendingStart.value = com.cruxcoach.android.ui.training.workout.PendingStart(conflict, title, begin)
        else begin(false)
    }

    /** true: end the open training and start; false: continue the open one; null: start nothing. */
    fun resolveStart(replace: Boolean?) = io {
        val pending = pendingStart.value ?: return@io
        pendingStart.value = null
        if (replace != null) pending.start(replace)
    }

    /** Starts today's suggestion in the guided player; [title] is the localized focus title. */
    fun startSuggestion(title: String) {
        val suggestion = _state.value.suggestion ?: return
        val routine = suggestion.routine.copy(id = "suggestion:${service.today()}", name = title)
        guardedStart(routine.id, title) { replace ->
            if (replace) service.closeOpenWorkout()
            startSuggestionNow(suggestion, routine, title)
        }
    }

    private fun startSuggestionNow(suggestion: SessionSuggestion, routine: Routine, title: String) {
        val id = service.startWorkout(routine, title, trimSets = !suggestion.shortenedForFatigue)
        // Planned work sets, so the summary can tell how much of the suggestion was done.
        val catalog = service.catalog
        val plannedSets = suggestion.routine.items.filter { !it.warmup }.sumOf { item ->
            item.sets * (if (catalog.fallbackFor(item.slug).unilateral && item.sides == SideMode.BOTH) 2 else 1)
        }
        service.logSuggestion(SuggestionEventKind.STARTED, suggestion.focus.name, suggestion.routine.items.map { it.slug },
            workoutId = id, value = plannedSets.toDouble())
        _state.update { it.copy(startedWorkout = true, startedGuided = true) }
    }

    /** Keeps the suggestion as one of the athlete's own workouts. */
    fun saveSuggestion(title: String) = io {
        val suggestion = _state.value.suggestion ?: return@io
        val now = System.currentTimeMillis()
        service.repo.saveRoutine(suggestion.routine.copy(id = service.repo.newId(), name = title, builtinKey = null,
            createdAt = now, updatedAt = now))
        service.logSuggestion(SuggestionEventKind.SAVED, suggestion.focus.name, suggestion.routine.items.map { it.slug })
        _state.update { it.copy(suggestionSaved = true) }
    }

    fun consumeSuggestionSaved() = _state.update { it.copy(suggestionSaved = false) }

    fun dismissSetupDone() = _state.update { it.copy(setupJustDone = false) }

    private fun covers(p: PausePeriod, day: LocalDate, today: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(p.startDay) }.getOrNull() ?: return false
        val end = p.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        return day in start..end
    }

    /**
     * The questions before the suggestion: how strong the athlete feels (the check-in's
     * energy) and what hurts today. Both optional; saving with neither means "skip".
     */
    fun saveQuickCheckin(strength: Int?, sore: Set<com.cruxcoach.athlete.model.InjuryRegion>) = io {
        val day = service.today().toString()
        val old = service.repo.checkin(day)
        service.repo.saveCheckin((old ?: Checkin(day, System.currentTimeMillis()))
            .copy(createdAt = System.currentTimeMillis(), energy = strength ?: old?.energy, sore = sore))
        dayCheck.update { it + 1 }
    }

    fun saveCheckin(sleep: Int?, energy: Int?, skin: Int?, fingers: Int?, sick: Boolean, sore: Set<com.cruxcoach.athlete.model.InjuryRegion> = emptySet()) = io {
        val day = service.today().toString()
        service.repo.saveCheckin(Checkin(day, System.currentTimeMillis(), sleep, energy, skin, fingers, sick = sick, sore = sore))
        dayCheck.update { it + 1 }
        if (sick && service.repo.openPause() == null) {
            service.repo.savePause(PausePeriod(service.repo.newId(), PauseReason.ILLNESS, day))
        }
    }

    fun clearCheckin() = io { service.repo.deleteCheckin(service.today().toString()) }

    fun endPause() = io {
        val open = service.repo.openPause() ?: return@io
        service.repo.savePause(open.copy(endDay = service.today().toString()))
    }

    fun startRoutine(key: String, title: String? = null) {
        val routine = BuiltinRoutines.byKey(key) ?: return
        guardedStart(routine.id, title) { replace ->
            if (replace) service.closeOpenWorkout()
            service.startWorkout(routine, null)
            _state.update { it.copy(startedWorkout = true, startedGuided = true) }
        }
    }

    fun startEmptyWorkout() = guardedStart(null, null) { replace ->
        if (replace) service.closeOpenWorkout()
        service.startWorkout(null, null)
        _state.update { it.copy(startedWorkout = true, startedGuided = false) }
    }

    fun consumeStartedWorkout() = _state.update { it.copy(startedWorkout = false, startedGuided = false) }

    fun addWater(ml: Int) = io { service.repo.addHydration(service.today().toString(), ml) }

    /** Undo for the water tile: the newest glass of today goes again. */
    fun removeLastWater() = io {
        service.repo.hydration(service.today().toString()).maxByOrNull { it.loggedAt }?.let { service.repo.deleteHydration(it.id) }
    }

    /** From the checklist's equipment sheet: what the athlete has, and that it was asked. */
    /** The equipment of the place the athlete trains at today. */
    fun saveEquipment(equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) = io { service.savePlaceEquipment(null, equipment) }

    /** Train somewhere else today; the suggestion follows that place's equipment. */
    fun pickPlace(placeId: String) = io { service.pickPlace(placeId) }

    fun logWeight(kg: Double) = io {
        val now = System.currentTimeMillis()
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", now))
    }

    fun logHeight(cm: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, cm, "cm", System.currentTimeMillis()))
    }

    fun saveWeightGoal(lose: Boolean, targetKg: Double?, paceKg: Double) = io { service.saveWeightGoal(lose, targetKg, paceKg) }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }

    init {
        startCollecting()
    }

    companion object {
        /** A circuit within this many minutes of the chosen time counts as fitting. */
        const val CIRCUIT_TOLERANCE_MIN = 4
        /** Rounds a circuit grows to at most to fill the chosen time (like sets on straight work). */
        const val MAX_CIRCUIT_ROUNDS = 5
        /** The card's "Wie lange heute?" choices in minutes. */
        val DURATION_OPTIONS = listOf(15, 20, 30, 45, 60, 75, 90, 120)

        /** The next choice clearly below [minutes], at least the shortest. */
        internal fun shorterThan(minutes: Int): Int = DURATION_OPTIONS.lastOrNull { it < minutes - 2 } ?: DURATION_OPTIONS.first()
    }
}
