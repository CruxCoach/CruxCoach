package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the next training of an exercise plans: the value-based prescription ([LoadPrescriber]) and, on top of it, a
 * step that follows the last training (owner 2026-11-21: "offering the fitting progression is the main feature").
 *
 * The performance value alone left three holes:
 *
 * - **Body-weight repetitions and timed holds stood still.** They are planned at 70 % of the maximum. A set done as
 *   planned proves less than the maximum, whatever reserve is answered ("3+" is the largest answer), so the value
 *   never rose: 15 push-ups in the first training made a maximum of 18 and a plan of 13, for good.
 * - **Too hard was planned again.** A value the athlete trusts only rises ([ValueUpdates]), so a training that went
 *   badly was repeated with the same load.
 * - **The top of a loaded range with two in reserve is the value's resting point.** The value plans exactly that, so
 *   nothing moved until the athlete answered "3+".
 *
 * The rule, relative to the last training of the exercise (double progression, one lever at a time):
 *
 * - everything reached with reserve: one repetition or a few seconds more; at the top of a loaded range one weight
 *   step more, with the repetitions the value allows at that weight;
 * - reached, but hard: the same again;
 * - too hard: one step back; after two such trainings in a row the plan is read from what they showed;
 * - whenever the value asks for more (a test, an entered value, a value that rose), the value wins.
 *
 * Finger work keeps its careful rules: a loaded hang or pick-up takes one smallest step, never two, and only after a
 * training in which every set was held for the planned time and answered with reserve – what the summary's tip
 * already said, while the plan stood still (training simulation 2026-11-21: +1 kg for ten weeks). Nothing steps up
 * while something keeps loads careful (an injury, a comeback, the return after a break, a young or first-year climber).
 * A load the athlete wrote into the workout is never changed.
 */
object NextTargets {

    /** Why the plan differs from the value-based prescription. */
    enum class Move {
        /** Nothing to go by, or nothing to change: the value-based prescription. */
        NONE,
        UP,
        /** Everything answered "3+": two steps. */
        UP_FAST,
        /** Reached but hard, or just missed: the same again. */
        REPEAT,
        DOWN,
        /** Two trainings in a row too hard: planned from what they showed. */
        RELEARN,
    }

    /**
     * The targets of a work set. With [Move.NONE] they are the prescription's (null where there is none); every other
     * move sets what it decides. [atTop]: the top of the range was reached with reserve — time for the next exercise
     * of the chain ([ExerciseDefinition.harder]).
     */
    data class Next(
        val reps: Int? = null,
        val durationS: Int? = null,
        val loadKg: Double? = null,
        val move: Move = Move.NONE,
        val atTop: Boolean = false,
    )

    private val NOT_STEPPED = setOf(ExerciseCategoryV2.MOBILITY, ExerciseCategoryV2.WARMUP, ExerciseCategoryV2.TECHNIQUE, ExerciseCategoryV2.ENDURANCE)

    /** A set this far under its target counts as missed; half of the sets missed is too hard. */
    const val TOO_HARD_SHARE = 0.8
    /** The value's load within this share of the last training's: the steps are this rule's own. */
    const val NEAR_SHARE = 0.9
    /** The same for holds, under their planning share ([LoadPrescriber.holdShare]). */
    const val HOLD_MARGIN = 0.05
    /** Repetitions a loaded range may stretch above its top while the weight step is too big to take. */
    const val EXTENDED_REPS = 10

    /** The completed work sets of [side] per training, newest first, without the training [exceptWorkout]. */
    fun sessions(history: List<ExerciseSet>, side: Side?, exceptWorkout: String? = null): List<List<ExerciseSet>> =
        history.filter { it.isCompleted && it.setType == SetType.WORK && it.side == side && it.workoutId != exceptWorkout }
            .groupBy { it.workoutId }.values
            .sortedByDescending { s -> s.maxOf { it.completedAt ?: 0L } }
            .map { s -> s.sortedBy { it.setIndex } }

    /**
     * [history]: sets of this exercise from other trainings, any order. [mayStepUp]: nothing keeps loads careful
     * ([LoadSteps.mayStepUp]); without it the plan still follows the value and steps back, and body-weight work
     * still adds its single repetition.
     */
    fun plan(
        def: ExerciseDefinition,
        item: RoutineItem,
        capacity: Capacity?,
        history: List<ExerciseSet>,
        side: Side?,
        bodyweightKg: Double?,
        incrementKg: Double,
        mayStepUp: Boolean,
        exceptWorkout: String? = null,
    ): Next {
        val base = LoadPrescriber.prescribe(def, item, capacity, bodyweightKg, incrementKg)
        val none = Next(base?.reps, base?.durationS, base?.loadKg)
        if (item.warmup || item.test || def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.INTERVAL) return none
        // Stretches, warm-ups, technique and endurance work are done as written; only strength work steps.
        if (def.category in NOT_STEPPED) return none
        val past = sessions(history, side, exceptWorkout)
        if (past.isEmpty()) return none
        return when (BenchmarkMath.capacityKind(def)) {
            CapacityKind.MAX_REPS -> reps(def, item, base?.reps, past.first(), mayStepUp) ?: none
            CapacityKind.MAX_SECONDS -> seconds(def, item, base?.durationS, past.first()) ?: none
            CapacityKind.E1RM_TOTAL ->
                if (item.loadKg != null) none else loaded(def, item, base, capacity, past, bodyweightKg, incrementKg, mayStepUp) ?: none
            CapacityKind.TEN_SECOND_MAX ->
                if (item.loadKg != null) none else hold(def, item, base, capacity, past, bodyweightKg, incrementKg, mayStepUp) ?: none
            null -> none
        }
    }

    /** How a training went against its own targets. */
    private class Read(val reached: Boolean, val tooHard: Boolean, val easy: Boolean, val veryEasy: Boolean)

    /**
     * [unknownIsEasy]: a set without a reserve answer counts as done with reserve. True where the result is measured
     * (repetitions) – reaching the target says enough unless the athlete said it was hard.
     */
    private fun read(achieved: List<Double>, targets: List<Double?>, rirs: List<Int?>, unknownIsEasy: Boolean): Read {
        val pairs = achieved.zip(targets)
        val reached = pairs.all { (a, t) -> t == null || a >= t - 1e-9 }
        val missed = pairs.count { (a, t) -> t != null && a < t * TOO_HARD_SHARE - 1e-9 }
        val noReserve = rirs.isNotEmpty() && rirs.all { it == 0 }
        val tooHard = (missed > 0 && missed * 2 >= pairs.size) || (!reached && noReserve)
        val easy = rirs.all { r -> if (r == null) unknownIsEasy else r >= 2 }
        val veryEasy = rirs.all { r -> r != null && r >= 3 }
        return Read(reached, tooHard, reached && easy, reached && veryEasy)
    }

    private fun lowerMedian(values: List<Int>): Int = values.sorted()[(values.size - 1) / 2]

    // ── Body-weight repetitions ──────────────────────────────────────

    private fun reps(def: ExerciseDefinition, item: RoutineItem, base: Int?, last: List<ExerciseSet>, mayStepUp: Boolean): Next? {
        val sets = last.filter { it.reps != null }
        if (sets.isEmpty()) return null
        val done = sets.map { it.reps!! }
        val r = read(done.map { it.toDouble() }, sets.map { it.targetReps?.toDouble() }, sets.map { it.rir }, unknownIsEasy = true)
        val anchor = done.min()
        val target = sets.mapNotNull { it.targetReps }.maxOrNull() ?: anchor
        val unit = if (anchor >= 20) 2 else 1
        val top = item.repsMax ?: def.defaults.repsMax
        val aboveTop = top != null && anchor >= top
        val stepped = when {
            r.tooHard -> return Next(reps = min(target - 1, lowerMedian(done)).coerceAtLeast(1), move = Move.DOWN)
            !r.reached -> return Next(reps = target, move = Move.REPEAT)
            // Above the range only what is clearly easy still grows: the next exercise of the chain is the better step.
            aboveTop -> if (r.veryEasy) anchor + unit else anchor
            r.veryEasy && mayStepUp -> anchor + 2 * unit
            r.easy -> anchor + unit
            else -> anchor
        }
        // The value asks for more than the step (a test, an entered value): the value wins.
        val next = max(stepped, base ?: 0)
        val move = when {
            next >= anchor + 2 * unit && r.veryEasy -> Move.UP_FAST
            next > anchor -> Move.UP
            else -> Move.REPEAT
        }
        return Next(reps = next, move = move, atTop = aboveTop && r.easy)
    }

    // ── Timed holds without a load ───────────────────────────────────

    /** Where a hold is long enough and the next exercise of the chain is the better step. */
    fun holdTop(def: ExerciseDefinition, item: RoutineItem): Int {
        val usual = item.durationS ?: def.defaults.durationS ?: 30
        return if (LoadDomain.FINGER in def.domains) (usual * 2).coerceIn(usual, max(usual, 30)) else (usual * 2).coerceIn(30, 120)
    }

    private fun secondsUnit(seconds: Int): Int = when {
        seconds < 20 -> 2
        seconds < 60 -> 5
        else -> 10
    }

    private fun seconds(def: ExerciseDefinition, item: RoutineItem, base: Int?, last: List<ExerciseSet>): Next? {
        val sets = last.filter { it.durationS != null }
        if (sets.isEmpty()) return null
        val finger = LoadDomain.FINGER in def.domains
        val done = sets.map { floor(it.durationS!! + 1e-9).toInt() }
        // A plain timed hold is logged from its timer and asks for no reserve: done counts as done. Hangs are asked.
        val r = read(sets.map { it.durationS!! }, sets.map { it.targetDurationS }, sets.map { it.rir },
            unknownIsEasy = def.kind == ExerciseKind.TIME && !finger)
        val anchor = done.min()
        val target = sets.mapNotNull { it.targetDurationS }.maxOrNull()?.roundToInt() ?: anchor
        val unit = secondsUnit(anchor)
        // Tidy numbers from five seconds on: 23 s + 5 s plans 25 s, never less than a second more.
        fun tidy(s: Int): Int = if (unit >= 5) max(s / unit * unit, anchor + 1) else s
        val top = holdTop(def, item)
        val atTop = anchor >= top
        val stepped = when {
            r.tooHard -> return Next(durationS = min(target - unit, lowerMedian(done)).coerceAtLeast(3), move = Move.DOWN)
            !r.reached -> return Next(durationS = target, move = Move.REPEAT)
            atTop -> anchor
            r.easy -> min(tidy(anchor + unit), max(top, anchor))
            else -> anchor
        }
        val next = max(stepped, base ?: 0)
        return Next(durationS = next, move = if (next > anchor) Move.UP else Move.REPEAT, atTop = atTop && r.easy)
    }

    // ── Loaded repetitions ───────────────────────────────────────────

    private fun loaded(
        def: ExerciseDefinition,
        item: RoutineItem,
        base: LoadTarget?,
        capacity: Capacity?,
        past: List<List<ExerciseSet>>,
        bodyweightKg: Double?,
        incrementKg: Double,
        mayStepUp: Boolean,
    ): Next? {
        val last = past.first().filter { it.loadKg != null && it.reps != null }
        if (last.isEmpty()) return null
        val top = item.repsMax ?: item.repsMin ?: def.defaults.repsMax ?: base?.reps ?: return null
        val bottom = (item.repsMin ?: def.defaults.repsMin ?: top).coerceAtMost(top)
        val step = ProgressionAdvisor.increment(def, incrementKg)
        val lastLoad = last.maxOf { it.loadKg!! }
        val lastReps = last.mapNotNull { it.targetReps }.maxOrNull() ?: last.minOf { it.reps!! }
        fun readOf(sets: List<ExerciseSet>) = read(sets.map { it.reps!!.toDouble() }, sets.map { it.targetReps?.toDouble() },
            sets.map { it.rir }, unknownIsEasy = true)
        val r = readOf(last)
        // One step lighter, where there is one: no dumbbell under its smallest step, no "assisted" leg work.
        val lighter = (lastLoad - step).takeIf {
            if (def.load == LoadMode.BODYWEIGHT_PLUS) it >= -1e-9 || LoadPrescriber.assistable(def) else it > 1e-9
        }

        if (r.tooHard) {
            // Twice in a row: the value is too high for now. Plan from the best these two trainings showed.
            val before = past.getOrNull(1)?.filter { it.loadKg != null && it.reps != null }.orEmpty()
            if (before.isNotEmpty() && readOf(before).tooHard) {
                val shown = (last + before).mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == CapacityKind.E1RM_TOTAL } }
                    .maxByOrNull { it.value }
                if (shown != null && (capacity == null || shown.value < capacity.value)) {
                    val relearnt = LoadPrescriber.prescribe(def, item, shown, bodyweightKg, incrementKg)
                    if (relearnt?.loadKg != null) {
                        val load = lighter?.let { min(relearnt.loadKg, it) } ?: relearnt.loadKg
                        return Next(relearnt.reps ?: top, null, load, Move.RELEARN)
                    }
                }
            }
            val load = lighter?.let { l -> base?.loadKg?.let { min(it, l) } ?: l } ?: return Next(max(bottom, lastReps - 1), null, lastLoad, Move.DOWN)
            return Next(base?.reps ?: top, null, load, Move.DOWN)
        }

        val baseLoad = base?.loadKg
        // The steps of this rule run a little ahead of the value (it rises once a set proves 2 % more). Far above the
        // value's load – more than [NEAR_SHARE] apart – the athlete or an edited value moved things, and the value decides.
        fun total(load: Double): Double = StrengthMath.effectiveLoad(def, load, bodyweightKg) ?: load
        val near = baseLoad == null || total(baseLoad) >= total(lastLoad) * NEAR_SHARE - 1e-9
        val aboveBase = baseLoad != null && lastLoad > baseLoad + 1e-9
        fun repsFor(load: Double): Int? = capacity?.let { LoadPrescriber.repsAt(def, it, load, bodyweightKg) }

        var load = baseLoad
        var reps = base?.reps
        var move = Move.NONE
        if (near && r.reached && LoadSteps.strengthExercise(def)) {
            val hitTop = last.all { it.reps!! >= top }
            val up = lastLoad + step
            val repsUp = repsFor(up)
            when {
                // Top of the range with reserve: the next weight, with the repetitions it leaves.
                r.easy && hitTop && mayStepUp && (repsUp == null || repsUp >= bottom || lastReps >= top + EXTENDED_REPS) ->
                    if (baseLoad == null || up > baseLoad + 1e-9) { load = up; reps = (repsUp ?: top).coerceIn(bottom, top); move = Move.UP }
                // The weight step is too big to take yet: more repetitions at the same weight.
                r.easy && hitTop && mayStepUp ->
                    if (aboveBase || (reps ?: top) <= lastReps) { load = lastLoad; reps = lastReps + 1; move = Move.UP }
                // Building the repetitions back up after a weight step.
                aboveBase && lastReps < top ->
                    { load = lastLoad; reps = if (r.easy) lastReps + 1 else lastReps; move = if (r.easy) Move.UP else Move.REPEAT }
                aboveBase -> { load = lastLoad; reps = lastReps.coerceAtMost(top + EXTENDED_REPS); move = Move.REPEAT }
            }
        } else if (near && !r.reached && aboveBase) {
            load = lastLoad; reps = lastReps; move = Move.REPEAT
        }
        // After an easy training two steps, small loads one step at a time ([LoadSteps]); that rule has the last word.
        val before = load ?: lastLoad
        val stepped = LoadSteps.adjust(def, load, last, incrementKg, mayStepUp)
        if (stepped != null && abs(stepped - before) > 1e-9) {
            load = stepped
            reps = (repsFor(stepped) ?: reps ?: top).coerceIn(bottom, max(top, reps ?: top))
            move = if (stepped > before) Move.UP_FAST else if (stepped > lastLoad) Move.UP else Move.REPEAT
        }
        if (move == Move.NONE) return null
        return Next(reps ?: top, null, load, move)
    }

    // ── Loaded holds (finger work): one careful step ─────────────────

    private fun hold(
        def: ExerciseDefinition,
        item: RoutineItem,
        base: LoadTarget?,
        capacity: Capacity?,
        past: List<List<ExerciseSet>>,
        bodyweightKg: Double?,
        incrementKg: Double,
        mayStepUp: Boolean,
    ): Next? {
        if (def.kind != ExerciseKind.HANG) return null
        val last = past.first().filter { it.loadKg != null && it.durationS != null }
        if (last.isEmpty()) return null
        // A hold is logged with its planned time: only an answered reserve says it went well.
        fun readOf(sets: List<ExerciseSet>) = read(sets.map { it.durationS!! }, sets.map { it.targetDurationS }, sets.map { it.rir }, unknownIsEasy = false)
        val r = readOf(last)
        val step = ProgressionAdvisor.increment(def, incrementKg)
        val lastLoad = last.maxOf { it.loadKg!! }
        if (!r.tooHard) {
            val baseLoad = base?.loadKg
            // A hold is planned at a share of the value (90 %, long hangs 85 %), so the steps can run up to the
            // value itself before a set proves more; beyond that the value decides.
            fun total(load: Double): Double = StrengthMath.effectiveLoad(def, load, bodyweightKg) ?: load
            val share = LoadPrescriber.holdShare(base?.durationS ?: item.durationS ?: def.defaults.durationS ?: 10) - HOLD_MARGIN
            val near = baseLoad == null || total(baseLoad) >= total(lastLoad) * share - 1e-9
            val aboveBase = baseLoad != null && lastLoad > baseLoad + 1e-9
            return when {
                !near -> null
                // Every set held with reserve: one smallest step, where the value does not already ask for it.
                r.easy && mayStepUp && (baseLoad == null || lastLoad + step > baseLoad + 1e-9) -> Next(null, base?.durationS ?: item.durationS, lastLoad + step, Move.UP)
                // The step taken last time stays while it is hard, instead of falling back to the value's load.
                aboveBase -> Next(null, base?.durationS ?: item.durationS, lastLoad, Move.REPEAT)
                else -> null
            }
        }
        val lighter = (lastLoad - step).takeIf { def.load == LoadMode.BODYWEIGHT_PLUS || it > 1e-9 } ?: return null
        val before = past.getOrNull(1)?.filter { it.loadKg != null && it.durationS != null }.orEmpty()
        if (before.isNotEmpty() && readOf(before).tooHard) {
            val shown = (last + before).mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == CapacityKind.TEN_SECOND_MAX } }
                .maxByOrNull { it.value }
            if (shown != null && (capacity == null || shown.value < capacity.value)) {
                val relearnt = LoadPrescriber.prescribe(def, item, shown, bodyweightKg, incrementKg)
                if (relearnt?.loadKg != null) return Next(null, relearnt.durationS, min(relearnt.loadKg, lighter), Move.RELEARN)
            }
        }
        return Next(null, base?.durationS, base?.loadKg?.let { min(it, lighter) } ?: lighter, Move.DOWN)
    }
}
