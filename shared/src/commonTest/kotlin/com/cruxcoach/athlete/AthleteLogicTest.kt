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
    fun prefilledHoldsWithoutReserveAnswerAreNotTooEasy() {
        val session = (0..4).map { set(maxHang.slug, index = it, duration = 10.0, load = 5.0, bw = 70.0) }
        assertEquals(ProgressionVerdict.ON_TRACK, ProgressionAdvisor.evaluate(maxHang, session, 1.0)?.verdict)
        val easy = session.map { it.copy(rir = 3) }
        assertEquals(ProgressionVerdict.TOO_EASY, ProgressionAdvisor.evaluate(maxHang, easy, 1.0)?.verdict)
    }

    @Test
    fun sideBalanceUsesTotalLoad() {
        val catalog = ExerciseCatalog(1, listOf(maxHang.copy(unilateral = true)))
        val sets = listOf(
            set(maxHang.slug, index = 0, side = Side.LEFT, duration = 10.0, load = 0.0, bw = 70.0),
            set(maxHang.slug, index = 0, side = Side.RIGHT, duration = 10.0, load = 2.0, bw = 70.0),
        )
        val summary = WorkoutSummarizer.summarize(sets, 30, catalog, { emptyList() }, 1.0)
        val left = summary.sideLoad.getValue(Side.LEFT)
        val right = summary.sideLoad.getValue(Side.RIGHT)
        assertTrue(right / left < 1.05)
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
    fun carbsFollowTheRatedClimbingIntensity() {
        // Two hours in the gym: an easy technique day needs less than a limit session.
        val light = DayActivity("d", climbingMinutes = 120, climbIntensity = ClimbIntensity.LIGHT)
        val limit = DayActivity("d", climbingMinutes = 120, climbIntensity = ClimbIntensity.LIMIT)
        assertEquals(DayLoad.HARD, FuelTargets.dayLoad(light))
        assertEquals(DayLoad.VERY_HARD, FuelTargets.dayLoad(limit))
        assertTrue(FuelTargets.compute(70.0, limit, 1.6).carbsG > FuelTargets.compute(70.0, light, 1.6).carbsG)
        // Without a rating the day counts like a typical board session.
        assertEquals(FuelTargets.dayLoad(DayActivity("d", climbingMinutes = 120)),
            FuelTargets.dayLoad(DayActivity("d", climbingMinutes = 120, climbIntensity = null)))
    }

    @Test
    fun lowBmiIsNamedWithItsValueAndNeverBlocksTheGoal() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 50.0))
        val report = RedsGuard.evaluate(AthleteProfile(goal = AthleteGoal.LOSE_WEIGHT, sex = Sex.MALE), 175.0, trend, emptyList())
        assertTrue(RedsSignal.LOW_BMI in report.signals)
        assertEquals(16.3, report.bmi!!, 0.05)
        assertEquals(18.5, report.bmiThreshold, 1e-9)
    }

    @Test
    fun femaleThresholdIsLower() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 54.0)) // BMI 18.0 at 173 cm
        assertTrue(RedsSignal.LOW_BMI in RedsGuard.evaluate(AthleteProfile(sex = Sex.MALE), 173.0, trend, emptyList()).signals)
        assertTrue(RedsSignal.LOW_BMI !in RedsGuard.evaluate(AthleteProfile(sex = Sex.FEMALE), 173.0, trend, emptyList()).signals)
    }

    @Test
    fun rapidLossIsDetectedWithItsRate() {
        val start = d("2026-09-01").toEpochDays()
        val trend = TrendWeight.compute((0..28).map { LocalDate.fromEpochDays(start + it) to 70.0 - it * 0.2 })
        val report = RedsGuard.evaluate(AthleteProfile(), 175.0, trend, emptyList())
        assertTrue(RedsSignal.RAPID_LOSS in report.signals)
        assertTrue(report.weeklyRateKg!! < 0)
        assertTrue(report.fourWeekShare!! < -0.03)
    }

    @Test
    fun lowCarbsOnTrainingDays() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 70.0))
        val days = (1..3).map { FuelDay("2026-10-0$it", logged = true, carbsG = 120.0, dayLoad = DayLoad.HARD) }
        val report = RedsGuard.evaluate(AthleteProfile(), 175.0, trend, days)
        assertTrue(RedsSignal.LOW_CARBS_ON_TRAINING_DAYS in report.signals)
        assertEquals(3, report.lowCarbDays)
    }

    @Test
    fun highDeficitNeedsThreeCompleteDays() {
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 70.0))
        fun day(n: Int, kcal: Double, complete: Boolean = true) =
            FuelDay("2026-10-0$n", logged = true, carbsG = 300.0, dayLoad = DayLoad.REST, kcal = kcal, needKcal = 2400, complete = complete)
        // Two days are not enough, and a day with one logged snack does not count.
        val two = RedsGuard.evaluate(AthleteProfile(), 175.0, trend, listOf(day(1, 1200.0), day(2, 1200.0), day(3, 300.0, complete = false)))
        assertTrue(RedsSignal.HIGH_DEFICIT !in two.signals)
        assertEquals(0, two.comparedDays)
        val three = RedsGuard.evaluate(AthleteProfile(), 175.0, trend, listOf(day(1, 1200.0), day(2, 1500.0), day(3, 1500.0)))
        assertTrue(RedsSignal.HIGH_DEFICIT in three.signals)
        assertEquals(1400, three.avgIntakeKcal)
        assertEquals(2400, three.avgNeedKcal)
        assertEquals(3, three.comparedDays)
        assertEquals(0.417, three.deficitShare!!, 0.001)
        // 20 % under need is a deficit, not a high one.
        val moderate = RedsGuard.evaluate(AthleteProfile(), 175.0, trend, (1..3).map { day(it, 1920.0) })
        assertTrue(RedsSignal.HIGH_DEFICIT !in moderate.signals)
    }

    // ── Energy need and weight plan ─────────────────────────────────

    @Test
    fun energyNeedAddsRestingEverydayAndTraining() {
        val profile = AthleteProfile(sex = Sex.MALE, birthYear = 1996)
        // Mifflin-St Jeor: 10 × 70 + 6.25 × 175 − 5 × 30 + 5 = 1648.75
        val rest = EnergyBalance.need(profile, 70.0, 175.0, null, 2026)!!
        assertEquals(1649, rest.restingKcal)
        assertEquals(659.5, rest.everydayKcal.toDouble(), 1.0) // × 1.4 mostly seated
        assertEquals(0, rest.trainingKcal)
        assertTrue(!rest.ageAssumed && !rest.sexAssumed)
        // Two hours of hard climbing: (6 − 1) MET × 70 kg × 2 h = 700 kcal on top.
        val climb = EnergyBalance.need(profile, 70.0, 175.0, DayActivity("d", climbingMinutes = 120, climbIntensity = ClimbIntensity.HARD), 2026)!!
        assertEquals(700, climb.trainingKcal)
        assertEquals(rest.totalKcal + 700, climb.totalKcal)
        val physical = EnergyBalance.need(profile.copy(everydayActivity = EverydayActivity.PHYSICAL), 70.0, 175.0, null, 2026)!!
        assertTrue(physical.totalKcal > rest.totalKcal)
    }

    @Test
    fun energyNeedNamesItsAssumptionsAndNeedsHeight() {
        assertEquals(null, EnergyBalance.need(AthleteProfile(), 70.0, null, null, 2026))
        val need = EnergyBalance.need(AthleteProfile(), 70.0, 175.0, null, 2026)!!
        assertTrue(need.ageAssumed && need.sexAssumed)
        assertEquals(30, EnergyBalance.age(AthleteProfile(), 2026))
        assertEquals(47, EnergyBalance.age(AthleteProfile(coach = CoachProfile(ageBand = AgeBand.Y40_54)), 2026))
    }

    @Test
    fun weightPlanAllowsEveryPaceAndNamesWhatArguesAgainstIt() {
        val need = EnergyBalance.need(AthleteProfile(sex = Sex.FEMALE, birthYear = 1996), 60.0, 165.0, null, 2026)!!
        val gentle = WeightPlan.plan(0.3, 57.0, 60.0, 165.0, Sex.FEMALE, need)
        assertEquals(330, gentle.dailyDeficitKcal)
        assertEquals(need.totalKcal - 330, gentle.targetKcal)
        assertEquals(10, gentle.weeksToTarget)
        assertTrue(gentle.warnings.isEmpty())
        // 1 kg a week at 60 kg is 1.7 % and a deficit of 1100 kcal: allowed, with both warnings.
        val fast = WeightPlan.plan(1.0, 45.0, 60.0, 165.0, Sex.FEMALE, need)
        assertEquals(1100, fast.dailyDeficitKcal)
        assertTrue(RedsSignal.FAST_PACE in fast.warnings)
        assertTrue(RedsSignal.LARGE_DEFICIT in fast.warnings)
        assertTrue(fast.belowResting)
        // 45 kg at 165 cm is BMI 16.5, under the female threshold of 17.5.
        assertTrue(RedsSignal.LOW_TARGET_BMI in fast.warnings)
        // The plan's warnings reach the energy report.
        val trend = TrendWeight.compute(listOf(d("2026-10-01") to 60.0))
        val report = RedsGuard.evaluate(AthleteProfile(sex = Sex.FEMALE), 165.0, trend, emptyList(), fast)
        assertTrue(report.signals.containsAll(fast.warnings))
    }

    @Test
    fun weightPlanAtTheTargetEatsTheNeed() {
        val need = EnergyBalance.need(AthleteProfile(sex = Sex.MALE, birthYear = 1996), 70.0, 175.0, null, 2026)!!
        val reached = WeightPlan.plan(0.5, 70.5, 70.0, 175.0, Sex.MALE, need)
        assertTrue(reached.targetReached)
        assertEquals(0, reached.dailyDeficitKcal)
        assertEquals(need.totalKcal, reached.targetKcal)
        assertEquals(0.1, WeightPlan.clampPace(0.0), 1e-9)
        assertEquals(2.0, WeightPlan.clampPace(5.0), 1e-9)
        assertEquals(0.7, WeightPlan.clampPace(0.66), 1e-9)
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

class AthleteBackupEnvelopeTest {

    private fun backupJson(athlete: String) = """
        {"version":3,"app":"CruxCoach","exportedAt":"2026-10-04T10:00:00",
         "athlete":$athlete}
    """.trimIndent()

    @Test
    fun previewCountsAthleteRowsPerCategory() {
        val json = backupJson("""{"workouts":[{"id":"w","startedAt":1,"day":"2026-10-04","updatedAt":1}],
            "measurements":[{"day":"2026-10-04","metric":"weight","value":70.0,"unit":"kg","measuredAt":1}],
            "hydration":[{"id":"h","day":"2026-10-04","loggedAt":1,"ml":250}]}""")
        val preview = com.cruxcoach.data.CruxCoachBackup.preview(json)
        assertEquals(1, preview.trainingRows)
        assertEquals(1, preview.athleteBodyRows)
        assertEquals(1, preview.fuelRows)
        val cats = preview.detectedCategories()
        assertTrue(com.cruxcoach.data.CruxCoachBackup.Category.TRAINING in cats)
        assertTrue(com.cruxcoach.data.CruxCoachBackup.Category.BODY_STATS in cats)
        assertTrue(com.cruxcoach.data.CruxCoachBackup.Category.FUEL in cats)
    }

    @Test
    fun backupWithoutAthletePartStillParses() {
        val preview = com.cruxcoach.data.CruxCoachBackup.preview(
            """{"version":3,"app":"CruxCoach","exportedAt":"2026-10-04T10:00:00"}""")
        assertEquals(0, preview.trainingRows)
    }

    @Test
    fun athletePayloadWithAbsurdValuesIsRejected() {
        val json = backupJson("""{"injuries":[{"id":"i","region":"FINGER","severity":42,"startedOn":"2026-10-01"}]}""")
        assertTrue(runCatching { com.cruxcoach.data.CruxCoachBackup.preview(json) }.isFailure)
    }
}

class BenchmarkLogicTest {

    private fun def(slug: String, kind: ExerciseKind, load: LoadMode, unilateral: Boolean = false, defaults: Prescription = Prescription()) =
        ExerciseDefinition(slug, ExerciseCategoryV2.PULL, kind, load, unilateral, defaults = defaults,
            i18n = mapOf("en" to ExerciseText(slug)))

    private val weightedPullUp = def("pull.weighted_pull_up", ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
        defaults = Prescription(sets = 5, repsMin = 3, repsMax = 5, restS = 180))
    private val pickup = def("finger.one_arm_pickup", ExerciseKind.HANG, LoadMode.EXTERNAL, unilateral = true,
        defaults = Prescription(sets = 5, durationS = 10, restS = 120, edgeMm = 20))
    private val repeaters = def("finger.repeaters", ExerciseKind.INTERVAL, LoadMode.BODYWEIGHT_PLUS,
        defaults = Prescription(sets = 4, workS = 7, restBetweenS = 3, repsPerSet = 6, restS = 180, edgeMm = 20))
    private val pullUp = def("pull.pull_up", ExerciseKind.REPS, LoadMode.BODYWEIGHT, defaults = Prescription(sets = 4, repsMin = 4, repsMax = 8))

    private fun bench(slug: String, load: Double? = null, reps: Int? = null, duration: Double? = null, side: Side? = null,
                      edge: Double? = null, bw: Double? = 70.0, at: Long = 1) =
        Benchmark("b$at$slug$side", slug, side, edge, null, load, reps, duration, bw, BenchmarkSource.MANUAL, at)

    @Test
    fun holdCurveIsMonotoneAndAnchoredAtTenSeconds() {
        assertEquals(1.0, HoldCurve.relative(10.0), 1e-9)
        val values = listOf(3.0, 5.0, 7.0, 10.0, 15.0, 20.0, 30.0, 60.0).map { HoldCurve.relative(it) }
        assertTrue(values.zipWithNext().all { (a, b) -> a > b })
    }

    @Test
    fun weightedPullUpPrescriptionComesFromTheE1rmAtTwoInReserve() {
        val cap = assertNotNull(BenchmarkMath.capacity(weightedPullUp, bench(weightedPullUp.slug, load = 20.0, reps = 5), null))
        assertEquals(105.0, cap.value, 1e-9)                    // (70 + 20) × (1 + 5/30)
        val t = assertNotNull(LoadPrescriber.prescribe(weightedPullUp, WorkoutPlanner.itemFor(weightedPullUp), cap, 70.0, 1.0))
        assertEquals(5, t.reps)
        assertEquals(15.0, t.loadKg)                            // 105 / (1 + 7/30) − 70 ≈ 15.1
    }

    @Test
    fun pickupMaxHangPlansNinetyPercentAndRepeatersBorrowSixtyFive() {
        val cap = assertNotNull(BenchmarkMath.capacity(pickup, bench(pickup.slug, load = 32.0, duration = 10.0, side = Side.RIGHT), 70.0))
        assertEquals(32.0, cap.value, 1e-9)
        assertEquals(29.0, LoadPrescriber.prescribe(pickup, WorkoutPlanner.itemFor(pickup), cap, 70.0, 1.0)?.loadKg)
        val hangCap = Capacity(CapacityKind.TEN_SECOND_MAX, 100.0, 70.0)  // body weight + 30 kg for 10 s
        val r = assertNotNull(LoadPrescriber.prescribe(repeaters, WorkoutPlanner.itemFor(repeaters), hangCap, 70.0, 1.0))
        assertEquals(-5.0, r.loadKg)                            // 65 kg total → 5 kg assistance
        assertEquals(7, r.durationS)
    }

    @Test
    fun longerHoldIsConvertedToTheTenSecondMaximum() {
        val cap = assertNotNull(BenchmarkMath.capacity(pickup, bench(pickup.slug, load = 28.2, duration = 20.0), 70.0))
        assertTrue(cap.value > 31.0 && cap.value < 32.5, "was ${cap.value}")
    }

    @Test
    fun bodyweightRepsAreSeventyPercentOfTheMaximumAndTestsGetNoTarget() {
        val cap = assertNotNull(BenchmarkMath.capacity(pullUp, bench(pullUp.slug, reps = 12), 70.0))
        assertEquals(8, LoadPrescriber.prescribe(pullUp, WorkoutPlanner.itemFor(pullUp), cap, 70.0, 1.0)?.reps)
        assertNull(LoadPrescriber.prescribe(pullUp, WorkoutPlanner.itemFor(pullUp).copy(test = true), cap, 70.0, 1.0))
    }

    @Test
    fun impliedCapacityCountsRepsInReserve() {
        val set = ExerciseSet("s", "w", weightedPullUp.slug, 0, 0, reps = 5, rir = 2, loadKg = 20.0, bodyweightKg = 70.0, completedAt = 1)
        assertEquals(90.0 * (1 + 7 / 30.0), BenchmarkMath.implied(weightedPullUp, set)!!.value, 1e-9)
        assertNull(BenchmarkMath.implied(weightedPullUp, set.copy(setType = SetType.WARMUP)))
    }

    @Test
    fun selectPrefersTheSameSideAndEdge() {
        val list = listOf(
            bench(pickup.slug, load = 30.0, duration = 10.0, side = Side.LEFT, edge = 20.0, at = 5),
            bench(pickup.slug, load = 34.0, duration = 10.0, side = Side.RIGHT, edge = 30.0, at = 4),
            bench(pickup.slug, load = 32.0, duration = 10.0, side = Side.RIGHT, edge = 20.0, at = 3),
        )
        assertEquals(32.0, BenchmarkMath.select(list, Side.RIGHT, 20.0, null)?.loadKg)
        assertEquals(30.0, BenchmarkMath.select(list, Side.LEFT, 20.0, null)?.loadKg)
    }

    @Test
    fun plannerUsesThePrescriptionInsteadOfLastTimesLoad() {
        val catalog = ExerciseCatalog(1, listOf(weightedPullUp))
        val history = listOf(ExerciseSet("old", "o", weightedPullUp.slug, 0, 0, reps = 5, loadKg = 10.0, completedAt = 2))
        val cap = Capacity(CapacityKind.E1RM_TOTAL, 105.0, 70.0)
        var n = 0
        val block = WorkoutPlanner.plan("w", 0, WorkoutPlanner.itemFor(weightedPullUp), catalog, history, emptyList(), 70.0,
            capacityFor = { cap }, incrementKg = 1.0, newId = { "id${n++}" })
        assertTrue(block.sets.all { it.loadKg == 15.0 && it.targetLoadKg == 15.0 && it.reps == 5 })
    }

    @Test
    fun playerOrdersSidesAndUsesAShortSideSwitch() {
        val left = ExerciseSet("l", "w", pickup.slug, 0, 0, side = Side.LEFT, restS = 120)
        val right = ExerciseSet("r", "w", pickup.slug, 0, 0, side = Side.RIGHT, restS = 120)
        val next = ExerciseSet("n", "w", pickup.slug, 0, 1, side = Side.LEFT, restS = 120)
        val sets = listOf(next, right, left)
        assertEquals("l", PlayerQueue.current(sets)?.id)
        assertEquals(30, PlayerQueue.restAfter(left, right, pickup))
        assertEquals(120, PlayerQueue.restAfter(right, next, pickup))
        assertEquals(0, PlayerQueue.restAfter(next, null, pickup))
        assertEquals(PlayerQueue.Position(2, 3, 1, 2), PlayerQueue.position(sets, right))
    }
}
