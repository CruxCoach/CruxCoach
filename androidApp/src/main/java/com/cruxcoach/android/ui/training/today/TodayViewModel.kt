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
    val heightCm: Double? = null,
    val energy: com.cruxcoach.athlete.logic.EnergyReport = com.cruxcoach.athlete.logic.EnergyReport(),
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
                    // Weight and height feed the targets and the energy estimate.
                    combine(repo.observeSeries(BodyMetric.WEIGHT.key), repo.observeSeries(BodyMetric.HEIGHT.key)) { w, h -> w.size to h.size },
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
    private val dislikedToday = mutableSetOf<String>()
    /** Suggestions already in the ledger as SHOWN today (focus + slugs). */
    private val shownToday = mutableSetOf<String>()

    private val refreshLock = Any()

    /** One refresh at a time, so "another suggestion" and the data collector never overwrite each other with older inputs. */
    private fun refresh(i: Inputs) = synchronized(refreshLock) { refreshUnlocked(i) }

    private fun refreshUnlocked(i: Inputs) {
        lastInputs = i
        val today = service.today()
        if (variantDay != today) {
            variant = 0; variantDay = today
            budgetFactor = 1.0; levelShift = 0; dislikedToday.clear(); shownToday.clear()
            runCatching {
                service.repo.suggestionEventsForDay(today.toString())
                    .filter { it.kind == SuggestionEventKind.SHOWN }
                    .forEach { shownToday += signature(it.focus, it.slugs) }
            }
        }
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

        val loadStatus = runCatching { service.loadStatus() }.getOrNull()
        val block = blockState(i, loadStatus, today)
        val weekly = runCatching { weeklyProgress(i, activities, block, today, weekStart) }.getOrDefault(emptyList())
        val (daily, plannedName) = if (i.open != null) null to null else suggest(i, readiness, activities, block, loadStatus, weekly, weekStart)
        daily?.let { s ->
            val sig = signature(s.focus.name, s.routine.items.map { it.slug })
            if (shownToday.add(sig)) service.logSuggestion(SuggestionEventKind.SHOWN, s.focus.name, s.routine.items.map { it.slug })
        }
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
        _state.update {
            it.copy(
                loading = false,
                setupJustDone = it.setupJustDone || justDone,
                suggestion = daily,
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
                fuelTargets = service.fuelTargets(activity, i.profile),
                proteinToday = i.food.sumOf { e -> e.proteinG ?: 0.0 },
                carbsToday = i.food.sumOf { e -> e.carbsG ?: 0.0 },
                waterTodayMl = i.water.sumOf { w -> w.ml },
                kcalToday = i.food.mapNotNull { e -> com.cruxcoach.athlete.logic.MacroTotals.energy(e)?.kcal }.takeIf { it.isNotEmpty() }?.sum(),
                need = service.energyNeed(i.profile, activity),
                heightCm = service.heightCm(),
                energy = service.energyReport(i.profile, activities),
                loadSpikes = spikes,
                suggestions = suggestions,
            )
        }
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
            val learned = PreferenceLearning.affinity(repo.suggestionEventsSince(sinceMs), repo.completedSetsSince(sinceMs), today)
            val affinity = learned + dislikedToday.associateWith { -10.0 }
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
            val input = SuggestionInput(
                catalog = service.catalog,
                profile = i.profile,
                today = today,
                readiness = readiness,
                injuries = i.injuries,
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
                logbookSends = runCatching { service.logbookSummary().sampleSize }.getOrDefault(0),
                historyWeeks = historyWeeks,
                weekly = weekly,
                weekStart = weekStart,
                returnState = returnState,
            )
            val suggestion = SessionSuggester.suggest(input)
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
                SuggestionFeedback.NO_TIME -> budgetFactor = (budgetFactor * 0.7).coerceAtLeast(0.3)
                SuggestionFeedback.DISLIKE_EXERCISE -> slug?.let { dislikedToday += it }
                SuggestionFeedback.TOO_EASY -> levelShift = (levelShift + 1).coerceAtMost(1)
                SuggestionFeedback.TOO_HARD -> levelShift = (levelShift - 1).coerceAtLeast(-1)
                else -> Unit
            }
            variant++
            lastInputs?.let { refreshUnlocked(it) }
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
        val id = service.startWorkout(routine, title)
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
    fun saveEquipment(equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) = io {
        service.repo.updateProfile { it.copy(equipment = equipment, equipmentConfigured = true) }
    }

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
}
