package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comparison review #12 through the real service: one typed set cannot jump the next plan, a self-estimate that
 * was too high comes down after the first training, and a hold's reserve counts. Body weight 70 kg.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProgressionRobustnessTest : AthleteScreenTest() {

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
    }

    private fun value(slug: String, load: Double, reps: Int? = null, seconds: Double? = null, source: BenchmarkSource = BenchmarkSource.MANUAL) =
        service.saveBenchmark(Benchmark("v-$slug", slug, loadKg = load, reps = reps, durationS = seconds, edgeMm = seconds?.let { 20.0 },
            bodyweightKg = 70.0, source = source, measuredAt = 1))

    private fun work(item: RoutineItem): List<ExerciseSet> {
        val id = service.startWorkout(Routine("r-${item.slug}", "Robust", items = listOf(item)), null)
        return repo.setsFor(id).filter { it.setType == SetType.WORK }.sortedBy { it.setIndex }
    }

    private val pullUps = RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5)

    @Test
    fun `one typed set raises the next plan by ten percent at most`() {
        value("pull.weighted_pull_up", 20.0, reps = 5)
        val first = work(pullUps)
        assertEquals(15.0, first.first().loadKg!!, 1e-9)
        // "50" instead of "5" reps.
        service.completeSetDetailed(first.first().copy(loadKg = 20.0, reps = 50), startRest = false)
        service.finishWorkout(first.first().workoutId, null, null)
        // Uncapped this planned about +125 kg (e1RM 240 kg); capped (e1RM ≤ 115.5 kg) it plans at most +23 kg.
        val next = work(pullUps).first().loadKg!!
        assertTrue("next $next", next in 15.0..23.0)
    }

    @Test
    fun `a self-estimate that was too high comes down after the first training`() {
        value("pull.weighted_pull_up", 30.0, reps = 5, source = BenchmarkSource.ESTIMATE)
        val first = work(pullUps)
        assertEquals(25.0, first.first().loadKg!!, 1e-9)
        // Far too heavy: the athlete goes down to +10 kg and makes 5 with one in reserve.
        service.completeSetDetailed(first.first().copy(loadKg = 10.0, reps = 5, rir = 1), startRest = false)
        service.finishWorkout(first.first().workoutId, null, null)
        // +10 kg × 6 → 96 kg e1RM → 5 reps two in reserve ≈ +8 kg next time (the estimate planned +25 kg).
        assertEquals(8.0, work(pullUps).first().loadKg!!, 1e-9)
    }

    @Test
    fun `a hold with seconds to spare counts them`() {
        value("finger.max_hang", 30.0, seconds = 10.0)
        val set = work(RoutineItem("finger.max_hang", sets = 2, durationS = 10, edgeMm = 20)).first()
        // +31.5 kg for 10 s: 101.5 kg is no clear gain over 100 kg – unless the athlete had three seconds left.
        assertEquals(null, service.completeSetDetailed(set.copy(loadKg = 31.5, durationS = 10.0), startRest = false).raisedBenchmark)
        service.updateSet(repo.setsFor(set.workoutId).first { it.id == set.id }.copy(rir = 3))
        val raised = repo.benchmarks("finger.max_hang").maxBy { it.measuredAt }
        assertEquals(BenchmarkSource.AUTO, raised.source)
        assertEquals(13.0, raised.durationS!!, 1e-9)
        assertEquals(31.5, raised.loadKg!!, 1e-9)
    }
}
