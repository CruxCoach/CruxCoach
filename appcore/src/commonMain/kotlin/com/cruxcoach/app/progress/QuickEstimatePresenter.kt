// Port of androidApp/.../ui/training/coach/QuickEstimateSheet.kt's QuickEstimateViewModel
// (0e1bad0c6) for the iOS app. Kept close to the Android file so later Android changes carry over
// by diff. Platform seams: an injected scope and IO dispatcher, the common clock, a one-shot state
// flag instead of a Channel for "saved".
package com.cruxcoach.app.progress

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Answer to "how many pull-ups in a row?". */
internal enum class PullAnswer(val reps: Int?) { NONE(0), FEW(2), SOME(5), MANY(10), LOTS(15), WEIGHTED(null) }

/** Answer to "10 s on a 20 mm edge, both hands?". */
internal enum class HangAnswer { CANNOT, BARELY, EASY, WEIGHTED }

internal data class QuickEstimateRunState(
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
    val saved: Boolean = false,
)

/**
 * The 15-second self-estimate of the coach setup (FEAT-071 §4.2). Answers
 * become performance values marked ESTIMATE; the first logged set replaces
 * them either way. The plausibility check against the grade is a hint only.
 */
internal class QuickEstimatePresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(QuickEstimateRunState())
    val state: StateFlow<QuickEstimateRunState> = _state.asStateFlow()

    init {
        scope.launch(io) {
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
            _state.value = QuickEstimateRunState(
                loading = false,
                units = profile.units,
                bodyweight = service.currentBodyweight(),
                // A block at any place counts: values are entered once, wherever they were measured.
                hasPickup = EquipmentV2.satisfied(EquipmentV2.PICKUP_BLOCK, TrainingPlaces.allEquipment(profile)),
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
        scope.launch(io) {
            service.ensureReady()
            service.repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, kg, "kg", nowMs()))
            _state.update { it.copy(bodyweight = kg) }
        }
    }

    fun save(
        pull: PullAnswer?, weightedKg: Double?, weightedReps: Int?,
        hang: HangAnswer?, hangKg: Double?,
        pickupLeftKg: Double?, pickupRightKg: Double?,
    ) {
        val s = _state.value
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val now = nowMs()
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
            _state.update { it.copy(saved = true) }
        }
    }

    fun close() = scope.cancel()

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
