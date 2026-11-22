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
 * What the next training of an exercise plans: the value-based prescription ([LoadPrescriber]) and, once there is a
 * last training to go by, a step that follows it (owner 2026-11-21: "offering the fitting progression is the main
 * feature").
 *
 * The performance value alone left holes:
 *
 * - **Body-weight repetitions and timed holds stood still.** They are planned at 70 % of the maximum. A set done as
 *   planned proves less than the maximum, whatever reserve is answered ("3+" is the largest answer), so the value
 *   never rose: 15 push-ups in the first training made a maximum of 18 and a plan of 13, for good.
 * - **Holds and loaded repetitions rested on their own plan.** A hang at 90 % of the value, or the top of a range
 *   with two in reserve, proves exactly the value that planned it. The summary's tip said "one step more", the plan
 *   stood (training simulation 2026-11-21: +1 kg hangs for ten weeks).
 * - **Too hard was planned again.** A value the athlete trusts only rises ([ValueUpdates]).
 *
 * The rule is a ladder relative to the last training of the exercise (double progression, one lever at a time):
 *
 * - everything reached with reserve: one repetition or a few seconds more; at the top of a loaded range one weight
 *   step, with the repetitions the value allows at that weight; a hold one smallest step;
 * - reached, but hard, or just missed: the same again – also on a rung below or above the value's own plan, so a
 *   step back stays until the training went well again;
 * - too hard: one step back; two such trainings in a row plan from what they showed.
 *
 * The value-based prescription decides whenever the ladder has nothing to stand on: no last training, a last
 * training long ago ([MAX_AGE_DAYS]), a value the athlete entered or tested after it, a load the athlete changed by
 * hand in it, sets logged without a plan, or a last training with another repetition range, hold time, edge or grip.
 * A load written into the workout is never changed.
 *
 * How far it may move is the caller's word ([Steps]): not at all in a comeback session, never upwards around an open
 * injury or in the return after a break, only body-weight work for young and first-year climbers.
 */
object NextTargets {

    /** How far the plan may move by itself. */
    enum class Steps {
        /** A comeback session: the rehab plan's numbers stand; only the value-based prescription, as before this rule. */
        OFF,
        /** An open injury, the return ramp after a break: the same again or a step back, never up. */
        CAREFUL,
        /** A growing or first-year climber: body-weight work adds its repetition or seconds; no weight and no finger step. */
        GENTLE,
        FULL,
    }

    /** Why the plan differs from the value-based prescription. */
    enum class Move {
        /** Nothing to go by, or nothing for this rule to decide: the value-based prescription. */
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
     * move sets what it decides. [atTop]: the top of the range was reached with reserve – time for the next exercise
     * of the chain ([ExerciseDefinition.harder]).
     */
    data class Next(
        val reps: Int? = null,
        val durationS: Int? = null,
        val loadKg: Double? = null,
        val move: Move = Move.NONE,
        val atTop: Boolean = false,
        /**
         * The plan cannot get lighter and is still under the exercise's range (three repetitions where eight are the
         * least, a hold under half its time, no lighter weight to take): time for the easier exercise of the chain.
         */
        val atBottom: Boolean = false,
    )

    /** Stretches, warm-ups, technique and endurance work are done as written: no share of a maximum, no step. */
    private val AS_WRITTEN = setOf(ExerciseCategoryV2.MOBILITY, ExerciseCategoryV2.WARMUP, ExerciseCategoryV2.TECHNIQUE, ExerciseCategoryV2.ENDURANCE)

    fun asWritten(def: ExerciseDefinition): Boolean =
        def.category in AS_WRITTEN || ("mobility" in def.tags && "strength" !in def.tags && "prehab" !in def.tags)

    /** A set this far under its target counts as missed; half of the sets missed is too hard. */
    const val TOO_HARD_SHARE = 0.8
    /** With no reserve on any set, one set this far under its target is too hard as well (9.4 of 10 s is not). */
    const val CLEARLY_SHORT_SHARE = 0.9
    /** A last training older than this says nothing about today. */
    const val MAX_AGE_DAYS = 42
    /** The last training's best set implies at least this share of the value: the value was shown there. */
    const val SHOWN_SHARE = 0.99
    /** Holds this many seconds apart are the same job. */
    const val SAME_HOLD_S = 1.0
    /** Repetitions may stretch this far above the top of a range: while a weight step is too big, or without a next exercise. */
    const val EXTENDED_REPS = 10
    /** A timed hold at least this long is logged from its timer: done counts as done with reserve. Shorter ones are strength holds and need the answer. */
    const val LONG_HOLD_S = 20

    /**
     * Whether a hold of [def] planned for [plannedS] seconds is a long plain hold: done counts as done with reserve
     * when nothing is answered, and the player asks in coarser steps ("how many seconds more: 0, 1, 2, 3+" after a
     * 30-second side plank says nothing – Nokia walk 2026-11-22).
     */
    fun longPlainHold(def: ExerciseDefinition, plannedS: Int?): Boolean =
        def.kind == ExerciseKind.TIME && LoadDomain.FINGER !in def.domains && (plannedS ?: def.defaults.durationS ?: 30) >= LONG_HOLD_S

    /** The completed work sets of [side] per training, newest first, without the training [exceptWorkout]. */
    fun sessions(history: List<ExerciseSet>, side: Side?, exceptWorkout: String? = null): List<List<ExerciseSet>> =
        history.filter { it.isCompleted && it.setType == SetType.WORK && it.side == side && it.workoutId != exceptWorkout }
            .groupBy { it.workoutId }.values
            .sortedByDescending { s -> s.maxOf { it.completedAt ?: 0L } }
            .map { s -> s.sortedBy { it.setIndex } }

    /**
     * [history]: sets of this exercise, any order. [now]: the clock (epoch ms), for [MAX_AGE_DAYS]; null = no age limit.
     */
    fun plan(
        def: ExerciseDefinition,
        item: RoutineItem,
        capacity: Capacity?,
        history: List<ExerciseSet>,
        side: Side?,
        bodyweightKg: Double?,
        incrementKg: Double,
        steps: Steps,
        exceptWorkout: String? = null,
        now: Long? = null,
        /**
         * The athlete can step on to the next exercise of the chain ([ExerciseDefinition.harder]): there is one, and
         * the place has what it needs. Null: whether the chain has one at all. Where the next exercise is out of reach
         * (a hip thrust asks for a barbell and a bench), repetitions are the only lever left.
         */
        stepOn: Boolean? = null,
    ): Next {
        val base = LoadPrescriber.prescribe(def, item, capacity, bodyweightKg, incrementKg)
        // A load written into the workout is the plan's load, here as in the planner: the tip must say what is planned.
        val none = Next(base?.reps, base?.durationS, item.loadKg ?: base?.loadKg)
        if (item.warmup || item.test || def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.INTERVAL) return none
        val kind = BenchmarkMath.capacityKind(def) ?: return none
        val loadedKind = kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX
        // A stretch or a warm-up is not trained towards a maximum: its own repetitions and seconds, every time.
        if (asWritten(def)) return if (loadedKind) none else Next()
        // Another edge or grip is another job with other loads: only trainings on this one count.
        fun sameHold(s: ExerciseSet) = (item.edgeMm == null || s.edgeMm == null || abs(s.edgeMm - item.edgeMm) < 0.5) &&
            (item.grip == null || s.grip == null || s.grip == item.grip)
        val past = sessions(history.filter(::sameHold), side, exceptWorkout)
        val last = past.firstOrNull()
        // The value alone, as before this rule: bigger steps after an easy training and small loads one step at a
        // time ([LoadSteps]) where the load is planned.
        fun valueOnly(): Next {
            if (!loadedKind || item.loadKg != null || last == null) return none
            val loads = last.filter { it.loadKg != null }
            val stepped = LoadSteps.adjust(def, base?.loadKg, loads, incrementKg, steps == Steps.FULL) ?: return none
            val before = base?.loadKg ?: loads.mapNotNull { it.loadKg }.maxOrNull() ?: return none
            // More than the value's plan is the bigger step; less is the value's plan taken one small step at a time.
            return if (abs(stepped - before) < 1e-9) none else none.copy(loadKg = stepped, move = if (stepped > before) Move.UP_FAST else Move.NONE)
        }
        // Body weight is the least this exercise can weigh, and even so the plan is under its range.
        fun checked(next: Next): Next {
            val least = item.repsMin ?: def.defaults.repsMin
            val stuck = kind == CapacityKind.E1RM_TOTAL && def.load == LoadMode.BODYWEIGHT_PLUS && !LoadPrescriber.assistable(def) &&
                next.loadKg != null && next.loadKg <= 1e-9 && next.reps != null && least != null && next.reps < least
            return if (stuck) next.copy(atBottom = true) else next
        }
        if (last == null || steps == Steps.OFF) return checked(valueOnly())
        val lastAt = last.maxOf { it.completedAt ?: 0L }
        if (now != null && now - lastAt > MAX_AGE_DAYS * 86_400_000L) return checked(valueOnly())
        // The athlete's newer word (an entered value, a test, a load lowered by hand) decides.
        if (capacity?.statedAt != null && capacity.statedAt > lastAt) return checked(valueOnly())
        val next = when (kind) {
            CapacityKind.MAX_REPS -> reps(def, item, last, steps, stepOn ?: (def.harder != null))
            CapacityKind.MAX_SECONDS -> seconds(def, item, last, steps)
            CapacityKind.E1RM_TOTAL -> if (item.loadKg != null) none else loaded(def, item, base, capacity, past, bodyweightKg, incrementKg, steps)
            CapacityKind.TEN_SECOND_MAX -> if (item.loadKg != null) none else hold(def, item, base, capacity, past, bodyweightKg, incrementKg, steps)
        } ?: valueOnly()
        return checked(next)
    }

    /** How a training went against its own targets. */
    private class Read(val reached: Boolean, val tooHard: Boolean, val easy: Boolean, val veryEasy: Boolean)

    /**
     * [unknownIsEasy]: a planned set that was reached without a reserve answer counts as done with reserve. True where
     * the result is measured (repetitions, a long timed hold). A set logged without a target says nothing either way.
     * [tolerance]: how far under its target a set still counts as reached (half a second on a hold).
     */
    private fun read(achieved: List<Double>, targets: List<Double?>, rirs: List<Int?>, unknownIsEasy: Boolean, tolerance: Double = 1e-9): Read {
        val pairs = achieved.zip(targets)
        val reached = pairs.all { (a, t) -> t == null || a >= t - tolerance }
        val missed = pairs.count { (a, t) -> t != null && a < t * TOO_HARD_SHARE - 1e-9 }
        val clearlyShort = pairs.any { (a, t) -> t != null && a < t * CLEARLY_SHORT_SHARE - 1e-9 }
        val noReserve = rirs.isNotEmpty() && rirs.all { it == 0 }
        val tooHard = (missed > 0 && missed * 2 >= pairs.size) || (noReserve && clearlyShort)
        val easy = rirs.indices.all { i -> rirs[i]?.let { it >= 2 } ?: (unknownIsEasy && targets[i] != null) }
        val veryEasy = rirs.all { r -> r != null && r >= 3 }
        return Read(reached, tooHard, reached && easy, reached && veryEasy)
    }

    private fun lowerMedian(values: List<Int>): Int = values.sorted()[(values.size - 1) / 2]

    // ── Body-weight repetitions ──────────────────────────────────────

    private fun reps(def: ExerciseDefinition, item: RoutineItem, last: List<ExerciseSet>, steps: Steps, stepOn: Boolean): Next? {
        val sets = last.filter { it.reps != null }
        // A training without targets (sets logged freely, one all-out set) is no rung to stand on.
        if (sets.isEmpty() || sets.none { it.targetReps != null }) return null
        val done = sets.map { it.reps!! }
        val r = read(done.map { it.toDouble() }, sets.map { it.targetReps?.toDouble() }, sets.map { it.rir }, unknownIsEasy = true)
        val anchor = done.min()
        val target = sets.mapNotNull { it.targetReps }.maxOrNull() ?: anchor
        if (r.tooHard) {
            val back = min(target - 1, lowerMedian(done)).coerceAtLeast(1)
            val least = item.repsMin ?: def.defaults.repsMin
            return Next(reps = back, move = Move.DOWN, atBottom = least != null && back < least)
        }
        if (!r.reached) return Next(reps = target, move = Move.REPEAT)
        val unit = if (anchor >= 20) 2 else 1
        val top = item.repsMax ?: def.defaults.repsMax
        val aboveTop = top != null && anchor >= top
        val mayGrow = steps >= Steps.GENTLE
        val next = when {
            !mayGrow -> anchor
            // Above the range only what is clearly easy still grows where the athlete can step on to the next exercise
            // of the chain: that is the better step. Without one – the end of a chain, or a next exercise the place
            // has no equipment for – repetitions are the only lever left and go on with reserve: no cap, each step
            // needs the athlete's own answer (a cap left band work too easy for good, simulation 2026-11-21; a
            // single-leg glute bridge stood at 17 for four weeks at home, its next exercise needs a barbell,
            // simulation 2026-11-22).
            aboveTop -> if (r.veryEasy || (r.easy && !stepOn)) anchor + unit else anchor
            r.veryEasy && steps == Steps.FULL -> anchor + 2 * unit
            r.easy -> anchor + unit
            else -> anchor
        }
        val move = when {
            next >= anchor + 2 * unit -> Move.UP_FAST
            next > anchor -> Move.UP
            else -> Move.REPEAT
        }
        // The next exercise of the chain is a step up too: not offered while something keeps the plan careful.
        return Next(reps = next, move = move, atTop = aboveTop && r.easy && steps >= Steps.GENTLE)
    }

    // ── Timed holds without a load ───────────────────────────────────

    /** Where a hold is long enough and the next exercise of the chain is the better step: twice its usual time. */
    fun holdTop(def: ExerciseDefinition, item: RoutineItem): Int {
        val usual = item.durationS ?: def.defaults.durationS ?: 30
        return if (LoadDomain.FINGER in def.domains) (usual * 2).coerceAtMost(max(usual, 30)) else (usual * 2).coerceAtMost(max(usual, 120))
    }

    private fun secondsUnit(seconds: Int): Int = when {
        seconds < 20 -> 2
        seconds < 60 -> 5
        else -> 10
    }

    private fun seconds(def: ExerciseDefinition, item: RoutineItem, last: List<ExerciseSet>, steps: Steps): Next? {
        val sets = last.filter { it.durationS != null }
        if (sets.isEmpty() || sets.none { it.targetDurationS != null }) return null
        val finger = LoadDomain.FINGER in def.domains
        val usual = item.durationS ?: def.defaults.durationS ?: 30
        val done = sets.map { floor(it.durationS!! + 1e-9).toInt() }
        // A long plain hold is logged from its timer: done counts as done. Hangs and short strength holds need the answer.
        val r = read(sets.map { it.durationS!! }, sets.map { it.targetDurationS }, sets.map { it.rir },
            unknownIsEasy = longPlainHold(def, usual), tolerance = 0.5)
        val anchor = done.min()
        val target = sets.mapNotNull { it.targetDurationS }.maxOrNull()?.roundToInt() ?: anchor
        val unit = secondsUnit(anchor)
        if (r.tooHard) {
            val back = min(target - unit, lowerMedian(done)).coerceAtLeast(min(3, target))
            return Next(durationS = back, move = Move.DOWN, atBottom = back * 2 < usual)
        }
        if (!r.reached) return Next(durationS = target, move = Move.REPEAT)
        // Tidy numbers from five seconds on: 23 s + 5 s plans 25 s, never less than a second more.
        fun tidy(s: Int): Int = if (unit >= 5) max(s / unit * unit, anchor + 1) else s
        val top = holdTop(def, item)
        val atTop = anchor >= top
        // Finger hangs only step where nothing keeps loads careful.
        val mayGrow = !atTop && (if (finger) steps == Steps.FULL else steps >= Steps.GENTLE)
        val next = if (mayGrow && r.easy) min(tidy(anchor + unit), max(top, anchor)) else anchor
        return Next(durationS = next, move = if (next > anchor) Move.UP else Move.REPEAT,
            atTop = atTop && r.easy && (if (finger) steps == Steps.FULL else steps >= Steps.GENTLE))
    }

    // ── Loaded repetitions ───────────────────────────────────────────

    /** Every set at the load the plan gave it: the athlete changed nothing by hand, so the rung is this rule's. */
    private fun asPlanned(sets: List<ExerciseSet>): Boolean = sets.all { s -> s.targetLoadKg != null && abs(s.loadKg!! - s.targetLoadKg) < 1e-6 }

    private fun loaded(
        def: ExerciseDefinition,
        item: RoutineItem,
        base: LoadTarget?,
        capacity: Capacity?,
        past: List<List<ExerciseSet>>,
        bodyweightKg: Double?,
        incrementKg: Double,
        steps: Steps,
    ): Next? {
        val last = past.first().filter { it.loadKg != null && it.reps != null }
        // A load the athlete changed by hand is their word ([OwnLoads], the value): no rung of this rule.
        if (last.isEmpty() || !asPlanned(last)) return null
        val top = item.repsMax ?: item.repsMin ?: def.defaults.repsMax ?: base?.reps ?: return null
        val bottom = (item.repsMin ?: def.defaults.repsMin ?: top).coerceAtMost(top)
        val step = ProgressionAdvisor.increment(def, incrementKg)
        val lastLoad = last.maxOf { it.loadKg!! }
        val lastReps = last.mapNotNull { it.targetReps }.maxOrNull() ?: last.minOf { it.reps!! }
        // A load step needs an answered reserve: "reached" alone must not make a load the athlete lowered heavier again.
        // A training is read against the repetitions it started with: where no weight is left to take off (body weight,
        // for work that cannot be assisted), the sets after one without reserve get a repetition less
        // ([SetAutoregulation]). Read against their own lowered targets, every such training counted as reached and the
        // step back to the easier exercise was never offered (walking lunges at 12, 11, 10 and 9 without reserve, week
        // after week: training simulation 2026-11-22).
        fun readOf(sets: List<ExerciseSet>): Read {
            val started = sets.mapNotNull { it.targetReps }.maxOrNull()?.toDouble()
            return read(sets.map { it.reps!!.toDouble() }, sets.map { s -> s.targetReps?.let { started } }, sets.map { it.rir }, unknownIsEasy = false)
        }
        val r = readOf(last)
        // One step lighter, where there is one: no dumbbell under its smallest step, no "assisted" leg work.
        val lighter = (lastLoad - step).takeIf {
            if (def.load == LoadMode.BODYWEIGHT_PLUS) it >= -1e-9 || LoadPrescriber.assistable(def) else it > 1e-9
        }
        val baseLoad = base?.loadKg

        if (r.tooHard) {
            // Twice in a row: the value is too high for now. Plan from the best these two trainings showed.
            val before = past.getOrNull(1)?.filter { it.loadKg != null && it.reps != null }.orEmpty()
            if (before.isNotEmpty() && readOf(before).tooHard) {
                val shown = (last + before).mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == CapacityKind.E1RM_TOTAL } }
                    .maxByOrNull { it.value }
                if (shown != null && (capacity == null || shown.value < capacity.value)) {
                    val relearnt = LoadPrescriber.prescribe(def, item, shown, bodyweightKg, incrementKg)
                    val load = relearnt?.loadKg
                    if (load != null) return Next(relearnt.reps ?: top, null, lighter?.let { min(load, it) } ?: load, Move.RELEARN)
                }
            }
            val load = lighter?.let { l -> baseLoad?.let { min(it, l) } ?: l }
                ?: return Next(max(bottom, lastReps - 1), null, lastLoad, Move.DOWN, atBottom = true)
            return Next(base?.reps ?: top, null, load, Move.DOWN)
        }

        // The rungs of this rule run apart from the value's own plan (the value rises once a set proves 2 % more, and
        // stays where it is after a step back). They are for the same job only: a workout with another repetition
        // range plans other loads, and there the value decides.
        if (lastReps !in bottom..(top + EXTENDED_REPS)) return null
        // What the best set of the last training showed. The repetitions at another weight follow the stronger of
        // it and the value: a set at the top of the range with reserve shows a little more than the value that
        // planned it, too little to raise the value and enough for the next fine step.
        val best = last.mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == CapacityKind.E1RM_TOTAL } }.maxByOrNull { it.value }
        val strongest = listOfNotNull(capacity, best).maxByOrNull { it.value }
        fun repsFor(load: Double): Int? = strongest?.let { LoadPrescriber.repsAt(def, it, load, bodyweightKg) }
        // The last training itself showed the value (it rose there): then the value's plan may lead. A value that
        // stands above a step back is not shown by it – the way up is rung by rung.
        val shown = capacity != null && best != null && best.value >= capacity.value * SHOWN_SHARE

        val hitTop = last.all { it.reps!! >= top }
        val strength = LoadSteps.strengthExercise(def)
        var load = lastLoad
        var reps = lastReps
        var move = Move.REPEAT
        when {
            !r.reached -> Unit
            // The value asks for more than the next rung (it rose in the training): the value's plan.
            r.easy && hitTop && shown && steps >= Steps.GENTLE && baseLoad != null && baseLoad > lastLoad + step + 1e-9 -> {
                load = baseLoad; reps = base.reps ?: top; move = Move.UP
            }
            // The top of the range with reserve: the next weight, with the repetitions it leaves.
            r.easy && hitTop && strength && steps == Steps.FULL -> {
                val up = lastLoad + step
                val repsUp = repsFor(up)
                if (repsUp == null || repsUp >= bottom || lastReps >= top + EXTENDED_REPS) {
                    load = up; reps = (repsUp ?: top).coerceIn(bottom, top); move = Move.UP
                } else {
                    // The weight step is too big to take yet: more repetitions at the same weight first.
                    reps = lastReps + 1; move = Move.UP
                }
            }
            // A young or first-year climber follows the value up, one step at a time; no rung of their own.
            r.easy && hitTop && shown && steps == Steps.GENTLE && baseLoad != null && baseLoad > lastLoad + 1e-9 -> {
                load = lastLoad + step; reps = (repsFor(load) ?: top).coerceIn(bottom, top); move = Move.UP
            }
            // Building the repetitions back up after a weight step.
            r.easy && lastReps < top && steps >= Steps.GENTLE -> { reps = lastReps + 1; move = Move.UP }
        }
        // After an easy training two steps, small loads one step at a time ([LoadSteps]) – where the value leaves the
        // repetitions for it; a step too big for the range was answered with repetitions above.
        val stepped = LoadSteps.adjust(def, load, last, incrementKg, steps == Steps.FULL)
        if (stepped != null && abs(stepped - load) > 1e-9) {
            val fits = stepped < load || (repsFor(stepped) ?: top) >= bottom
            if (fits) {
                move = if (stepped > load) Move.UP_FAST else if (stepped > lastLoad) Move.UP else Move.REPEAT
                load = stepped
                reps = (repsFor(stepped) ?: reps).coerceIn(bottom, max(top, reps))
            }
        }
        return Next(reps, null, load, move)
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
        steps: Steps,
    ): Next? {
        if (def.kind != ExerciseKind.HANG) return null
        val last = past.first().filter { it.loadKg != null && it.durationS != null }
        if (last.isEmpty() || !asPlanned(last)) return null
        // A hold is logged with its planned time: only an answered reserve says it went well.
        fun readOf(sets: List<ExerciseSet>) = read(sets.map { it.durationS!! }, sets.map { it.targetDurationS }, sets.map { it.rir },
            unknownIsEasy = false, tolerance = 0.5)
        val r = readOf(last)
        val step = ProgressionAdvisor.increment(def, incrementKg)
        val lastLoad = last.maxOf { it.loadKg!! }
        val baseLoad = base?.loadKg
        val duration = base?.durationS ?: item.durationS ?: def.defaults.durationS

        if (r.tooHard) {
            val lighter = (lastLoad - step).takeIf { def.load == LoadMode.BODYWEIGHT_PLUS || it > 1e-9 }
                ?: return Next(null, duration, lastLoad, Move.DOWN, atBottom = true)
            val before = past.getOrNull(1)?.filter { it.loadKg != null && it.durationS != null }.orEmpty()
            if (before.isNotEmpty() && readOf(before).tooHard) {
                val shown = (last + before).mapNotNull { s -> BenchmarkMath.implied(def, s)?.takeIf { it.kind == CapacityKind.TEN_SECOND_MAX } }
                    .maxByOrNull { it.value }
                if (shown != null && (capacity == null || shown.value < capacity.value)) {
                    val relearnt = LoadPrescriber.prescribe(def, item, shown, bodyweightKg, incrementKg)
                    val load = relearnt?.loadKg
                    if (load != null) return Next(null, relearnt.durationS, min(load, lighter), Move.RELEARN)
                }
            }
            return Next(null, duration, baseLoad?.let { min(it, lighter) } ?: lighter, Move.DOWN)
        }

        // The rungs are for the same job only: a hold of another length plans another load, and there the value decides.
        val lastDuration = last.mapNotNull { it.targetDurationS }.maxOrNull()
        if (duration != null && lastDuration != null && abs(lastDuration - duration) > SAME_HOLD_S) return null
        return when {
            // Every set held with reserve: one smallest step, never two – also on the way back up after a step back.
            r.easy && steps == Steps.FULL -> Next(null, duration, lastLoad + step, Move.UP)
            else -> Next(null, duration, lastLoad, Move.REPEAT)
        }
    }
}
