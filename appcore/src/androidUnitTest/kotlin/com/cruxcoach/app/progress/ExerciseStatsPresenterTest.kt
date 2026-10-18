package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.ExerciseStatsScreenModel
import com.cruxcoach.app.ui.ExerciseStatsScreenState
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One exercise over time on iOS runs Android's ExerciseStatsViewModel (ported); the case follows
 * TrainingScreensSmokeTest's "exercise stats chart renders" at the state level, plus one-handed work.
 */
class ExerciseStatsPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun stats(slug: String): Pair<ExerciseStatsPresenter, ExerciseStatsScreenModel> {
        val p = ExerciseStatsPresenter(service, kit.scope(), kit.dispatcher)
        val model = ExerciseStatsScreenModel(p, "en")
        model.load(slug)
        kit.until("loaded") { !p.state.value.loading }
        return p to model
    }

    private fun checkTexts(s: ExerciseStatsScreenState, model: ExerciseStatsScreenModel) {
        listOf(s.meaning, s.best, s.last, s.change, s.percentBodyweight, s.capacityTitle, s.capacityDescription, s.currentValue,
            s.volumeDescription, s.volumeHint).forEach { TrTextCheck.check(it, "page") }
        s.series.forEach { TrTextCheck.check(it.label, "series") }
        s.bests.forEach { TrTextCheck.check(it.label, "best label"); TrTextCheck.check(it.value, "best value") }
        s.sessions.forEach { session ->
            TrTextCheck.check(session.best, "session best")
            TrTextCheck.check(session.setsText, "session sets")
            session.sets.forEach { TrTextCheck.check(it, "set") }
        }
        model.rangeCodes.forEach { TrTextCheck.check(model.rangeLabel(it), "range") }
    }

    private fun train(item: RoutineItem, done: (com.cruxcoach.athlete.model.ExerciseSet) -> com.cruxcoach.athlete.model.ExerciseSet) = kit.db {
        val id = service.startWorkout(Routine("r", "T", items = listOf(item)), null)
        repo.setsFor(id).filter { it.setType == SetType.WORK }.forEach { service.completeSet(done(it), startRest = false) }
        service.finishWorkout(id, 6, null)
    }

    @Test
    fun `pull-ups show the estimated maximum, volume, bests and the sets`() {
        train(RoutineItem("pull.weighted_pull_up", sets = 2, repsMin = 5, repsMax = 5, loadKg = 10.0)) { it.copy(reps = 5, loadKg = 10.0) }
        kit.db {
            service.saveBenchmark(Benchmark("b", "pull.weighted_pull_up", loadKg = 20.0, reps = 5, bodyweightKg = 70.0,
                source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis()))
        }
        val (_, model) = stats("pull.weighted_pull_up")
        val s = model.currentState
        assertTrue(s.found)
        assertTrue(s.hasSessions)
        assertEquals(1, s.sessionCount)
        assertEquals("m6", s.rangeCode)
        assertEquals("trs_capacity_chart", s.capacityTitle.key)
        assertEquals(1, s.series.size)
        assertEquals("", s.series.single().sideCode)
        // The value of a bodyweight-plus exercise is read as added kilos: 5 reps at +10 kg → about +17 kg.
        val point = s.series.single().points.single().value
        assertTrue(point > 10.0 && point < 25.0, "$point")
        assertTrue(s.hasLevel)
        assertEquals("trs_current_value", s.currentValue.key)
        assertEquals(1, s.volume.size)
        assertEquals("trs_volume_hint_load", s.volumeHint.key)
        assertTrue(s.bests.isNotEmpty())
        val session = s.sessions.single()
        assertEquals(2, session.sets.size)
        assertTrue(session.bestIndex in 0..1)
        checkTexts(s, model)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `one-handed work draws a line per hand`() {
        train(RoutineItem("finger.one_arm_pickup", sets = 1, durationS = 10, edgeMm = 20, loadKg = 30.0)) {
            it.copy(durationS = 10.0, loadKg = if (it.side == Side.LEFT) 28.0 else 32.0, edgeMm = 20.0)
        }
        val (_, model) = stats("finger.one_arm_pickup")
        val s = model.currentState
        assertEquals(listOf("left", "right"), s.series.map { it.sideCode })
        assertFalse(s.hasLevel, "no guide line for one-handed work")
        assertTrue(s.series.single { it.sideCode == "right" }.points.single().value >
            s.series.single { it.sideCode == "left" }.points.single().value)
        assertTrue(s.bests.size >= 2)
        checkTexts(s, model)
    }

    @Test
    fun `an exercise without sets says so and a range change keeps the page`() {
        val (p, model) = stats("core.hollow_hold")
        val s = model.currentState
        assertTrue(s.found)
        assertFalse(s.hasSessions)
        assertTrue(s.series.isEmpty())
        model.setRange("w4")
        kit.until("range") { p.state.value.range == ProgressRange.FOUR_WEEKS }
        assertEquals("w4", model.currentState.rangeCode)
        checkTexts(model.currentState, model)
    }
}
