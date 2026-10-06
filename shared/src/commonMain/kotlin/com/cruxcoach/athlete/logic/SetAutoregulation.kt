package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import kotlin.math.abs
import kotlin.math.max

/** Why the rest of a block changed after a set. */
enum class AdjustReason {
    /** Fewer reps than planned. */
    MISSED_REPS,
    /** Reps made, nothing left in the tank. */
    NO_RESERVE,
    /** Clearly more in the tank than the plan meant. */
    BIG_RESERVE,
    /** The hold ended before the planned time. */
    HOLD_FAILED,
    /** The hold had several seconds to spare. */
    HOLD_EASY,
}

/**
 * Change for the remaining planned sets of one block (same exercise, same
 * side): added/lifted load, reps or hold time. All three are deltas against
 * the plan, so applying the same adjustment twice must be avoided — callers
 * pass what they already applied (see [SetAutoregulation.difference]).
 */
data class SetAdjustment(
    val loadDeltaKg: Double = 0.0,
    val repsDelta: Int = 0,
    val durationDeltaS: Double = 0.0,
    val reason: AdjustReason,
) {
    val isZero: Boolean get() = abs(loadDeltaKg) < 1e-9 && repsDelta == 0 && abs(durationDeltaS) < 1e-9
}

/**
 * Set-to-set autoregulation, the MCI way but for climbers: after a completed
 * work set the next sets of the same block move by one small step.
 *
 * - Reps (with or without load): missed reps or no reserve (RIR 0) → one
 *   weight step less, or one rep less without load; a big reserve (the
 *   athlete's "3+") with all reps made → one step more.
 * - Weighted holds: a hold that ends clearly early → about 5 % less total
 *   load (at least one step); 3+ seconds to spare → one step more. RIR 0 on a
 *   hold is the intended effort for max hangs and changes nothing.
 * - Body-weight holds: the time moves instead of the load.
 *
 * Increases never go beyond the performance-value prescription plus one step
 * ([ceilingKg]); warm-ups, tests, intervals and climbing never change.
 */
object SetAutoregulation {

    /** The reserve answer the UI offers as "3+" counts as a big reserve. */
    const val BIG_RESERVE = 3
    private const val HOLD_TOLERANCE = 0.9
    private const val HOLD_LOAD_SHARE = 0.05
    private const val MIN_HOLD_S = 3.0
    private const val EASY_HOLD_STEP_S = 2.0

    fun adjust(
        def: ExerciseDefinition,
        done: ExerciseSet,
        nextPlanned: ExerciseSet?,
        incrementKg: Double,
        ceilingKg: Double? = null,
    ): SetAdjustment? {
        if (!done.isCompleted || done.setType != SetType.WORK) return null
        val step = incrementKg.takeIf { it > 0 } ?: 1.0
        val loaded = def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS
        return when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> reps(done, nextPlanned, loaded, step, ceilingKg)
            ExerciseKind.HANG -> hold(def, done, nextPlanned, loaded, step, ceilingKg)
            ExerciseKind.TIME, ExerciseKind.INTERVAL, ExerciseKind.CLIMB -> null
        }
    }

    private fun reps(done: ExerciseSet, next: ExerciseSet?, loaded: Boolean, step: Double, ceilingKg: Double?): SetAdjustment? {
        val target = done.targetReps
        val achieved = done.reps
        val missed = target != null && achieved != null && achieved < target
        val rir = done.rir
        return when {
            missed || rir == 0 -> {
                val reason = if (missed) AdjustReason.MISSED_REPS else AdjustReason.NO_RESERVE
                if (loaded && done.loadKg != null) SetAdjustment(loadDeltaKg = -step, reason = reason)
                else {
                    val plannedReps = next?.targetReps ?: target ?: return null
                    if (plannedReps <= 1) null else SetAdjustment(repsDelta = -1, reason = reason)
                }
            }
            rir != null && rir >= BIG_RESERVE && (target == null || (achieved ?: 0) >= target) -> {
                if (loaded && done.loadKg != null) increase(next, step, ceilingKg, AdjustReason.BIG_RESERVE)
                else SetAdjustment(repsDelta = 1, reason = AdjustReason.BIG_RESERVE)
            }
            else -> null
        }
    }

    private fun hold(def: ExerciseDefinition, done: ExerciseSet, next: ExerciseSet?, loaded: Boolean, step: Double, ceilingKg: Double?): SetAdjustment? {
        val target = done.targetDurationS ?: return null
        val held = done.durationS ?: return null
        val failed = held < target * HOLD_TOLERANCE
        val easy = !failed && (done.rir ?: 0) >= BIG_RESERVE
        return when {
            failed && loaded && done.loadKg != null -> {
                val total = StrengthMath.effectiveLoad(def.load, done.loadKg, done.bodyweightKg) ?: done.loadKg
                val drop = max(step, StrengthMath.roundTo(total * HOLD_LOAD_SHARE, step))
                // Lifted weight cannot go below zero; added weight may turn into assistance.
                val limited = if (def.load == LoadMode.EXTERNAL) minOf(drop, max(0.0, (next?.targetLoadKg ?: done.loadKg))) else drop
                if (limited <= 0.0) null else SetAdjustment(loadDeltaKg = -limited, reason = AdjustReason.HOLD_FAILED)
            }
            failed -> {
                val planned = next?.targetDurationS ?: target
                val cut = max(1.0, target - held)
                val delta = -minOf(cut, planned - MIN_HOLD_S)
                if (delta >= 0.0) null else SetAdjustment(durationDeltaS = delta, reason = AdjustReason.HOLD_FAILED)
            }
            easy && loaded && done.loadKg != null -> increase(next, step, ceilingKg, AdjustReason.HOLD_EASY)
            easy -> SetAdjustment(durationDeltaS = EASY_HOLD_STEP_S, reason = AdjustReason.HOLD_EASY)
            else -> null
        }
    }

    private fun increase(next: ExerciseSet?, step: Double, ceilingKg: Double?, reason: AdjustReason): SetAdjustment? {
        val planned = next?.targetLoadKg ?: next?.loadKg
        if (ceilingKg != null && planned != null && planned + step > ceilingKg + 1e-9) return null
        return SetAdjustment(loadDeltaKg = step, reason = reason)
    }

    /** What still has to be applied when [previous] was applied for the same set already. */
    fun difference(current: SetAdjustment?, previous: SetAdjustment?): SetAdjustment? {
        if (previous == null) return current
        val reason = current?.reason ?: previous.reason
        val d = SetAdjustment(
            loadDeltaKg = (current?.loadDeltaKg ?: 0.0) - previous.loadDeltaKg,
            repsDelta = (current?.repsDelta ?: 0) - previous.repsDelta,
            durationDeltaS = (current?.durationDeltaS ?: 0.0) - previous.durationDeltaS,
            reason = reason,
        )
        return d.takeUnless { it.isZero }
    }

    /**
     * Applies [delta] to one planned set: target and the prefilled value move
     * together, so the set still counts as untouched by the athlete.
     */
    fun apply(def: ExerciseDefinition, set: ExerciseSet, delta: SetAdjustment): ExerciseSet {
        val minLoad = if (def.load == LoadMode.EXTERNAL) 0.0 else Double.NEGATIVE_INFINITY
        fun load(v: Double?) = v?.let { (it + delta.loadDeltaKg).coerceAtLeast(minLoad) }
        fun reps(v: Int?) = v?.let { (it + delta.repsDelta).coerceAtLeast(1) }
        fun secs(v: Double?) = v?.let { (it + delta.durationDeltaS).coerceAtLeast(MIN_HOLD_S) }
        return set.copy(
            targetLoadKg = load(set.targetLoadKg), loadKg = load(set.loadKg),
            targetReps = reps(set.targetReps), reps = reps(set.reps),
            targetDurationS = secs(set.targetDurationS), durationS = secs(set.durationS),
        )
    }

    /** A planned set the athlete has not edited yet: prefilled values still equal the plan. */
    fun untouched(set: ExerciseSet): Boolean =
        !set.isCompleted &&
            (set.loadKg ?: 0.0) == (set.targetLoadKg ?: 0.0) &&
            (set.reps == null || set.targetReps == null || set.reps == set.targetReps) &&
            (set.durationS == null || set.targetDurationS == null || set.durationS == set.targetDurationS)
}
