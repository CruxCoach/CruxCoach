package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Whether a completed set moves an exercise's performance value, and to what (comparison review #12: progression
 * that cannot be thrown off by one set).
 *
 * - A test set is the truth, also when lower; within one test the best set counts.
 * - Without a value from before this training, or with only a self-estimate ([BenchmarkSource.ESTIMATE]) from
 *   before it, the training learns the value: its best set so far stands for it – also below the estimate, so a
 *   guess that was too high is corrected after the first sets instead of staying until a test.
 * - A value the athlete trusts (entered, tested or from earlier trainings) only rises: a set has to prove more than
 *   [RAISE_THRESHOLD] times the value the training started with, and one set alone raises it by at most
 *   [MAX_SINGLE_SET_RAISE] (whole reps by one, seconds by two at least) – a typo ("50 reps") or a lucky set must not
 *   jump the next plan, and the reserve answered after it does not raise it twice. A second set of the same training
 *   that proves as much confirms it: the value then rises to what both sets show.
 */
object ValueUpdates {

    /** A set has to prove this much more than the value before the training to move it (noise). */
    const val RAISE_THRESHOLD = 1.02
    /** One set alone raises a trusted value by at most this much; a second set of the training confirms more. */
    const val MAX_SINGLE_SET_RAISE = 1.10

    /** The value after a set: [value] in [kind], read from [source] (scaled down to [value] when [capped]). */
    data class Update(val source: ExerciseSet, val kind: CapacityKind, val value: Double, val capped: Boolean)

    /**
     * [current]: the value that applies to the set's side now; [before]: the one that applied when the training
     * started (null: none); [session]: the other completed sets of this exercise and side in the same training.
     * Returns the new value, or null to keep the current one.
     */
    fun decide(
        def: ExerciseDefinition,
        set: ExerciseSet,
        current: Benchmark?,
        before: Benchmark?,
        session: List<ExerciseSet>,
        bodyweightKg: Double? = set.bodyweightKg,
    ): Update? {
        if (!set.isCompleted || set.setType == SetType.WARMUP) return null
        val implied = BenchmarkMath.implied(def, set) ?: return null
        if (set.setType == SetType.TEST) {
            val sameTest = session.filter { it.id != set.id && it.setType == SetType.TEST }.mapNotNull { BenchmarkMath.implied(def, it)?.value }
            return if (sameTest.all { implied.value > it }) Update(set, implied.kind, implied.value, capped = false) else null
        }
        val now = current?.let { BenchmarkMath.capacity(def, it, bodyweightKg) }
            ?: return Update(set, implied.kind, implied.value, capped = false)   // the first set learns the starting value
        if (now.kind != implied.kind) return null
        // What this training's work sets show, best first.
        val shown = (session.filter { it.id != set.id && it.isCompleted && it.setType == SetType.WORK } + set)
            .mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == implied.kind }?.let { s to it.value } }
            .sortedByDescending { it.second }
        // A value entered or tested is the athlete's word, also when entered during this training.
        val trustedBefore = before != null && before.source != BenchmarkSource.ESTIMATE
        val trusted = trustedBefore || current.source == BenchmarkSource.MANUAL || current.source == BenchmarkSource.TEST
        if (!trusted) {
            // Learning: the best set of the training stands for the value, up or down; only that set writes it.
            val best = shown.first()
            if (best.first.id != set.id || abs(best.second - now.value) < 1e-9) return null
            return Update(set, implied.kind, implied.value, capped = false)
        }
        val start = before?.takeIf { trustedBefore }?.let { BenchmarkMath.capacity(def, it, bodyweightKg) }?.takeIf { it.kind == implied.kind } ?: now
        val best = shown.first().second
        val second = shown.getOrNull(1)?.second ?: 0.0
        // Whole reps and seconds: one step at least, or a small value could never move.
        val cap = when (implied.kind) {
            CapacityKind.MAX_REPS -> max(start.value * MAX_SINGLE_SET_RAISE, start.value + 1.0)
            CapacityKind.MAX_SECONDS -> max(start.value * MAX_SINGLE_SET_RAISE, start.value + 2.0)
            else -> start.value * MAX_SINGLE_SET_RAISE
        }
        val target = min(best, max(cap, second))
        // Clearly above the value the training started with and the one that applies now (a capped value sits just under its cap).
        if (target <= start.value * RAISE_THRESHOLD || target <= now.value * RAISE_THRESHOLD) return null
        // The set that shows the target, or the closest one above it (scaled down to it).
        val source = shown.filter { it.second >= target - 1e-9 }.minBy { it.second }
        return Update(source.first, implied.kind, target, capped = source.second > target + 1e-9)
    }

    /**
     * The value [b] (saved from a set) brought down to [value] of its kind: the load for loaded work, the reps or
     * seconds otherwise. Loads round down to half a kilo, so the value never claims more than the cap.
     */
    fun scaledTo(def: ExerciseDefinition, b: Benchmark, value: Double, bodyweightKg: Double?): Benchmark {
        val bw = b.bodyweightKg ?: bodyweightKg
        fun load(total: Double): Double? = when (def.load) {
            LoadMode.BODYWEIGHT_PLUS -> bw?.let { floorHalf(total - it) }
            LoadMode.EXTERNAL -> floorHalf(max(total, 0.0))
            else -> null
        }
        return when (BenchmarkMath.capacityKind(def)) {
            CapacityKind.E1RM_TOTAL -> {
                val reps = b.reps ?: return b
                val lifted = StrengthMath.effectiveLoad(def.load, b.loadKg, bw)
                if (lifted != null && lifted > 0 && lifted <= value) {
                    // The load stays, the reps come down to what the value allows ("+20 kg × 8", not "−27 kg × 50").
                    b.copy(reps = max(1, floor(30.0 * (value / lifted - 1.0) + 1e-9).toInt()))
                } else {
                    val total = if (reps <= 1) value else value / (1.0 + reps / 30.0)
                    load(total)?.let { b.copy(loadKg = it) } ?: b
                }
            }
            CapacityKind.TEN_SECOND_MAX -> {
                val total = value * HoldCurve.relative(b.durationS?.takeIf { it > 0 } ?: 10.0)
                load(total)?.let { b.copy(loadKg = it) } ?: b
            }
            CapacityKind.MAX_REPS -> b.copy(reps = max(1, floor(value + 1e-9).toInt()))
            CapacityKind.MAX_SECONDS -> b.copy(durationS = floor(value + 1e-9))
            null -> b
        }
    }

    private fun floorHalf(kg: Double): Double = floor(kg * 2.0 + 1e-9) / 2.0
}
