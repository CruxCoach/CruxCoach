package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionSuggesterTest {

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
        ex("pull.dumbbell_row", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL),
            listOf(LoadDomain.ELBOW), unilateral = true),
        ex("push.push_up", ExerciseCategoryV2.PUSH, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.SHOULDER)),
        ex("antagonist.band_external_rotation", ExerciseCategoryV2.ANTAGONIST, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
            listOf(EquipmentV2.BANDS), listOf(LoadDomain.SHOULDER)),
        ex("antagonist.face_pull", ExerciseCategoryV2.ANTAGONIST, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.BANDS)),
        ex("core.hollow_hold", ExerciseCategoryV2.CORE, ExerciseKind.TIME, LoadMode.BODYWEIGHT, listOf(EquipmentV2.MAT)),
        ex("core.dead_bug", ExerciseCategoryV2.CORE, ExerciseKind.REPS, LoadMode.BODYWEIGHT, listOf(EquipmentV2.MAT)),
        ex("legs.goblet_squat", ExerciseCategoryV2.LEGS, ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, listOf(EquipmentV2.DUMBBELL),
            listOf(LoadDomain.LOWER_BODY)),
        ex("legs.air_squat", ExerciseCategoryV2.LEGS, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.LOWER_BODY)),
        ex("legs.reverse_lunge", ExerciseCategoryV2.LEGS, ExerciseKind.REPS, LoadMode.BODYWEIGHT, domains = listOf(LoadDomain.LOWER_BODY)),
        ex("mobility.frog", ExerciseCategoryV2.MOBILITY, ExerciseKind.TIME, LoadMode.NONE, listOf(EquipmentV2.MAT)),
        ex("mobility.pigeon", ExerciseCategoryV2.MOBILITY, ExerciseKind.TIME, LoadMode.NONE, listOf(EquipmentV2.MAT)),
        ex("mobility.open_book", ExerciseCategoryV2.MOBILITY, ExerciseKind.REPS, LoadMode.NONE, listOf(EquipmentV2.MAT)),
        ex("power.limit_bouldering", ExerciseCategoryV2.POWER, ExerciseKind.CLIMB, LoadMode.NONE, listOf(EquipmentV2.WALL),
            listOf(LoadDomain.FINGER, LoadDomain.SKIN)),
    ))

    private val home = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES,
        EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
    private val monday = LocalDate.parse("2026-10-05")
    private val profile = AthleteProfile(equipment = home, equipmentConfigured = true, sessionMinutes = 60)

    private fun input(
        profile: AthleteProfile = this.profile,
        checkin: Checkin? = null,
        injuries: List<Injury> = emptyList(),
        activities: Map<LocalDate, DayActivity> = emptyMap(),
        favorites: Set<String> = emptySet(),
        routines: List<Routine> = emptyList(),
        variant: Int = 0,
    ) = SuggestionInput(catalog, profile, monday, ReadinessEvaluator.evaluate(checkin, injuries), injuries, activities,
        favorites = favorites, routines = routines, variant = variant)

    private fun slugs(s: SessionSuggestion) = s.routine.items.map { it.slug }

    @Test
    fun restWhenSick() {
        val s = SessionSuggester.suggest(input(checkin = Checkin(monday.toString(), 0, sick = true)))
        assertEquals(SuggestionFocus.REST, s.focus)
        assertEquals(SuggestionReason.SICK, s.reasons.first())
        assertTrue(s.estimatedMinutes <= 10, "was ${s.estimatedMinutes}")
        assertTrue(slugs(s).all { catalog[it]!!.category == ExerciseCategoryV2.MOBILITY })
    }

    @Test
    fun weekPlanWins() {
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("legs.air_squat"), RoutineItem("core.dead_bug")))
        val s = SessionSuggester.suggest(input(profile = profile.copy(weekPlan = mapOf(1 to "r1")), routines = listOf(mine)))
        assertEquals(SuggestionFocus.PLANNED, s.focus)
        assertEquals("r1", s.plannedEntry)
        assertEquals(listOf("legs.air_squat", "core.dead_bug"), slugs(s))
        assertEquals(SuggestionReason.WEEK_PLAN, s.reasons.first())
    }

    @Test
    fun plannedBoardDayIsReplacedWhileClimbingIsPaused() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 5, climbingPaused = true, startedOn = "2026-10-01")
        val s = SessionSuggester.suggest(input(profile = profile.copy(weekPlan = mapOf(1 to PLAN_BOARD)), injuries = listOf(injury)))
        assertEquals(SuggestionFocus.INJURY_SAFE, s.focus)
        assertTrue(SuggestionReason.PLAN_REPLACED_FOR_INJURY in s.reasons)
    }

    @Test
    fun injurySafeKeepsOneArmPickupAndPullUpsWithoutWallOrTwoArmFingerWork() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 5, climbingPaused = true, startedOn = "2026-10-01")
        val s = SessionSuggester.suggest(input(injuries = listOf(injury)))
        assertEquals(SuggestionFocus.INJURY_SAFE, s.focus)
        val picked = slugs(s)
        assertTrue("finger.one_arm_pickup" in picked, "$picked")
        assertTrue("pull.pull_up" in picked, "$picked")
        assertTrue("finger.max_hang" !in picked && "warmup.finger_ramp" !in picked && "power.limit_bouldering" !in picked, "$picked")
    }

    @Test
    fun plannedRoutineFollowsTheInjuryRulesWhileClimbingIsPaused() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, climbingPaused = true, startedOn = "2026-10-01")
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("finger.max_hang"), RoutineItem("pull.pull_up"),
            RoutineItem("core.dead_bug"), RoutineItem("legs.air_squat")))
        val s = SessionSuggester.suggest(input(profile = profile.copy(weekPlan = mapOf(1 to "r1")), injuries = listOf(injury),
            routines = listOf(mine)))
        assertEquals(SuggestionFocus.PLANNED, s.focus)
        assertTrue("finger.max_hang" !in slugs(s), "${slugs(s)}")
        assertTrue(SuggestionReason.PLAN_FILTERED_FOR_INJURY in s.reasons)
    }

    @Test
    fun plannedFingerWorkIsDroppedWhenFingersAreTired() {
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("finger.max_hang"), RoutineItem("pull.pull_up"),
            RoutineItem("core.dead_bug"), RoutineItem("legs.air_squat")))
        val s = SessionSuggester.suggest(input(profile = profile.copy(weekPlan = mapOf(1 to "r1")), routines = listOf(mine),
            checkin = Checkin(monday.toString(), 0, fingers = 1)))
        assertEquals(SuggestionFocus.PLANNED, s.focus)
        assertTrue("finger.max_hang" !in slugs(s), "${slugs(s)}")
        assertTrue(SuggestionReason.FINGERS_TIRED in s.reasons)
    }

    @Test
    fun injurySessionLeavesOutFingerWorkWhenFingersAreTired() {
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, climbingPaused = true, startedOn = "2026-10-01")
        val s = SessionSuggester.suggest(input(injuries = listOf(injury), checkin = Checkin(monday.toString(), 0, fingers = 1)))
        assertEquals(SuggestionFocus.INJURY_SAFE, s.focus)
        assertTrue(s.routine.items.none { !it.warmup && catalog[it.slug]!!.category == ExerciseCategoryV2.FINGER }, "${slugs(s)}")
        assertTrue(SuggestionReason.FINGERS_TIRED in s.reasons)
    }

    @Test
    fun withoutFingerEquipmentTheReasonSaysSo() {
        val noBoard = profile.copy(equipment = home - EquipmentV2.HANGBOARD - EquipmentV2.PICKUP_BLOCK)
        val s = SessionSuggester.suggest(input(profile = noBoard))
        assertEquals(SuggestionFocus.PULL_PUSH, s.focus)
        assertTrue(SuggestionReason.NO_FINGER_EQUIPMENT in s.reasons && SuggestionReason.FINGERS_RESTED !in s.reasons, "${s.reasons}")
    }

    @Test
    fun fingerFocusAfterTwoFingerFreeDays() {
        val s = SessionSuggester.suggest(input())
        assertEquals(SuggestionFocus.FINGER_STRENGTH, s.focus)
        assertTrue(slugs(s).any { catalog[it]!!.category == ExerciseCategoryV2.FINGER })
        assertEquals("warmup.finger_ramp", slugs(s).first())
        assertTrue(s.routine.items.first().warmup)
    }

    @Test
    fun noFingerFocusTheDayAfterABoardSession() {
        val yesterday = monday.minus(DatePeriod(days = 1))
        val s = SessionSuggester.suggest(input(activities = mapOf(yesterday to DayActivity(yesterday.toString(), climbingMinutes = 60))))
        assertTrue(s.focus == SuggestionFocus.PULL_PUSH || s.focus == SuggestionFocus.LEGS_CORE, "${s.focus}")
        assertTrue(SuggestionReason.FINGERS_LOADED_RECENTLY in s.reasons)
        assertTrue(slugs(s).none { LoadDomain.FINGER in catalog[it]!!.domains })
    }

    @Test
    fun favouritesArePreferred() {
        repeat(6) { variant ->
            val s = SessionSuggester.suggest(input(favorites = setOf("core.dead_bug"), variant = variant))
            assertTrue("core.dead_bug" in slugs(s), "variant $variant: ${slugs(s)}")
            assertTrue("core.hollow_hold" !in slugs(s))
        }
    }

    @Test
    fun anotherVariantChangesThePicks() {
        val distinct = (0 until 10).map { slugs(SessionSuggester.suggest(input(variant = it))) }.toSet()
        assertTrue(distinct.size > 1)
    }

    @Test
    fun timeBudgetIsRespected() {
        val yesterday = monday.minus(DatePeriod(days = 1))
        val board = mapOf(yesterday to DayActivity(yesterday.toString(), climbingMinutes = 60))
        val short = SessionSuggester.suggest(input(profile = profile.copy(sessionMinutes = 20), activities = board))
        val long = SessionSuggester.suggest(input(profile = profile.copy(sessionMinutes = 90), activities = board))
        assertTrue(short.estimatedMinutes <= 20, "was ${short.estimatedMinutes}")
        assertTrue(short.routine.items.sumOf { it.sets } < long.routine.items.sumOf { it.sets })
        assertTrue(SuggestionReason.SHORTENED_TO_TIME in short.reasons)
    }

    @Test
    fun equipmentIsRespected() {
        val owned = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.PULL_UP_BAR)
        val s = SessionSuggester.suggest(input(profile = profile.copy(equipment = owned)))
        assertTrue(s.routine.items.isNotEmpty())
        // No hangboard or block: no finger session is suggested.
        assertEquals(SuggestionFocus.PULL_PUSH, s.focus)
        s.routine.items.forEach { item ->
            assertTrue(catalog[item.slug]!!.equipment.all { EquipmentV2.satisfied(it, owned) }, item.slug)
        }
    }

    @Test
    fun lowEnergyShortensTheSession() {
        val tired = SessionSuggester.suggest(input(checkin = Checkin(monday.toString(), 0, sleep = 2, energy = 3)))
        assertTrue(SuggestionReason.LOW_ENERGY in tired.reasons)
        assertTrue(tired.estimatedMinutes <= 36, "was ${tired.estimatedMinutes}")
    }
}
