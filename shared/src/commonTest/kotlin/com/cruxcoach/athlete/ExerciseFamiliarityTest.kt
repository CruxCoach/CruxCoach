package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Well-known exercises first (issue #68): levels, known exercises, the new-exercise limit, the preference and "Don't know it". */
class ExerciseFamiliarityTest {

    private fun ex(
        slug: String, category: ExerciseCategoryV2, familiarity: Familiarity, difficulty: Int = 1,
        kind: ExerciseKind = ExerciseKind.REPS, tags: List<String> = emptyList(),
    ) = ExerciseDefinition(slug, category, kind, LoadMode.BODYWEIGHT, equipment = listOf(EquipmentV2.NONE),
        difficulty = difficulty, familiarity = familiarity, tags = tags,
        defaults = Prescription(sets = 3, repsMin = 6, repsMax = 10, durationS = 20, restS = 90), i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP, Familiarity.BASIC, tags = listOf("warmup")),
        ex("warmup.inchworm", ExerciseCategoryV2.WARMUP, Familiarity.COMMON, tags = listOf("warmup")),
        ex("pull.table_row", ExerciseCategoryV2.PULL, Familiarity.BASIC),
        ex("pull.door_row", ExerciseCategoryV2.PULL, Familiarity.BASIC),
        ex("pull.scap_pull", ExerciseCategoryV2.PULL, Familiarity.COMMON),
        ex("pull.recruitment_pull", ExerciseCategoryV2.PULL, Familiarity.SPECIAL),
        ex("push.push_up", ExerciseCategoryV2.PUSH, Familiarity.BASIC),
        ex("push.pike_push_up", ExerciseCategoryV2.PUSH, Familiarity.COMMON),
        ex("antagonist.scapular_push_up", ExerciseCategoryV2.ANTAGONIST, Familiarity.COMMON),
        ex("antagonist.wall_lower_trap_raise", ExerciseCategoryV2.ANTAGONIST, Familiarity.SPECIAL),
        ex("antagonist.reverse_fly", ExerciseCategoryV2.ANTAGONIST, Familiarity.BASIC),
        ex("antagonist.wrist_curl", ExerciseCategoryV2.ANTAGONIST, Familiarity.BASIC),
        ex("core.front_plank", ExerciseCategoryV2.CORE, Familiarity.BASIC, kind = ExerciseKind.TIME),
        ex("core.side_plank", ExerciseCategoryV2.CORE, Familiarity.BASIC, kind = ExerciseKind.TIME),
        ex("core.dead_bug", ExerciseCategoryV2.CORE, Familiarity.COMMON),
        ex("core.pallof_press", ExerciseCategoryV2.CORE, Familiarity.SPECIAL),
        ex("legs.air_squat", ExerciseCategoryV2.LEGS, Familiarity.BASIC),
        ex("legs.cossack_squat", ExerciseCategoryV2.LEGS, Familiarity.SPECIAL),
        ex("mobility.child_pose", ExerciseCategoryV2.MOBILITY, Familiarity.BASIC, kind = ExerciseKind.TIME),
        ex("mobility.open_book", ExerciseCategoryV2.MOBILITY, Familiarity.COMMON, kind = ExerciseKind.TIME),
    ))

    /** Without a floor equipment antagonist that is Basic, as in the real catalogue. */
    private val onlyUnusualAntagonists = ExerciseCatalog(1, catalog.all.filter { it.slug != "antagonist.reverse_fly" && it.slug != "antagonist.wrist_curl" })

    private val floor = setOf(EquipmentV2.NONE, EquipmentV2.MAT)
    private val monday = LocalDate.parse("2026-10-05")
    private val intermediate = CoachProfile(experience = ExperienceBand.Y3_5, setupState = SetupState.DONE)
    private val advanced = CoachProfile(experience = ExperienceBand.OVER_5, setupState = SetupState.DONE)

    private fun input(
        coach: CoachProfile = CoachProfile(),
        catalog: ExerciseCatalog = this.catalog,
        known: Set<String> = emptySet(),
        unfamiliar: Set<String> = emptySet(),
        favorites: Set<String> = emptySet(),
        wish: SuggestionFocus? = SuggestionFocus.PULL_PUSH,
        variant: Int = 0,
    ): SuggestionInput {
        val profile = AthleteProfile(equipment = floor, equipmentConfigured = true, sessionMinutes = 60, coach = coach)
        return SuggestionInput(catalog, profile, monday, ReadinessEvaluator.evaluate(null, emptyList()), emptyList(),
            favorites = favorites, wish = wish, variant = variant, known = known, unfamiliar = unfamiliar)
    }

    private fun main(s: SessionSuggestion) = s.routine.items.filter { !it.warmup }.map { catalog.fallbackFor(it.slug) }

    private fun newOnes(input: SuggestionInput, s: SessionSuggestion) = s.routine.items.filter { SessionSuggester.isNewTo(input, it.slug) }

    @Test
    fun aNewAthleteGetsBasicsAndAtMostOneNewExercise() {
        // Nothing known about the athlete: the beginner rules, in every variant of the suggestion.
        repeat(6) { variant ->
            val i = input(variant = variant)
            val s = SessionSuggester.suggest(i)
            assertTrue(main(s).none { it.familiarity == Familiarity.SPECIAL }, "variant $variant: ${main(s).map { it.slug }}")
            assertTrue(newOnes(i, s).size <= 1, "variant $variant: ${newOnes(i, s).map { it.slug }}")
            // Where a Basic exercise fits the slot, it is the pick.
            assertTrue(main(s).map { it.slug }.containsAll(listOf("pull.table_row", "pull.door_row", "push.push_up")),
                "variant $variant: ${main(s).map { it.slug }}")
        }
    }

    @Test
    fun aSlotWithOnlyUnusualExercisesIsFilledAndMarked() {
        // A beginner's antagonist slots have only Common and Special exercises: the first is filled, Common first, and
        // marked new; the repeated one stays out rather than bringing a second new exercise.
        val i = input(catalog = onlyUnusualAntagonists)
        val s = SessionSuggester.suggest(i)
        val antagonists = s.routine.items.filter { !it.warmup }.map { onlyUnusualAntagonists.fallbackFor(it.slug) }
            .filter { it.category == ExerciseCategoryV2.ANTAGONIST }
        assertEquals(listOf("antagonist.scapular_push_up"), antagonists.map { it.slug })
        assertTrue(SessionSuggester.isNewTo(i, antagonists.single().slug))
        assertEquals(1, newOnes(i, s).size)
    }

    @Test
    fun knownExercisesAreNeverNew() {
        val i = input(known = setOf("core.pallof_press", "antagonist.wall_lower_trap_raise"))
        assertFalse(SessionSuggester.isNewTo(i, "core.pallof_press"))
        assertFalse(SessionSuggester.isNewTo(i, "push.push_up"), "Basic is known to everyone")
        assertTrue(SessionSuggester.isNewTo(i, "core.dead_bug"))
        // Favourites and the athlete's own exercises count as known too.
        assertFalse(SessionSuggester.isNewTo(input(favorites = setOf("core.dead_bug")), "core.dead_bug"))
        val custom = catalog.all.first { it.slug == "core.pallof_press" }.copy(slug = "custom.mine", custom = true)
        val withCustom = ExerciseCatalog(1, catalog.all + custom)
        assertFalse(SessionSuggester.isNewTo(input(catalog = withCustom), "custom.mine"))
    }

    @Test
    fun twoTrainingsOrAnOwnValueMakeAnExerciseKnown() {
        fun value(slug: String, source: BenchmarkSource) = Benchmark("b-$slug", slug, source = source, measuredAt = 1)
        val known = ExerciseFamiliarity.known(mapOf("core.dead_bug" to 2L, "core.pallof_press" to 1L, "pull.scap_pull" to 1L),
            listOf(value("antagonist.wall_lower_trap_raise", BenchmarkSource.MANUAL), value("pull.scap_pull", BenchmarkSource.AUTO)))
        // A value the coach learned from the one training is not enough; one the athlete entered is.
        assertEquals(setOf("core.dead_bug", "antagonist.wall_lower_trap_raise"), known)
    }

    @Test
    fun warmUpsAndMobilityCountOnlyWhenSpecial() {
        val i = input()
        assertFalse(SessionSuggester.isNewTo(i, "warmup.inchworm"))
        assertFalse(SessionSuggester.isNewTo(i, "mobility.open_book"))
        assertTrue(SessionSuggester.isNewTo(i, "core.pallof_press"))
    }

    @Test
    fun anIntermediateAthleteGetsAtMostOneNewExercise() {
        repeat(8) { variant ->
            val i = input(coach = intermediate, variant = variant)
            val s = SessionSuggester.suggest(i)
            assertTrue(newOnes(i, s).size <= 1, "variant $variant: ${newOnes(i, s).map { it.slug }}")
            assertTrue(main(s).count { it.familiarity == Familiarity.SPECIAL } <= 1, "variant $variant: ${main(s).map { it.slug }}")
        }
    }

    @Test
    fun openToUnusualExercisesAllowsTwoNewOnesAndBringsSpecialOnes() {
        var special = 0
        repeat(8) { variant ->
            val i = input(coach = intermediate.copy(exerciseChoice = ExerciseChoice.OPEN), variant = variant)
            val s = SessionSuggester.suggest(i)
            assertTrue(newOnes(i, s).size <= 2, "variant $variant: ${newOnes(i, s).map { it.slug }}")
            special += main(s).count { it.familiarity == Familiarity.SPECIAL }
        }
        assertTrue(special >= 4, "Special exercises in 8 suggestions: $special")
    }

    @Test
    fun mostlyClassicsKeepsAnAdvancedAthleteOnTheClassics() {
        repeat(6) { variant ->
            val i = input(coach = advanced.copy(exerciseChoice = ExerciseChoice.CLASSICS), variant = variant)
            val s = SessionSuggester.suggest(i)
            assertTrue("pull.table_row" in main(s).map { it.slug } && "push.push_up" in main(s).map { it.slug },
                "variant $variant: ${main(s).map { it.slug }}")
            assertTrue(newOnes(i, s).size <= 1)
        }
    }

    @Test
    fun anExerciseMarkedUnknownIsNotTheNewPickWhileSomethingElseFits() {
        // Open to unusual exercises: the new ones come in many variants, so their absence below means something.
        val coach = advanced.copy(exerciseChoice = ExerciseChoice.OPEN)
        // The Pallof press; the dead bug stays, so the core slots always have something else.
        val unusualCore = setOf("core.pallof_press")
        // Legs and core: the core slots come while there is still room for new exercises.
        fun legsCore(v: Int, unfamiliar: Set<String> = emptySet()) =
            SessionSuggester.suggest(input(coach = coach, variant = v, wish = SuggestionFocus.LEGS_CORE, unfamiliar = unfamiliar)).routine.items.map { it.slug }
        val before = (0 until 8).flatMap { v -> legsCore(v) }
        assertTrue(before.any { it in unusualCore }, "$before")
        (0 until 8).forEach { v ->
            val again = legsCore(v, unusualCore)
            assertTrue(again.none { it in unusualCore }, "variant $v: $again")
        }
    }

    @Test
    fun groupsFollowExperienceAndThePreference() {
        fun g(coach: CoachProfile, level: Int = 2, value: Boolean = false, grade: Double? = null) =
            ExerciseFamiliarity.group(coach, level, value, grade)
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, g(CoachProfile()))
        assertEquals(ExerciseFamiliarity.Group.INTERMEDIATE, g(CoachProfile(), level = 3, grade = 21.0), "a 6c+ logbook without setup")
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, g(CoachProfile(experience = ExperienceBand.UNDER_1), level = 3))
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, g(CoachProfile(experience = ExperienceBand.Y1_2)))
        assertEquals(ExerciseFamiliarity.Group.INTERMEDIATE, g(CoachProfile(experience = ExperienceBand.Y1_2), value = true))
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, g(intermediate, level = 1))
        assertEquals(ExerciseFamiliarity.Group.INTERMEDIATE, g(intermediate, level = 3))
        assertEquals(ExerciseFamiliarity.Group.ADVANCED, g(intermediate, level = 4))
        assertEquals(ExerciseFamiliarity.Group.ADVANCED, g(advanced, level = 2))
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, g(advanced.copy(exerciseChoice = ExerciseChoice.CLASSICS), level = 5))
        assertEquals(ExerciseFamiliarity.Group.ADVANCED, g(CoachProfile(exerciseChoice = ExerciseChoice.OPEN)))
    }

    @Test
    fun dontKnowItSwapsForSomethingKnown() {
        val items = listOf(RoutineItem("pull.table_row"), RoutineItem("core.pallof_press"), RoutineItem("antagonist.wall_lower_trap_raise"))
        val i = input(coach = intermediate)
        val core = SessionSuggester.familiarSwap(i, items, SuggestionFocus.PULL_PUSH, "core.pallof_press")
        assertTrue(core?.slug in setOf("core.front_plank", "core.side_plank"), "$core")
        // Known counts like Basic: the only antagonist the athlete knows.
        val known = input(coach = intermediate, catalog = onlyUnusualAntagonists, known = setOf("antagonist.scapular_push_up"))
        assertEquals("antagonist.scapular_push_up",
            SessionSuggester.familiarSwap(known, items, SuggestionFocus.PULL_PUSH, "antagonist.wall_lower_trap_raise")?.slug)
        // Nothing Basic or known for the slot: no replacement (the sheet offers the steps instead).
        assertNull(SessionSuggester.familiarSwap(input(coach = intermediate, catalog = onlyUnusualAntagonists), items,
            SuggestionFocus.PULL_PUSH, "antagonist.wall_lower_trap_raise"))
    }

    @Test
    fun aBeginnersSwapListStartsWithKnownExercises() {
        val items = listOf(RoutineItem("pull.table_row"), RoutineItem("push.push_up"), RoutineItem("core.front_plank"))
        // A beginner: Basic and known alternatives first.
        val beginner = SessionSuggester.alternatives(input(), items, SuggestionFocus.PULL_PUSH, "core.front_plank", count = 4).map { it.def }
        val firstNew = beginner.indexOfFirst { it.familiarity != Familiarity.BASIC }
        assertTrue(firstNew < 0 || beginner.drop(firstNew).all { it.familiarity != Familiarity.BASIC }, "${beginner.map { it.slug }}")
        assertEquals("core.side_plank", beginner.first().slug)
        // A known Special exercise counts like a Basic one.
        val known = SessionSuggester.alternatives(input(known = setOf("core.pallof_press")), items, SuggestionFocus.PULL_PUSH, "core.front_plank", count = 4)
            .map { it.def.slug }
        assertTrue(known.indexOf("core.pallof_press") < known.indexOf("core.dead_bug"), "$known")
    }

    @Test
    fun dontKnowItAfterAHandSwapTakesTheSwapBack() {
        val day = "2026-10-05"
        var t = 1_000L
        fun ev(kind: SuggestionEventKind, slugs: List<String>, focus: String? = "PULL_PUSH", workout: String? = null) =
            SuggestionEvent("e$t", day, t++, kind, focus, slugs, null, workout)
        val events = listOf(
            // The athlete swaps the plank for the Pallof press by hand, then says they don't know the Pallof press.
            ev(SuggestionEventKind.SWAPPED, listOf("core.front_plank", "core.pallof_press")),
            ev(SuggestionEventKind.FEEDBACK, listOf("core.pallof_press", "core.side_plank"), focus = PreferenceLearning.DONT_KNOW),
            ev(SuggestionEventKind.STARTED, listOf("pull.table_row", "core.side_plank"), workout = "w"),
        )
        // No "from now on like this?" for that slot: the coach picked the side plank.
        assertEquals(emptyList(), PreferenceLearning.changes(events, "w"))
        // The swap teaches nothing: no point for the Pallof press, none against the plank, and the plank does not rest.
        val today = LocalDate.parse(day)
        val affinity = PreferenceLearning.affinity(events, emptyList(), today)
        assertNull(affinity["core.pallof_press"])
        assertNull(affinity["core.front_plank"])
        assertTrue(PreferenceLearning.resting(events, LocalDate.parse("2026-10-06")).isEmpty())
        // Back to the coach's own pick: the same, and still no question.
        val back = listOf(events[0], ev(SuggestionEventKind.FEEDBACK, listOf("core.pallof_press", "core.front_plank"), focus = PreferenceLearning.DONT_KNOW),
            ev(SuggestionEventKind.STARTED, listOf("pull.table_row", "core.front_plank"), workout = "w2"))
        assertEquals(emptyList(), PreferenceLearning.changes(back, "w2"))
    }

    @Test
    fun aChangeByHandAfterDontKnowItIsAskedAbout() {
        val day = "2026-10-05"
        var t = 1_000L
        fun ev(kind: SuggestionEventKind, slugs: List<String>, focus: String? = "PULL_PUSH", workout: String? = null,
               feedback: SuggestionFeedback? = null, value: Double? = null) =
            SuggestionEvent("e$t", day, t++, kind, focus, slugs, feedback, workout, value)
        fun dontKnow() = ev(SuggestionEventKind.FEEDBACK, listOf("core.front_plank", "core.side_plank"), focus = PreferenceLearning.DONT_KNOW)
        fun started(vararg slugs: String) = ev(SuggestionEventKind.STARTED, listOf("pull.table_row", *slugs), workout = "w")
        val plankTo = { chosen: String? -> listOf(PreferenceLearning.Change("core.front_plank", chosen)) }

        // "Kenne ich nicht" alone asks nothing: the coach picked the side plank.
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(dontKnow(), started("core.side_plank")), "w"))
        // Then the athlete swaps the coach's pick by hand on the card: asked, from the suggested exercise.
        assertEquals(plankTo("core.dead_bug"), PreferenceLearning.changes(listOf(
            dontKnow(), ev(SuggestionEventKind.SWAPPED, listOf("core.side_plank", "core.dead_bug")), started("core.dead_bug"),
        ), "w"))
        // … or leaves it out on the card.
        assertEquals(plankTo(null), PreferenceLearning.changes(listOf(
            dontKnow(),
            ev(SuggestionEventKind.NEXT, listOf("core.side_plank"), feedback = SuggestionFeedback.DISLIKE_EXERCISE, value = PreferenceLearning.LEFT_OUT),
            started(),
        ), "w"))
        // … or swaps it during the training.
        assertEquals(plankTo("core.dead_bug"), PreferenceLearning.changes(listOf(
            dontKnow(), started("core.side_plank"),
            ev(SuggestionEventKind.SWAPPED, listOf("core.side_plank", "core.dead_bug"), focus = null, workout = "w"),
        ), "w"))
        // … or skips or removes it during the training.
        assertEquals(plankTo(null), PreferenceLearning.changes(listOf(
            dontKnow(), started("core.side_plank"), ev(SuggestionEventKind.SKIPPED_SET, listOf("core.side_plank"), focus = null, workout = "w"),
        ), "w"))
        assertEquals(plankTo(null), PreferenceLearning.changes(listOf(
            dontKnow(), started("core.side_plank"),
            ev(SuggestionEventKind.FEEDBACK, listOf("core.side_plank"), focus = PreferenceLearning.REMOVED, workout = "w"),
        ), "w"))
        // A swap back to the suggested exercise cancels out and asks nothing.
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(
            dontKnow(), started("core.side_plank"),
            ev(SuggestionEventKind.SWAPPED, listOf("core.side_plank", "core.dead_bug"), focus = null, workout = "w"),
            ev(SuggestionEventKind.SWAPPED, listOf("core.dead_bug", "core.front_plank"), focus = null, workout = "w"),
        ), "w"))
        // A swap there and back to the coach's pick asks nothing either: the slot holds what the coach chose.
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(
            dontKnow(), started("core.side_plank"),
            ev(SuggestionEventKind.SWAPPED, listOf("core.side_plank", "core.dead_bug"), focus = null, workout = "w"),
            ev(SuggestionEventKind.SWAPPED, listOf("core.dead_bug", "core.side_plank"), focus = null, workout = "w"),
        ), "w"))
        // The same on the card.
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(
            dontKnow(), ev(SuggestionEventKind.SWAPPED, listOf("core.side_plank", "core.dead_bug")),
            ev(SuggestionEventKind.SWAPPED, listOf("core.dead_bug", "core.side_plank")), started("core.side_plank"),
        ), "w"))
    }

    @Test
    fun dontKnowItStaysWithinTheCoachsRules() {
        // An intermediate athlete (level 2 without a grade): nothing above the level, nothing from another pick's chain,
        // nothing made for the comeback plan only.
        val hard = ex("core.ab_wheel_standing", ExerciseCategoryV2.CORE, Familiarity.BASIC, difficulty = 5)
        val knee = ex("core.knee_raise", ExerciseCategoryV2.CORE, Familiarity.BASIC).copy(harder = "core.leg_raise")
        val leg = ex("core.leg_raise", ExerciseCategoryV2.CORE, Familiarity.BASIC, difficulty = 2).copy(easier = "core.knee_raise", harder = "core.toes_to_bar")
        val toes = ex("core.toes_to_bar", ExerciseCategoryV2.CORE, Familiarity.BASIC, difficulty = 2).copy(easier = "core.leg_raise")
        val comeback = ex("core.balance_hold", ExerciseCategoryV2.CORE, Familiarity.BASIC, tags = listOf(ExerciseDefinition.TAG_COMEBACK))
        val cat = ExerciseCatalog(1, listOf(
            ex("core.pallof_press", ExerciseCategoryV2.CORE, Familiarity.SPECIAL), hard, knee, leg, toes, comeback,
            ex("pull.table_row", ExerciseCategoryV2.PULL, Familiarity.BASIC),
        ))
        val i = input(coach = intermediate, catalog = cat)
        // With toes-to-bar in the session: the ab wheel is too hard, the knee and leg raises are in its chain, the balance
        // hold is for the comeback plan only.
        val items = listOf(RoutineItem("pull.table_row"), RoutineItem("core.pallof_press"), RoutineItem("core.toes_to_bar"))
        assertNull(SessionSuggester.familiarSwap(i, items, SuggestionFocus.PULL_PUSH, "core.pallof_press"))
        val withoutChain = listOf(RoutineItem("pull.table_row"), RoutineItem("core.pallof_press"))
        assertTrue(SessionSuggester.familiarSwap(i, withoutChain, SuggestionFocus.PULL_PUSH, "core.pallof_press")?.slug in
            setOf("core.knee_raise", "core.leg_raise", "core.toes_to_bar"))
    }

    @Test
    fun theGroupFollowsTheStepKeptForAKindOfTraining() {
        // Over five years: all levels. "From now on easier" kept for legs and core makes that kind level 1: classics first there.
        val cores = ExerciseCatalog(1, catalog.all.filter { it.category == ExerciseCategoryV2.CORE })
        fun firstCore(kept: Map<String, Int>, v: Int): String {
            val profile = AthleteProfile(equipment = floor, equipmentConfigured = true, sessionMinutes = 60, coach = advanced, keptLevels = kept)
            val i = SuggestionInput(cores, profile, monday, ReadinessEvaluator.evaluate(null, emptyList()), emptyList(),
                wish = SuggestionFocus.LEGS_CORE, variant = v)
            return SessionSuggester.suggest(i).routine.items.first { !it.warmup }.slug
        }
        val open = (0 until 8).map { firstCore(emptyMap(), it) }
        assertTrue(open.any { cores.fallbackFor(it).familiarity != Familiarity.BASIC }, "$open")
        val easier = (0 until 8).map { firstCore(mapOf(SuggestionFocus.LEGS_CORE.name to -1), it) }
        assertTrue(easier.all { cores.fallbackFor(it).familiarity == Familiarity.BASIC }, "$easier")
        // Another kind keeps its own group: pull and push with the legs-and-core step stays open.
        val pullPush = (0 until 8).map { v ->
            val profile = AthleteProfile(equipment = floor, equipmentConfigured = true, sessionMinutes = 60, coach = advanced,
                keptLevels = mapOf(SuggestionFocus.LEGS_CORE.name to -1))
            SessionSuggester.suggest(SuggestionInput(catalog, profile, monday, ReadinessEvaluator.evaluate(null, emptyList()), emptyList(),
                wish = SuggestionFocus.PULL_PUSH, variant = v)).routine.items.filter { !it.warmup }.map { it.slug }
        }.flatten()
        assertTrue(pullPush.any { catalog.fallbackFor(it).familiarity != Familiarity.BASIC }, "$pullPush")
    }

    @Test
    fun dontKnowItIsNoDislike() {
        val day = "2026-10-01"
        val events = listOf(SuggestionEvent("e1", day, 1_000L, SuggestionEventKind.FEEDBACK, PreferenceLearning.DONT_KNOW,
            listOf("core.pallof_press", "core.front_plank")))
        val today = LocalDate.parse("2026-10-05")
        val affinity = PreferenceLearning.affinity(events, emptyList(), today)
        assertNull(affinity["core.pallof_press"])
        assertNull(affinity["core.front_plank"])
        assertTrue(PreferenceLearning.resting(events, today).isEmpty())
        assertEquals(setOf("core.pallof_press"), PreferenceLearning.unfamiliar(events, today))
        // After 14 days it may come again; forgetting the exercise ends it at once.
        assertTrue(PreferenceLearning.unfamiliar(events, LocalDate.parse("2026-10-15")).isEmpty())
        assertTrue(PreferenceLearning.unfamiliar(events, today, mapOf(PreferenceLearning.exerciseKey("core.pallof_press") to 2_000L)).isEmpty())
    }
}
