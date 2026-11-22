package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.AdjustReason
import com.cruxcoach.athlete.logic.SetAdjustment
import com.cruxcoach.athlete.logic.SetAutoregulation
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetAutoregulationTest {

    private fun def(kind: ExerciseKind, load: LoadMode) = ExerciseDefinition(
        "x", ExerciseCategoryV2.PULL, kind, load, false, listOf(EquipmentV2.NONE), emptyList(),
        defaults = Prescription(sets = 4, repsMin = 5, repsMax = 5, durationS = 10, restS = 120),
        i18n = mapOf("en" to ExerciseText("x")),
    )

    private val weightedPull = def(ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS)
    private val pushUps = def(ExerciseKind.REPS, LoadMode.BODYWEIGHT)
    private val maxHang = def(ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS)
    private val pickup = def(ExerciseKind.HANG, LoadMode.EXTERNAL)
    private val deadHang = def(ExerciseKind.HANG, LoadMode.BODYWEIGHT)

    private fun set(
        reps: Int? = null, targetReps: Int? = null, load: Double? = null, duration: Double? = null, target: Double? = null,
        rir: Int? = null, type: SetType = SetType.WORK, done: Boolean = true,
    ) = ExerciseSet("s", "w", "x", 0, 0, type, targetReps = targetReps, reps = reps, loadKg = load, targetLoadKg = load,
        durationS = duration, targetDurationS = target, rir = rir, bodyweightKg = 70.0, completedAt = if (done) 1L else null)

    @Test
    fun noReserveOnWeightedRepsDropsOneStep() {
        val a = SetAutoregulation.adjust(weightedPull, set(reps = 5, targetReps = 5, load = 20.0, rir = 0), set(targetReps = 5, load = 20.0, done = false), 2.5)
        assertEquals(-2.5, a?.loadDeltaKg)
        assertEquals(AdjustReason.NO_RESERVE, a?.reason)
    }

    @Test
    fun missedRepsWithoutLoadTakeOneRepOff() {
        val a = SetAutoregulation.adjust(pushUps, set(reps = 8, targetReps = 10), set(targetReps = 10, done = false), 1.0)
        assertEquals(-1, a?.repsDelta)
        assertEquals(AdjustReason.MISSED_REPS, a?.reason)
    }

    @Test
    fun bigReserveAddsAStepButRespectsTheCeiling() {
        val next = set(targetReps = 5, load = 20.0, done = false)
        val up = SetAutoregulation.adjust(weightedPull, set(reps = 5, targetReps = 5, load = 20.0, rir = 3), next, 2.5, ceilingKg = 25.0)
        assertEquals(2.5, up?.loadDeltaKg)
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 5, targetReps = 5, load = 20.0, rir = 3), next, 2.5, ceilingKg = 21.0))
    }

    @Test
    fun middleReserveKeepsThePlan() {
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 5, targetReps = 5, load = 20.0, rir = 2), null, 2.5))
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 5, targetReps = 5, load = 20.0), null, 2.5))
    }

    @Test
    fun earlyFailedWeightedHoldDropsAboutFivePercentOfTheTotal() {
        // 70 kg body + 20 kg = 90 kg total → 5 % = 4.5 → snapped to 5.0 with 2.5 steps.
        val a = SetAutoregulation.adjust(maxHang, set(load = 20.0, duration = 7.0, target = 10.0), set(load = 20.0, target = 10.0, done = false), 2.5)
        assertEquals(-5.0, a?.loadDeltaKg)
        assertEquals(AdjustReason.HOLD_FAILED, a?.reason)
    }

    @Test
    fun liftedWeightNeverGoesBelowZero() {
        val a = SetAutoregulation.adjust(pickup, set(load = 1.0, duration = 4.0, target = 10.0), set(load = 1.0, target = 10.0, done = false), 2.5)
        assertEquals(-1.0, a?.loadDeltaKg)
        val applied = SetAutoregulation.apply(pickup, set(load = 1.0, target = 10.0, duration = 10.0, done = false), SetAdjustment(loadDeltaKg = -2.5, reason = AdjustReason.HOLD_FAILED))
        assertEquals(0.0, applied.loadKg)
    }

    @Test
    fun easyHoldAddsAStepAndRirZeroOnAHoldChangesNothing() {
        val next = set(load = 20.0, target = 10.0, done = false)
        assertEquals(2.5, SetAutoregulation.adjust(maxHang, set(load = 20.0, duration = 10.0, target = 10.0, rir = 3), next, 2.5)?.loadDeltaKg)
        assertNull(SetAutoregulation.adjust(maxHang, set(load = 20.0, duration = 10.0, target = 10.0, rir = 0), next, 2.5))
    }

    @Test
    fun bodyweightHoldsMoveTheTime() {
        val a = SetAutoregulation.adjust(deadHang, set(duration = 20.0, target = 30.0), set(target = 30.0, done = false), 1.0)
        assertEquals(-10.0, a?.durationDeltaS)
        val easy = SetAutoregulation.adjust(deadHang, set(duration = 30.0, target = 30.0, rir = 3), set(target = 30.0, done = false), 1.0)
        assertEquals(2.0, easy?.durationDeltaS)
    }

    @Test
    fun warmupsAndOpenSetsNeverAdjust() {
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 3, targetReps = 5, load = 20.0, type = SetType.WARMUP), null, 2.5))
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 3, targetReps = 5, load = 20.0, done = false), null, 2.5))
        assertNull(SetAutoregulation.adjust(weightedPull, set(reps = 3, targetReps = 5, load = 20.0, type = SetType.TEST), null, 2.5))
    }

    @Test
    fun workThatCannotBeAssistedNeverGoesBelowBodyWeight() {
        // Simulation 2026-11-22: walking lunges went to −1, −2 and −3 kg set by set. A band helps a pull-up, not a lunge.
        val lunge = weightedPull.copy(slug = "legs.walking_lunge", category = ExerciseCategoryV2.LEGS)
        val next = set(targetReps = 12, load = 0.0, done = false)
        val atBodyWeight = SetAutoregulation.adjust(lunge, set(reps = 12, targetReps = 12, load = 0.0, rir = 0), next, 1.0)
        assertEquals(0.0, atBodyWeight?.loadDeltaKg)
        assertEquals(-1, atBodyWeight?.repsDelta)
        // What is left above body weight comes off first.
        val light = SetAutoregulation.adjust(lunge, set(reps = 10, targetReps = 12, load = 2.0), set(targetReps = 12, load = 2.0, done = false), 2.5)
        assertEquals(-2.0, light?.loadDeltaKg)
        assertEquals(0, light?.repsDelta)
        // A step that is still there is applied down to body weight, never below.
        val applied = SetAutoregulation.apply(lunge, set(reps = 12, targetReps = 12, load = 1.0, done = false),
            SetAdjustment(loadDeltaKg = -2.5, reason = AdjustReason.MISSED_REPS))
        assertEquals(0.0, applied.loadKg)
        assertEquals(0.0, applied.targetLoadKg)
        // A pull-up at body weight still turns into an assisted one.
        assertEquals(-2.5, SetAutoregulation.adjust(weightedPull, set(reps = 4, targetReps = 5, load = 0.0), set(targetReps = 5, load = 0.0, done = false), 2.5)?.loadDeltaKg)
    }

    @Test
    fun theReserveAnswerAfterAStepDownToBodyWeightChangesNothingMore() {
        // The player asks twice for one set: when it is done, and again when the reserve is answered. The next sets
        // then hold the first step already; read from them, the second answer gave the kilo back and took a repetition.
        val lunge = weightedPull.copy(slug = "legs.walking_lunge", category = ExerciseCategoryV2.LEGS)
        val done = set(reps = 10, targetReps = 12, load = 1.0)
        val planned = set(targetReps = 12, load = 1.0, done = false)
        val first = SetAutoregulation.adjust(lunge, done, planned, 1.0)
        assertEquals(-1.0, first?.loadDeltaKg)
        val afterFirst = SetAutoregulation.apply(lunge, planned, first!!)
        assertEquals(0.0, afterFirst.loadKg)
        val second = SetAutoregulation.adjust(lunge, done.copy(rir = 0), afterFirst, 1.0)
        assertNull(SetAutoregulation.difference(second, first))
    }

    @Test
    fun feelingOutAGuessMovesTenPercentBothWaysAndPastTheGuess() {
        // Owner 2026-10-10: a guessed start load is felt out – 32 kg held easily goes to 35, a hold that ends early to 29.
        val next = set(load = 32.0, target = 10.0, done = false)
        val easy = SetAutoregulation.adjust(pickup, set(load = 32.0, duration = 10.0, target = 10.0, rir = 3), next, 1.0, ceilingKg = 33.0, feelOut = true)
        assertEquals(3.0, easy?.loadDeltaKg)
        val failed = SetAutoregulation.adjust(pickup, set(load = 32.0, duration = 6.0, target = 10.0), next, 1.0, feelOut = true)
        assertEquals(-3.0, failed?.loadDeltaKg)
        // Outside the first session the old small step and the ceiling stay.
        assertNull(SetAutoregulation.adjust(pickup, set(load = 32.0, duration = 10.0, target = 10.0, rir = 3), next, 1.0, ceilingKg = 32.5))
        assertEquals(-2.0, SetAutoregulation.adjust(pickup, set(load = 32.0, duration = 6.0, target = 10.0), next, 1.0)?.loadDeltaKg)
    }

    @Test
    fun differenceAppliesOnlyWhatIsNew() {
        val first = SetAdjustment(loadDeltaKg = -2.5, reason = AdjustReason.MISSED_REPS)
        // Same answer again: nothing more to apply.
        assertNull(SetAutoregulation.difference(first, first))
        // Answer changed from "missed" to "plan kept": undo the earlier step.
        assertEquals(2.5, SetAutoregulation.difference(null, first)?.loadDeltaKg)
        assertEquals(first, SetAutoregulation.difference(first, null))
    }

    @Test
    fun applyMovesTargetAndValueTogether() {
        val planned = set(reps = 5, targetReps = 5, load = 20.0, done = false)
        val moved = SetAutoregulation.apply(weightedPull, planned, SetAdjustment(loadDeltaKg = -2.5, repsDelta = -1, reason = AdjustReason.NO_RESERVE))
        assertEquals(17.5, moved.loadKg); assertEquals(17.5, moved.targetLoadKg)
        assertEquals(4, moved.reps); assertEquals(4, moved.targetReps)
        assertTrue(SetAutoregulation.untouched(moved))
        assertTrue(!SetAutoregulation.untouched(moved.copy(loadKg = 30.0)))
    }
}
