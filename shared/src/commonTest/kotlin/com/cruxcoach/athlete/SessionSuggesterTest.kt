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
        assertTrue(s.routine.items.first().warmup)
    }

    @Test
    fun warmupIsALighterVersionOfWhatFollows() {
        // Owner 2026-10-09: the hangboard ramp only before hangboard work; before block lifts the
        // block itself ramps up (added when the training starts), not a hangboard.
        val onlyHangboard = home - EquipmentV2.PICKUP_BLOCK
        val hang = SessionSuggester.suggest(input(profile = profile.copy(equipment = onlyHangboard)))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, hang.focus)
        assertEquals("warmup.finger_ramp", slugs(hang).first())
        val onlyBlock = home - EquipmentV2.HANGBOARD
        val block = SessionSuggester.suggest(input(profile = profile.copy(equipment = onlyBlock)))
        assertTrue(slugs(block).any { EquipmentV2.PICKUP_BLOCK in catalog[it]!!.equipment }, "${slugs(block)}")
        assertTrue("warmup.finger_ramp" !in slugs(block))
        assertTrue(block.routine.items.first().warmup)
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

    // ── FEAT-071: load model, coach profile, blocks, learning ───────

    private val yesterday get() = monday.minus(DatePeriod(days = 1))
    private val big = profile.copy(sessionMinutes = 120)

    private fun climbed(day: LocalDate, intensity: ClimbIntensity?) =
        mapOf(day to DayActivity(day.toString(), climbingMinutes = 60, climbingEfforts = 30, climbIntensity = intensity))

    private fun mainSets(s: SessionSuggestion) = s.routine.items.filter { !it.warmup }.sumOf { it.sets }

    @Test
    fun limitSessionYesterdayBlocksMaxFingerWork() {
        val s = SessionSuggester.suggest(input(activities = climbed(yesterday, ClimbIntensity.LIMIT)))
        assertTrue(s.focus == SuggestionFocus.PULL_PUSH || s.focus == SuggestionFocus.LEGS_CORE, "${s.focus}")
        assertTrue(SuggestionReason.RECOVERY_AFTER_LIMIT in s.reasons, "${s.reasons}")
        assertTrue(s.basedOn.any { it is Evidence.Climbing && it.daysAgo == 1 && it.intensity == ClimbIntensity.LIMIT }, "${s.basedOn}")
    }

    @Test
    fun lightClimbingYesterdayKeepsTheFingerDay() {
        val s = SessionSuggester.suggest(input(activities = climbed(yesterday, ClimbIntensity.LIGHT)))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, s.focus, "${s.reasons}")
    }

    @Test
    fun volumeClimbingFollowsTheRecoveryHours() {
        val blocked = ClimbingLoad.recoveryHours(ClimbIntensity.VOLUME, null) > 24
        val s = SessionSuggester.suggest(input(activities = climbed(yesterday, ClimbIntensity.VOLUME)))
        assertEquals(blocked, s.focus != SuggestionFocus.FINGER_STRENGTH, "${s.focus} ${s.reasons}")
        if (blocked) assertTrue(SuggestionReason.RECOVERY_AFTER_VOLUME in s.reasons)
    }

    @Test
    fun mastersGetAnExtraDayAfterFingerSets() {
        val twoDaysAgo = monday.minus(DatePeriod(days = 2))
        val hang = ExerciseSet("x", "w", "finger.max_hang", 0, 0, durationS = 10.0, completedAt = 1)
        val young = SessionSuggester.suggest(input().copy(recentSets = listOf(twoDaysAgo to hang)))
        val masters = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(ageBand = AgeBand.Y55_PLUS)))
            .copy(recentSets = listOf(twoDaysAgo to hang)))
        assertEquals(SuggestionFocus.FINGER_STRENGTH, young.focus)
        assertTrue(masters.focus != SuggestionFocus.FINGER_STRENGTH, "${masters.focus}")
        assertTrue(SuggestionReason.GUARDRAIL_MASTERS in masters.reasons, "${masters.reasons}")
    }

    @Test
    fun mastersRecoveryFromClimbingFollowsTheLoadModel() {
        val twoDaysAgo = monday.minus(DatePeriod(days = 2))
        val youngBlocked = ClimbingLoad.recoveryHours(ClimbIntensity.LIMIT, null) > 48
        val oldBlocked = ClimbingLoad.recoveryHours(ClimbIntensity.LIMIT, AgeBand.Y55_PLUS) > 48
        val young = SessionSuggester.suggest(input(activities = climbed(twoDaysAgo, ClimbIntensity.LIMIT)))
        val old = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(ageBand = AgeBand.Y55_PLUS)),
            activities = climbed(twoDaysAgo, ClimbIntensity.LIMIT)))
        assertEquals(youngBlocked, young.focus != SuggestionFocus.FINGER_STRENGTH)
        assertEquals(oldBlocked, old.focus != SuggestionFocus.FINGER_STRENGTH)
        if (oldBlocked && !youngBlocked) assertTrue(SuggestionReason.GUARDRAIL_MASTERS in old.reasons)
    }

    @Test
    fun risingFingerLoadMeansNoMaxFingerWork() {
        val status = LoadStatus(mapOf(LoadStructure.FINGER to StructureLoad(30.0, 15.0, 2.0, LoadTrend.SPIKE)), emptyMap())
        val s = SessionSuggester.suggest(input().copy(loadStatus = status))
        assertTrue(s.focus != SuggestionFocus.FINGER_STRENGTH)
        assertTrue(SuggestionReason.FINGER_LOAD_RISING in s.reasons)
        assertTrue(s.basedOn.contains(Evidence.LoadTrendNote(LoadStructure.FINGER, LoadTrend.SPIKE)))
        assertTrue(slugs(s).none { LoadDomain.FINGER in catalog[it]!!.domains })
    }

    @Test
    fun youngClimbersGetNoMaxHangsOrOneArmWork() {
        val s = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(ageBand = AgeBand.UNDER_16))))
        assertTrue("finger.max_hang" !in slugs(s) && "finger.one_arm_pickup" !in slugs(s), "${slugs(s)}")
        assertEquals(SuggestionReason.GUARDRAIL_YOUTH, s.reasons.first(), "${s.reasons}")
        assertTrue(s.basedOn.contains(Evidence.Guardrail(GuardrailKind.YOUTH)))
    }

    @Test
    fun secondYearClimbersAreGuardedUntilAValueExists() {
        val coach = CoachProfile(experience = ExperienceBand.Y1_2)
        val guarded = SessionSuggester.suggest(input(profile = profile.copy(coach = coach)))
        assertTrue(SuggestionReason.GUARDRAIL_NOVICE in guarded.reasons)
        assertTrue("finger.max_hang" !in slugs(guarded))
        val known = SessionSuggester.suggest(input(profile = profile.copy(coach = coach)).copy(benchmarkSlugs = setOf("finger.max_hang")))
        assertTrue(SuggestionReason.GUARDRAIL_NOVICE !in known.reasons)
        assertEquals(SuggestionFocus.FINGER_STRENGTH, known.focus)
    }

    @Test
    fun fingerPreferencePicksTheFamily() {
        fun finger(pref: FingerPreference) = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(fingerPreference = pref))))
        assertTrue("finger.one_arm_pickup" in slugs(finger(FingerPreference.PICKUP)))
        assertTrue("finger.one_arm_pickup" in slugs(finger(FingerPreference.ONE_ARM)))
        assertTrue("finger.max_hang" in slugs(finger(FingerPreference.HANGBOARD)))
        val none = finger(FingerPreference.NONE)
        assertTrue(none.focus != SuggestionFocus.FINGER_STRENGTH)
        assertTrue(SuggestionReason.FINGER_PREFERENCE_NONE in none.reasons)
        assertTrue(none.routine.items.none { !it.warmup && catalog[it.slug]!!.category == ExerciseCategoryV2.FINGER })
    }

    @Test
    fun focusAreasAddTheirSlots() {
        val mobility = SessionSuggester.suggest(input(profile = big.copy(coach = CoachProfile(focus = setOf(FocusArea.MOBILITY)))))
        assertTrue(slugs(mobility).any { catalog[it]!!.category == ExerciseCategoryV2.MOBILITY }, "${slugs(mobility)}")
        assertTrue(SuggestionReason.FOCUS_AREAS in mobility.reasons)
        val prevention = SessionSuggester.suggest(input(profile = big.copy(coach = CoachProfile(focus = setOf(FocusArea.PREVENTION)))))
        assertTrue(slugs(prevention).count { catalog[it]!!.category == ExerciseCategoryV2.ANTAGONIST } >= 2, "${slugs(prevention)}")
    }

    @Test
    fun excludedExercisesAreNeverSuggested() {
        val excluded = setOf("core.dead_bug", "core.hollow_hold")
        repeat(5) { variant ->
            val s = SessionSuggester.suggest(input(profile = profile.copy(excludedExercises = excluded), variant = variant))
            assertTrue(slugs(s).none { it in excluded }, "${slugs(s)}")
        }
    }

    @Test
    fun learnedPreferencesShapeThePicks() {
        repeat(4) { variant ->
            val s = SessionSuggester.suggest(input(variant = variant).copy(affinity = mapOf("core.hollow_hold" to 3.0, "core.dead_bug" to -3.0)))
            assertTrue("core.hollow_hold" in slugs(s) && "core.dead_bug" !in slugs(s), "${slugs(s)}")
            assertTrue(SuggestionReason.PREFERENCES_LEARNED in s.reasons)
        }
    }

    @Test
    fun deloadAndTaperWeeksTrimVolumeNotExercises() {
        fun with(phase: BlockPhase) = SessionSuggester.suggest(input(profile = big).copy(block = BlockState(phase, 1, 1, null)))
        val build = with(BlockPhase.BUILD)
        val deload = with(BlockPhase.DELOAD)
        val taper = with(BlockPhase.TAPER)
        assertTrue(mainSets(deload) < mainSets(build), "${mainSets(deload)} vs ${mainSets(build)}")
        assertTrue(mainSets(taper) < mainSets(build))
        assertEquals(slugs(build), slugs(taper))
        assertTrue(SuggestionReason.BLOCK_DELOAD in deload.reasons && SuggestionReason.BLOCK_TAPER in taper.reasons)
    }

    @Test
    fun eventDayIsForArrivingFresh() {
        val s = SessionSuggester.suggest(input().copy(block = BlockState(BlockPhase.EVENT, 1, 1, 0)))
        assertEquals(SuggestionFocus.MOBILITY_RECOVERY, s.focus)
        assertEquals(SuggestionReason.BLOCK_EVENT, s.reasons.first())
        assertTrue(slugs(s).all { catalog[it]!!.category == ExerciseCategoryV2.MOBILITY })
    }

    @Test
    fun afterClimbingTodayOnlyAShortAddOn() {
        val s = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(addOnAfterClimbing = true)),
            activities = climbed(monday, ClimbIntensity.HARD)).copy(climbingToday = true))
        assertTrue(s.addOn)
        assertEquals(SuggestionReason.CLIMBING_DAY_ADDON, s.reasons.first())
        assertTrue(s.estimatedMinutes <= 15, "was ${s.estimatedMinutes}")
        assertTrue(slugs(s).all { catalog[it]!!.category in setOf(ExerciseCategoryV2.ANTAGONIST, ExerciseCategoryV2.CORE) }, "${slugs(s)}")
        val declined = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(addOnAfterClimbing = false)),
            activities = climbed(monday, ClimbIntensity.HARD)).copy(climbingToday = true))
        assertTrue(!declined.addOn)
    }

    @Test
    fun aUsualClimbingDayGetsTheBoardWarmUpAndAPlan() {
        val s = SessionSuggester.suggest(input().copy(climbingToday = true))
        assertEquals(SuggestionFocus.BOARD_DAY, s.focus)
        assertEquals(SuggestionReason.CLIMBING_DAY, s.reasons.first())
        assertEquals(com.cruxcoach.domain.playlist.GeneratorType.LIMIT, s.boardPlan?.type)
    }

    @Test
    fun boardPlanFollowsBlockAndFocus() {
        val boardDay = profile.copy(weekPlan = mapOf(1 to PLAN_BOARD))
        fun plan(p: AthleteProfile, phase: BlockPhase?) =
            SessionSuggester.suggest(input(profile = p).copy(block = phase?.let { BlockState(it, 1, 1, null) })).boardPlan
        assertEquals(com.cruxcoach.domain.playlist.GeneratorType.VOLUME, plan(boardDay, BlockPhase.DELOAD)?.type)
        assertEquals(com.cruxcoach.domain.playlist.GeneratorType.VOLUME, plan(boardDay, BlockPhase.INTRO)?.type)
        assertEquals(40, plan(boardDay, BlockPhase.TAPER)?.minutes)
        assertEquals(com.cruxcoach.domain.playlist.GeneratorType.LIMIT, plan(boardDay, BlockPhase.BUILD)?.type)
        val pe = boardDay.copy(coach = CoachProfile(focus = setOf(FocusArea.POWER_ENDURANCE)))
        assertEquals(com.cruxcoach.domain.playlist.GeneratorType.POWER_ENDURANCE, plan(pe, BlockPhase.BUILD)?.type)
    }

    @Test
    fun confidenceGrowsWithData() {
        assertEquals(Confidence.LOW, SessionSuggester.suggest(input()).confidence)
        val rich = input(profile = profile.copy(coach = CoachProfile(setupState = SetupState.DONE)))
            .copy(logbookSends = 40, benchmarkSlugs = setOf("finger.max_hang", "pull.pull_up"), historyWeeks = 8)
        assertEquals(Confidence.HIGH, SessionSuggester.suggest(rich).confidence)
    }

    @Test
    fun performanceValuesAppearInTheEvidence() {
        val value = Benchmark(id = "b", exerciseSlug = "finger.max_hang", loadKg = 10.0, durationS = 10.0, measuredAt = 1)
        val s = SessionSuggester.suggest(input(profile = profile.copy(coach = CoachProfile(fingerPreference = FingerPreference.HANGBOARD)))
            .copy(benchmarks = listOf(value), benchmarkSlugs = setOf("finger.max_hang")))
        assertTrue(s.basedOn.contains(Evidence.PerformanceValue(value)), "${s.basedOn}")
    }

    @Test
    fun noTimeTodayShortensTheBudget() {
        val s = SessionSuggester.suggest(input(profile = profile.copy(sessionMinutes = 60)).copy(budgetFactor = 0.5))
        assertTrue(SuggestionReason.NO_TIME_TODAY in s.reasons)
        assertTrue(s.estimatedMinutes <= 30, "was ${s.estimatedMinutes}")
    }

    @Test
    fun plannedRoutineFollowsTheBlock() {
        val mine = Routine("r1", "Mine", items = listOf(RoutineItem("pull.pull_up", sets = 5), RoutineItem("core.dead_bug", sets = 4)))
        val p = profile.copy(weekPlan = mapOf(1 to "r1"))
        val build = SessionSuggester.suggest(input(profile = p, routines = listOf(mine)))
        val deload = SessionSuggester.suggest(input(profile = p, routines = listOf(mine)).copy(block = BlockState(BlockPhase.DELOAD, 1, 1, null)))
        assertEquals(SuggestionFocus.PLANNED, deload.focus)
        assertTrue(mainSets(deload) < mainSets(build))
    }

    @Test
    fun withoutEquipmentSetUpOnlyExercisesWithoutGearAreSuggested() {
        // Render test 2026-10-10: a new athlete got a hangboard and dumbbell rows before saying what they own.
        val s = SessionSuggester.suggest(input(profile = AthleteProfile(sessionMinutes = 60)))
        val gear = s.routine.items.flatMap { catalog[it.slug]!!.equipment }.toSet() - EquipmentV2.NONE - EquipmentV2.MAT
        assertTrue(gear.isEmpty(), "${slugs(s)} need $gear")
        assertTrue(s.routine.items.isNotEmpty())
    }
}
