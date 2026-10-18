// Port of androidApp/.../ui/training/stats/StatsHubScreen.kt's StatsHubViewModel (0e1bad0c6) for
// the iOS app. Kept close to the Android file so later Android changes carry over by diff.
// Platform seams: an injected scope and IO dispatcher, the common clock.
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.CapacityKind
import com.cruxcoach.athlete.logic.DomainWeek
import com.cruxcoach.athlete.logic.LoadStatus
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.PersonalRecords
import com.cruxcoach.athlete.logic.ProgressStats
import com.cruxcoach.athlete.logic.TrendWeight
import com.cruxcoach.athlete.logic.WeekAggregate
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SetType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus

/** Time windows of the stats screens (Android `StatsRange`). */
internal enum class ProgressRange(val days: Int, val weeks: Int) {
    FOUR_WEEKS(28, 4), TWELVE_WEEKS(84, 12), SIX_MONTHS(182, 26), YEAR(365, 52)
}

/** One trained exercise in the stats list. */
internal data class ProgressExerciseRow(
    val def: ExerciseDefinition,
    val kind: CapacityKind?,
    val last: Double?,
    val change: Double?,
    val spark: List<Double>,
    val lastAt: Long?,
)

internal data class ProgressRunState(
    val loading: Boolean = true,
    val range: ProgressRange = ProgressRange.TWELVE_WEEKS,
    val profile: AthleteProfile = AthleteProfile(),
    val today: LocalDate? = null,
    val weeks: List<WeekAggregate> = emptyList(),
    val calendar: Map<LocalDate, Int> = emptyMap(),
    val domainWeeks: List<DomainWeek> = emptyList(),
    val daysThisWeek: Int = 0,
    val trainingDaysInRange: Int = 0,
    val recordsInRange: Int = 0,
    val weight: List<TrendWeight.Point> = emptyList(),
    /** % body weight per strength exercise and day. */
    val strength: Map<String, List<Pair<LocalDate, Double>>> = emptyMap(),
    val strengthDefs: Map<String, ExerciseDefinition> = emptyMap(),
    val exercises: List<ProgressExerciseRow> = emptyList(),
    /** Teaser of the climber profile and the current load per structure. */
    val logbook: LogbookSummary = LogbookSummary(),
    val load: LoadStatus? = null,
    /** Latest trend weight, whatever the range; null before the first weigh-in. */
    val latestWeightKg: Double? = null,
    val weeklyRateKg: Double? = null,
    /** Start values the athlete has entered or tested. */
    val benchmarkCount: Int = 0,
) {
    val hasAnyData: Boolean get() = weeks.any { it.trainingDays > 0 } || exercises.isNotEmpty() || weight.isNotEmpty()
}

internal class ProgressPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ProgressRunState())
    val state: StateFlow<ProgressRunState> = _state.asStateFlow()
    private var job: Job? = null

    init { load(ProgressRange.TWELVE_WEEKS) }

    fun setRange(range: ProgressRange) {
        if (range != _state.value.range) load(range)
    }

    /** The tab keeps this presenter; a return to it recomputes, so a just-finished training shows up. */
    fun reload() = load(_state.value.range)

    /** A weigh-in from the body tile, where the hub asks for it. */
    fun logWeight(kg: Double) {
        scope.launch(io) {
            service.ensureReady()
            service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", nowMs()))
            reload()
        }
    }

    private fun load(range: ProgressRange) {
        _state.value = _state.value.copy(range = range)
        job?.cancel()
        job = scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val zone = TimeZone.currentSystemDefault()
            val today = service.today()
            val profile = repo.profile()
            val catalog = service.catalog
            val rangeStart = today.minus(DatePeriod(days = range.days - 1))
            val rangeStartMs = rangeStart.atStartOfDayIn(zone).toEpochMilliseconds()
            val calendarDays = CALENDAR_DAYS
            val activities = service.activities(days = maxOf(range.weeks * 7 + 7, calendarDays))
            val setsInRange = repo.completedSetsSince(rangeStartMs - 7L * 86_400_000L)
                .filter { it.setType != SetType.WARMUP }
            val setsWithDay = setsInRange.mapNotNull { s -> s.completedAt?.let { ProgressStats.dayOf(it, zone) to s } }
            val weeks = ProgressStats.weekly(activities, setsWithDay.map { it.first }, today, range.weeks)
            val bodyweight = service.currentBodyweight()

            val trained = repo.trainedExercises().sortedByDescending { it.lastAt ?: 0L }
            var records = 0
            val rows = trained.mapNotNull { t ->
                val def = catalog[t.slug] ?: catalog.fallbackFor(t.slug)
                val history = repo.history(t.slug, AthleteService.HISTORY_LIMIT)
                // Warm-up-only exercises (arm circles before every session) are not progress.
                if (history.none { it.setType != SetType.WARMUP }) return@mapNotNull null
                // Personal bests set inside the range, each judged against what came before it.
                val chronological = history.sortedBy { it.completedAt ?: 0L }
                chronological.forEachIndexed { i, set ->
                    if ((set.completedAt ?: 0L) >= rangeStartMs &&
                        PersonalRecords.detect(def, set, chronological.subList(0, i)) != null) records++
                }
                val progress = ProgressStats.exerciseProgress(def, history, zone, bodyweight)
                ProgressExerciseRow(
                    def = def,
                    kind = progress.kind,
                    last = progress.last,
                    change = progress.change28,
                    spark = progress.sessions.mapNotNull { it.bestDisplay }.takeLast(12),
                    lastAt = t.lastAt,
                )
            }
            val strength = STRENGTH_TO_WEIGHT_SLUGS.mapNotNull { slug ->
                val def = catalog[slug] ?: return@mapNotNull null
                val points = ProgressStats.exerciseProgress(def, repo.history(slug, AthleteService.HISTORY_LIMIT), zone, bodyweight)
                    .sessions.filter { it.day >= rangeStart }
                    .mapNotNull { s ->
                        val cap = s.bestCapacity ?: return@mapNotNull null
                        val bw = s.bodyweightKg?.takeIf { it > 0 } ?: return@mapNotNull null
                        s.day to cap / bw * 100.0
                    }
                if (points.isEmpty()) null else slug to points
            }.toMap()

            val logbook = service.logbookSummary()
            val load = runCatching { service.loadStatus() }.getOrNull()
            val trend = service.weightTrend()

            // A cancelled load keeps running its blocking queries; it must not overwrite a newer range.
            ensureActive()
            if (_state.value.range != range) return@launch
            _state.value = ProgressRunState(
                loading = false,
                range = range,
                profile = profile,
                today = today,
                weeks = weeks,
                calendar = ProgressStats.calendar(activities, today, calendarDays),
                domainWeeks = ProgressStats.domainWeeks(activities, setsWithDay, catalog, today, range.weeks),
                daysThisWeek = weeks.lastOrNull()?.trainingDays ?: 0,
                trainingDaysInRange = activities.values.count { a ->
                    a.trained && runCatching { LocalDate.parse(a.day) }.getOrNull()?.let { it >= rangeStart } == true
                },
                recordsInRange = records,
                weight = trend.filter { it.day >= rangeStart },
                latestWeightKg = trend.lastOrNull()?.trend,
                benchmarkCount = repo.allBenchmarks().size,
                weeklyRateKg = TrendWeight.weeklyRate(trend),
                strength = strength,
                strengthDefs = strength.keys.mapNotNull { slug -> catalog[slug]?.let { slug to it } }.toMap(),
                exercises = rows,
                logbook = logbook,
                load = load,
            )
        }
    }

    fun close() = scope.cancel()

    companion object {
        /** The calendar heatmap: 16 weeks. */
        const val CALENDAR_DAYS = 16 * 7

        /** Exercises whose % body weight is the climber's strength-to-weight signal. */
        val STRENGTH_TO_WEIGHT_SLUGS = listOf("finger.max_hang", "finger.one_arm_pickup", "pull.weighted_pull_up")
    }
}
