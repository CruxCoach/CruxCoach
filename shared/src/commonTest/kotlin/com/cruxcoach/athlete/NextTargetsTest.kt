package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.logic.NextTargets.Move
import com.cruxcoach.athlete.logic.NextTargets.Steps
import com.cruxcoach.athlete.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-11-21: "offering the fitting progression is the main feature". The plan of the next training follows
 * the last one; the value-based prescription alone let body-weight work stand still and repeated what was too hard.
 */
class NextTargetsTest {

    private fun def(slug: String, kind: ExerciseKind, load: LoadMode, equipment: List<EquipmentV2> = emptyList(),
                    domains: List<LoadDomain> = emptyList(), defaults: Prescription = Prescription(sets = 3, repsMin = 8, repsMax = 15)) =
        ExerciseDefinition(slug, ExerciseCategoryV2.PUSH, kind, load, false, equipment, domains, defaults = defaults,
            i18n = mapOf("en" to ExerciseText(slug)))

    private val pushUp = def("push.push_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT)
    private val plank = def("core.plank", ExerciseKind.TIME, LoadMode.BODYWEIGHT, defaults = Prescription(sets = 3, durationS = 30))
    private val deadHang = def("finger.bodyweight_hang", ExerciseKind.HANG, LoadMode.BODYWEIGHT, listOf(EquipmentV2.HANGBOARD),
        listOf(LoadDomain.FINGER), Prescription(sets = 4, durationS = 10))
    private val press = def("push.press", ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL),
        defaults = Prescription(sets = 3, repsMin = 6, repsMax = 8))
    private val wrist = def("antagonist.wrist", ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL),
        defaults = Prescription(sets = 3, repsMin = 12, repsMax = 15))
    private val pickup = def("finger.pickup", ExerciseKind.HANG, LoadMode.EXTERNAL, listOf(EquipmentV2.PICKUP_BLOCK),
        listOf(LoadDomain.FINGER), Prescription(sets = 5, durationS = 10))

    private fun item(d: ExerciseDefinition) = WorkoutPlanner.itemFor(d)

    /**
     * One training: work sets with the same target; [done] and [rir] per set. [planned]: the load the plan gave the
     * sets (the athlete changed it by hand when it differs from [load]; null = nothing was planned).
     */
    private fun training(d: ExerciseDefinition, workout: String, at: Long, target: Int?, done: List<Int>, rir: List<Int?>,
                         load: Double? = null, planned: Double? = load) =
        done.indices.map { i ->
            val timed = d.kind == ExerciseKind.TIME || d.kind == ExerciseKind.HANG
            ExerciseSet("$workout-$i", workout, d.slug, 0, i, SetType.WORK,
                targetReps = target.takeIf { !timed }, reps = done[i].takeIf { !timed },
                targetDurationS = target?.toDouble().takeIf { timed }, durationS = done[i].toDouble().takeIf { timed },
                loadKg = load, targetLoadKg = planned, bodyweightKg = 70.0, rir = rir[i], completedAt = at + i)
        }

    private fun next(d: ExerciseDefinition, history: List<ExerciseSet>, capacity: Capacity? = null, steps: Steps = Steps.FULL,
                     item: RoutineItem = item(d), increment: Double = 1.0, now: Long? = null) =
        NextTargets.plan(d, item, capacity, history, null, 70.0, increment, steps, now = now)

    private fun maxReps(n: Int) = Capacity(CapacityKind.MAX_REPS, n.toDouble(), 70.0)
    private fun maxSeconds(n: Int) = Capacity(CapacityKind.MAX_SECONDS, n.toDouble(), 70.0)
    private fun oneRepMax(kg: Double) = Capacity(CapacityKind.E1RM_TOTAL, kg, 70.0)

    // ── Body-weight repetitions ──────────────────────────────────────

    @Test
    fun pushUpsDoneWithReserveGoUpInsteadOfBackToSeventyPercent() {
        // 15 push-ups with three in reserve make a maximum of 18, and 70 % of that is 13: the plan went down and stayed.
        val first = training(pushUp, "w1", 1_000, target = 12, done = listOf(12, 12, 12), rir = listOf(3, 3, 3))
        assertEquals(13, LoadPrescriber.prescribe(pushUp, item(pushUp), maxReps(18), 70.0, 1.0)?.reps)
        assertEquals(NextTargets.Next(reps = 14, move = Move.UP_FAST), next(pushUp, first, maxReps(15)))
        // A climber under 18 or in the first year: one repetition, not two.
        assertEquals(NextTargets.Next(reps = 13, move = Move.UP), next(pushUp, first, maxReps(15), steps = Steps.GENTLE))
    }

    @Test
    fun repetitionsFollowTheReserveOfTheLastTraining() {
        fun after(done: List<Int>, rir: List<Int?>) = next(pushUp, training(pushUp, "w1", 1_000, 12, done, rir), maxReps(15))
        assertEquals(NextTargets.Next(reps = 13, move = Move.UP), after(listOf(12, 12, 12), listOf(3, 2, 2)))
        // Reached, but hard: the same again.
        assertEquals(NextTargets.Next(reps = 12, move = Move.REPEAT), after(listOf(12, 12, 12), listOf(2, 1, 0)))
        // Just missed on the last set: the same target again.
        assertEquals(NextTargets.Next(reps = 12, move = Move.REPEAT), after(listOf(12, 12, 11), listOf(2, 1, 1)))
        // Too hard: back to what the middle set managed.
        assertEquals(NextTargets.Next(reps = 9, move = Move.DOWN), after(listOf(12, 9, 7), listOf(1, 0, 0)))
        // No reserve answered: repetitions are measured, reaching the target counts.
        assertEquals(NextTargets.Next(reps = 13, move = Move.UP), after(listOf(12, 12, 12), listOf(null, null, null)))
    }

    @Test
    fun theAthletesNewerWordDecidesAndAnOldTrainingSaysNothing() {
        val last = training(pushUp, "w1", 1_000, target = 10, done = listOf(10, 10, 10), rir = listOf(2, 2, 2))
        // A test after the last training showed 30: 70 % of it is 21. A re-test that showed 8 plans 6 the same way
        // (review 2026-11-21: 3 × 12 before a break and a new maximum of 8 planned 13).
        assertEquals(NextTargets.Next(reps = 21), next(pushUp, last, maxReps(30).copy(statedAt = 5_000)))
        assertEquals(NextTargets.Next(reps = 6), next(pushUp, last, maxReps(8).copy(statedAt = 5_000)))
        // A value from before the training, or one the coach learned, does not: the plan follows the training.
        assertEquals(NextTargets.Next(reps = 11, move = Move.UP), next(pushUp, last, maxReps(30).copy(statedAt = 500)))
        assertEquals(NextTargets.Next(reps = 11, move = Move.UP), next(pushUp, last, maxReps(30)))
        // Six weeks without the exercise: the value alone again.
        val day = 86_400_000L
        assertEquals(NextTargets.Next(reps = 11, move = Move.UP), next(pushUp, last, maxReps(20), now = 1_000 + 42 * day))
        assertEquals(NextTargets.Next(reps = 14), next(pushUp, last, maxReps(20), now = 1_100 + 42 * day))
    }

    @Test
    fun aStepBackStaysUntilTheTrainingWentWellAgain() {
        // Review 2026-11-21: 14/11/9 at the limit planned 11, and after 11/11/11 "hard" the unchanged value planned 14 again.
        val value = maxReps(20)
        val bad = training(pushUp, "w1", 1_000, target = 14, done = listOf(14, 11, 9), rir = listOf(0, 0, 0))
        assertEquals(NextTargets.Next(reps = 11, move = Move.DOWN), next(pushUp, bad, value))
        val hard = training(pushUp, "w2", 2_000, target = 11, done = listOf(11, 11, 11), rir = listOf(1, 1, 1))
        assertEquals(NextTargets.Next(reps = 11, move = Move.REPEAT), next(pushUp, bad + hard, value))
        val well = training(pushUp, "w3", 3_000, target = 11, done = listOf(11, 11, 11), rir = listOf(2, 2, 2))
        assertEquals(NextTargets.Next(reps = 12, move = Move.UP), next(pushUp, bad + hard + well, value))
    }

    @Test
    fun setsLoggedWithoutAPlanAreNoRung() {
        // One all-out set of 20 must not become the target of every set: the value decides (70 % of 20).
        val free = training(pushUp, "w1", 1_000, target = null, done = listOf(20), rir = listOf(null))
        assertEquals(NextTargets.Next(reps = 14), next(pushUp, free, maxReps(20)))
    }

    @Test
    fun stretchesAndWarmUpsAreDoneAsWritten() {
        // Review 2026-11-21: a 60 s stretch was planned at 42 s after its first training, for good.
        val stretch = def("mobility.pigeon", ExerciseKind.TIME, LoadMode.NONE, defaults = Prescription(sets = 2, durationS = 60))
            .copy(category = ExerciseCategoryV2.MOBILITY)
        val last = training(stretch, "w1", 1_000, 60, listOf(60, 60), listOf(null, null))
        assertEquals(NextTargets.Next(), next(stretch, last, maxSeconds(60)))
        assertEquals(NextTargets.Next(), next(stretch, emptyList(), maxSeconds(60)))
        // A mobility hold filed under legs is one too; strength work with a mobility side is not.
        val squatHold = def("legs.deep_squat_hold", ExerciseKind.TIME, LoadMode.NONE, defaults = Prescription(sets = 3, durationS = 45))
            .copy(category = ExerciseCategoryV2.LEGS, tags = listOf("mobility", "warmup"))
        assertEquals(NextTargets.Next(), next(squatHold, training(squatHold, "w1", 1_000, 45, listOf(45, 45, 45), listOf(null, null, null)), maxSeconds(45)))
        val cossack = pushUp.copy(slug = "legs.cossack_squat", category = ExerciseCategoryV2.LEGS, tags = listOf("mobility", "strength"))
        val done = training(cossack, "w1", 1_000, 8, listOf(8, 8, 8), listOf(2, 2, 2))
        assertEquals(NextTargets.Next(reps = 9, move = Move.UP), next(cossack, done, maxReps(12)))
    }

    @Test
    fun nothingStepsUpInAComebackSessionAroundAnInjuryOrInALighterWeek() {
        // Review 2026-11-21 (safety): a wrist comeback hold of 45 s grew to 90 s in six sessions on the injured side.
        val wristHold = def("antagonist.isometric_wrist_extension", ExerciseKind.TIME, LoadMode.NONE, defaults = Prescription(sets = 4, durationS = 45))
        val held = training(wristHold, "w1", 1_000, 45, listOf(45, 45, 45, 45), listOf(null, null, null, null))
        val rehab = item(wristHold)
        // A comeback session: the value-based plan as before this rule, never the step.
        assertEquals(NextTargets.Next(durationS = 35), next(wristHold, held, maxSeconds(50), steps = Steps.OFF, item = rehab))
        // An open injury, the return ramp, a lighter week: the same again.
        assertEquals(NextTargets.Next(durationS = 45, move = Move.REPEAT), next(wristHold, held, maxSeconds(50), steps = Steps.CAREFUL))
        assertEquals(NextTargets.Next(durationS = 50, move = Move.UP), next(wristHold, held, maxSeconds(50), steps = Steps.GENTLE))
        // Repetitions and weights alike.
        val pushed = training(pushUp, "w1", 1_000, target = 12, done = listOf(12, 12, 12), rir = listOf(3, 3, 3))
        assertEquals(NextTargets.Next(reps = 12, move = Move.REPEAT), next(pushUp, pushed, maxReps(20), steps = Steps.CAREFUL))
        assertEquals(NextTargets.Next(reps = 14), next(pushUp, pushed, maxReps(20), steps = Steps.OFF))
        // Too hard still steps back.
        val bad = training(pushUp, "w2", 2_000, target = 12, done = listOf(12, 9, 7), rir = listOf(1, 0, 0))
        assertEquals(NextTargets.Next(reps = 9, move = Move.DOWN), next(pushUp, pushed + bad, maxReps(20), steps = Steps.CAREFUL))
        // A finger hang steps for adults without anything careful only: not for a climber under 18 or in the first year.
        val hang = training(deadHang, "w1", 1_000, 10, listOf(10, 10, 10), listOf(3, 3, 2))
        assertEquals(NextTargets.Next(durationS = 10, move = Move.REPEAT), next(deadHang, hang, maxSeconds(14), steps = Steps.GENTLE))
        assertEquals(NextTargets.Next(durationS = 10, move = Move.REPEAT), next(deadHang, hang, maxSeconds(14), steps = Steps.CAREFUL))
    }

    @Test
    fun aboveTheRangeOnlyWhatIsClearlyEasyStillGrows() {
        // The chain has a next exercise: that is the better step, the repetitions only grow when it is clearly easy.
        val chained = pushUp.copy(harder = "push.diamond_push_up")
        val easy = training(chained, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(2, 2, 2))
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT, atTop = true), next(chained, easy, maxReps(18)))
        val veryEasy = training(chained, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(3, 3, 3))
        assertEquals(NextTargets.Next(reps = 16, move = Move.UP, atTop = true), next(chained, veryEasy, maxReps(18)))
        // Reached at the top, but hard: no hint at the next exercise yet.
        val hard = training(chained, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(1, 0, 0))
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT), next(chained, hard, maxReps(18)))
    }

    @Test
    fun withoutANextExerciseRepetitionsGoOnAboveTheRange() {
        // The end of a chain (band work, a plain push-up here): repetitions are the lever that is left. With reserve
        // one more, far above the range too – no cap, each step needs the athlete's answer (simulation 2026-11-21:
        // a cap ten above the range left band work too easy for good).
        val easy = training(pushUp, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(2, 2, 2))
        assertEquals(NextTargets.Next(reps = 16, move = Move.UP, atTop = true), next(pushUp, easy, maxReps(18)))
        val far = training(pushUp, "w1", 1_000, target = 30, done = listOf(30, 30, 30), rir = listOf(3, 3, 3))
        assertEquals(NextTargets.Next(reps = 32, move = Move.UP, atTop = true), next(pushUp, far, maxReps(40)))
        val hard = training(pushUp, "w1", 1_000, target = 30, done = listOf(30, 30, 30), rir = listOf(1, 1, 1))
        assertEquals(NextTargets.Next(reps = 30, move = Move.REPEAT), next(pushUp, hard, maxReps(40)))
    }

    @Test
    fun aNextExerciseOutOfReachLetsRepetitionsGoOnAboveTheRange() {
        // Simulation 2026-11-22: a single-leg glute bridge at home, its next exercise needs a barbell and a bench. The
        // training raises the later sets itself after "3+", so not every set is answered "3+" – and the plan stood at
        // 17 for four weeks. With no exercise to step on to, reserve is enough, as at the end of a chain.
        val chained = pushUp.copy(harder = "push.diamond_push_up")
        val easy = training(chained, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(3, 3, 2))
        fun plan(stepOn: Boolean?) = NextTargets.plan(chained, item(chained), maxReps(18), easy, null, 70.0, 1.0, Steps.FULL, stepOn = stepOn)
        assertEquals(NextTargets.Next(reps = 16, move = Move.UP, atTop = true), plan(stepOn = false))
        // Within reach it is the better step: the repetitions wait, the next exercise is on offer.
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT, atTop = true), plan(stepOn = true))
        // Not said: whether the chain has a next exercise at all.
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT, atTop = true), plan(stepOn = null))
        // Hard stays hard, in reach or not.
        val hard = training(chained, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(1, 0, 0))
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT),
            NextTargets.plan(chained, item(chained), maxReps(18), hard, null, 70.0, 1.0, Steps.FULL, stepOn = false))
    }

    @Test
    fun underTheRangeWithNothingLighterToPlanIsTimeForTheEasierExercise() {
        // Four push-ups where eight are the least: the plan cannot get lighter, the easier exercise is the step.
        val bad = training(pushUp, "w1", 1_000, target = 8, done = listOf(5, 4, 3), rir = listOf(0, 0, 0))
        assertEquals(NextTargets.Next(reps = 4, move = Move.DOWN, atBottom = true), next(pushUp, bad, maxReps(10)))
        // Inside the range a step back is just a step back.
        val inRange = training(pushUp, "w1", 1_000, target = 14, done = listOf(14, 11, 9), rir = listOf(0, 0, 0))
        assertEquals(false, next(pushUp, inRange, maxReps(20)).atBottom)
        // Body weight is the least a sit-up weighs: three repetitions there, where eight are the least.
        val sitUp = def("core.weighted_sit_up", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS, defaults = Prescription(sets = 3, repsMin = 8, repsMax = 12))
            .copy(category = ExerciseCategoryV2.CORE)
        val weak = Capacity(CapacityKind.E1RM_TOTAL, 70.0 * (1.0 + 5.0 / 30.0), 70.0)
        assertEquals(NextTargets.Next(reps = 3, loadKg = 0.0, atBottom = true), next(sitUp, emptyList(), weak))
        // Pulling can be made lighter (a band, a pulley): no such signal.
        val pull = def("pull.weighted", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS, defaults = Prescription(sets = 3, repsMin = 5, repsMax = 8))
            .copy(category = ExerciseCategoryV2.PULL)
        assertEquals(false, next(pull, emptyList(), Capacity(CapacityKind.E1RM_TOTAL, 70.0, 70.0)).atBottom)
        // A hold that falls under half its time.
        val short = training(plank, "w1", 1_000, 30, listOf(12, 10, 8), listOf(0, 0, 0))
        assertEquals(NextTargets.Next(durationS = 10, move = Move.DOWN, atBottom = true), next(plank, short, maxSeconds(30)))
    }

    @Test
    fun onlyTheLastTrainingCountsAndNeverTheOneBeingPlanned() {
        val old = training(pushUp, "w1", 1_000, target = 8, done = listOf(8, 8, 8), rir = listOf(3, 3, 3))
        val last = training(pushUp, "w2", 5_000, target = 12, done = listOf(12, 12, 12), rir = listOf(1, 1, 1))
        assertEquals(12, next(pushUp, old + last, maxReps(15)).reps)
        // Sets of the training that is being planned (an exercise added twice) are not "last time".
        val now = training(pushUp, "now", 9_000, target = 12, done = listOf(12, 12, 12), rir = listOf(3, 3, 3))
        assertEquals(12, NextTargets.plan(pushUp, item(pushUp), maxReps(15), old + last + now, null, 70.0, 1.0, Steps.FULL, exceptWorkout = "now").reps)
        // Warm-up sets and the other side say nothing.
        val warmup = last.first().copy(id = "wu", setType = SetType.WARMUP, reps = 3, targetReps = 3, completedAt = 6_000)
        assertEquals(12, next(pushUp, last + warmup, maxReps(15)).reps)
        val right = training(pushUp, "w3", 7_000, target = 9, done = listOf(9, 9, 9), rir = listOf(2, 2, 2)).map { it.copy(side = Side.RIGHT) }
        assertEquals(listOf("w3"), NextTargets.sessions(last + right, Side.RIGHT).map { it.first().workoutId })
        assertEquals(listOf("w2"), NextTargets.sessions(last + right, null).map { it.first().workoutId })
    }

    @Test
    fun withoutAnEarlierTrainingThePrescriptionStands() {
        assertEquals(NextTargets.Next(reps = 13), next(pushUp, emptyList(), maxReps(18)))
        assertEquals(NextTargets.Next(), next(pushUp, emptyList()))
        // Warm-up and test items are never stepped.
        val last = training(pushUp, "w1", 1_000, target = 12, done = listOf(12, 12, 12), rir = listOf(3, 3, 3))
        assertEquals(Move.NONE, next(pushUp, last, maxReps(18), item = item(pushUp).copy(warmup = true)).move)
        assertEquals(Move.NONE, next(pushUp, last, maxReps(18), item = item(pushUp).copy(test = true)).move)
    }

    // ── Timed holds ──────────────────────────────────────────────────

    @Test
    fun aPlankGrowsInTidyStepsUpToItsTop() {
        // 30 s made a maximum of 30 and a plan of 21 s, for good. A plain hold is logged from its timer, without a reserve.
        fun after(target: Int, done: List<Int>) = next(plank, training(plank, "w1", 1_000, target, done, listOf(null, null, null)), maxSeconds(30))
        assertEquals(NextTargets.Next(durationS = 35, move = Move.UP), after(30, listOf(30, 30, 30)))
        assertEquals(NextTargets.Next(durationS = 25, move = Move.UP), after(21, listOf(21, 21, 21)))
        // Let go early on the last set: the same again. Half of the sets far short: back.
        assertEquals(NextTargets.Next(durationS = 30, move = Move.REPEAT), after(30, listOf(30, 30, 26)))
        assertEquals(NextTargets.Next(durationS = 20, move = Move.DOWN), after(30, listOf(30, 20, 15)))
        // Twice the usual time is long enough: the next exercise of the chain is the better step.
        assertEquals(60, NextTargets.holdTop(plank, item(plank)))
        assertEquals(NextTargets.Next(durationS = 60, move = Move.UP), after(55, listOf(55, 55, 55)))
        assertEquals(NextTargets.Next(durationS = 60, move = Move.REPEAT, atTop = true), after(60, listOf(60, 60, 60)))
        // The reserve of a hold says "hard" too.
        val hard = training(plank, "w1", 1_000, 30, listOf(30, 30, 30), listOf(1, 0, 0))
        assertEquals(NextTargets.Next(durationS = 30, move = Move.REPEAT), next(plank, hard, maxSeconds(30)))
    }

    @Test
    fun aShortStrengthHoldNeedsItsReserveAndHasALowTop() {
        // Review 2026-11-21: a 5 s front lever grew to 30 s before the next exercise was offered.
        val lever = def("pull.front_lever", ExerciseKind.TIME, LoadMode.BODYWEIGHT, defaults = Prescription(sets = 4, durationS = 5))
        assertEquals(10, NextTargets.holdTop(lever, item(lever)))
        val silent = training(lever, "w1", 1_000, 5, listOf(5, 5, 5, 5), listOf(null, null, null, null))
        assertEquals(NextTargets.Next(durationS = 5, move = Move.REPEAT), next(lever, silent, maxSeconds(7)))
        val easy = training(lever, "w1", 1_000, 5, listOf(5, 5, 5, 5), listOf(2, 2, 2, 2))
        assertEquals(NextTargets.Next(durationS = 7, move = Move.UP), next(lever, easy, maxSeconds(7)))
        val top = training(lever, "w1", 1_000, 10, listOf(10, 10, 10, 10), listOf(2, 2, 2, 2))
        assertEquals(NextTargets.Next(durationS = 10, move = Move.REPEAT, atTop = true), next(lever, top, maxSeconds(12)))
    }

    @Test
    fun aHangWithoutWeightNeedsAnAnsweredReserve() {
        // Finger work: a logged hang alone is no reason for more.
        val silent = training(deadHang, "w1", 1_000, 10, listOf(10, 10, 10), listOf(null, null, null))
        assertEquals(NextTargets.Next(durationS = 10, move = Move.REPEAT), next(deadHang, silent, maxSeconds(14)))
        val easy = training(deadHang, "w1", 1_000, 10, listOf(10, 10, 10), listOf(3, 3, 2))
        assertEquals(NextTargets.Next(durationS = 12, move = Move.UP), next(deadHang, easy, maxSeconds(14)))
        assertEquals(20, NextTargets.holdTop(deadHang, item(deadHang)))
    }

    // ── Loaded repetitions ───────────────────────────────────────────

    @Test
    fun theTopOfALoadedRangeWithReserveTakesTheNextWeight() {
        // A one-rep max of 53.3 kg plans 40 kg × 8 with two in reserve – and a training done exactly like that proves
        // nothing more, so the plan stood until the athlete answered "3+".
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        assertEquals(LoadTarget(40.0, 8, null, value), LoadPrescriber.prescribe(press, item(press), value, 70.0, 1.0))
        val atTop = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(2, 2, 2), load = 40.0)
        assertEquals(NextTargets.Next(reps = 7, loadKg = 41.0, move = Move.UP), next(press, atTop, value))
        // The repetitions come back to the top of the range at the new weight …
        val step = training(press, "w2", 2_000, 7, listOf(7, 7, 7), listOf(2, 2, 2), load = 41.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 41.0, move = Move.UP), next(press, atTop + step, value))
        // … and wait there while it is hard, instead of falling back to the value's 40 kg.
        val hard = training(press, "w3", 3_000, 8, listOf(8, 8, 8), listOf(1, 1, 0), load = 41.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 41.0, move = Move.REPEAT), next(press, atTop + step + hard, value))
        val missed = training(press, "w3", 3_000, 8, listOf(8, 8, 7), listOf(1, 0, 1), load = 41.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 41.0, move = Move.REPEAT), next(press, atTop + step + missed, value))
    }

    @Test
    fun aHeavyLoadWithFineStepsClimbsWithoutFallingBack() {
        // +60 kg on 70 kg body weight, 3–5 repetitions, 1 kg steps: a step is 1.2 % of the total, less than what
        // raises the value, so two steps run ahead of it. The plan must not fall back to the value's load in between.
        val weighted = def("pull.weighted_pull_up", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS, listOf(EquipmentV2.PULL_UP_BAR),
            defaults = Prescription(sets = 3, repsMin = 3, repsMax = 5))
        val value = oneRepMax(130.0 * (1.0 + 7.0 / 30.0))
        assertEquals(LoadTarget(60.0, 5, null, value), LoadPrescriber.prescribe(weighted, item(weighted), value, 70.0, 1.0))
        val s1 = training(weighted, "w1", 1_000, 5, listOf(5, 5, 5), listOf(2, 2, 2), load = 60.0)
        assertEquals(NextTargets.Next(reps = 5, loadKg = 61.0, move = Move.UP), next(weighted, s1, value))
        // Five with two in reserve at +61 kg shows a little more than the value: enough for five at +62 kg.
        val s2 = training(weighted, "w2", 2_000, 5, listOf(5, 5, 5), listOf(2, 2, 2), load = 61.0)
        assertEquals(NextTargets.Next(reps = 5, loadKg = 62.0, move = Move.UP), next(weighted, s1 + s2, value))
        // Hard at two steps above the value's load: it stays there, it does not drop to +60 kg.
        val hard = training(weighted, "w4", 4_000, 5, listOf(5, 5, 5), listOf(1, 0, 0), load = 62.0)
        assertEquals(NextTargets.Next(reps = 5, loadKg = 62.0, move = Move.REPEAT), next(weighted, s1 + s2 + hard, value))
        // A load the athlete changed by hand is no rung: they tried +80 kg once, the value decides.
        val far = training(weighted, "w5", 5_000, 5, listOf(5, 5, 5), listOf(1, 1, 1), load = 80.0, planned = 60.0)
        assertEquals(NextTargets.Next(reps = 5, loadKg = 60.0), next(weighted, far, value))
        // Another workout with another range (8–10) is another job: its loads come from the value.
        val volume = item(weighted).copy(repsMin = 8, repsMax = 10)
        assertEquals(Move.NONE, next(weighted, s1 + s2, value, item = volume).move)
    }

    @Test
    fun aCarefulStateTakesNoWeightStepButKeepsTheValue() {
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        val atTop = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(3, 3, 3), load = 40.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 40.0, move = Move.REPEAT), next(press, atTop, value, steps = Steps.GENTLE))
        assertEquals(NextTargets.Next(reps = 8, loadKg = 40.0, move = Move.REPEAT), next(press, atTop, value, steps = Steps.CAREFUL))
        assertEquals(NextTargets.Next(reps = 8, loadKg = 40.0), next(press, atTop, value, steps = Steps.OFF))
        // Everything "3+" and nothing careful: two steps ([LoadSteps]), with the repetitions the training showed for them.
        assertEquals(NextTargets.Next(reps = 7, loadKg = 42.0, move = Move.UP_FAST), next(press, atTop, value))
    }

    @Test
    fun tooHardStepsBackAndTwiceInARowReadsThePlanFromWhatWasShown() {
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        val bad = training(press, "w2", 2_000, 8, listOf(6, 5, 4), listOf(0, 0, 0), load = 40.0)
        // A trusted value only rises ([ValueUpdates]), so this was planned at 40 kg again.
        assertEquals(NextTargets.Next(reps = 8, loadKg = 39.0, move = Move.DOWN), next(press, bad, value))
        // Again too hard, at 39 kg: the best set of both (6 at 40 kg) stands for the value now: 36 kg × 8.
        val worse = training(press, "w3", 3_000, 8, listOf(6, 4, 4), listOf(0, 0, 0), load = 39.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 36.0, move = Move.RELEARN), next(press, bad + worse, value))
        // One bad training between good ones is one step back, no more.
        val fine = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(1, 1, 1), load = 40.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 39.0, move = Move.DOWN), next(press, fine + bad, value))
        // The step back stays while it is hard, and the way up is rung by rung – not back to the value's 40 kg at once
        // (review 2026-11-21: 39 → 40 → 39, and 36 → 40 after one good training).
        val stillHard = training(press, "w3", 3_000, 8, listOf(8, 8, 7), listOf(1, 1, 0), load = 39.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 39.0, move = Move.REPEAT), next(press, bad + stillHard, value))
        val well = training(press, "w4", 4_000, 8, listOf(8, 8, 8), listOf(2, 2, 2), load = 39.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 40.0, move = Move.UP), next(press, bad + stillHard + well, value))
        val afterRelearn = training(press, "w4", 4_000, 8, listOf(8, 8, 8), listOf(2, 2, 2), load = 36.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 37.0, move = Move.UP), next(press, bad + worse + afterRelearn, value))
    }

    @Test
    fun aWeightStepTooBigToTakeAddsRepetitionsFirst() {
        // 3 kg × 15 with two in reserve: 4 kg would be a third more, the value allows 9 repetitions there (range 12–15).
        val value = oneRepMax(3.0 * (1.0 + 17.0 / 30.0))
        assertEquals(LoadTarget(3.0, 15, null, value), LoadPrescriber.prescribe(wrist, item(wrist), value, 70.0, 1.0))
        val atTop = training(wrist, "w1", 1_000, 15, listOf(15, 15, 15), listOf(2, 2, 2), load = 3.0)
        assertEquals(NextTargets.Next(reps = 16, loadKg = 3.0, move = Move.UP), next(wrist, atTop, value))
        // The value has grown with the repetitions: 4 kg × 12 is in the range now.
        val grown = oneRepMax(4.0 * (1.0 + 14.0 / 30.0))
        val more = training(wrist, "w2", 2_000, 22, listOf(22, 22, 22), listOf(2, 2, 2), load = 3.0)
        assertEquals(NextTargets.Next(reps = 12, loadKg = 4.0, move = Move.UP), next(wrist, more, grown))
    }

    @Test
    fun thePrescriptionAnswersACoarseStepWithRepetitions() {
        // Ideal 3.9 kg rounds to 4 kg: the value allows 14 there, not the 15 of the top.
        val near = oneRepMax(3.9 * (1.0 + 17.0 / 30.0))
        assertEquals(LoadTarget(4.0, 14, null, near), LoadPrescriber.prescribe(wrist, item(wrist), near, 70.0, 1.0))
        // Ideal 3.6 kg rounds to 4 kg too, but only 10 repetitions fit: the lighter step with the top of the range.
        val far = oneRepMax(3.6 * (1.0 + 17.0 / 30.0))
        assertEquals(LoadTarget(3.0, 15, null, far), LoadPrescriber.prescribe(wrist, item(wrist), far, 70.0, 1.0))
        // A fine step changes nothing: 40.4 kg plans 40 kg × 8 as before.
        val fine = oneRepMax(40.4 * (1.0 + 10.0 / 30.0))
        assertEquals(LoadTarget(40.0, 8, null, fine), LoadPrescriber.prescribe(press, item(press), fine, 70.0, 1.0))
    }

    @Test
    fun legWorkIsNeverPlannedAssisted() {
        // A one-rep max of 88 kg at 63 kg body weight planned walking lunges at "-12 kg × 20".
        val lunge = def("legs.walking_lunge", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
            defaults = Prescription(sets = 3, repsMin = 12, repsMax = 20)).copy(category = ExerciseCategoryV2.LEGS)
        val value = Capacity(CapacityKind.E1RM_TOTAL, 88.2, 63.0)
        assertEquals(LoadTarget(0.0, 10, null, value), LoadPrescriber.prescribe(lunge, item(lunge), value, 63.0, 1.0))
        // Pulling can be assisted: a weak pull-up still gets its band.
        val pull = def("pull.weighted", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
            defaults = Prescription(sets = 3, repsMin = 5, repsMax = 8)).copy(category = ExerciseCategoryV2.PULL)
        assertTrue(LoadPrescriber.prescribe(pull, item(pull), Capacity(CapacityKind.E1RM_TOTAL, 70.0, 63.0), 63.0, 1.0)!!.loadKg!! < 0)
        // Fixed repetitions and a coarse step (review 2026-11-21: "-2.5 kg × 20"): body weight, with the 19 the value allows.
        val fixed = item(lunge).copy(repsMin = 20, repsMax = 20)
        val close = Capacity(CapacityKind.E1RM_TOTAL, 107.64, 63.0)
        assertEquals(LoadTarget(0.0, 19, null, close), LoadPrescriber.prescribe(lunge, fixed, close, 63.0, 2.5))
    }

    @Test
    fun aLoadTheAthleteWroteIntoTheWorkoutIsNeverMoved() {
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        val bad = training(press, "w2", 2_000, 8, listOf(6, 5, 4), listOf(0, 0, 0), load = 45.0)
        val own = item(press).copy(loadKg = 45.0)
        assertEquals(Move.NONE, next(press, bad, value, item = own).move)
    }

    // ── Finger work ──────────────────────────────────────────────────

    @Test
    fun loadedFingerWorkTakesOneCarefulStepAndStepsBack() {
        // A pick-up planned at 90 % of the value proves less than the value however long the reserve: the plan stood
        // at the same load for weeks while the summary's tip said "+1 kg".
        val value = Capacity(CapacityKind.TEN_SECOND_MAX, 40.0, 70.0)
        val base = LoadPrescriber.prescribe(pickup, item(pickup), value, 70.0, 1.0)!!
        val easy = training(pickup, "w1", 1_000, 10, listOf(10, 10, 10), listOf(3, 3, 3), load = base.loadKg)
        // One smallest step, never two – and none while something keeps loads careful or nothing was answered.
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg!! + 1.0, move = Move.UP), next(pickup, easy, value))
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg, move = Move.REPEAT), next(pickup, easy, value, steps = Steps.GENTLE))
        val silent = training(pickup, "w1", 1_000, 10, listOf(10, 10, 10), listOf(null, null, null), load = base.loadKg)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg, move = Move.REPEAT), next(pickup, silent, value))
        // The step stays while it is hard, and goes on when it went well again.
        val stepped = training(pickup, "w2", 2_000, 10, listOf(10, 10, 10), listOf(1, 1, 0), load = base.loadKg!! + 1.0)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg!! + 1.0, move = Move.REPEAT), next(pickup, easy + stepped, value))
        val solid = training(pickup, "w2", 2_000, 10, listOf(10, 10, 10), listOf(2, 2, 2), load = base.loadKg!! + 1.0)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg!! + 2.0, move = Move.UP), next(pickup, easy + solid, value))
        val bad = training(pickup, "w2", 2_000, 10, listOf(7, 6, 5), listOf(0, 0, 0), load = base.loadKg)
        val back = next(pickup, bad, value)
        assertEquals(Move.DOWN, back.move)
        assertEquals(base.loadKg!! - 1.0, back.loadKg)
        val again = training(pickup, "w3", 3_000, 10, listOf(7, 6, 5), listOf(0, 0, 0), load = back.loadKg)
        val relearnt = next(pickup, bad + again, value)
        assertEquals(Move.RELEARN, relearnt.move)
        assertTrue(relearnt.loadKg!! < back.loadKg!!, "$relearnt")
        // The way back up is one step at a time, also far under the value's plan.
        val recovered = training(pickup, "w4", 4_000, 10, listOf(10, 10, 10), listOf(2, 2, 2), load = relearnt.loadKg)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = relearnt.loadKg!! + 1.0, move = Move.UP), next(pickup, bad + again + recovered, value))
        // The step back stays while it is hard.
        val hardBelow = training(pickup, "w3", 3_000, 10, listOf(10, 10, 10), listOf(1, 1, 0), load = back.loadKg)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = back.loadKg, move = Move.REPEAT), next(pickup, bad + hardBelow, value))
    }

    @Test
    fun aMaxHangJustShortOfItsTimeIsNotTooHard() {
        // Review 2026-11-21: 10/10/10/10/9.4 s at the limit – the intended effort – planned a step back.
        val value = Capacity(CapacityKind.TEN_SECOND_MAX, 40.0, 70.0)
        val base = LoadPrescriber.prescribe(pickup, item(pickup), value, 70.0, 1.0)!!
        val limit = training(pickup, "w1", 1_000, 10, listOf(10, 10, 10, 10, 10), listOf(0, 0, 0, 0, 0), load = base.loadKg)
            .mapIndexed { i, s -> if (i == 4) s.copy(durationS = 9.4) else s }
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg, move = Move.REPEAT), next(pickup, limit, value))
        // Clearly short with nothing in reserve is.
        val short = limit.mapIndexed { i, s -> if (i == 4) s.copy(durationS = 8.0) else s }
        assertEquals(Move.DOWN, next(pickup, short, value).move)
    }

    @Test
    fun anotherEdgeIsAnotherJob() {
        val value = Capacity(CapacityKind.TEN_SECOND_MAX, 40.0, 70.0)
        val base = LoadPrescriber.prescribe(pickup, item(pickup), value, 70.0, 1.0)!!
        val on15 = training(pickup, "w1", 1_000, 10, listOf(10, 10, 10), listOf(3, 3, 3), load = base.loadKg).map { it.copy(edgeMm = 15.0) }
        // The training on the 15 mm edge says nothing about the 20 mm plan; on the same edge it is the rung.
        assertEquals(Move.NONE, next(pickup, on15, value, item = item(pickup).copy(edgeMm = 20)).move)
        assertEquals(Move.UP, next(pickup, on15, value, item = item(pickup).copy(edgeMm = 15)).move)
    }

    // ── In the planner ───────────────────────────────────────────────

    @Test
    fun thePlannerWritesTheStepIntoTargetAndPrefill() {
        val catalog = ExerciseCatalog(1, listOf(pushUp, press))
        val last = training(pushUp, "w1", 1_000, target = 12, done = listOf(12, 12, 12), rir = listOf(2, 2, 2))
        var n = 0
        val block = WorkoutPlanner.plan("w", 0, item(pushUp), catalog, last, emptyList(), 70.0,
            capacityFor = { maxReps(15) }, incrementKg = 1.0, newId = { "id${n++}" })
        assertTrue(block.sets.all { it.targetReps == 13 && it.reps == 13 }, "${block.sets}")
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        val atTop = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(2, 2, 2), load = 40.0)
        val loaded = WorkoutPlanner.plan("w", 1, item(press), catalog, atTop, emptyList(), 70.0,
            capacityFor = { value }, incrementKg = 1.0, steps = Steps.FULL, newId = { "id${n++}" })
        assertTrue(loaded.sets.all { it.targetLoadKg == 41.0 && it.loadKg == 41.0 && it.targetReps == 7 && it.reps == 7 }, "${loaded.sets}")
        // Without a value and without a step the target stays open and the last load is the prefill, as before.
        val plain = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(1, 1, 1), load = 20.0, planned = null)
        val open = WorkoutPlanner.plan("w", 2, item(press), catalog, plain, emptyList(), 70.0, newId = { "id${n++}" })
        assertNull(open.sets.first().targetLoadKg)
        assertEquals(20.0, open.sets.first().loadKg)
    }
}
