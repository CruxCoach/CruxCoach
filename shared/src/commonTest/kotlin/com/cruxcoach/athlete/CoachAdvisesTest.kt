package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The coach advises, the athlete decides (owner 2026-11-22): a kind held back for the fingers comes back with "anyway"
 * ([SessionSuggestion.heldBack], [SuggestionInput.insist]) – chosen today or planned for today –, a hard reason stays
 * one, and recovery after climbing follows the athlete's level ([DayPlans.tier]).
 */
class CoachAdvisesTest {

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

    private val yesterday get() = monday.minus(DatePeriod(days = 1))

    private fun climbed(day: LocalDate, intensity: ClimbIntensity?) =
        mapOf(day to DayActivity(day.toString(), climbingMinutes = 60, climbingEfforts = 30, climbIntensity = intensity))

    private val gym = profile.copy(equipment = home + EquipmentV2.BOARD + EquipmentV2.WALL)
    private val tiredFingers = Checkin(monday.toString(), 0, fingers = 1)

    // ── A kind chosen today ──────────────────────────────────────────

    @Test
    fun aChosenFingerDayHeldBackForTiredFingersComesBackWithAnyway() {
        val held = SessionSuggester.suggest(input(checkin = tiredFingers).copy(wish = SuggestionFocus.FINGER_STRENGTH))
        assertEquals(SuggestionFocus.PULL_PUSH, held.focus)
        assertEquals(SuggestionFocus.FINGER_STRENGTH, held.heldBack)
        val anyway = SessionSuggester.suggest(input(checkin = tiredFingers).copy(wish = SuggestionFocus.FINGER_STRENGTH, insist = true))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, anyway.focus, "${anyway.reasons}")
        assertTrue(anyway.routine.items.any { !it.warmup && catalog[it.slug]?.category == ExerciseCategoryV2.FINGER }, "${slugs(anyway)}")
        // Decided: nothing is offered again, and the card still says what the coach sees.
        assertEquals(null, anyway.heldBack)
        assertTrue(SuggestionReason.CHOSEN_TODAY in anyway.reasons && SuggestionReason.FINGERS_TIRED in anyway.reasons, "${anyway.reasons}")
        // Without anything held back "anyway" changes nothing.
        val fresh = SessionSuggester.suggest(input().copy(wish = SuggestionFocus.FINGER_STRENGTH))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, fresh.focus)
        assertEquals(null, fresh.heldBack)
        assertEquals(slugs(fresh), slugs(SessionSuggester.suggest(input().copy(wish = SuggestionFocus.FINGER_STRENGTH, insist = true))))
    }

    @Test
    fun aChosenFingerDayWhileRecoveringFromClimbingComesBackWithAnyway() {
        val afterHard = climbed(yesterday, ClimbIntensity.HARD)
        val held = SessionSuggester.suggest(input(activities = afterHard).copy(wish = SuggestionFocus.FINGER_STRENGTH))
        assertEquals(SuggestionFocus.PULL_PUSH, held.focus)
        assertEquals(SuggestionReason.RECOVERY_AFTER_HARD, held.reasons.first(), "${held.reasons}")
        assertEquals(SuggestionFocus.FINGER_STRENGTH, held.heldBack)
        val anyway = SessionSuggester.suggest(input(activities = afterHard).copy(wish = SuggestionFocus.FINGER_STRENGTH, insist = true))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, anyway.focus, "${anyway.reasons}")
        assertTrue(SuggestionReason.RECOVERY_AFTER_HARD in anyway.reasons, "${anyway.reasons}")
    }

    @Test
    fun aChosenBoardDayHeldBackComesBackWithAnyway() {
        // Tired fingers turn the board day into pull and push …
        val held = SessionSuggester.suggest(input(profile = gym, checkin = tiredFingers).copy(wish = SuggestionFocus.BOARD_DAY))
        assertEquals(SuggestionFocus.PULL_PUSH, held.focus)
        assertEquals(SuggestionReason.FINGERS_TIRED, held.reasons.first(), "${held.reasons}")
        assertEquals(SuggestionFocus.BOARD_DAY, held.heldBack)
        val anyway = SessionSuggester.suggest(input(profile = gym, checkin = tiredFingers).copy(wish = SuggestionFocus.BOARD_DAY, insist = true))
        assertEquals(SuggestionFocus.BOARD_DAY, anyway.focus, "${anyway.reasons}")
        assertEquals(null, anyway.heldBack)
        assertTrue(SuggestionReason.FINGERS_TIRED in anyway.reasons, "${anyway.reasons}")
        // … recovery after a limit day makes it a light session, and "anyway" the session as chosen.
        val afterLimit = climbed(yesterday, ClimbIntensity.LIMIT)
        val light = SessionSuggester.suggest(input(profile = gym, activities = afterLimit).copy(wish = SuggestionFocus.BOARD_DAY))
        assertEquals(SuggestionFocus.BOARD_DAY, light.focus)
        assertEquals(GeneratorType.VOLUME, light.boardPlan?.type)
        assertEquals(SuggestionFocus.BOARD_DAY, light.heldBack)
        val full = SessionSuggester.suggest(input(profile = gym, activities = afterLimit).copy(wish = SuggestionFocus.BOARD_DAY, insist = true))
        assertEquals(SuggestionFocus.BOARD_DAY, full.focus)
        assertEquals(null, full.heldBack)
        assertTrue(SuggestionReason.BOARD_LIGHT !in full.reasons && SuggestionReason.RECOVERY_AFTER_LIMIT in full.reasons, "${full.reasons}")
    }

    @Test
    fun aHardReasonIsNoAdviceAndAnywayDoesNotOverruleIt() {
        // Sore skin keeps the day off the wall: nothing to insist on.
        val soreSkin = Checkin(monday.toString(), 0, skin = 1)
        listOf(false, true).forEach { insist ->
            val s = SessionSuggester.suggest(input(profile = gym, checkin = soreSkin).copy(wish = SuggestionFocus.BOARD_DAY, insist = insist))
            assertEquals(SuggestionFocus.PULL_PUSH, s.focus, "insist=$insist ${s.reasons}")
            assertEquals(null, s.heldBack, "insist=$insist")
        }
        // An injury that pauses climbing: the injury-safe session, whatever was chosen.
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, climbingPaused = true, startedOn = "2026-10-01")
        listOf(SuggestionFocus.BOARD_DAY, SuggestionFocus.FINGER_STRENGTH).forEach { wish ->
            val s = SessionSuggester.suggest(input(profile = gym, injuries = listOf(injury)).copy(wish = wish, insist = true))
            assertEquals(SuggestionFocus.INJURY_SAFE, s.focus, "$wish ${s.reasons}")
            assertEquals(null, s.heldBack, "$wish")
        }
        // Ill: rest.
        val ill = SessionSuggester.suggest(input(checkin = Checkin(monday.toString(), 0, sick = true)).copy(wish = SuggestionFocus.FINGER_STRENGTH, insist = true))
        assertEquals(SuggestionFocus.REST, ill.focus)
        assertEquals(null, ill.heldBack)
    }

    // ── A board day planned for today ────────────────────────────────

    @Test
    fun aPlannedBoardDayHeldBackForTiredFingersComesBackWithAnyway() {
        // "Heute klettere ich" plans the day; before, tired fingers replaced it and the card had no way back.
        val planned = gym.copy(weekPlan = mapOf(1 to PLAN_BOARD))
        val held = SessionSuggester.suggest(input(profile = planned, checkin = tiredFingers))
        assertTrue(held.focus != SuggestionFocus.BOARD_DAY, "${held.focus}")
        assertTrue(SuggestionReason.BOARD_SKIPPED_TODAY in held.reasons && SuggestionReason.FINGERS_TIRED in held.reasons, "${held.reasons}")
        assertEquals(SuggestionFocus.BOARD_DAY, held.heldBack)
        val anyway = SessionSuggester.suggest(input(profile = planned, checkin = tiredFingers).copy(insist = true))
        assertEquals(SuggestionFocus.BOARD_DAY, anyway.focus, "${anyway.reasons}")
        assertEquals(SuggestionReason.WEEK_PLAN_BOARD, anyway.reasons.first(), "${anyway.reasons}")
        assertTrue(SuggestionReason.FINGERS_TIRED in anyway.reasons && SuggestionReason.BOARD_SKIPPED_TODAY !in anyway.reasons, "${anyway.reasons}")
        assertEquals(null, anyway.heldBack)
        // The same on a usual climbing day that has no entry in the plan.
        val usual = SessionSuggester.suggest(input(profile = gym, checkin = tiredFingers).copy(climbingToday = true))
        assertTrue(usual.focus != SuggestionFocus.BOARD_DAY, "${usual.focus}")
        assertEquals(SuggestionFocus.BOARD_DAY, usual.heldBack)
        val usualAnyway = SessionSuggester.suggest(input(profile = gym, checkin = tiredFingers).copy(climbingToday = true, insist = true))
        assertEquals(SuggestionFocus.BOARD_DAY, usualAnyway.focus, "${usualAnyway.reasons}")
        assertEquals(SuggestionReason.CLIMBING_DAY, usualAnyway.reasons.first(), "${usualAnyway.reasons}")
    }

    @Test
    fun aPlannedBoardDayWhileRecoveringIsLightAndAnywayThePlannedOne() {
        val planned = gym.copy(weekPlan = mapOf(1 to PLAN_BOARD))
        val afterLimit = climbed(yesterday, ClimbIntensity.LIMIT)
        val light = SessionSuggester.suggest(input(profile = planned, activities = afterLimit))
        assertEquals(SuggestionFocus.BOARD_DAY, light.focus)
        assertEquals(GeneratorType.VOLUME, light.boardPlan?.type)
        assertEquals(SuggestionFocus.BOARD_DAY, light.heldBack)
        val full = SessionSuggester.suggest(input(profile = planned, activities = afterLimit).copy(insist = true))
        assertEquals(SuggestionFocus.BOARD_DAY, full.focus)
        assertTrue(SuggestionReason.BOARD_LIGHT !in full.reasons && SuggestionReason.RECOVERY_AFTER_LIMIT in full.reasons, "${full.reasons}")
        assertEquals(null, full.heldBack)
        // Nothing in the way: the planned board day as it is, nothing held back.
        val fresh = SessionSuggester.suggest(input(profile = planned))
        assertEquals(SuggestionFocus.BOARD_DAY, fresh.focus)
        assertEquals(null, fresh.heldBack)
    }

    @Test
    fun aPlannedBoardDayRuledOutByAHardReasonStaysOut() {
        val planned = gym.copy(weekPlan = mapOf(1 to PLAN_BOARD))
        // Sore skin: off the wall, with or without "anyway", and no button for it.
        listOf(false, true).forEach { insist ->
            val s = SessionSuggester.suggest(input(profile = planned, checkin = Checkin(monday.toString(), 0, skin = 1)).copy(insist = insist))
            assertTrue(s.focus != SuggestionFocus.BOARD_DAY && SuggestionReason.BOARD_SKIPPED_TODAY in s.reasons, "insist=$insist ${s.focus} ${s.reasons}")
            assertEquals(null, s.heldBack, "insist=$insist")
        }
        // Climbing paused by an injury, tired fingers on top: the injury decides.
        val injury = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, climbingPaused = true, startedOn = "2026-10-01")
        val paused = SessionSuggester.suggest(input(profile = planned, injuries = listOf(injury), checkin = tiredFingers).copy(insist = true))
        assertEquals(SuggestionFocus.INJURY_SAFE, paused.focus, "${paused.reasons}")
        assertEquals(null, paused.heldBack)
    }

    // ── Recovery after climbing by level ─────────────────────────────

    /** Advanced: more than five years, an adult, four days a week, 7A. */
    private val pro = CoachProfile(experience = ExperienceBand.OVER_5, ageBand = AgeBand.Y18_39, trainingDaysPerWeek = 4, currentGrade = "7A")

    private fun dayAfter(coach: CoachProfile, intensity: ClimbIntensity, history: Map<LocalDate, DayActivity> = emptyMap()) =
        SessionSuggester.suggest(input(profile = profile.copy(coach = coach), activities = history + climbed(yesterday, intensity)))

    private fun heldBackToday(s: SessionSuggestion) = s.focus != SuggestionFocus.FINGER_STRENGTH

    @Test
    fun aSeasonedClimberIsHeldBackTheSameDayOnlyAndAfterALimitDayTheNextToo() {
        val standard = pro.copy(currentGrade = "6C+")
        assertTrue(heldBackToday(dayAfter(standard, ClimbIntensity.HARD)) && heldBackToday(dayAfter(standard, ClimbIntensity.VOLUME)))
        assertTrue(SuggestionReason.RECOVERY_AFTER_HARD in dayAfter(standard, ClimbIntensity.HARD).reasons)
        // Advanced: the day after a hard or a volume day the fingers are free again, after a limit day not yet.
        assertEquals(SuggestionFocus.FINGER_STRENGTH, dayAfter(pro, ClimbIntensity.HARD).focus)
        assertEquals(SuggestionFocus.FINGER_STRENGTH, dayAfter(pro, ClimbIntensity.VOLUME).focus)
        val afterLimit = dayAfter(pro, ClimbIntensity.LIMIT)
        assertTrue(heldBackToday(afterLimit) && SuggestionReason.RECOVERY_AFTER_LIMIT in afterLimit.reasons, "${afterLimit.focus} ${afterLimit.reasons}")
        // Elite: a limit day holds back the same day only.
        val elite = pro.copy(currentGrade = "8A", trainingDaysPerWeek = 5)
        assertEquals(SuggestionFocus.FINGER_STRENGTH, dayAfter(elite, ClimbIntensity.LIMIT).focus)
        // The same day everyone is held back.
        val sameDay = SessionSuggester.suggest(input(profile = profile.copy(coach = elite), activities = climbed(monday, ClimbIntensity.HARD))
            .copy(wish = SuggestionFocus.FINGER_STRENGTH))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, sameDay.heldBack, "${sameDay.focus} ${sameDay.reasons}")
    }

    @Test
    fun from40OnRecoveryIsAsForEveryoneAndTheCardSaysSo() {
        val forty = dayAfter(pro.copy(ageBand = AgeBand.Y40_54), ClimbIntensity.HARD)
        assertTrue(heldBackToday(forty), "${forty.focus}")
        assertTrue(SuggestionReason.RECOVERY_AFTER_HARD in forty.reasons && SuggestionReason.GUARDRAIL_MASTERS in forty.reasons, "${forty.reasons}")
    }

    @Test
    fun theLevelCountsWhatTheLastFourWeeksShow() {
        // Three days in the setup, four a week trained: 15 trainings and yesterday's climbing in the last four weeks.
        val threeSet = pro.copy(trainingDaysPerWeek = 3)
        fun trained(days: IntRange) = days.associate { n -> monday.minus(DatePeriod(days = n)).let { it to DayActivity(it.toString(), workoutMinutes = 45) } }
        assertTrue(heldBackToday(dayAfter(threeSet, ClimbIntensity.HARD)))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, dayAfter(threeSet, ClimbIntensity.HARD, trained(2..16)).focus)
        // One training fewer is three a week: as everyone.
        assertTrue(heldBackToday(dayAfter(threeSet, ClimbIntensity.HARD, trained(2..15))))
    }
}
