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

/** Comparison review #3: "from now on like this?" after a training – what counts as a change, and what "yes" keeps. */
class KeepChoiceTest {

    private val today = LocalDate.parse("2026-10-05")
    private var t = 1_000L

    private fun e(kind: SuggestionEventKind, slugs: List<String> = emptyList(), focus: String? = "PULL_PUSH", workout: String? = null,
                  feedback: SuggestionFeedback? = null, value: Double? = null) =
        SuggestionEvent("e${t}", today.toString(), t++, kind, focus, slugs, feedback, workout, value)

    private fun started(slugs: List<String>) = e(SuggestionEventKind.STARTED, slugs, workout = "w")

    @Test
    fun swapsAndLeftOutOfTheSuggestionCountFromTheSuggestedExercise() {
        val events = listOf(
            e(SuggestionEventKind.SHOWN, listOf("pull.pull_up", "push.push_up", "core.plank")),
            // Card: pull-ups → ring rows → inverted rows (counts once), plank left out, push-ups swapped and back (cancels).
            e(SuggestionEventKind.SWAPPED, listOf("pull.pull_up", "pull.ring_row")),
            e(SuggestionEventKind.SWAPPED, listOf("pull.ring_row", "pull.inverted_row")),
            e(SuggestionEventKind.SWAPPED, listOf("push.push_up", "push.dips")),
            e(SuggestionEventKind.SWAPPED, listOf("push.dips", "push.push_up")),
            e(SuggestionEventKind.NEXT, listOf("core.plank"), feedback = SuggestionFeedback.DISLIKE_EXERCISE, value = PreferenceLearning.LEFT_OUT),
            started(listOf("pull.inverted_row", "push.push_up", "legs.squat", "mobility.frog")),
            // In the training: squats skipped, the mobility removed; an exercise the athlete added and then removed is not the suggestion's.
            e(SuggestionEventKind.SKIPPED_SET, listOf("legs.squat"), focus = null, workout = "w"),
            e(SuggestionEventKind.FEEDBACK, listOf("mobility.frog"), focus = PreferenceLearning.REMOVED, workout = "w"),
            e(SuggestionEventKind.FEEDBACK, listOf("core.dead_bug"), focus = PreferenceLearning.REMOVED, workout = "w"),
            // Another training's swap does not count.
            e(SuggestionEventKind.SWAPPED, listOf("push.push_up", "push.pike_push_up"), focus = null, workout = "other"),
        )
        assertEquals(listOf(
            PreferenceLearning.Change("pull.pull_up", "pull.inverted_row"),
            PreferenceLearning.Change("core.plank", null),
            PreferenceLearning.Change("legs.squat", null),
            PreferenceLearning.Change("mobility.frog", null),
        ), PreferenceLearning.changes(events, "w"))
    }

    @Test
    fun noChangeNoQuestionAndAnEarlierAnswerCoversTheCardsChanges() {
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(started(listOf("pull.pull_up"))), "w"))
        // Without a start from the suggestion there is nothing to ask.
        assertEquals(emptyList(), PreferenceLearning.changes(listOf(e(SuggestionEventKind.SWAPPED, listOf("a", "b"))), "w"))
        val first = listOf(
            e(SuggestionEventKind.SWAPPED, listOf("pull.pull_up", "pull.ring_row")),
            e(SuggestionEventKind.STARTED, listOf("pull.ring_row"), workout = "w1"),
            e(SuggestionEventKind.FEEDBACK, focus = PreferenceLearning.KEEP_TODAY_ONLY, workout = "w1"),
            // The summary's "swap in your workouts" carries no suggestion focus: no card change.
            e(SuggestionEventKind.SWAPPED, listOf("core.plank", "core.hollow_hold"), focus = null),
        )
        assertEquals(emptyList(), PreferenceLearning.changes(first + e(SuggestionEventKind.STARTED, listOf("pull.ring_row", "core.plank"), workout = "w2"), "w2"))
        // A new change on the card after the answer is asked about.
        val later = first + e(SuggestionEventKind.SWAPPED, listOf("pull.ring_row", "pull.inverted_row")) +
            e(SuggestionEventKind.STARTED, listOf("pull.inverted_row"), workout = "w2")
        assertEquals(listOf(PreferenceLearning.Change("pull.pull_up", "pull.inverted_row")), PreferenceLearning.changes(later, "w2"))
    }

    private fun kept(replaced: String, chosen: String?, until: LocalDate, at: Long) = SuggestionEvent("k$at", today.toString(), at,
        SuggestionEventKind.FEEDBACK, PreferenceLearning.KEEP_CHOICE, listOfNotNull(replaced, chosen), workoutId = "w",
        value = until.toEpochDays().toDouble())

    @Test
    fun keptChoicesHoldUntilTheirDayAndANewerOneWins() {
        val until = today.plus(DatePeriod(days = 21))
        val events = listOf(
            kept("pull.pull_up", "pull.ring_row", until, 1),
            kept("core.plank", null, until, 2),
            // Later the ring rows were swapped for inverted rows and kept: ring rows rest now, inverted rows lead.
            kept("pull.ring_row", "pull.inverted_row", until, 3),
            kept("legs.squat", "legs.lunge", today, 4),   // ended today
        )
        val k = PreferenceLearning.kept(events, today)
        assertEquals(3, k.size)
        val effect = PreferenceLearning.keptEffect(k)
        assertEquals(listOf("pull.inverted_row"), effect.preferred)
        assertEquals(setOf("pull.ring_row", "core.plank", "pull.pull_up"), effect.resting.keys)
        assertEquals(until, effect.resting["core.plank"])
        // Forgetting an exercise (or everything) ends what was kept about it.
        val forgot = PreferenceLearning.kept(events, today, mapOf(PreferenceLearning.exerciseKey("pull.inverted_row") to 10L))
        assertTrue(forgot.none { it.chosen == "pull.inverted_row" })
        assertEquals(emptyList(), PreferenceLearning.kept(events, today, mapOf(PreferenceLearning.RESET_ALL to 10L)))
        assertEquals(emptyList(), PreferenceLearning.kept(events, today, mapOf(PreferenceLearning.keptKey("k2") to 10L)).filter { it.eventId == "k2" })
    }

    @Test
    fun aKeptChoiceHoldsToTheBlocksEndAndThreeWeeksAtLeast() {
        val start = LocalDate.parse("2026-09-28")   // intro week from here; the next block starts 2026-11-02
        assertEquals(LocalDate.parse("2026-11-02"), TrainingBlocks.blockEnd(start, null, today))
        assertEquals(LocalDate.parse("2026-11-02"), PreferenceLearning.keepUntil(today, TrainingBlocks.blockEnd(start, null, today)))
        // Late in the block: three weeks.
        val late = LocalDate.parse("2026-10-30")
        assertEquals(late.plus(DatePeriod(days = 21)), PreferenceLearning.keepUntil(late, TrainingBlocks.blockEnd(start, null, late)))
        // Without a block: three weeks; a trip ends the block on its day.
        assertEquals(today.plus(DatePeriod(days = 21)), PreferenceLearning.keepUntil(today, null))
        assertEquals(LocalDate.parse("2026-10-20"), TrainingBlocks.blockEnd(start, LocalDate.parse("2026-10-20"), today))
    }

    // ── The suggestion follows the kept choice ──

    private fun ex(slug: String, category: ExerciseCategoryV2, equipment: List<EquipmentV2> = listOf(EquipmentV2.NONE), difficulty: Int = 1,
                   contraindications: List<BodyRegion> = emptyList()) =
        ExerciseDefinition(slug, category, ExerciseKind.REPS, LoadMode.BODYWEIGHT, equipment = equipment, difficulty = difficulty,
            defaults = Prescription(sets = 3, repsMin = 6, repsMax = 10, restS = 60), contraindications = contraindications,
            i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP),
        ex("pull.pull_up", ExerciseCategoryV2.PULL, listOf(EquipmentV2.PULL_UP_BAR), difficulty = 2),
        ex("pull.ring_row", ExerciseCategoryV2.PULL, listOf(EquipmentV2.RINGS)),
        ex("pull.towel_row", ExerciseCategoryV2.PULL, contraindications = listOf(BodyRegion.ELBOW)),
        ex("push.push_up", ExerciseCategoryV2.PUSH),
        ex("antagonist.band_pull_apart", ExerciseCategoryV2.ANTAGONIST),
        ex("core.dead_bug", ExerciseCategoryV2.CORE),
    ))

    private val profile = AthleteProfile(equipment = setOf(EquipmentV2.NONE, EquipmentV2.PULL_UP_BAR, EquipmentV2.RINGS), equipmentConfigured = true)

    private fun input(kept: List<String>, resting: Set<String> = emptySet(), injuries: List<Injury> = emptyList(), levelShift: Int = 0) =
        SuggestionInput(catalog, profile, today, ReadinessEvaluator.evaluate(null, injuries), injuries, wish = SuggestionFocus.PULL_PUSH,
            kept = kept, resting = resting, levelShift = levelShift, favorites = setOf("pull.pull_up"))

    private fun firstPull(s: SessionSuggestion) = s.routine.items.first { !it.warmup && catalog[it.slug]?.category == ExerciseCategoryV2.PULL }.slug

    @Test
    fun theKeptPickLeadsItsSlotThroughEverySafetyFilter() {
        // A favourite would win the pull slot – the kept choice wins over it, and the replaced one rests.
        assertEquals("pull.pull_up", firstPull(SessionSuggester.suggest(input(emptyList()))))
        val s = SessionSuggester.suggest(input(listOf("pull.ring_row"), resting = setOf("pull.pull_up")))
        assertEquals("pull.ring_row", firstPull(s))
        assertTrue(SuggestionReason.KEPT_CHOICE in s.reasons, "${s.reasons}")
        // Never past the filters: an injury the kept exercise loads keeps it out.
        val elbow = Injury("i", InjuryRegion.ELBOW, InjurySide.BOTH, severity = 5, climbingPaused = false, startedOn = today.toString())
        val hurt = SessionSuggester.suggest(input(listOf("pull.towel_row"), injuries = listOf(elbow)))
        assertTrue(hurt.routine.items.none { it.slug == "pull.towel_row" })
        // "Easier today" still counts: the kept pick only stays when it fits today's level.
        assertEquals("pull.ring_row", firstPull(SessionSuggester.suggest(input(listOf("pull.ring_row"), levelShift = -1))))
    }
}
