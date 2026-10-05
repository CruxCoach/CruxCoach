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

    @Test
    fun actualWeekdaysNeedThreeOfEightWeeks() {
        val mondays = (0 until 4).map { today.minus(DatePeriod(days = 7 * it)) } // 2026-10-05 is a Monday
        val activities = (mondays + today.minus(DatePeriod(days = 2))).associate { d ->
            d.toString() to DayActivity(d.toString(), climbingMinutes = 60)
        }
        assertEquals(setOf(1), PreferenceLearning.actualTrainingWeekdays(activities, today))
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
