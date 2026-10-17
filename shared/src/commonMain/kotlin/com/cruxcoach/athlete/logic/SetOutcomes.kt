package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType

/** How a done set compares with its target. */
enum class TargetOutcome { MET, UNDER, OVER }

/**
 * Target against result of one done set, for the set rows of the training
 * view ("8/8 ✓", "6/8 ↓", "7/10 s ↓"). Nothing is stored: it is read from the
 * target and actual columns every time.
 *
 * - Reps (with or without load): reps against the target reps, exactly.
 * - Holds and timed sets: held seconds against the target within ±0.5 s, so a
 *   timer stopped a moment early still reads as met. A hold the autoregulation
 *   counts as failed ([SetAutoregulation]: under 90 % of the target) always
 *   reads under; one a little short reads under too but moves nothing.
 * - Load, for added and lifted weight: lighter than the target is under,
 *   heavier is over. For body weight plus, more assistance (a more negative
 *   value) is lighter. A missing load counts as 0, as in [SetAutoregulation.untouched].
 *
 * No result for open sets, warm-ups, tests, intervals and climbing, nor for a
 * part without a target.
 */
object SetOutcomes {

    /** Seconds a hold may miss or exceed its target and still count as met. */
    const val HOLD_TOLERANCE_S = 0.5
    /** Rounding noise of added-up load steps; far below any weight step. */
    private const val LOAD_TOLERANCE_KG = 0.01
    private const val EPS = 1e-9

    data class Result(val main: TargetOutcome?, val load: TargetOutcome?) {
        val isEmpty: Boolean get() = main == null && load == null
    }

    private val NONE = Result(null, null)

    fun of(def: ExerciseDefinition, set: ExerciseSet): Result {
        if (!set.isCompleted || set.setType != SetType.WORK) return NONE
        val main = when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> compare(set.reps?.toDouble(), set.targetReps?.toDouble(), 0.0)
            ExerciseKind.TIME, ExerciseKind.HANG -> compare(set.durationS, set.targetDurationS, HOLD_TOLERANCE_S)
            ExerciseKind.INTERVAL, ExerciseKind.CLIMB -> return NONE
        }
        val loaded = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL
        val load = if (!loaded) null else set.targetLoadKg?.let { target -> compare(set.loadKg ?: 0.0, target, LOAD_TOLERANCE_KG) }
        return Result(main, load)
    }

    private fun compare(actual: Double?, target: Double?, tolerance: Double): TargetOutcome? {
        if (actual == null || target == null) return null
        return when {
            actual < target - tolerance - EPS -> TargetOutcome.UNDER
            actual > target + tolerance + EPS -> TargetOutcome.OVER
            else -> TargetOutcome.MET
        }
    }
}
