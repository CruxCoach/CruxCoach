// Port of androidApp/.../ui/training/benchmarks/BenchmarksScreen.kt's BenchmarksViewModel and its
// pure helpers (0e1bad0c6) for the iOS app. Kept close to the Android file so later Android
// changes carry over by diff. Platform seams: an injected scope and IO dispatcher, the common
// clock, a one-shot state flag instead of a Channel for "test started".
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.BenchmarkMath
import com.cruxcoach.athlete.logic.CapacityKind
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.LearningState
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Exercises whose starting values matter most for planning loads. */
internal val KEY_BENCHMARK_SLUGS = listOf(
    "finger.max_hang",
    "finger.one_arm_pickup",
    "finger.two_arm_pickup",
    "pull.weighted_pull_up",
    "pull.pull_up",
    "push.push_up",
    "core.hollow_hold",
)

internal data class BenchmarkValuesRow(
    val def: ExerciseDefinition,
    /** Newest value per side (null key = two-handed). */
    val values: Map<Side?, Benchmark>,
    val injury: InjuryAdvice,
    /** Estimated / being learnt / known (FEAT-071). */
    val learning: LearningState.State = LearningState.State.None,
)

internal data class BenchmarksRunState(
    val loading: Boolean = true,
    val key: List<BenchmarkValuesRow> = emptyList(),
    val others: List<BenchmarkValuesRow> = emptyList(),
    val profile: AthleteProfile = AthleteProfile(),
    val bodyweight: Double? = null,
    /** Grade anchor for the plausibility hint: the athlete's grade or the logbook's working grade. */
    val workingDifficulty: Double? = null,
    /** A test training was just started; the screen opens the player once. */
    val testStarted: Boolean = false,
)

internal class BenchmarksPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(BenchmarksRunState())
    val state: StateFlow<BenchmarksRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val logbook = runCatching { service.gradeSummary() }.getOrNull()
            combine(repo.observeAllBenchmarks(), repo.observeProfile(), repo.observeActiveInjuries()) { all, profile, injuries ->
                val catalog = service.catalog
                val bySlug = all.groupBy { it.exerciseSlug }
                fun row(slug: String): BenchmarkValuesRow? {
                    val def = catalog[slug] ?: return null
                    val list = bySlug[slug].orEmpty()
                    return BenchmarkValuesRow(def, latestPerSide(list), InjuryAdvisor.assess(def, injuries),
                        LearningState.of(slug, list, workSessionCount(service, slug)))
                }
                BenchmarksRunState(
                    loading = false,
                    key = KEY_BENCHMARK_SLUGS.mapNotNull(::row),
                    others = bySlug.keys.filter { it !in KEY_BENCHMARK_SLUGS }.mapNotNull(::row)
                        .sortedBy { it.def.name("en") },
                    profile = profile,
                    bodyweight = service.currentBodyweight(),
                    workingDifficulty = workingDifficulty(profile, logbook),
                    testStarted = _state.value.testStarted,
                )
            }.collect { _state.value = it }
        }
    }

    fun save(b: Benchmark) = io { service.saveBenchmark(b) }

    /** Body weight from the hint on top: values with added weight and % body weight need it. */
    fun logWeight(kg: Double) = io {
        service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", nowMs()))
        _state.update { it.copy(bodyweight = service.currentBodyweight() ?: kg) }
    }

    fun delete(id: String) = io { service.repo.deleteBenchmark(id) }

    fun startTest(slug: String) = io { if (service.startTest(slug) != null) _state.update { it.copy(testStarted = true) } }

    fun consumeTestStarted() = _state.update { it.copy(testStarted = false) }

    fun newId(): String = service.repo.newId()

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launch(io) { service.ensureReady(); block() }
    }

    companion object {
        fun latestPerSide(list: List<Benchmark>): Map<Side?, Benchmark> =
            list.groupBy { it.side }.mapValues { (_, v) -> v.maxBy { it.measuredAt } }

        /** The athlete's own grade first, then the logbook's working grade when it rests on enough sends. */
        fun workingDifficulty(profile: AthleteProfile, logbook: LogbookSummary?): Double? =
            profile.coach.currentGrade?.let { LogbookSummaries.difficultyOf(it) } ?: logbook?.takeIf { it.hasGrades }?.workingDifficulty

        /** Android `workSessionCount` (LearningBadge.kt): finished trainings with work sets of [slug]. */
        fun workSessionCount(service: AthleteService, slug: String): Int {
            val open = service.repo.openWorkout()?.id
            return service.repo.history(slug, 200)
                .filter { it.isCompleted && it.setType == SetType.WORK && it.workoutId != open }
                .map { it.workoutId }.distinct().size
        }

        /**
         * The value in % body weight on a scale [GradeStrengthNorms] knows (10-s
         * max on ~20 mm with both hands, pull-up 1RM), or null for other protocols.
         */
        fun normValue(def: ExerciseDefinition, b: Benchmark, bodyweight: Double?): Pair<GradeStrengthNorms.Metric, Double>? {
            val bw = (b.bodyweightKg ?: bodyweight)?.takeIf { it > 0 } ?: return null
            val cap = BenchmarkMath.capacity(def, b, bw) ?: return null
            return when {
                def.slug == "finger.max_hang" && cap.kind == CapacityKind.TEN_SECOND_MAX && (b.edgeMm?.let { kotlin.math.abs(it - 20.0) <= 2.0 } ?: true) ->
                    GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM to cap.value / bw * 100.0
                def.slug == "pull.weighted_pull_up" && cap.kind == CapacityKind.E1RM_TOTAL ->
                    GradeStrengthNorms.Metric.PULL_UP_1RM to cap.value / bw * 100.0
                def.slug == "pull.pull_up" && cap.kind == CapacityKind.MAX_REPS ->
                    GradeStrengthNorms.Metric.PULL_UP_1RM to StrengthMath.epley(bw, cap.value.roundToInt()) / bw * 100.0
                else -> null
            }
        }
    }
}
