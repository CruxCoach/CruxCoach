package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingBlocksTest {

    private val start = LocalDate.parse("2026-09-07") // a Monday

    private fun at(days: Int) = start.plus(DatePeriod(days = days))

    @Test
    fun cycleRunsIntroBuildDeloadAndRepeats() {
        assertEquals(BlockPhase.INTRO, TrainingBlocks.state(start, null, at(0))?.phase)
        assertEquals(BlockState(BlockPhase.BUILD, 1, 3, null), TrainingBlocks.state(start, null, at(7)))
        assertEquals(BlockState(BlockPhase.BUILD, 3, 3, null), TrainingBlocks.state(start, null, at(27)))
        assertEquals(BlockPhase.DELOAD, TrainingBlocks.state(start, null, at(28))?.phase)
        assertEquals(BlockPhase.INTRO, TrainingBlocks.state(start, null, at(35))?.phase)
    }

    @Test
    fun noBlockWithoutAStartAndBeforeIt() {
        assertNull(TrainingBlocks.state(null, null, at(3)))
        assertNull(TrainingBlocks.state(at(10), null, at(3)))
    }

    @Test
    fun aTargetDateBringsTaperAndTheEventDay() {
        val event = at(60)
        assertEquals(BlockPhase.EVENT, TrainingBlocks.state(start, event, event)?.phase)
        val taper = TrainingBlocks.state(start, event, event.minus(DatePeriod(days = 6)))
        assertEquals(BlockPhase.TAPER, taper?.phase)
        assertEquals(6, taper?.daysToEvent)
        // Long before the event the cycle runs and counts down.
        assertEquals(60 - 8, TrainingBlocks.state(start, event, at(8))?.daysToEvent)
        // A past event is ignored.
        assertEquals(BlockPhase.BUILD, TrainingBlocks.state(start, at(1), at(8))?.phase)
    }

    @Test
    fun fatiguePullsTheDeloadForwardFromTheSecondBuildWeek() {
        assertEquals(BlockPhase.BUILD, TrainingBlocks.state(start, null, at(8), fatigueSignals = 5)?.phase)
        assertEquals(BlockPhase.DELOAD, TrainingBlocks.state(start, null, at(15), fatigueSignals = 3)?.phase)
        assertEquals(BlockPhase.BUILD, TrainingBlocks.state(start, null, at(15), fatigueSignals = 2)?.phase)
    }
}

class PreferenceLearningTest {

    private val today = LocalDate.parse("2026-10-05")
    private val dayMs = 86_400_000L
    private val todayMs = today.toEpochDays().toLong() * dayMs + dayMs / 2

    private fun event(kind: SuggestionEventKind, slugs: List<String>, daysAgo: Int = 0, feedback: SuggestionFeedback? = null, value: Double? = null) =
        SuggestionEvent("e$kind$daysAgo${slugs.joinToString()}", today.minus(DatePeriod(days = daysAgo)).toString(),
            todayMs - daysAgo * dayMs, kind, slugs = slugs, feedback = feedback, value = value)

    @Test
    fun dislikesAndSkipsAreNegativeRepeatsPositive() {
        val events = listOf(
            event(SuggestionEventKind.NEXT, listOf("core.dead_bug"), feedback = SuggestionFeedback.DISLIKE_EXERCISE),
            event(SuggestionEventKind.SKIPPED_SET, listOf("legs.air_squat")),
            event(SuggestionEventKind.COMPLETED, listOf("pull.pull_up"), value = 1.0),
        )
        val sets = (1..4).map { i -> ExerciseSet("s$i", "w$i", "pull.pull_up", 0, 0, reps = 6, completedAt = todayMs - i * dayMs) }
        val a = PreferenceLearning.affinity(events, sets, today)
        assertTrue((a["core.dead_bug"] ?: 0.0) < -1.5, "$a")
        assertTrue((a["legs.air_squat"] ?: 0.0) < 0, "$a")
        assertTrue((a["pull.pull_up"] ?: 0.0) > 1.0, "$a")
    }

    @Test
    fun followingTheCoachDoesNotLockInItsOwnPicks() {
        // Owner 2026-10-17: learn from changes, do not overfit – doing what was suggested is not a preference.
        val events = (1..6).flatMap { i -> listOf(
            event(SuggestionEventKind.STARTED, listOf("pull.pull_up"), daysAgo = i * 2).copy(workoutId = "w$i"),
            event(SuggestionEventKind.COMPLETED, listOf("pull.pull_up"), daysAgo = i * 2, value = 1.0).copy(workoutId = "w$i")) }
        val followed = (1..6).map { i -> ExerciseSet("s$i", "w$i", "pull.pull_up", 0, 0, reps = 6, completedAt = todayMs - i * 2 * dayMs) }
        val own = (1..6).map { i -> ExerciseSet("o$i", "own$i", "core.dead_bug", 0, 0, reps = 10, completedAt = todayMs - i * 2 * dayMs) }
        val a = PreferenceLearning.affinity(events, followed + own, today)
        assertTrue((a["pull.pull_up"] ?: 0.0) < 1.0, "$a")
        assertTrue((a["core.dead_bug"] ?: 0.0) >= 1.0, "$a")
    }

    @Test
    fun aRejectedExerciseRestsAFewDaysAndRepeatedRejectionsLonger() {
        val once = listOf(event(SuggestionEventKind.SWAPPED, listOf("pull.negative_pull_up", "pull.pull_up"), daysAgo = 1))
        assertEquals(today.plus(DatePeriod(days = 2)), PreferenceLearning.resting(once, today)["pull.negative_pull_up"])
        // Not the exercise swapped to.
        assertTrue("pull.pull_up" !in PreferenceLearning.resting(once, today))
        // After its rest it may come back: the coach stays a coach.
        val earlier = listOf(event(SuggestionEventKind.SWAPPED, listOf("pull.negative_pull_up", "pull.pull_up"), daysAgo = 3))
        assertTrue(PreferenceLearning.resting(earlier, today).isEmpty())
        val twice = listOf(
            event(SuggestionEventKind.NEXT, listOf("core.dead_bug"), daysAgo = 9, feedback = SuggestionFeedback.DISLIKE_EXERCISE),
            event(SuggestionEventKind.SWAPPED, listOf("core.dead_bug", "core.hollow_hold"), daysAgo = 5))
        assertEquals(today.plus(DatePeriod(days = 5)), PreferenceLearning.resting(twice, today)["core.dead_bug"])
        // One skipped exercise alone does not rest it; forgetting starts fresh.
        assertTrue(PreferenceLearning.resting(listOf(event(SuggestionEventKind.SKIPPED_SET, listOf("legs.air_squat"))), today).isEmpty())
        assertTrue(PreferenceLearning.resting(once, today, mapOf(PreferenceLearning.exerciseKey("pull.negative_pull_up") to todayMs)).isEmpty())
        // Many rejections on one day count once (device test 2026-10-17: a skip per set moved past rested it for 21 days).
        val storm = (1..8).map { i -> event(SuggestionEventKind.SKIPPED_SET, listOf("finger.max_hang"), daysAgo = 1).copy(id = "s$i") } +
            (1..3).map { i -> event(SuggestionEventKind.SWAPPED, listOf("finger.max_hang", "finger.half_crimp"), daysAgo = 1).copy(id = "w$i") }
        assertEquals(today.plus(DatePeriod(days = 2)), PreferenceLearning.resting(storm, today)["finger.max_hang"])
    }

    @Test
    fun aSwapJustAfterLocalMidnightCountsFully() {
        // Device test 2026-10-17, 00:30 in Berlin = 22:30 UTC the day before: the swap aged a day and missed "gladly".
        val berlin = kotlinx.datetime.TimeZone.of("Europe/Berlin")
        val justAfterMidnight = today.toEpochDays().toLong() * dayMs - 90 * 60_000L  // 22:30 UTC the evening before
        val swap = SuggestionEvent("s", today.toString(), justAfterMidnight, SuggestionEventKind.SWAPPED,
            slugs = listOf("pull.negative_pull_up", "pull.pull_up"))
        val a = PreferenceLearning.affinity(listOf(swap), emptyList(), today, zone = berlin)
        assertEquals(1.0, a["pull.pull_up"])
        assertEquals(-1.0, a["pull.negative_pull_up"])
    }

    @Test
    fun signalsFadeAndAreClamped() {
        val old = listOf(event(SuggestionEventKind.NEXT, listOf("core.dead_bug"), daysAgo = 50, feedback = SuggestionFeedback.DISLIKE_EXERCISE))
        assertTrue(PreferenceLearning.affinity(old, emptyList(), today).isEmpty())
        val many = (0 until 10).map { event(SuggestionEventKind.EXCLUDED, listOf("x"), daysAgo = it) }
        assertEquals(-PreferenceLearning.MAX_AFFINITY, PreferenceLearning.affinity(many, emptyList(), today)["x"])
    }

    @Test
    fun suggestedMinutesFollowRealTrainings() {
        fun w(i: Int, minutes: Int) = Workout("w$i", 0, minutes * 60_000L, today.minus(DatePeriod(days = i)).toString(), updatedAt = 0)
        val workouts = listOf(w(1, 28), w(3, 32), w(5, 30), w(8, 31))
        assertEquals(30, PreferenceLearning.suggestedSessionMinutes(workouts, current = 60, today = today))
        assertNull(PreferenceLearning.suggestedSessionMinutes(workouts, current = 30, today = today))
        assertNull(PreferenceLearning.suggestedSessionMinutes(workouts.take(3), current = 60, today = today))
    }

    // ── Choices per weekday and forgetting (UX review 2026-10-16) ──

    private fun chosen(daysAgo: Int, focus: String, value: Double? = null, started: Boolean = true, at: Long = 0) = buildList {
        val day = today.minus(DatePeriod(days = daysAgo)).toString()
        val ms = todayMs - daysAgo * dayMs + at
        add(SuggestionEvent("c$daysAgo$focus$at", day, ms, SuggestionEventKind.FEEDBACK, focus = focus, value = value))
        if (started) add(SuggestionEvent("s$daysAgo$focus$at", day, ms + 1000, SuggestionEventKind.STARTED, focus = "PULL_PUSH"))
    }

    @Test
    fun aKindChosenTwiceOnAWeekdayBecomesUsual() {
        // 2026-10-05 is a Monday: the last two Mondays the athlete chose pull and push and trained.
        val events = chosen(7, PreferenceLearning.CHOSEN_KIND + "PULL_PUSH") + chosen(14, PreferenceLearning.CHOSEN_KIND + "PULL_PUSH") +
            chosen(7, PreferenceLearning.CHOSEN_MINUTES, 30.0) + chosen(21, PreferenceLearning.CHOSEN_MINUTES, 30.0)
        val usual = PreferenceLearning.usualChoices(events, today)[1]
        assertEquals(SuggestionFocus.PULL_PUSH, usual?.focus)
        assertEquals(30, usual?.minutes)
        // Once is no habit, and a choice without a training does not count.
        assertNull(PreferenceLearning.usualChoices(chosen(7, PreferenceLearning.CHOSEN_KIND + "LEGS_CORE"), today)[1])
        val idle = chosen(7, PreferenceLearning.CHOSEN_KIND + "LEGS_CORE", started = false) + chosen(14, PreferenceLearning.CHOSEN_KIND + "LEGS_CORE")
        assertNull(PreferenceLearning.usualChoices(idle, today)[1])
    }

    @Test
    fun theMostRecentChoiceOfAWeekdayDecides() {
        val kind = PreferenceLearning.CHOSEN_KIND
        // Two Mondays legs, the last one back to the coach: nothing usual any more.
        val back = chosen(14, kind + "LEGS_CORE") + chosen(21, kind + "LEGS_CORE") + chosen(7, kind)
        assertNull(PreferenceLearning.usualChoices(back, today)[1])
        // Within one day the last choice wins.
        val changed = chosen(7, kind + "FINGER_STRENGTH") + chosen(7, kind + "LEGS_CORE", at = 5000) + chosen(14, kind + "LEGS_CORE")
        assertEquals(SuggestionFocus.LEGS_CORE, PreferenceLearning.usualChoices(changed, today)[1]?.focus)
        // Today's own choice is today's, not a habit yet; other weekdays stay apart.
        val todayToo = chosen(0, kind + "LEGS_CORE") + chosen(7, kind + "LEGS_CORE") + chosen(8, kind + "LEGS_CORE")
        assertNull(PreferenceLearning.usualChoices(todayToo, today)[1])
    }

    @Test
    fun forgottenSignalsNoLongerCount() {
        val kind = PreferenceLearning.CHOSEN_KIND
        val events = chosen(7, kind + "PULL_PUSH") + chosen(14, kind + "PULL_PUSH")
        val reset = mapOf(PreferenceLearning.weekdayKey(1) to todayMs - 10 * dayMs)
        assertNull(PreferenceLearning.usualChoices(events, today, reset)[1])
        val dislikes = listOf(event(SuggestionEventKind.NEXT, listOf("core.dead_bug"), daysAgo = 3, feedback = SuggestionFeedback.DISLIKE_EXERCISE),
            event(SuggestionEventKind.NEXT, listOf("legs.air_squat"), daysAgo = 3, feedback = SuggestionFeedback.DISLIKE_EXERCISE))
        val one = PreferenceLearning.affinity(dislikes, emptyList(), today, mapOf(PreferenceLearning.exerciseKey("core.dead_bug") to todayMs))
        assertNull(one["core.dead_bug"]); assertTrue((one["legs.air_squat"] ?: 0.0) < 0)
        assertTrue(PreferenceLearning.affinity(dislikes, emptyList(), today, mapOf(PreferenceLearning.RESET_ALL to todayMs)).isEmpty())
    }

    @Test
    fun actualWeekdaysNeedThreeOfEightWeeks() {
        val mondays = (0 until 4).map { today.minus(DatePeriod(days = 7 * it)) } // 2026-10-05 is a Monday
        val activities = (mondays + today.minus(DatePeriod(days = 2))).associate { d ->
            d.toString() to DayActivity(d.toString(), climbingMinutes = 60)
        }
        assertEquals(setOf(1), PreferenceLearning.actualTrainingWeekdays(activities, today))
    }
}

class OwnLoadsTest {

    private val pull = ExerciseDefinition("pull.weighted_pull_up", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
        false, listOf(EquipmentV2.PULL_UP_BAR), emptyList(), defaults = Prescription(sets = 4, repsMin = 5, repsMax = 5, restS = 150),
        i18n = mapOf("en" to ExerciseText("Weighted pull-up")))

    private fun set(i: Int, load: Double, target: Double, reps: Int = 5, done: Boolean = true, type: SetType = SetType.WORK) =
        ExerciseSet("s$i", "w", pull.slug, 0, i, type, targetReps = 5, targetLoadKg = target, reps = reps, loadKg = load,
            bodyweightKg = 70.0, completedAt = if (done) 1000L + i else null)

    @Test
    fun aLoadLoweredByTheAthleteBecomesTheirValue() {
        // Planned +20 kg; the athlete set +15 on the first set, the rest followed (their plan moved with it).
        val sets = listOf(set(0, 15.0, 20.0), set(1, 15.0, 15.0), set(2, 15.0, 15.0, reps = 4), set(3, 15.0, 15.0))
        val values = OwnLoads.lowered(pull, sets, { "b" }, 5000)
        assertEquals(1, values.size)
        val b = values.single()
        assertEquals(15.0, b.loadKg); assertEquals(4, b.reps); assertEquals(4, b.sets); assertEquals(BenchmarkSource.AUTO, b.source)
        // Read back the way plans are made: 4 reps at +15 kg (reserve 2) again.
        val capacity = BenchmarkMath.capacity(pull, b, 70.0)!!
        val item = RoutineItem(pull.slug, sets = 4, repsMin = 4, repsMax = 4)
        assertEquals(15.0, LoadPrescriber.prescribe(pull, item, capacity, 70.0, 1.0)?.loadKg)
    }

    @Test
    fun autoregulationAndOneTiredSetAreNoChoice() {
        // Plan and load moved together (autoregulation): nothing to learn.
        assertTrue(OwnLoads.lowered(pull, listOf(set(0, 20.0, 20.0), set(1, 17.5, 17.5), set(2, 17.5, 17.5)), { "b" }, 0).isEmpty())
        // Only the last set lighter: fatigue, not the athlete's load.
        assertTrue(OwnLoads.lowered(pull, listOf(set(0, 20.0, 20.0), set(1, 20.0, 20.0), set(2, 15.0, 20.0)), { "b" }, 0).isEmpty())
        // Heavier than planned is handled by the performance value rising, warm-ups and open sets never count.
        assertTrue(OwnLoads.lowered(pull, listOf(set(0, 25.0, 20.0), set(1, 25.0, 25.0)), { "b" }, 0).isEmpty())
        assertTrue(OwnLoads.lowered(pull, listOf(set(0, 10.0, 20.0, type = SetType.WARMUP), set(1, 10.0, 20.0, done = false),
            set(2, 10.0, 20.0, done = false)), { "b" }, 0).isEmpty())
    }
}

class ReadinessModifiersTest {

    private fun def(slug: String, tags: List<String> = listOf("strength"), domains: List<LoadDomain> = listOf(LoadDomain.FINGER),
                    load: LoadMode = LoadMode.BODYWEIGHT_PLUS) =
        ExerciseDefinition(slug, ExerciseCategoryV2.FINGER, ExerciseKind.HANG, load, domains = domains, tags = tags)

    private val pullUp = ExerciseDefinition("pull.weighted_pull_up", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
        domains = listOf(LoadDomain.ELBOW))

    @Test
    fun goodDayChangesNothing() {
        val m = ReadinessModifiers.of(ReadinessEvaluator.evaluate(null, emptyList()), null)
        assertTrue(m.isNeutral)
    }

    @Test
    fun tiredMeansOneSetLessAndLighter() {
        val m = ReadinessModifiers.of(ReadinessEvaluator.evaluate(Checkin("d", 0, sleep = 2), emptyList()), null)
        assertEquals(-1, m.setsDelta)
        assertEquals(0.95, m.loadFactor)
        assertEquals(ReadinessReason.LOW_SLEEP, m.reason)
        assertEquals(3, ReadinessModifiers.applyToItem(RoutineItem("pull.weighted_pull_up", sets = 4), pullUp, m).sets)
        assertEquals(4, ReadinessModifiers.applyToItem(RoutineItem("pull.weighted_pull_up", sets = 4, warmup = true), pullUp, m).sets)
    }

    @Test
    fun skinOrInjuryAloneDoNotTrimVolume() {
        val m = ReadinessModifiers.of(ReadinessEvaluator.evaluate(Checkin("d", 0, skin = 1), emptyList()), null)
        assertEquals(0, m.setsDelta)
        assertEquals(1.0, m.loadFactor)
    }

    @Test
    fun tiredFingersOrALimitSessionLightenMaxFingerWork() {
        val tired = ReadinessModifiers.of(ReadinessEvaluator.evaluate(Checkin("d", 0, fingers = 2), emptyList()), null)
        val hang = def("finger.max_hang")
        assertTrue(tired.dropMaxFinger)
        assertEquals(2, ReadinessModifiers.applyToItem(RoutineItem("finger.max_hang", sets = 5), hang, tired).sets)
        assertEquals(ReadinessModifiers.LIGHT_FINGER_FACTOR, ReadinessModifiers.loadFactorFor(hang, tired))
        assertEquals(1.0, ReadinessModifiers.loadFactorFor(pullUp, tired))

        val hard = ReadinessModifiers.of(ReadinessEvaluator.evaluate(null, emptyList()), ClimbIntensity.HARD)
        assertEquals(0.9, ReadinessModifiers.loadFactorFor(hang, hard))
        assertTrue(!hard.dropMaxFinger)
        val limit = ReadinessModifiers.of(ReadinessEvaluator.evaluate(null, emptyList()), ClimbIntensity.LIMIT)
        assertTrue(limit.dropMaxFinger)
        assertEquals(ClimbIntensity.LIMIT, limit.climbedToday)
    }

    @Test
    fun restDayLeavesAStartedTrainingAlone() {
        val sick = ReadinessModifiers.of(ReadinessEvaluator.evaluate(Checkin("d", 0, sick = true), emptyList()), ClimbIntensity.LIMIT)
        assertTrue(sick.isNeutral)
    }

    @Test
    fun loadsScaleOnTheTotal() {
        val hang = def("finger.max_hang")
        // 70 kg + 10 kg = 80 kg × 0.9 = 72 kg → +2 kg
        assertEquals(2.0, ReadinessModifiers.scaleLoad(hang, 10.0, 70.0, 0.9, 1.0))
        val block = def("finger.one_arm_pickup", load = LoadMode.EXTERNAL)
        assertEquals(27.0, ReadinessModifiers.scaleLoad(block, 30.0, 70.0, 0.9, 1.0))
        assertNull(ReadinessModifiers.scaleLoad(block, null, 70.0, 0.9, 1.0))
    }
}

class ProgressionTrendTest {

    private val pullUp = ExerciseDefinition("pull.weighted_pull_up", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.BODYWEIGHT_PLUS,
        harder = "pull.one_arm_pull_up", easier = "pull.pull_up",
        defaults = Prescription(sets = 3, repsMin = 3, repsMax = 5, restS = 180))

    private fun session(w: String, load: Double, reps: Int, rir: Int?) =
        (0 until 3).map { i -> ExerciseSet("$w$i", w, pullUp.slug, 0, i, targetReps = 5, reps = reps, loadKg = load, rir = rir,
            bodyweightKg = 70.0, completedAt = 1) }

    @Test
    fun twoEasySessionsOfferTheHarderVariant() {
        val t = ProgressionAdvisor.trend(pullUp, listOf(session("a", 20.0, 5, 3), session("b", 20.0, 5, 3)))
        assertEquals(TrendKind.TOO_EASY_TWICE, t.kind)
        assertEquals("pull.one_arm_pull_up", t.harderSlug)
    }

    @Test
    fun twoHardSessionsOfferTheEasierVariant() {
        val t = ProgressionAdvisor.trend(pullUp, listOf(session("a", 20.0, 2, 0), session("b", 20.0, 2, 0)))
        assertEquals(TrendKind.TOO_HARD_TWICE, t.kind)
        assertEquals("pull.pull_up", t.easierSlug)
    }

    @Test
    fun aClearDropWithMissesSuggestsALighterWeek() {
        val t = ProgressionAdvisor.trend(pullUp, listOf(session("a", 10.0, 2, 0), session("b", 20.0, 5, 1), session("c", 20.0, 5, 1)))
        assertEquals(TrendKind.DECLINE, t.kind)
    }

    @Test
    fun threeSessionsWithoutProgressSuggestAVariation() {
        val t = ProgressionAdvisor.trend(pullUp, listOf(session("a", 20.0, 4, 1), session("b", 20.0, 4, 1), session("c", 20.0, 4, 1)))
        assertEquals(TrendKind.STALL, t.kind)
        assertEquals("pull.one_arm_pull_up", t.variationSlug)
    }

    @Test
    fun progressIsProgress() {
        val t = ProgressionAdvisor.trend(pullUp, listOf(session("a", 25.0, 4, 1), session("b", 20.0, 4, 1), session("c", 15.0, 4, 1)))
        assertEquals(TrendKind.PROGRESSING, t.kind)
        assertEquals(TrendKind.NONE, ProgressionAdvisor.trend(pullUp, listOf(session("a", 20.0, 4, 1))).kind)
    }
}
