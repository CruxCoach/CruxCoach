package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comparison review #12: a value is learned from the first sets, never thrown off by one set, and holds count their reserve. */
class ValueUpdatesTest {

    private fun ex(slug: String, kind: ExerciseKind, load: LoadMode) = ExerciseDefinition(slug, ExerciseCategoryV2.PULL, kind, load,
        defaults = Prescription(sets = 3, repsMin = 5, repsMax = 5, durationS = 10, restS = 120), i18n = mapOf("en" to ExerciseText(slug)))

    private val weighted = ex("pull.weighted_pull_up", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS)
    private val hang = ex("finger.max_hang", ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS)
    private val pullUp = ex("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT_PLUS)
    private val plank = ex("core.plank", ExerciseKind.TIME, LoadMode.BODYWEIGHT)

    private fun value(def: ExerciseDefinition, load: Double? = null, reps: Int? = null, seconds: Double? = null,
                      source: BenchmarkSource = BenchmarkSource.MANUAL, at: Long = 1) =
        Benchmark("b$at$source", def.slug, loadKg = load, reps = reps, durationS = seconds, bodyweightKg = 70.0, source = source, measuredAt = at)

    private fun set(id: String, def: ExerciseDefinition, load: Double? = null, reps: Int? = null, seconds: Double? = null, rir: Int? = null,
                    type: SetType = SetType.WORK) =
        ExerciseSet(id, "w", def.slug, 0, id.last().digitToIntOrNull() ?: 0, type, loadKg = load, reps = reps, durationS = seconds, rir = rir,
            bodyweightKg = 70.0, completedAt = 100)

    private fun e1rm(load: Double, reps: Int) = (70.0 + load) * (1 + reps / 30.0)

    // ── Golden: unchanged behaviour ──

    @Test
    fun anOrdinarySetKeepsAValueAndAClearlyBetterOneRaisesItToThatSet() {
        val manual = value(weighted, 20.0, reps = 5)
        // At the plan with two in reserve: 85 kg × 7 → 104.8 kg, under the 105 kg value.
        assertNull(ValueUpdates.decide(weighted, set("s1", weighted, 15.0, 5, rir = 2), manual, manual, emptyList()))
        // Under 2 % better is noise.
        assertNull(ValueUpdates.decide(weighted, set("s1", weighted, 20.0, 5, rir = 0).copy(reps = 5, loadKg = 21.0), manual, manual, emptyList()))
        val better = set("s1", weighted, 25.0, 5, rir = 0)
        val up = assertNotNull(ValueUpdates.decide(weighted, better, manual, manual, emptyList()))
        assertEquals(better, up.source); assertEquals(e1rm(25.0, 5), up.value, 1e-9); assertEquals(false, up.capped)
    }

    @Test
    fun aTestIsTheTruthAndTheFirstSetLearnsTheValue() {
        val manual = value(hang, 30.0, seconds = 10.0)
        val test = set("t1", hang, 20.0, seconds = 10.0, type = SetType.TEST)
        assertEquals(90.0, assertNotNull(ValueUpdates.decide(hang, test, manual, manual, emptyList())).value, 1e-9)
        // Within one test the best set counts.
        assertNull(ValueUpdates.decide(hang, test, manual, manual, listOf(set("t2", hang, 25.0, seconds = 10.0, type = SetType.TEST))))
        val first = assertNotNull(ValueUpdates.decide(pullUp, set("s1", pullUp, 0.0, 8, rir = 1), null, null, emptyList()))
        assertEquals(9.0, first.value, 1e-9)
    }

    // ── New: a single set is capped, a second one confirms ──

    @Test
    fun oneImplausibleSetRaisesAValueByAtMostTenPercent() {
        val manual = value(weighted, 20.0, reps = 5)   // 105 kg
        // "50 reps" typed instead of 5: one set alone moves the value by 10 % at most.
        val typo = set("s1", weighted, 20.0, 50)
        val up = assertNotNull(ValueUpdates.decide(weighted, typo, manual, manual, emptyList()))
        assertEquals(105.0 * ValueUpdates.MAX_SINGLE_SET_RAISE, up.value, 1e-9)
        assertTrue(up.capped)
        // Saved with that set's load and the reps the cap allows: "+20 kg × 8", no more than the cap.
        val saved = ValueUpdates.scaledTo(weighted, BenchmarkMath.benchmarkFromSet(weighted, typo, "n", BenchmarkSource.AUTO, 2), up.value, 70.0)
        assertEquals(20.0, saved.loadKg); assertEquals(8, saved.reps)
        assertTrue(assertNotNull(BenchmarkMath.capacity(weighted, saved, 70.0)).value <= up.value + 1e-9)
        // A load typo ("+200 kg") keeps the reps and comes down in load instead.
        val heavy = ValueUpdates.scaledTo(weighted, BenchmarkMath.benchmarkFromSet(weighted, set("s9", weighted, 200.0, 5), "h", BenchmarkSource.AUTO, 2),
            up.value, 70.0)
        assertEquals(5, heavy.reps); assertEquals(29.0, heavy.loadKg)
        // The next, ordinary set does not add to it.
        val raised = saved.copy(measuredAt = 150)
        assertNull(ValueUpdates.decide(weighted, set("s2", weighted, 20.0, 5), raised, manual, listOf(typo)))
        // The reserve answered after the typo set does not raise it a second time.
        assertNull(ValueUpdates.decide(weighted, typo.copy(rir = 3), raised, manual, emptyList()))
    }

    @Test
    fun aSecondSetOfTheTrainingConfirmsAClearlyStrongerAthlete() {
        val manual = value(weighted, 20.0, reps = 5)   // 105 kg
        val s1 = set("s1", weighted, 35.0, 5)           // 122.5 kg
        val s2 = set("s2", weighted, 34.0, 5)           // 121.3 kg
        val first = assertNotNull(ValueUpdates.decide(weighted, s1, manual, manual, emptyList()))
        assertEquals(115.5, first.value, 1e-9)
        val second = assertNotNull(ValueUpdates.decide(weighted, s2, manual.copy(id = "capped", measuredAt = 150), manual, listOf(s1)))
        assertEquals(e1rm(34.0, 5), second.value, 1e-9)
        assertEquals(s2, second.source); assertEquals(false, second.capped)
    }

    @Test
    fun integerValuesStillMoveByAtLeastOneRepOrTwoSeconds() {
        val reps = value(pullUp, 0.0, reps = 5)
        val up = assertNotNull(ValueUpdates.decide(pullUp, set("s1", pullUp, 0.0, 12), reps, reps, emptyList()))
        assertEquals(6.0, up.value, 1e-9)
        assertEquals(6, ValueUpdates.scaledTo(pullUp, BenchmarkMath.benchmarkFromSet(pullUp, up.source, "n", BenchmarkSource.AUTO, 2), up.value, 70.0).reps)
        val hold = value(plank, seconds = 15.0)
        assertEquals(17.0, assertNotNull(ValueUpdates.decide(plank, set("s1", plank, seconds = 40.0), hold, hold, emptyList())).value, 1e-9)
    }

    // ── New: estimates are corrected down after the first sets ──

    @Test
    fun aSelfEstimateIsReplacedByTheTrainingsBestSetAlsoDownwards() {
        val estimate = value(weighted, 30.0, reps = 5, source = BenchmarkSource.ESTIMATE)   // 116.7 kg guessed
        val s1 = set("s1", weighted, 10.0, 5, rir = 1)   // 80 × 6 = 96 kg
        val down = assertNotNull(ValueUpdates.decide(weighted, s1, estimate, estimate, emptyList()))
        assertEquals(e1rm(10.0, 6), down.value, 1e-9)
        val learned = value(weighted, 10.0, reps = 6, source = BenchmarkSource.AUTO, at = 150)
        // A better second set of the same training wins, uncapped; a weaker one changes nothing.
        val s2 = set("s2", weighted, 20.0, 5, rir = 1)
        assertEquals(e1rm(20.0, 6), assertNotNull(ValueUpdates.decide(weighted, s2, learned, estimate, listOf(s1))).value, 1e-9)
        assertNull(ValueUpdates.decide(weighted, set("s3", weighted, 5.0, 5), learned, estimate, listOf(s1)))
        // A value entered during the training is the athlete's word: no set lowers it.
        val typedNow = value(weighted, 25.0, reps = 5, at = 150)
        assertNull(ValueUpdates.decide(weighted, set("s4", weighted, 10.0, 5), typedNow, estimate, emptyList()))
    }

    // ── New: holds count their reserve ──

    @Test
    fun aHoldsReserveCountsAsSecondsHeld() {
        val held = set("s1", hang, 30.0, seconds = 10.0, rir = 3)
        // 100 kg held 10 s with 3 s to spare: what 13 s at 100 kg stand for.
        assertEquals(100.0 / HoldCurve.relative(13.0), assertNotNull(BenchmarkMath.implied(hang, held)).value, 1e-9)
        val b = BenchmarkMath.benchmarkFromSet(hang, held, "n", BenchmarkSource.AUTO, 2)
        assertEquals(13.0, b.durationS)
        assertEquals(BenchmarkMath.implied(hang, held)!!.value, BenchmarkMath.capacity(hang, b, 70.0)!!.value, 1e-9)
        // Body-weight holds the same; no answer counts as none.
        assertEquals(45.0, BenchmarkMath.implied(plank, set("p", plank, seconds = 40.0, rir = 5))!!.value, 1e-9)
        assertEquals(10.0, BenchmarkMath.benchmarkFromSet(hang, held.copy(rir = null), "n", BenchmarkSource.AUTO, 2).durationS)
    }
}
