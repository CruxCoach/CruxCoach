package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Owner 2026-11-21: starter workouts and the fixed climbing warm-up are templates. They are fitted to the place and to
 * what the athlete knows; an exercise that has to go is replaced, not dropped. New athletes stay with the classics.
 */
class TemplateFitTest {

    private fun ex(slug: String, category: ExerciseCategoryV2, equipment: List<EquipmentV2> = listOf(EquipmentV2.NONE),
                   familiarity: Familiarity = Familiarity.BASIC, easier: String? = null, harder: String? = null,
                   kind: ExerciseKind = ExerciseKind.REPS) =
        ExerciseDefinition(slug, category, kind, LoadMode.BODYWEIGHT, false, equipment, familiarity = familiarity,
            easier = easier, harder = harder, defaults = Prescription(sets = 3, repsMin = 8, repsMax = 12, restS = 60),
            tags = if (category == ExerciseCategoryV2.WARMUP) listOf("warmup") else listOf("strength"),
            i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP),
        ex("warmup.band_pull_apart_warmup", ExerciseCategoryV2.WARMUP, listOf(EquipmentV2.BANDS)),
        ex("warmup.finger_tendon_glides", ExerciseCategoryV2.WARMUP, familiarity = Familiarity.SPECIAL),
        ex("warmup.easy_climbing", ExerciseCategoryV2.WARMUP, listOf(EquipmentV2.WALL), kind = ExerciseKind.CLIMB),
        ex("pull.pull_up", ExerciseCategoryV2.PULL, listOf(EquipmentV2.PULL_UP_BAR)),
        ex("pull.inverted_row", ExerciseCategoryV2.PULL, listOf(EquipmentV2.RINGS)),
        ex("pull.table_row", ExerciseCategoryV2.PULL),
        ex("push.push_up", ExerciseCategoryV2.PUSH, harder = "push.diamond_push_up"),
        ex("push.diamond_push_up", ExerciseCategoryV2.PUSH, easier = "push.push_up"),
        ex("antagonist.face_pull", ExerciseCategoryV2.ANTAGONIST, listOf(EquipmentV2.BANDS)),
        ex("antagonist.prone_y_raise", ExerciseCategoryV2.ANTAGONIST),
        ex("antagonist.pallof_press", ExerciseCategoryV2.ANTAGONIST, familiarity = Familiarity.SPECIAL),
        ex("core.copenhagen", ExerciseCategoryV2.CORE, familiarity = Familiarity.SPECIAL),
        ex("core.nordic", ExerciseCategoryV2.CORE, familiarity = Familiarity.COMMON),
        ex("core.plank", ExerciseCategoryV2.CORE),
        ex("core.leg_raise", ExerciseCategoryV2.CORE),
    ))

    private val bar = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.PULL_UP_BAR)
    private val today = LocalDate.parse("2026-11-23")

    private fun input(equipment: Set<EquipmentV2>? = bar, starter: Boolean = false, kept: List<String> = emptyList(),
                      coach: CoachProfile = CoachProfile(experience = ExperienceBand.OVER_5, currentGrade = "7A"),
                      resting: Set<String> = emptySet()) =
        SuggestionInput(catalog, AthleteProfile(equipment = equipment ?: setOf(EquipmentV2.NONE, EquipmentV2.MAT),
            equipmentConfigured = equipment != null, coach = coach), today, ReadinessEvaluator.evaluate(null, emptyList()), emptyList(),
            kept = kept, resting = resting, starter = starter)

    private fun fit(input: SuggestionInput, vararg items: RoutineItem, focus: SuggestionFocus = SuggestionFocus.PLANNED) =
        SessionSuggester.fitTemplate(input, items.toList(), focus)

    @Test
    fun whatThePlaceLacksIsReplacedByTheSameKindOfWork() {
        val template = arrayOf(
            RoutineItem("warmup.band_pull_apart_warmup", sets = 1, warmup = true),
            RoutineItem("pull.pull_up", sets = 4),
            RoutineItem("pull.inverted_row", sets = 2, restS = 75),
            RoutineItem("antagonist.face_pull", sets = 3),
        )
        val fitted = fit(input(), *template)
        assertEquals(listOf("warmup.arm_circles", "pull.pull_up", "pull.table_row", "antagonist.prone_y_raise"), fitted.map { it.slug })
        // The template's sets, rest and warm-up role stay.
        assertEquals(RoutineItem("pull.table_row", sets = 2, repsMin = 8, repsMax = 12, restS = 75), fitted[2])
        assertTrue(fitted[0].warmup && fitted[0].sets == 1)
        // Nothing is touched before the equipment is set up, and nothing when everything is there.
        assertEquals(template.toList(), fit(input(equipment = null), *template))
        assertEquals(template.toList(), fit(input(equipment = bar + EquipmentV2.BANDS + EquipmentV2.RINGS), *template))
    }

    @Test
    fun withoutAReplacementTheExerciseIsLeftOut() {
        // No pull-up bar and no rings: the table row can take one place only.
        val fitted = fit(input(equipment = setOf(EquipmentV2.NONE, EquipmentV2.MAT)), RoutineItem("pull.pull_up"), RoutineItem("pull.inverted_row"))
        assertEquals(listOf("pull.table_row"), fitted.map { it.slug })
    }

    @Test
    fun someoneNewToTrainingGetsTheClassicsInATemplate() {
        val template = arrayOf(
            RoutineItem("warmup.finger_tendon_glides", sets = 1, warmup = true),
            RoutineItem("core.nordic"),
            RoutineItem("core.copenhagen"),
            RoutineItem("antagonist.pallof_press"),
        )
        // Seven years and 7A, but new here: one new exercise a training, the others give way to well-known ones, and a
        // warm-up nobody knows is left out or replaced.
        val starter = fit(input(starter = true), *template)
        assertEquals(listOf("warmup.arm_circles", "core.nordic", "core.leg_raise", "antagonist.prone_y_raise"), starter.map { it.slug })
        // Once they know their way around, the template stands.
        assertEquals(template.toList(), fit(input(starter = false), *template))
        // "Open to unusual exercises" stands above the starter phase.
        val open = input(starter = true, coach = CoachProfile(experience = ExperienceBand.OVER_5, currentGrade = "7A", exerciseChoice = ExerciseChoice.OPEN))
        assertEquals(template.toList(), fit(open, *template))
    }

    @Test
    fun theNextStepTheAthleteKeptTakesItsPlaceInATemplate() {
        // A step after a training: the next exercise leads, the one it was stepped from rests ([AthleteService.keepStep]).
        val stepped = input(kept = listOf("push.diamond_push_up"), resting = setOf("push.push_up"))
        val fitted = fit(stepped, RoutineItem("push.push_up", sets = 4, restS = 100), RoutineItem("core.plank"))
        assertEquals(listOf("push.diamond_push_up", "core.plank"), fitted.map { it.slug })
        assertEquals(4, fitted[0].sets)
        assertEquals(100, fitted[0].restS)
        // Never twice in one training.
        val both = fit(stepped, RoutineItem("push.push_up"), RoutineItem("push.diamond_push_up"))
        assertEquals(listOf("push.push_up", "push.diamond_push_up"), both.map { it.slug })
    }

    @Test
    fun theKeptStepTakesOnlyThePlaceOfTheExerciseItWasSteppedFrom() {
        // Review 2026-11-21: stepped from the hollow hold to hollow rocks – "dead bug", first of the chain in the
        // template, gave way and both hollow exercises stood in the session.
        val chain = ExerciseCatalog(1, listOf(
            ex("core.dead_bug", ExerciseCategoryV2.CORE, harder = "core.hollow_hold"),
            ex("core.hollow_hold", ExerciseCategoryV2.CORE, easier = "core.dead_bug", harder = "core.hollow_rock"),
            ex("core.hollow_rock", ExerciseCategoryV2.CORE, easier = "core.hollow_hold"),
            ex("core.plank", ExerciseCategoryV2.CORE),
        ))
        val stepped = input(kept = listOf("core.hollow_rock"), resting = setOf("core.hollow_hold")).copy(catalog = chain)
        val fitted = fit(stepped, RoutineItem("core.dead_bug"), RoutineItem("core.hollow_hold"), RoutineItem("core.plank"))
        assertEquals(listOf("core.dead_bug", "core.hollow_rock", "core.plank"), fitted.map { it.slug })
        // A member of the chain that only rests (swapped away lately) is not replaced by the kept one of another place.
        val other = input(kept = listOf("core.hollow_rock"), resting = emptySet()).copy(catalog = chain)
        assertEquals(listOf("core.dead_bug", "core.plank"), fit(other, RoutineItem("core.dead_bug"), RoutineItem("core.plank")).map { it.slug })
    }

    @Test
    fun theClimbingWarmUpKeepsTheWallAndLosesWhatIsNotThere() {
        // A planned day in the gym: the athlete's place has a pull-up bar and no wall, but today they climb.
        val gymDay = input(starter = true).let { i ->
            i.copy(profile = i.profile.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.HARD).entry)))
        }
        val s = SessionSuggester.suggest(gymDay)
        assertEquals(SuggestionFocus.BOARD_DAY, s.focus)
        val warmup = s.routine.items.filter { it.warmup }.map { it.slug }
        assertTrue("warmup.easy_climbing" in warmup, "$warmup")
        assertTrue("warmup.band_pull_apart_warmup" !in warmup && "warmup.finger_tendon_glides" !in warmup, "$warmup")
    }

    @Test
    fun byDefaultTheCoachStaysWithWhatWasTrainedLately() {
        // Two classics for the same job, one trained last week: with the default of the app it comes again, day after
        // day; with the old default of 50 the one not trained for longer took its turn.
        val lately = mapOf("core.plank" to today.minus(DatePeriod(days = 3)), "core.leg_raise" to today.minus(DatePeriod(days = 12)))
        fun core(variety: Int, day: Int) = SessionSuggester.suggest(input().copy(wish = SuggestionFocus.LEGS_CORE, lastTrained = lately,
            varietyDefault = variety, today = today.plus(DatePeriod(days = day)))).routine.items
            .map { it.slug }.first { it == "core.plank" || it == "core.leg_raise" }
        assertEquals(List(5) { "core.plank" }, (0 until 5).map { core(ExerciseFamiliarity.varietyDefault(starter = true), it) })
        assertEquals(List(5) { "core.plank" }, (0 until 5).map { core(ExerciseFamiliarity.SETTLED_VARIETY, it) })
        assertEquals(0, ExerciseFamiliarity.varietyDefault(starter = true))
    }

    @Test
    fun theStarterPhaseEndsWithEnoughKnownExercises() {
        assertTrue(ExerciseFamiliarity.starter(emptySet()))
        assertTrue(ExerciseFamiliarity.starter((1 until ExerciseFamiliarity.STARTER_KNOWN).map { "e$it" }.toSet()))
        assertTrue(!ExerciseFamiliarity.starter((1..ExerciseFamiliarity.STARTER_KNOWN).map { "e$it" }.toSet()))
        val veteran = CoachProfile(experience = ExperienceBand.OVER_5)
        assertEquals(ExerciseFamiliarity.Group.ADVANCED, ExerciseFamiliarity.group(veteran, 4, true, 24.0))
        assertEquals(ExerciseFamiliarity.Group.BEGINNER, ExerciseFamiliarity.group(veteran, 4, true, 24.0, starter = true))
        assertEquals(ExerciseFamiliarity.Group.ADVANCED,
            ExerciseFamiliarity.group(veteran.copy(exerciseChoice = ExerciseChoice.OPEN), 4, true, 24.0, starter = true))
    }
}
