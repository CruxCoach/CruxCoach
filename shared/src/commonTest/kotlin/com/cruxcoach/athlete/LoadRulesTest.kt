package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Issue #58: what a weight number refers to. Dumbbells are logged per dumbbell (owner decision); totals –
 * volume, % body weight, the one-rep max estimate – count both of a pair, and planning hands back the
 * weight of one.
 */
class LoadRulesTest {

    private fun def(
        slug: String,
        kind: ExerciseKind = ExerciseKind.LOAD_REPS,
        load: LoadMode = LoadMode.EXTERNAL,
        equipment: List<EquipmentV2> = emptyList(),
        unilateral: Boolean = false,
        implements: Int = 1,
        tags: List<String> = emptyList(),
        defaults: Prescription = Prescription(sets = 3, repsMin = 6, repsMax = 10),
    ) = ExerciseDefinition(slug, ExerciseCategoryV2.PUSH, kind, load, unilateral, equipment, defaults = defaults,
        tags = tags, i18n = mapOf("en" to ExerciseText(slug)), implements = implements)

    private val bench = def("push.dumbbell_bench_press", equipment = listOf(EquipmentV2.DUMBBELL, EquipmentV2.BENCH), implements = 2)
    private val goblet = def("legs.goblet_squat", equipment = listOf(EquipmentV2.DUMBBELL))
    private val row = def("pull.dumbbell_row", equipment = listOf(EquipmentV2.DUMBBELL, EquipmentV2.BENCH), unilateral = true)
    private val squat = def("legs.back_squat", equipment = listOf(EquipmentV2.BARBELL, EquipmentV2.PLATES))
    private val pulldown = def("pull.lat_pulldown", equipment = listOf(EquipmentV2.CABLE))
    private val oneArmPickup = def("finger.one_arm_pickup", ExerciseKind.HANG, equipment = listOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES),
        unilateral = true)
    private val twoArmPickup = def("finger.two_arm_pickup", ExerciseKind.HANG, equipment = listOf(EquipmentV2.PICKUP_BLOCK_PAIR, EquipmentV2.PLATES))
    private val edgePull = def("finger.isometric_edge_pull", ExerciseKind.HANG, equipment = listOf(EquipmentV2.PICKUP_BLOCK),
        unilateral = true, tags = listOf(ExerciseDefinition.TAG_FORCE_GAUGE))
    private val weightedPullUp = def("pull.weighted_pull_up", load = LoadMode.BODYWEIGHT_PLUS, equipment = listOf(EquipmentV2.PULL_UP_BAR))

    private fun set(d: ExerciseDefinition, load: Double, reps: Int, rir: Int? = null, bw: Double = 80.0, side: Side? = null) =
        ExerciseSet("s-${d.slug}-$load-$reps", "w", d.slug, 0, 0, SetType.WORK, side, reps = reps, loadKg = load, rir = rir,
            bodyweightKg = bw, completedAt = 1_000L)

    @Test
    fun everyCaseHasOneBasis() {
        assertEquals(LoadBasis.PER_DUMBBELL, LoadRules.basis(bench))
        assertEquals(LoadBasis.PER_KETTLEBELL, LoadRules.basis(def("x.swing", equipment = listOf(EquipmentV2.KETTLEBELL), implements = 2)))
        assertEquals(LoadBasis.DUMBBELL, LoadRules.basis(goblet))
        assertEquals(LoadBasis.DUMBBELL, LoadRules.basis(row))
        assertEquals(LoadBasis.BARBELL, LoadRules.basis(squat))
        assertEquals(LoadBasis.STACK, LoadRules.basis(pulldown))
        assertEquals(LoadBasis.PICKUP, LoadRules.basis(oneArmPickup))
        assertEquals(LoadBasis.PICKUP_BOTH_HANDS, LoadRules.basis(twoArmPickup))
        assertEquals(LoadBasis.FORCE, LoadRules.basis(edgePull))
        assertEquals(LoadBasis.ADDED, LoadRules.basis(weightedPullUp))
        assertEquals(LoadBasis.NONE, LoadRules.basis(def("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT)))
        assertEquals(LoadBasis.WEIGHT, LoadRules.basis(def("user.sandbag", equipment = listOf(EquipmentV2.PLATES))))
    }

    @Test
    fun onlyFreeWeightsCountAPair() {
        assertEquals(2, LoadRules.implements(bench))
        // A pair flag on a barbell, a block or body-weight work is ignored: those numbers are totals already.
        assertEquals(1, LoadRules.implements(squat.copy(implements = 2)))
        assertEquals(1, LoadRules.implements(twoArmPickup.copy(implements = 2)))
        assertEquals(1, LoadRules.implements(weightedPullUp.copy(implements = 2)))
        assertEquals(1, LoadRules.implements(goblet))
        assertEquals(2, LoadRules.implements(goblet.copy(implements = 5)), "never more than a pair")
    }

    @Test
    fun volumeCountsBothDumbbells() {
        // 10 reps with two 16 kg dumbbells move 320 kg, not 160.
        assertEquals(320.0, ProgressStats.volume(bench, set(bench, 16.0, 10), null), 1e-9)
        assertEquals(160.0, ProgressStats.volume(goblet, set(goblet, 16.0, 10), null), 1e-9)
        // A barbell is already the total.
        assertEquals(600.0, ProgressStats.volume(squat, set(squat, 60.0, 10), null), 1e-9)
    }

    @Test
    fun percentBodyweightCountsBothDumbbells() {
        assertEquals(40.0, StrengthMath.percentBodyweight(bench, 16.0, 80.0)!!, 1e-9)
        assertEquals(20.0, StrengthMath.percentBodyweight(goblet, 16.0, 80.0)!!, 1e-9)
        assertEquals(125.0, StrengthMath.percentBodyweight(weightedPullUp, 20.0, 80.0)!!, 1e-9)
    }

    @Test
    fun oneRepMaxIsTheTotalAndReadsPerDumbbell() {
        val capacity = assertNotNull(BenchmarkMath.implied(bench, set(bench, 20.0, 10)))
        // Epley on both dumbbells: 40 kg × (1 + 10/30).
        assertEquals(40.0 * (1 + 10 / 30.0), capacity.value, 1e-9)
        assertEquals(20.0 * (1 + 10 / 30.0), ProgressStats.displayValue(bench, CapacityKind.E1RM_TOTAL, capacity.value, 80.0), 1e-9)
        val record = assertNotNull(PersonalRecords.score(bench, set(bench, 20.0, 10)))
        assertEquals(RecordKind.ESTIMATED_MAX, record.first)
        assertEquals(capacity.value, record.second, 1e-9)
        // The session's % body weight is the total.
        val progress = ProgressStats.exerciseProgress(bench, listOf(set(bench, 20.0, 10)), TimeZone.UTC, 80.0)
        assertEquals(capacity.value / 80.0 * 100.0, progress.percentBodyweight!!, 1e-9)
        assertEquals(20.0 * (1 + 10 / 30.0), progress.best!!, 1e-9)
    }

    @Test
    fun planningHandsBackTheWeightOfOneDumbbell() {
        // What 2 × 20 kg for 10 reps with two in reserve proves plans 2 × 20 kg for 10 reps again.
        val capacity = assertNotNull(BenchmarkMath.implied(bench, set(bench, 20.0, 10, rir = 2)))
        val target = assertNotNull(LoadPrescriber.prescribe(bench, RoutineItem(bench.slug, repsMax = 10), capacity, 80.0, 1.0))
        assertEquals(20.0, target.loadKg!!, 1e-9)
        assertEquals(10, target.reps)
    }

    @Test
    fun theFirstSessionPlansTheSameWeightPerDumbbellAsBefore() {
        // The start guess was always meant per dumbbell (0.18 × body weight on the bench press); counting both
        // dumbbells in the estimate must not double what gets planned.
        val pair = def("push.dumbbell_bench_press", equipment = listOf(EquipmentV2.DUMBBELL, EquipmentV2.BENCH), implements = 2,
            defaults = Prescription(sets = 3, repsMin = 6, repsMax = 10))
        val one = pair.copy(implements = 1)
        val planned = { d: ExerciseDefinition ->
            LoadPrescriber.prescribe(d, RoutineItem(d.slug), StartEstimate.liftCapacity(d, 80.0), 80.0, 0.5)!!.loadKg!!
        }
        assertEquals(80.0 * 0.18, planned(pair), 0.26)
        assertEquals(planned(one), planned(pair), 1e-9)
    }

    @Test
    fun climbingLoadCountsBothDumbbells() {
        val shoulderPair = bench.copy(domains = listOf(LoadDomain.SHOULDER))
        val shoulderOne = shoulderPair.copy(implements = 1)
        val catalogPair = ExerciseCatalog(1, listOf(shoulderPair))
        val catalogOne = ExerciseCatalog(1, listOf(shoulderOne))
        val s = set(shoulderPair, 20.0, 10)
        val pair = ClimbingLoad.dayUnits(emptyList(), LogbookSummary(), 0, emptyList(), listOf(s), catalogPair)
        val single = ClimbingLoad.dayUnits(emptyList(), LogbookSummary(), 0, emptyList(), listOf(s), catalogOne)
        assertEquals(2 * single.getValue(LoadStructure.SHOULDER), pair.getValue(LoadStructure.SHOULDER), 1e-9)
    }
}
