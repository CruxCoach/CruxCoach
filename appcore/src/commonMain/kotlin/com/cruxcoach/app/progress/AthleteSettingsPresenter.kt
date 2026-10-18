// Port of androidApp/.../ui/training/athlete/AthleteSettingsScreen.kt's AthleteSettingsViewModel
// (0e1bad0c6) for the iOS app, without the nutrition targets (the nutrition part of the port owns
// those). Kept close to the Android file so later Android changes carry over by diff. Platform
// seams: an injected scope and IO dispatcher, the common clock.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.CoachLogic
import com.cruxcoach.athlete.logic.Completeness
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.PlaceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

internal data class AthleteSettingsRunState(
    val profile: AthleteProfile = AthleteProfile(),
    val heightCm: Double? = null,
    val loaded: Boolean = false,
    /** How personal the coach can be (FEAT-071). */
    val completeness: Completeness? = null,
    /** "Nie vorschlagen" exercises with their definitions (unknown slugs fall back to a stub). */
    val excluded: List<ExerciseDefinition> = emptyList(),
    /** Latest body weight, shown and entered on "Über dich". */
    val weightKg: Double? = null,
    /** What the coach learned from the athlete's choices, each part forgettable (UX review 2026-10-16). */
    val learned: LearnedChoices = LearnedChoices(),
    /** Exercise names for the kept choices. */
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val today: LocalDate? = null,
)

/**
 * What the coach learned and uses: exercises done gladly or often swapped away (affinity ≥ 1 / ≤ −1),
 * the usual kind and time per weekday, and loads the athlete chose lower than planned.
 */
internal data class LearnedChoices(
    val liked: List<ExerciseDefinition> = emptyList(),
    val less: List<ExerciseDefinition> = emptyList(),
    val weekdays: List<PreferenceLearning.UsualChoice> = emptyList(),
    val ownLoads: List<Pair<Benchmark, ExerciseDefinition>> = emptyList(),
    /** The athlete's real training length when it differs clearly from the preferred one. */
    val minutes: Int? = null,
    /** Turned down lately: the day each may be suggested again (see [PreferenceLearning.resting]). */
    val resting: Map<String, LocalDate> = emptyMap(),
    /** Kept after a training ("künftig so", comparison review #3), newest first. */
    val kept: List<PreferenceLearning.Kept> = emptyList(),
    /** Level steps kept per kind of training (comparison review #10). */
    val levels: List<Pair<SuggestionFocus, Int>> = emptyList(),
) {
    val isEmpty: Boolean get() = liked.isEmpty() && less.isEmpty() && weekdays.isEmpty() && ownLoads.isEmpty() && minutes == null &&
        kept.isEmpty() && levels.isEmpty()
    val count: Int get() = liked.size + less.size + weekdays.size + ownLoads.size + (if (minutes != null) 1 else 0) + kept.size + levels.size
}

internal class AthleteSettingsPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(AthleteSettingsRunState())
    val state: StateFlow<AthleteSettingsRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrDefault(LogbookSummary())
            combine(
                service.repo.observeProfile(),
                service.repo.observeSeries(BodyMetric.HEIGHT.key),
                service.repo.observeAllBenchmarks(),
                service.repo.observeSeries(BodyMetric.WEIGHT.key),
            ) { profile, heights, benchmarks, _ ->
                val height = heights.lastOrNull()?.value
                val trend = service.weightTrend()
                AthleteSettingsRunState(
                    profile, height, loaded = true,
                    completeness = CoachLogic.completeness(profile, benchmarks, logbook),
                    excluded = profile.excludedExercises.sorted().map { service.catalog.fallbackFor(it) },
                    weightKg = trend.lastOrNull()?.trend,
                    learned = runCatching { learned(profile, benchmarks) }.getOrDefault(LearnedChoices()),
                    catalog = service.catalog,
                    today = service.today(),
                )
            }.collect { _state.value = it }
        }
    }

    fun update(transform: (AthleteProfile) -> AthleteProfile) = io { service.repo.updateProfile(transform) }

    private fun learned(profile: AthleteProfile, benchmarks: List<Benchmark>): LearnedChoices {
        val repo = service.repo
        val today = service.today()
        val since = nowMs() - PreferenceLearning.WINDOW_DAYS * 24L * 3600 * 1000
        val events = repo.suggestionEventsSince(since)
        val resets = profile.learningResets
        val affinity = PreferenceLearning.affinity(events, repo.completedSetsSince(since), today, resets)
            .filterKeys { it !in profile.excludedExercises }
        fun defs(pick: (Double) -> Boolean) = affinity.filterValues(pick).entries.sortedByDescending { kotlin.math.abs(it.value) }
            .map { service.catalog.fallbackFor(it.key) }
        // The value that plans each exercise and side now, when it is one the athlete's lower loads left.
        val own = benchmarks.groupBy { it.exerciseSlug to it.side }.values.mapNotNull { list -> list.maxByOrNull { it.measuredAt } }
            .filter { it.source == BenchmarkSource.AUTO && it.sets != null }
        val kept = PreferenceLearning.kept(events, today, resets)
            .filter { it.replaced !in profile.excludedExercises }
        // Resting after a rejection or for a kept choice: the later day counts.
        val keptRests = PreferenceLearning.keptEffect(kept).resting
        val rejected = PreferenceLearning.resting(events, today, resets)
        val resting = (rejected.keys + keptRests.keys).associateWith { slug -> listOfNotNull(rejected[slug], keptRests[slug]).max() }
            .filterKeys { it !in profile.excludedExercises }
        val less = defs { it <= -1.0 }
        return LearnedChoices(
            liked = defs { it >= 1.0 },
            // A resting exercise is listed even when its dislike already faded below one step.
            less = less + resting.keys.filter { slug -> less.none { it.slug == slug } }.map { service.catalog.fallbackFor(it) },
            resting = resting,
            weekdays = PreferenceLearning.usualChoices(events, today, resets).values.sortedBy { it.isoDay },
            ownLoads = own.map { it to service.catalog.fallbackFor(it.exerciseSlug) }.sortedBy { it.second.slug },
            kept = kept,
            levels = profile.keptLevels.mapNotNull { (kind, step) ->
                SuggestionFocus.entries.firstOrNull { it.name == kind }?.takeIf { step != 0 }?.let { it to step }
            }.sortedBy { it.first.ordinal },
            minutes = PreferenceLearning.suggestedSessionMinutes(
                repo.workoutsBetween(today.minus(DatePeriod(days = PreferenceLearning.WINDOW_DAYS)).toString(), today.toString()),
                profile.sessionMinutes, today),
        )
    }

    private fun forget(key: String) = update { it.copy(learningResets = it.learningResets + (key to nowMs())) }

    /** The coach forgets what it learned about one exercise; favourites and "never" stay. */
    fun forgetExercise(slug: String) = forget(PreferenceLearning.exerciseKey(slug))

    /** A kept level step goes, with the choices that led to it: the coach's own level for that kind again. */
    fun forgetLevel(focus: SuggestionFocus) = update {
        it.copy(keptLevels = it.keptLevels - focus.name,
            learningResets = it.learningResets + (PreferenceLearning.levelKey(focus.name) to nowMs()))
    }

    /** A choice kept after a training is forgotten: the coach chooses that slot again. */
    fun forgetKept(eventId: String) = forget(PreferenceLearning.keptKey(eventId))

    /** The usual kind and time of one weekday are forgotten. */
    fun forgetWeekday(isoDay: Int) = forget(PreferenceLearning.weekdayKey(isoDay))

    /** An own lower load goes: planning falls back to the value before it. */
    fun forgetOwnLoad(b: Benchmark) = io { service.repo.deleteBenchmark(b.id) }

    /** Everything learned from choices so far, kept level steps included (not favourites, "never" or performance values). */
    fun forgetAll() = update {
        it.copy(keptLevels = emptyMap(),
            learningResets = it.learningResets + (PreferenceLearning.RESET_ALL to nowMs()))
    }

    /** Lets an excluded exercise be suggested again. */
    fun allow(slug: String) = update { it.copy(excludedExercises = it.excludedExercises - slug) }

    /** Training days per week: the streak's goal and the coach's days are one number. */
    fun setWeeklyGoal(days: Int) = update {
        val d = days.coerceIn(1, 7)
        it.copy(weeklyGoal = d, coach = it.coach.copy(trainingDaysPerWeek = d))
    }

    fun setBirthYear(year: Int?) = update { it.copy(birthYear = year) }

    // Places with their own equipment; every change is kept at once.
    /** [placeId] null: today's place, created as the first place when there is none yet. */
    fun savePlaceEquipment(placeId: String?, equipment: Set<EquipmentV2>) = io { service.savePlaceEquipment(placeId, equipment) }
    fun renamePlace(placeId: String, name: String?) = io { service.renamePlace(placeId, name) }
    fun makeDefaultPlace(placeId: String) = io { service.makeDefaultPlace(placeId) }
    fun removePlace(placeId: String) = io { service.removePlace(placeId) }
    fun addPlace(kind: PlaceKind, equipment: Set<EquipmentV2>) = io { service.addPlace(kind, equipment) }

    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", nowMs()))
    }

    fun setHeight(cm: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, cm, "cm", nowMs()))
    }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launch(io) { service.ensureReady(); block() }
    }
}
