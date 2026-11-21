package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.logic.NextTargets.Move
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

    /** One training: three work sets with the same target; [done] and [rir] per set. */
    private fun training(d: ExerciseDefinition, workout: String, at: Long, target: Int, done: List<Int>, rir: List<Int?>, load: Double? = null) =
        done.indices.map { i ->
            val timed = d.kind == ExerciseKind.TIME || d.kind == ExerciseKind.HANG
            ExerciseSet("$workout-$i", workout, d.slug, 0, i, SetType.WORK,
                targetReps = target.takeIf { !timed }, reps = done[i].takeIf { !timed },
                targetDurationS = target.toDouble().takeIf { timed }, durationS = done[i].toDouble().takeIf { timed },
                loadKg = load, targetLoadKg = load, rir = rir[i], completedAt = at + i)
        }

    private fun next(d: ExerciseDefinition, history: List<ExerciseSet>, capacity: Capacity? = null, mayStepUp: Boolean = true,
                     item: RoutineItem = item(d), increment: Double = 1.0) =
        NextTargets.plan(d, item, capacity, history, null, 70.0, increment, mayStepUp)

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
        // Something keeps loads careful (youth, first year, a return ramp): one repetition, not two.
        assertEquals(NextTargets.Next(reps = 13, move = Move.UP), next(pushUp, first, maxReps(15), mayStepUp = false))
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
    fun theValueWinsWhenItAsksForMore() {
        val last = training(pushUp, "w1", 1_000, target = 10, done = listOf(10, 10, 10), rir = listOf(2, 2, 2))
        // A test showed 30: 70 % of it is 21.
        assertEquals(NextTargets.Next(reps = 21, move = Move.UP), next(pushUp, last, maxReps(30)))
    }

    @Test
    fun aboveTheRangeOnlyWhatIsClearlyEasyStillGrows() {
        val easy = training(pushUp, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(2, 2, 2))
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT, atTop = true), next(pushUp, easy, maxReps(18)))
        val veryEasy = training(pushUp, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(3, 3, 3))
        assertEquals(NextTargets.Next(reps = 16, move = Move.UP, atTop = true), next(pushUp, veryEasy, maxReps(18)))
        // Reached at the top, but hard: no hint at the next exercise yet.
        val hard = training(pushUp, "w1", 1_000, target = 15, done = listOf(15, 15, 15), rir = listOf(1, 0, 0))
        assertEquals(NextTargets.Next(reps = 15, move = Move.REPEAT), next(pushUp, hard, maxReps(18)))
    }

    @Test
    fun onlyTheLastTrainingCountsAndNeverTheOneBeingPlanned() {
        val old = training(pushUp, "w1", 1_000, target = 8, done = listOf(8, 8, 8), rir = listOf(3, 3, 3))
        val last = training(pushUp, "w2", 5_000, target = 12, done = listOf(12, 12, 12), rir = listOf(1, 1, 1))
        assertEquals(12, next(pushUp, old + last, maxReps(15)).reps)
        // Sets of the training that is being planned (an exercise added twice) are not "last time".
        val now = training(pushUp, "now", 9_000, target = 12, done = listOf(12, 12, 12), rir = listOf(3, 3, 3))
        assertEquals(12, NextTargets.plan(pushUp, item(pushUp), maxReps(15), old + last + now, null, 70.0, 1.0, true, exceptWorkout = "now").reps)
        // Warm-up sets and the other side say nothing.
        val warmup = last.first().copy(id = "wu", setType = SetType.WARMUP, reps = 3, targetReps = 3, completedAt = 6_000)
        assertEquals(12, next(pushUp, last + warmup, maxReps(15)).reps)
        assertEquals(1, NextTargets.sessions(last + last.map { it.copy(id = it.id + "r", side = Side.RIGHT) }, Side.RIGHT).size)
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
    fun aCarefulStateTakesNoWeightStepButKeepsTheValue() {
        val value = oneRepMax(40.0 * (1.0 + 10.0 / 30.0))
        val atTop = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(3, 3, 3), load = 40.0)
        assertEquals(NextTargets.Next(reps = 8, loadKg = 40.0), next(press, atTop, value, mayStepUp = false))
        // Everything "3+" and nothing careful: two steps ([LoadSteps]), with the repetitions the value leaves.
        assertEquals(NextTargets.Next(reps = 6, loadKg = 42.0, move = Move.UP_FAST), next(press, atTop, value))
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
        assertEquals(NextTargets.Next(reps = 12, loadKg = 4.0), next(wrist, more, grown))
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
    fun loadedFingerWorkOnlyEverStepsBack() {
        val value = Capacity(CapacityKind.TEN_SECOND_MAX, 40.0, 70.0)
        val base = LoadPrescriber.prescribe(pickup, item(pickup), value, 70.0, 1.0)!!
        val easy = training(pickup, "w1", 1_000, 10, listOf(10, 10, 10), listOf(3, 3, 3), load = base.loadKg)
        assertEquals(NextTargets.Next(durationS = 10, loadKg = base.loadKg), next(pickup, easy, value))
        val bad = training(pickup, "w2", 2_000, 10, listOf(7, 6, 5), listOf(0, 0, 0), load = base.loadKg)
        val back = next(pickup, bad, value)
        assertEquals(Move.DOWN, back.move)
        assertEquals(base.loadKg!! - 1.0, back.loadKg)
        val again = training(pickup, "w3", 3_000, 10, listOf(7, 6, 5), listOf(0, 0, 0), load = back.loadKg)
        val relearnt = next(pickup, bad + again, value)
        assertEquals(Move.RELEARN, relearnt.move)
        assertTrue(relearnt.loadKg!! < back.loadKg!!, "$relearnt")
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
            capacityFor = { value }, incrementKg = 1.0, stepUp = true, newId = { "id${n++}" })
        assertTrue(loaded.sets.all { it.targetLoadKg == 41.0 && it.loadKg == 41.0 && it.targetReps == 7 && it.reps == 7 }, "${loaded.sets}")
        // Without a value and without a step the target stays open and the last load is the prefill, as before.
        val plain = training(press, "w1", 1_000, 8, listOf(8, 8, 8), listOf(1, 1, 1), load = 20.0)
        val open = WorkoutPlanner.plan("w", 2, item(press), catalog, plain, emptyList(), 70.0, newId = { "id${n++}" })
        assertNull(open.sets.first().targetLoadKg)
        assertEquals(20.0, open.sets.first().loadKg)
    }
}
