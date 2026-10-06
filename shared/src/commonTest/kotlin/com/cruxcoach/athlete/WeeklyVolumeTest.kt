package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyVolumeTest {

    private fun ex(
        slug: String, category: ExerciseCategoryV2, kind: ExerciseKind, load: LoadMode,
        equipment: List<EquipmentV2> = listOf(EquipmentV2.NONE), domains: List<LoadDomain> = emptyList(),
        tags: List<String> = emptyList(), unilateral: Boolean = false, difficulty: Int = 1,
        contraindications: List<BodyRegion> = emptyList(),
        defaults: Prescription = Prescription(sets = 3, repsMin = 6, repsMax = 10, durationS = 20, restS = 90),
    ) = ExerciseDefinition(slug, category, kind, load, unilateral, equipment, domains, difficulty = difficulty,
        defaults = defaults, contraindications = contraindications, tags = tags, i18n = mapOf("en" to ExerciseText(slug)))

    private val fingerContra = listOf(BodyRegion.FINGER, BodyRegion.PULLEY)
    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP, ExerciseKind.REPS, LoadMode.BODYWEIGHT, tags = listOf("warmup")),
        ex("warmup.finger_ramp", ExerciseCategoryV2.WARMUP, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS, listOf(EquipmentV2.HANGBOARD),
            listOf(LoadDomain.FINGER), listOf("warmup"), contraindications = fingerContra),
        ex("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS, listOf(EquipmentV2.HANGBOARD),
            listOf(LoadDomain.FINGER), listOf("strength"), difficulty = 2, contraindications = fingerContra,
            defaults = Prescription(sets = 5, durationS = 10, restS = 180, edgeMm = 20)),
        ex("finger.one_arm_pickup", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.EXTERNAL,
            listOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES), listOf(LoadDomain.FINGER, LoadDomain.WRIST), listOf("strength"),
            unilateral = true, difficulty = 2, contraindications = fingerContra,
            defaults = Prescription(sets = 5, durationS = 10, restS = 120, edgeMm = 20)),
        ex("pull.pull_up", ExerciseCategoryV2.PULL, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.PULL_UP_BAR),
            listOf(LoadDomain.ELBOW, LoadDomain.SHOULDER), listOf("strength", "finger_free"), difficulty = 2),
        ex("pull.inverted_row", ExerciseCategoryV2.PULL, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.RINGS),
            listOf(LoadDomain.ELBOW)),
        ex("push.push_up", ExerciseCategoryV2.PUSH, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.SHOULDER)),
        ex("push.pike_push_up", ExerciseCategoryV2.PUSH, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.SHOULDER)),
        ex("antagonist.band_external_rotation", ExerciseCategoryV2.ANTAGONIST, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
            listOf(EquipmentV2.BANDS), listOf(LoadDomain.SHOULDER)),
        ex("antagonist.face_pull", ExerciseCategoryV2.ANTAGONIST, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.BANDS)),
        ex("core.hollow_hold", ExerciseCategoryV2.CORE, ExerciseKind.TIME, LoadMode.BODYWEIGHT, listOf(EquipmentV2.MAT)),
        ex("core.dead_bug", ExerciseCategoryV2.CORE, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.MAT)),
        ex("legs.air_squat", ExerciseCategoryV2.LEGS, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.LOWER_BODY)),
        ex("legs.reverse_lunge", ExerciseCategoryV2.LEGS, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.LOWER_BODY)),
        ex("mobility.frog", ExerciseCategoryV2.MOBILITY, ExerciseKind.TIME, LoadMode.NONE, listOf(EquipmentV2.MAT)),
        ex("mobility.pigeon", ExerciseCategoryV2.MOBILITY, ExerciseKind.TIME, LoadMode.NONE, listOf(EquipmentV2.MAT)),
        ex("power.limit_bouldering", ExerciseCategoryV2.POWER, ExerciseKind.CLIMB, LoadMode.NONE, listOf(EquipmentV2.WALL),
            listOf(LoadDomain.FINGER, LoadDomain.SKIN)),
    ))

    private val home = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES,
        EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL, EquipmentV2.RINGS)
    private val profile = AthleteProfile(equipment = home, equipmentConfigured = true, sessionMinutes = 60)
    private val monday = LocalDate.parse("2026-10-05")
    private fun day(offset: Int) = monday.plus(DatePeriod(days = offset))

    private var setId = 0
    private fun sets(slug: String, day: LocalDate, count: Int, type: SetType = SetType.WORK, completed: Boolean = true) =
        (0 until count).map { i ->
            day to ExerciseSet("s${setId++}", "w-$day", slug, 0, i, setType = type, reps = 8, completedAt = if (completed) 1L else null)
        }

    private fun board(day: LocalDate, intensity: ClimbIntensity?, minutes: Int = 60) =
        day.toString() to DayActivity(day.toString(), climbingMinutes = minutes, climbingEfforts = 30, climbIntensity = intensity)

    private fun target(list: List<AreaTarget>, area: VolumeArea) = list.single { it.area == area }
    private fun progress(list: List<AreaProgress>, area: VolumeArea) = list.single { it.area == area }

    // ── Targets ──────────────────────────────────────────────────────

    @Test
    fun baselineBuildWeekAtThreeDays() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        assertEquals(AreaTarget(VolumeArea.PULL, 6, 12, 1), target(t, VolumeArea.PULL))
        assertEquals(AreaTarget(VolumeArea.ANTAGONIST, 6, 12, 2), target(t, VolumeArea.ANTAGONIST))
        val finger = target(t, VolumeArea.FINGER)
        assertEquals(2, finger.minSessions)
        assertEquals(12, finger.maxSets)
        assertEquals(0, finger.minSets)
    }

    @Test
    fun blockPhaseDaysGoalAndAgeScaleTheTargets() {
        val deload = WeeklyVolume.targets(profile, BlockState(BlockPhase.DELOAD, 1, 1, null), emptyList(), 3)
        assertEquals(4, target(deload, VolumeArea.PULL).minSets)
        assertEquals(1, target(deload, VolumeArea.FINGER).minSessions)

        val event = WeeklyVolume.targets(profile, BlockState(BlockPhase.EVENT, 1, 1, 0), emptyList(), 3)
        assertTrue(event.all { it.isOff }, "$event")

        assertEquals(4, target(WeeklyVolume.targets(profile, null, emptyList(), 2), VolumeArea.PULL).minSets)

        val strength = profile.copy(coach = CoachProfile(goal = CoachGoal.BUILD_STRENGTH))
        assertEquals(8, target(WeeklyVolume.targets(strength, null, emptyList(), 3), VolumeArea.PULL).minSets)

        val youth = profile.copy(coach = CoachProfile(ageBand = AgeBand.UNDER_16))
        val y = WeeklyVolume.targets(youth, null, emptyList(), 3)
        assertEquals(0, target(y, VolumeArea.FINGER).maxSets, "no off-wall finger sets while growing")
        assertEquals(5, target(y, VolumeArea.PULL).minSets)
    }

    @Test
    fun fingerInjuriesShrinkOrRemoveTheFingerTarget() {
        val left = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 4, climbingPaused = true, startedOn = "2026-10-01")
        assertEquals(6, target(WeeklyVolume.targets(profile, null, listOf(left), 3), VolumeArea.FINGER).maxSets)
        val both = left.copy(side = InjurySide.BOTH)
        assertTrue(target(WeeklyVolume.targets(profile, null, listOf(both), 3), VolumeArea.FINGER).isOff)
    }

    // ── Progress ─────────────────────────────────────────────────────

    @Test
    fun countsOnlyCompletedWorkSetsOfTheWeek() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        val weekSets = sets("pull.pull_up", day(0), 3) + sets("pull.pull_up", day(2), 2) +
            sets("pull.pull_up", day(2), 2, type = SetType.WARMUP) + sets("pull.inverted_row", day(3), 2, completed = false) +
            sets("pull.pull_up", day(-1), 4) + // last week
            sets("warmup.arm_circles", day(0), 2)
        val p = progress(WeeklyVolume.progress(weekSets, emptyMap(), catalog, monday, t), VolumeArea.PULL)
        assertEquals(5, p.doneSets)
        assertEquals(2, p.sessions)
        assertEquals(AreaStatus.BELOW, p.copy(expectedByNow = 6.0).status)
    }

    @Test
    fun fingerDaysInARowCountOnceAndBoardDaysCount() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        val fingerSets = sets("finger.max_hang", day(0), 5) + sets("finger.max_hang", day(1), 5)
        val onlySets = progress(WeeklyVolume.progress(fingerSets, emptyMap(), catalog, monday, t), VolumeArea.FINGER)
        assertEquals(1, onlySets.sessions, "Monday and Tuesday are one finger session")
        assertEquals(AreaStatus.BELOW, onlySets.status)

        val activities = mapOf(board(day(3), ClimbIntensity.HARD), board(day(5), ClimbIntensity.VOLUME))
        val all = WeeklyVolume.progress(fingerSets, activities, catalog, monday, t)
        val finger = progress(all, VolumeArea.FINGER)
        assertEquals(1, finger.sessions)
        assertEquals(1.5, finger.climbingCredit, 1e-9)
        assertEquals(AreaStatus.DONE, finger.status)
        assertEquals(3.0, progress(all, VolumeArea.PULL).climbingCredit, 1e-9)
    }

    @Test
    fun deficitGrowsAsTheWeekRunsOutAndOverIsNegative() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        val p = WeeklyVolume.progress(sets("legs.air_squat", day(0), 10), emptyMap(), catalog, monday, t)
        val early = WeeklyVolume.deficitWeights(p, day(0), monday)
        val late = WeeklyVolume.deficitWeights(p, day(5), monday)
        assertTrue(late.getValue(VolumeArea.PUSH) > early.getValue(VolumeArea.PUSH), "$early / $late")
        assertEquals(-1.0, early.getValue(VolumeArea.LEGS))
    }

    // ── Suggester ────────────────────────────────────────────────────

    private fun input(
        today: LocalDate,
        weekly: List<AreaProgress>,
        activities: Map<String, DayActivity> = emptyMap(),
        injuries: List<Injury> = emptyList(),
        lastTrained: Map<String, LocalDate> = emptyMap(),
    ) = SuggestionInput(
        catalog, profile, today, ReadinessEvaluator.evaluate(null, injuries), injuries,
        activities = activities.mapKeys { LocalDate.parse(it.key) }, lastTrained = lastTrained,
        weekly = weekly, weekStart = monday,
    )

    @Test
    fun pullAndPushFarBelowTargetMidWeekWinOverLegsLastTrainedRule() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        val activities = mapOf(board(day(0), ClimbIntensity.HARD), board(day(2), ClimbIntensity.HARD))
        val weekSets = sets("legs.air_squat", day(0), 8) + sets("core.dead_bug", day(1), 10)
        val weekly = WeeklyVolume.progress(weekSets, activities, catalog, monday, t, today = day(3))
        // Without the weekly plan, "pull trained yesterday, legs never" would pick legs.
        val lastTrained = mapOf("pull.pull_up" to day(2))
        val without = SessionSuggester.suggest(input(day(3), emptyList(), activities, lastTrained = lastTrained))
        assertEquals(SuggestionFocus.LEGS_CORE, without.focus)

        val s = SessionSuggester.suggest(input(day(3), weekly, activities, lastTrained = lastTrained))
        assertEquals(SuggestionFocus.PULL_PUSH, s.focus)
        assertTrue(SuggestionReason.WEEKLY_TARGET in s.reasons, "${s.reasons}")
        assertTrue(s.basedOn.any { it is Evidence.WeeklyTarget }, "${s.basedOn}")
        val categories = s.routine.items.filter { !it.warmup }.map { catalog[it.slug]!!.category }
        assertTrue(ExerciseCategoryV2.PUSH in categories && ExerciseCategoryV2.PULL in categories, "$categories")
        assertTrue(ExerciseCategoryV2.CORE !in categories, "core is over its weekly maximum: $categories")
        // Recovery after Wednesday's hard board day still holds.
        assertTrue(categories.none { it == ExerciseCategoryV2.FINGER }, "$categories")
    }

    @Test
    fun twoHardBoardDaysMeetTheFingerWeekSoNoFingerMain() {
        val t = WeeklyVolume.targets(profile, null, emptyList(), 3)
        val activities = mapOf(board(day(0), ClimbIntensity.HARD), board(day(3), ClimbIntensity.HARD))
        val saturday = day(5)
        val control = SessionSuggester.suggest(input(saturday, emptyList(), activities))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, control.focus, "fingers rested 48 h → finger day without the weekly plan")

        val weekly = WeeklyVolume.progress(emptyList(), activities, catalog, monday, t, today = saturday)
        val s = SessionSuggester.suggest(input(saturday, weekly, activities))
        assertTrue(s.focus != SuggestionFocus.FINGER_STRENGTH, "${s.focus}")
        assertTrue(s.routine.items.none { !it.warmup && catalog[it.slug]!!.category == ExerciseCategoryV2.FINGER }, "${s.routine.items}")
        assertTrue(SuggestionReason.WEEKLY_TARGET in s.reasons)
        assertTrue(s.basedOn.contains(Evidence.WeeklyTarget(VolumeArea.FINGER, 2, 2)), "${s.basedOn}")
    }

    @Test
    fun injurySessionDropsHealthySideFingerWorkOnceTheWeekIsMetButKeepsSafetyRules() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 4, climbingPaused = true, startedOn = "2026-10-01")
        val t = WeeklyVolume.targets(profile, null, listOf(injury), 3)
        val friday = day(4)
        val control = SessionSuggester.suggest(input(friday, emptyList(), injuries = listOf(injury)))
        assertTrue("finger.one_arm_pickup" in control.routine.items.map { it.slug }, "${control.routine.items}")

        val fingerSets = sets("finger.one_arm_pickup", day(0), 5) + sets("finger.one_arm_pickup", day(2), 5)
        val weekly = WeeklyVolume.progress(fingerSets, emptyMap(), catalog, monday, t, today = friday)
        val s = SessionSuggester.suggest(input(friday, weekly, injuries = listOf(injury)))
        assertEquals(SuggestionFocus.INJURY_SAFE, s.focus)
        val slugs = s.routine.items.map { it.slug }
        assertTrue("finger.one_arm_pickup" !in slugs && "finger.max_hang" !in slugs && "power.limit_bouldering" !in slugs, "$slugs")
    }
}
