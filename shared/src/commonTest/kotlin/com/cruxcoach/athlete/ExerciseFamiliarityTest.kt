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
        // A beginner's antagonist slots have only Common and Special exercises: filled, Common first, and marked new.
        val i = input(catalog = onlyUnusualAntagonists)
        val s = SessionSuggester.suggest(i)
        val antagonists = s.routine.items.filter { !it.warmup }.map { onlyUnusualAntagonists.fallbackFor(it.slug) }
            .filter { it.category == ExerciseCategoryV2.ANTAGONIST }
        assertEquals(listOf("antagonist.scapular_push_up", "antagonist.wall_lower_trap_raise"), antagonists.map { it.slug })
        assertTrue(antagonists.all { SessionSuggester.isNewTo(i, it.slug) })
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
        val base = input(coach = intermediate)
        val first = SessionSuggester.suggest(base)
        val new = newOnes(base, first).firstOrNull() ?: return
        val again = SessionSuggester.suggest(input(coach = intermediate, unfamiliar = setOf(new.slug)))
        assertFalse(new.slug in again.routine.items.map { it.slug }, "${new.slug} came back: ${again.routine.items.map { it.slug }}")
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
