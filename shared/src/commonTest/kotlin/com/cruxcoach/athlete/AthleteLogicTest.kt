package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AthleteLogicTest {

    private fun def(
        slug: String,
        kind: ExerciseKind,
        load: LoadMode,
        unilateral: Boolean = false,
        domains: List<LoadDomain> = emptyList(),
        contraindications: List<BodyRegion> = emptyList(),
        equipment: List<EquipmentV2> = emptyList(),
        defaults: Prescription = Prescription(),
        easier: String? = null,
        harder: String? = null,
        tags: List<String> = emptyList(),
    ) = ExerciseDefinition(slug, ExerciseCategoryV2.FINGER, kind, load, unilateral, equipment, domains,
        difficulty = 2, easier = easier, harder = harder, defaults = defaults, contraindications = contraindications,
        tags = tags, i18n = mapOf("en" to ExerciseText(slug), "de" to ExerciseText("$slug-de")))

    private val pickup = def("finger.one_arm_pickup", ExerciseKind.HANG, LoadMode.EXTERNAL, unilateral = true,
        domains = listOf(LoadDomain.FINGER, LoadDomain.WRIST), contraindications = listOf(BodyRegion.FINGER, BodyRegion.PULLEY),
        equipment = listOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES), defaults = Prescription(sets = 3, durationS = 10, restS = 120, edgeMm = 20))
    private val pullUp = def("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT,
        domains = listOf(LoadDomain.ELBOW, LoadDomain.SHOULDER), equipment = listOf(EquipmentV2.PULL_UP_BAR),
        defaults = Prescription(sets = 3, repsMin = 4, repsMax = 8), harder = "pull.weighted_pull_up", tags = listOf("finger_free"))
    private val maxHang = def("finger.max_hang", ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
        domains = listOf(LoadDomain.FINGER), contraindications = listOf(BodyRegion.FINGER, BodyRegion.PULLEY),
        equipment = listOf(EquipmentV2.HANGBOARD), defaults = Prescription(sets = 5, durationS = 10, edgeMm = 20))
    private val limitBoulder = def("power.limit_bouldering", ExerciseKind.CLIMB, LoadMode.NONE,
        domains = listOf(LoadDomain.FINGER, LoadDomain.SKIN), equipment = listOf(EquipmentV2.WALL))

    private fun set(
        slug: String, workout: String = "w1", index: Int = 0, side: Side? = null, reps: Int? = null,
        duration: Double? = null, load: Double? = null, bw: Double? = null, rir: Int? = null, at: Long = 1,
        type: SetType = SetType.WORK, targetReps: Int? = null, edge: Double? = null,
    ) = ExerciseSet("$workout-$slug-$index-$side-$at", workout, slug, 0, index, type, side, targetReps = targetReps,
        reps = reps, durationS = duration, loadKg = load, edgeMm = edge, rir = rir, bodyweightKg = bw, completedAt = at)

    private fun d(s: String) = LocalDate.parse(s)

    // ── Trend weight ──────────────────────────────────────────────

    @Test
    fun trendStartsAtFirstWeightAndMovesTenPercentPerDay() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 70.0, d("2026-10-02") to 71.0))
        assertEquals(70.0, trend[0].trend, 1e-9)
        assertEquals(70.1, trend[1].trend, 1e-9)
    }

    @Test
    fun trendCompoundsOverGaps() {
        val daily = TrendWeight.compute((1..7).map { d("2026-10-0$it") to (if (it == 1) 70.0 else 72.0) })
        val gap = TrendWeight.compute(listOf(d("2026-10-01") to 70.0, d("2026-10-07") to 72.0))
        // One reading after six days pulls as far as six daily readings would.
        assertEquals(daily.last().trend, gap.last().trend, 1e-9)
    }

    @Test
    fun weeklyRateNeedsAWeekOfData() {
        val short = TrendWeight.compute(listOf(d("2026-10-01") to 70.0, d("2026-10-04") to 69.0))
        assertNull(TrendWeight.weeklyRate(short))
        val long = TrendWeight.compute((0..20).map { LocalDate.fromEpochDays(d("2026-09-01").toEpochDays() + it) to 70.0 - it * 0.1 })
        val rate = assertNotNull(TrendWeight.weeklyRate(long))
        assertTrue(rate < 0)
    }

    // ── Strength math, records, ghosts ─────────────────────────────

    @Test
    fun effectiveLoadReadsAddedAndAssistedWeight() {
        assertEquals(80.0, StrengthMath.effectiveLoad(LoadMode.BODYWEIGHT_PLUS, 10.0, 70.0))
        assertEquals(55.0, StrengthMath.effectiveLoad(LoadMode.BODYWEIGHT_PLUS, -15.0, 70.0))
        assertEquals(30.0, StrengthMath.effectiveLoad(LoadMode.EXTERNAL, 30.0, 70.0))
        assertEquals(50.0, StrengthMath.percentBodyweight(LoadMode.EXTERNAL, 35.0, 70.0)!!, 1e-9)
    }

    @Test
    fun firstLogIsABaselineNotARecord() {
        val first = set(pickup.slug, load = 30.0, duration = 10.0, side = Side.RIGHT, edge = 20.0)
        assertNull(PersonalRecords.detect(pickup, first, emptyList()))
    }

    @Test
    fun pickupRecordComparesSameSideAndEdge() {
        val history = listOf(
            set(pickup.slug, workout = "a", load = 30.0, duration = 10.0, side = Side.RIGHT, edge = 20.0),
            set(pickup.slug, workout = "a", load = 40.0, duration = 10.0, side = Side.RIGHT, edge = 30.0),
        )
        val now = set(pickup.slug, workout = "b", load = 32.0, duration = 10.0, side = Side.RIGHT, edge = 20.0, at = 5)
        val pr = assertNotNull(PersonalRecords.detect(pickup, now, history))
        assertEquals(RecordKind.LOAD, pr.kind)
        assertEquals(30.0, pr.previous)
        val leftSide = now.copy(side = Side.LEFT)
        assertNull(PersonalRecords.detect(pickup, leftSide, history))
    }

    @Test
    fun hangsShorterThanFiveSecondsDoNotCount() {
        val history = listOf(set(maxHang.slug, workout = "a", load = 5.0, duration = 10.0, bw = 70.0, edge = 20.0))
        val tooShort = set(maxHang.slug, workout = "b", load = 20.0, duration = 3.0, bw = 70.0, edge = 20.0, at = 9)
        assertNull(PersonalRecords.detect(maxHang, tooShort, history))
    }

    @Test
    fun ghostValuesComeFromTheLastWorkout() {
        val history = listOf(
            set(pullUp.slug, workout = "new", index = 0, reps = 7, at = 20),
            set(pullUp.slug, workout = "new", index = 1, reps = 6, at = 21),
            set(pullUp.slug, workout = "old", index = 0, reps = 4, at = 5),
        ).sortedByDescending { it.completedAt }
        val ghosts = GhostValues.lastSession(history)
        assertEquals(listOf(7, 6), ghosts.map { it.reps })
        assertEquals(6, GhostValues.forSet(ghosts, 5, null)?.reps)
    }

    // ── Progression and warm-up ────────────────────────────────────

    @Test
    fun allSetsAtTopWithReserveSuggestsHarderVariant() {
        val session = (0..2).map { set(pullUp.slug, index = it, reps = 8, rir = 2) }
        val s = assertNotNull(ProgressionAdvisor.evaluate(pullUp, session, 1.0))
        assertEquals(ProgressionVerdict.TOO_EASY, s.verdict)
        assertEquals("pull.weighted_pull_up", s.harderSlug)
    }

    @Test
    fun missedHoldsOnAssistedHangAddAssistance() {
        val session = (0..4).map { set(maxHang.slug, index = it, duration = 6.0, load = -10.0, bw = 70.0) }
        val s = assertNotNull(ProgressionAdvisor.evaluate(maxHang, session, 2.5))
        assertEquals(ProgressionVerdict.TOO_HARD, s.verdict)
        assertEquals(-12.5, s.nextLoadKg)
    }

    @Test
    fun warmupForAddedWeightHangStartsAssisted() {
        val steps = WarmupRamp.build(maxHang, workingLoadKg = 10.0, bodyweightKg = 70.0, incrementKg = 1.0)
        assertEquals(listOf(50, 70, 85), steps.map { it.percent })
        assertEquals(-30.0, steps.first().loadKg)   // 50 % of 80 kg = 40 kg → 30 kg assistance
        assertTrue(steps.all { it.loadKg < 10.0 })
    }

    // ── Injury mode ────────────────────────────────────────────────

    private val leftFinger = Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, severity = 5, climbingPaused = true, startedOn = "2026-10-01")

    @Test
    fun injuredLeftFingerKeepsOneArmPickupForTheRightHand() {
        val advice = InjuryAdvisor.assess(pickup, listOf(leftFinger))
        assertEquals(InjuryVerdict.ONE_SIDE_ONLY, advice.verdict)
        assertEquals(Side.RIGHT, advice.allowedSide)
    }

    @Test
    fun injuredFingerAvoidsTwoArmHangsAndWallClimbingButKeepsPullUps() {
        assertEquals(InjuryVerdict.AVOID, InjuryAdvisor.assess(maxHang, listOf(leftFinger)).verdict)
        assertEquals(InjuryVerdict.AVOID, InjuryAdvisor.assess(limitBoulder, listOf(leftFinger)).verdict)
        assertEquals(InjuryVerdict.OK, InjuryAdvisor.assess(pullUp, listOf(leftFinger)).verdict)
    }

    @Test
    fun injuriesOnBothSidesLeaveNoSide() {
        val right = leftFinger.copy(id = "i2", side = InjurySide.RIGHT)
        assertEquals(InjuryVerdict.AVOID, InjuryAdvisor.assess(pickup, listOf(leftFinger, right)).verdict)
    }

    @Test
    fun plannerDropsTheInjuredSide() {
        val catalog = ExerciseCatalog(1, listOf(pickup, pullUp, maxHang))
        var n = 0
        val block = WorkoutPlanner.plan("w", 0, WorkoutPlanner.itemFor(pickup), catalog, emptyList(),
            listOf(leftFinger), 70.0) { "id${n++}" }
        assertEquals(3, block.sets.size)
        assertTrue(block.sets.all { it.side == Side.RIGHT })
        val skipped = WorkoutPlanner.plan("w", 1, WorkoutPlanner.itemFor(maxHang), catalog, emptyList(),
            listOf(leftFinger), 70.0) { "id${n++}" }
        assertTrue(skipped.skippedForInjury)
        assertTrue(skipped.sets.isEmpty())
    }

    @Test
    fun plannerPrefillsGhostValues() {
        val catalog = ExerciseCatalog(1, listOf(pullUp))
        val history = listOf(set(pullUp.slug, workout = "old", index = 0, reps = 6, at = 3))
        var n = 0
        val block = WorkoutPlanner.plan("w", 0, WorkoutPlanner.itemFor(pullUp), catalog, history, emptyList(), null) { "id${n++}" }
        assertEquals(6, block.sets.first().reps)
        assertEquals(8, block.sets.first().targetReps)
        assertTrue(block.sets.none { it.isCompleted })
    }

    // ── Readiness ──────────────────────────────────────────────────

    @Test
    fun badSkinRedirectsInsteadOfCancelling() {
        val r = ReadinessEvaluator.evaluate(Checkin("2026-10-04", 0, sleep = 4, energy = 4, skin = 1, fingers = 4), emptyList())
        assertEquals(ReadinessLevel.ADAPT, r.level)
        assertEquals(ReadinessReason.SKIN_LOW, r.decidingFactor)
        assertTrue(r.avoidClimbing)
    }

    @Test
    fun sicknessMeansRest() {
        val r = ReadinessEvaluator.evaluate(Checkin("2026-10-04", 0, sick = true), emptyList())
        assertEquals(ReadinessLevel.REST, r.level)
    }

    @Test
    fun noCheckinAndNoInjuryIsGo() {
        val r = ReadinessEvaluator.evaluate(null, emptyList())
        assertEquals(ReadinessLevel.GO, r.level)
        assertEquals(ReadinessReason.ALL_GOOD, r.decidingFactor)
    }

    // ── Fueling and RED-S guard ────────────────────────────────────

    @Test
    fun carbsFollowTheTrainingDay() {
        val rest = FuelTargets.compute(70.0, null, 1.6)
        val board = FuelTargets.compute(70.0, DayActivity("d", climbingMinutes = 90), 1.6)
        assertEquals(DayLoad.REST, rest.dayLoad)
        assertEquals(210, rest.carbsG)
        assertEquals(DayLoad.HARD, board.dayLoad)
        assertEquals(112, board.proteinG)
        assertTrue(board.carbsG > rest.carbsG)
        assertTrue(board.waterMl > rest.waterMl)
    }

    @Test
    fun lowBmiTriggersSignalAndPausesLossGoal() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 50.0))
        val signals = RedsGuard.evaluate(AthleteProfile(goal = AthleteGoal.LOSE_WEIGHT, sex = Sex.MALE), 175.0, trend, emptyList())
        assertTrue(RedsSignal.LOW_BMI in signals)
        assertTrue(RedsSignal.LOSS_GOAL_PAUSED in signals)
        assertTrue(!RedsGuard.lossGoalAllowed(AthleteProfile(), 175.0, trend))
    }

    @Test
    fun femaleThresholdIsLower() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 54.0)) // BMI 18.0 at 173 cm
        assertTrue(RedsSignal.LOW_BMI in RedsGuard.evaluate(AthleteProfile(sex = Sex.MALE), 173.0, trend, emptyList()))
        assertTrue(RedsSignal.LOW_BMI !in RedsGuard.evaluate(AthleteProfile(sex = Sex.FEMALE), 173.0, trend, emptyList()))
    }

    @Test
    fun rapidLossIsDetected() {
        val start = d("2026-09-01").toEpochDays()
        val trend = TrendWeight.compute((0..28).map { LocalDate.fromEpochDays(start + it) to 70.0 - it * 0.2 })
        assertTrue(RedsSignal.RAPID_LOSS in RedsGuard.evaluate(AthleteProfile(), 175.0, trend, emptyList()))
    }

    @Test
    fun lowCarbsOnTrainingDays() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 70.0))
        val days = (1..3).map { FuelDay("2026-10-0$it", logged = true, carbsG = 120.0, dayLoad = DayLoad.HARD) }
        assertTrue(RedsSignal.LOW_CARBS_ON_TRAINING_DAYS in RedsGuard.evaluate(AthleteProfile(), 175.0, trend, days))
    }

    // ── Consistency streak ─────────────────────────────────────────

    @Test
    fun streakCountsWeeksAndJokersProtectAMissedWeek() {
        val weeks = (0 until 6).map { WeekStat(LocalDate.fromEpochDays(d("2026-08-03").toEpochDays() + it * 7), 3, 0) } +
            WeekStat(d("2026-09-14"), 1, 0) +   // missed, protected by the joker earned after 4 good weeks
            WeekStat(d("2026-09-21"), 3, 0)     // current week, done
        val s = ConsistencyStreak.compute(weeks, goal = 3)
        assertEquals(7, s.weeks)
        assertEquals(0, s.jokers)
        assertTrue(s.currentWeekDone)
    }

    @Test
    fun pausedWeeksFreezeTheStreak() {
        val weeks = listOf(
            WeekStat(d("2026-09-07"), 3, 0),
            WeekStat(d("2026-09-14"), 0, 7),
            WeekStat(d("2026-09-21"), 1, 0),
        )
        val s = ConsistencyStreak.compute(weeks, goal = 3)
        assertEquals(1, s.weeks)
        assertTrue(!s.currentWeekDone)
    }

    @Test
    fun missedWeekWithoutJokerResets() {
        val weeks = listOf(WeekStat(d("2026-09-07"), 3, 0), WeekStat(d("2026-09-14"), 0, 0), WeekStat(d("2026-09-21"), 0, 0))
        assertEquals(0, ConsistencyStreak.compute(weeks, 3).weeks)
    }

    @Test
    fun weekStartIsMonday() {
        assertEquals(d("2026-09-28"), ConsistencyStreak.weekStart(d("2026-10-04")))
        assertEquals(d("2026-09-28"), ConsistencyStreak.weekStart(d("2026-09-28")))
    }

    // ── Catalogue ──────────────────────────────────────────────────

    @Test
    fun equipmentSubstitutesKeepPickupsVisibleWithAKettlebell() {
        val filter = CatalogFilter(ownedEquipment = setOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.KETTLEBELL))
        assertTrue(filter.matches(pickup))
        assertTrue(!CatalogFilter(ownedEquipment = setOf(EquipmentV2.KETTLEBELL)).matches(pickup))
    }

    @Test
    fun chainIsOrderedEasyToHard() {
        val a = def("x.a", ExerciseKind.REPS, LoadMode.BODYWEIGHT, harder = "x.b")
        val b = def("x.b", ExerciseKind.REPS, LoadMode.BODYWEIGHT, easier = "x.a", harder = "x.c")
        val c = def("x.c", ExerciseKind.REPS, LoadMode.BODYWEIGHT, easier = "x.b", harder = "x.a") // cycle back
        val catalog = ExerciseCatalog(1, listOf(a, b, c))
        assertEquals(listOf("x.a", "x.b", "x.c"), catalog.chainOf("x.b").map { it.slug })
    }

    @Test
    fun searchFindsGermanAliasesAndWithoutClimbingFilter() {
        val catalog = ExerciseCatalog(1, listOf(pickup, pullUp, limitBoulder))
        assertEquals(listOf(pullUp.slug), catalog.search("pull.pull", "de").map { it.slug })
        val noWall = catalog.search("", "de", CatalogFilter(withoutClimbing = true)).map { it.slug }
        assertTrue(limitBoulder.slug !in noWall)
        assertTrue(catalog.search("", "de", CatalogFilter(fingerFreeOnly = true)).all { it.fingerFree })
    }

    @Test
    fun unknownSlugGetsAReadableFallback() {
        val stub = ExerciseCatalog.EMPTY.fallbackFor("legs.some_old_move")
        assertEquals("Some old move", stub.name("de"))
    }

    @Test
    fun apeIndex() {
        assertEquals(5.0, Units.apeIndex(180.0, 175.0))
        assertNull(Units.apeIndex(null, 175.0))
    }
}
