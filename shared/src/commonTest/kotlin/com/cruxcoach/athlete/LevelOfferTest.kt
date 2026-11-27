package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import com.cruxcoach.domain.playlist.GeneratorType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Comparison review #10: "easier"/"harder" chosen twice for one kind of training is offered for good, once. */
class LevelOfferTest {

    private val today = LocalDate.parse("2026-10-05")
    private val dayMs = 86_400_000L
    private val todayMs = today.toEpochDays() * dayMs + dayMs / 2
    private var n = 0

    private fun level(daysAgo: Int, feedback: SuggestionFeedback, focus: String = "PULL_PUSH", at: Long = 0) =
        SuggestionEvent("l${n++}", today.minus(DatePeriod(days = daysAgo)).toString(), todayMs - daysAgo * dayMs + at,
            SuggestionEventKind.NEXT, focus, feedback = feedback)

    private fun back(daysAgo: Int, focus: String = "PULL_PUSH", at: Long = 0) =
        SuggestionEvent("b${n++}", today.minus(DatePeriod(days = daysAgo)).toString(), todayMs - daysAgo * dayMs + at,
            SuggestionEventKind.FEEDBACK, PreferenceLearning.CHOSEN_LEVEL + focus, value = 0.0)

    private fun answer(daysAgo: Int, value: Double, focus: String = "PULL_PUSH") =
        SuggestionEvent("a${n++}", today.minus(DatePeriod(days = daysAgo)).toString(), todayMs - daysAgo * dayMs + 5_000,
            SuggestionEventKind.FEEDBACK, PreferenceLearning.LEVEL_ANSWER + focus, value = value)

    private fun offers(events: List<SuggestionEvent>, kept: Map<String, Int> = emptyMap(), resets: Map<String, Long> = emptyMap()) =
        PreferenceLearning.levelOffers(events, today, kept, resets)

    @Test
    fun twoDaysOfTheSameDirectionForOneKindAreOfferedOnce() {
        val easier = listOf(level(9, SuggestionFeedback.TOO_HARD), level(0, SuggestionFeedback.TOO_HARD))
        assertEquals(listOf(PreferenceLearning.LevelOffer(SuggestionFocus.PULL_PUSH, -1)), offers(easier))
        // One day, another kind, or longer ago than three weeks: nothing yet.
        assertEquals(emptyList(), offers(listOf(level(0, SuggestionFeedback.TOO_HARD), level(0, SuggestionFeedback.TOO_HARD, at = 10))))
        assertEquals(emptyList(), offers(listOf(level(9, SuggestionFeedback.TOO_HARD, "LEGS_CORE"), level(0, SuggestionFeedback.TOO_HARD))))
        assertEquals(emptyList(), offers(listOf(level(25, SuggestionFeedback.TOO_HARD), level(0, SuggestionFeedback.TOO_HARD))))
        // A day counts with its last choice: "lighter" taken back the same day does not count.
        assertEquals(emptyList(), offers(listOf(level(9, SuggestionFeedback.TOO_HARD), level(0, SuggestionFeedback.TOO_HARD), back(0, at = 10))))
        // Harder twice is offered as more demanding; a rest day never.
        assertEquals(listOf(PreferenceLearning.LevelOffer(SuggestionFocus.LEGS_CORE, 1)),
            offers(listOf(level(3, SuggestionFeedback.TOO_EASY, "LEGS_CORE"), level(1, SuggestionFeedback.TOO_EASY, "LEGS_CORE"))))
        assertEquals(emptyList(), offers(listOf(level(3, SuggestionFeedback.TOO_HARD, "REST"), level(1, SuggestionFeedback.TOO_HARD, "REST"))))
    }

    @Test
    fun anAnswerEndsTheOfferAndANoKeepsItAwayThreeWeeks() {
        val asked = listOf(level(9, SuggestionFeedback.TOO_HARD), level(8, SuggestionFeedback.TOO_HARD))
        // "No": two more days of the same within three weeks ask nothing.
        assertEquals(emptyList(), offers(asked + answer(7, 0.0) + level(3, SuggestionFeedback.TOO_HARD) + level(1, SuggestionFeedback.TOO_HARD)))
        // "Yes": kept; the same direction cannot go further, the other direction is offered as the way back.
        val kept = mapOf("PULL_PUSH" to -1)
        assertEquals(emptyList(), offers(asked + answer(7, -1.0) + level(3, SuggestionFeedback.TOO_HARD) + level(1, SuggestionFeedback.TOO_HARD), kept))
        assertEquals(listOf(PreferenceLearning.LevelOffer(SuggestionFocus.PULL_PUSH, 1)),
            offers(asked + answer(7, -1.0) + level(3, SuggestionFeedback.TOO_EASY) + level(1, SuggestionFeedback.TOO_EASY), kept))
        // Choices before an answer do not count again.
        assertEquals(emptyList(), offers(asked + answer(7, -1.0), emptyMap()))
        // Forgotten on the learned page: the choices before it count no more.
        assertEquals(emptyList(), offers(asked, resets = mapOf(PreferenceLearning.levelKey("PULL_PUSH") to todayMs)))
    }

    // ── The suggestion follows a kept step for its kind only ──

    private fun ex(slug: String, category: ExerciseCategoryV2, difficulty: Int, equipment: List<EquipmentV2> = listOf(EquipmentV2.NONE)) =
        ExerciseDefinition(slug, category, ExerciseKind.REPS, LoadMode.BODYWEIGHT, equipment = equipment, difficulty = difficulty,
            defaults = Prescription(sets = 3, repsMin = 6, repsMax = 10, restS = 60), i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP, 1),
        ex("pull.easy_row", ExerciseCategoryV2.PULL, 1),
        ex("pull.hard_row", ExerciseCategoryV2.PULL, 3),
        ex("push.push_up", ExerciseCategoryV2.PUSH, 1),
        ex("legs.easy_squat", ExerciseCategoryV2.LEGS, 1),
        ex("legs.hard_squat", ExerciseCategoryV2.LEGS, 3),
        ex("core.dead_bug", ExerciseCategoryV2.CORE, 1),
        ex("mobility.frog", ExerciseCategoryV2.MOBILITY, 1),
    ))

    private fun suggest(wish: SuggestionFocus?, kept: Map<String, Int>, levelShift: Int = 0, plan: Map<Int, String> = emptyMap(),
                        routines: List<Routine> = emptyList()) =
        SessionSuggester.suggest(SuggestionInput(catalog, AthleteProfile(equipmentConfigured = true, keptLevels = kept, weekPlan = plan),
            today, ReadinessEvaluator.evaluate(null, emptyList()), emptyList(), wish = wish, levelShift = levelShift, routines = routines))

    /** The first pull pick: at level 2 the easy row, from level 3 on (no main work two steps below) the hard one. */
    private fun firstPull(s: SessionSuggestion) = s.routine.items.first { !it.warmup && catalog[it.slug]?.category == ExerciseCategoryV2.PULL }.slug

    @Test
    fun aKeptStepShiftsItsKindAndTodaysChoiceStillMovesOneStep() {
        val normal = suggest(SuggestionFocus.PULL_PUSH, emptyMap())
        val harder = suggest(SuggestionFocus.PULL_PUSH, mapOf("PULL_PUSH" to 1))
        assertEquals("pull.easy_row", firstPull(normal))
        assertEquals("pull.hard_row", firstPull(harder))
        assertTrue(SuggestionReason.LEVEL_HARDER_KEPT in harder.reasons, "${harder.reasons}")
        // Another kind keeps its level.
        assertEquals(suggest(SuggestionFocus.LEGS_CORE, emptyMap()).routine, suggest(SuggestionFocus.LEGS_CORE, mapOf("PULL_PUSH" to 1)).routine)
        // "Lighter" today on top of a kept "harder": the coach's own level, and today's reason.
        val lighter = suggest(SuggestionFocus.PULL_PUSH, mapOf("PULL_PUSH" to 1), levelShift = -1)
        assertEquals(normal.routine, lighter.routine)
        assertTrue(SuggestionReason.LEVEL_HARDER_KEPT !in lighter.reasons && SuggestionReason.LEVEL_EASIER in lighter.reasons, "${lighter.reasons}")
        // The own planned workout: one set less per exercise when kept easier.
        val own = Routine("own", "Mine", items = listOf(RoutineItem("pull.hard_row", sets = 3), RoutineItem("push.push_up", sets = 3)))
        val plan = mapOf(today.dayOfWeek.isoDayNumber to "own")
        val planned = suggest(null, mapOf("PLANNED" to -1), plan = plan, routines = listOf(own))
        assertEquals(SuggestionFocus.PLANNED, planned.focus)
        assertEquals(listOf(2, 2), planned.routine.items.map { it.sets })
    }
}
