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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden cases of planning and value updates (comparison review #12): what an athlete with a value sees
 * today. Changes to the progression rules must keep these – loads must not move by surprise.
 * Body weight 70 kg throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProgressionGoldenTest : AthleteScreenTest() {

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
    }

    private fun manual(slug: String, load: Double?, reps: Int? = null, seconds: Double? = null, edge: Double? = null) =
        service.saveBenchmark(Benchmark("m-$slug", slug, loadKg = load, reps = reps, durationS = seconds, edgeMm = edge,
            bodyweightKg = 70.0, source = BenchmarkSource.MANUAL, measuredAt = 1))

    private fun work(item: RoutineItem): List<ExerciseSet> {
        val id = service.startWorkout(Routine("r-${item.slug}", "Golden", items = listOf(item)), null)
        return repo.setsFor(id).filter { it.setType == SetType.WORK }.sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
    }

    private fun newest(slug: String) = repo.benchmarks(slug).maxBy { it.measuredAt }

    @Test
    fun `a value plans the loads it planned before`() {
        // Weighted pull-up max +20 kg × 5: 90 kg × (1 + 5/30) = 105 kg e1RM → 5 reps two in reserve ≈ +15 kg.
        manual("pull.weighted_pull_up", 20.0, reps = 5)
        assertEquals(listOf(15.0, 15.0, 15.0), work(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5)).map { it.loadKg })
        service.closeOpenWorkout()
        // Max hang +30 kg for 10 s on 20 mm: 100 kg total, 90 % for 10 s → +20 kg.
        manual("finger.max_hang", 30.0, seconds = 10.0, edge = 20.0)
        assertEquals(listOf(20.0, 20.0), work(RoutineItem("finger.max_hang", sets = 2, durationS = 10, edgeMm = 20)).map { it.loadKg })
        service.closeOpenWorkout()
        // One-arm dumbbell row 20 kg × 10: e1RM 26.7 kg → 10 reps two in reserve ≈ 19 kg, per side.
        manual("pull.dumbbell_row", 20.0, reps = 10)
        assertEquals(List(4) { 19.0 }, work(RoutineItem("pull.dumbbell_row", sets = 2, repsMin = 10, repsMax = 10)).map { it.loadKg })
    }

    @Test
    fun `a set at the plan with an ordinary reserve leaves the value as it is`() {
        manual("pull.weighted_pull_up", 20.0, reps = 5)
        val set = work(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5)).first()
        val done = service.completeSetDetailed(set.copy(reps = 5, rir = 2), startRest = false)
        assertEquals(null, done.raisedBenchmark)
        assertEquals(1, repo.benchmarks("pull.weighted_pull_up").size)
    }

    @Test
    fun `a clearly better set raises the value to exactly that set`() {
        manual("pull.weighted_pull_up", 20.0, reps = 5)
        val set = work(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5)).first()
        // +25 kg × 5 with nothing left: 95 × (1 + 5/30) = 110.8 kg, about 6 % above the value.
        service.completeSetDetailed(set.copy(loadKg = 25.0, reps = 5, rir = 0), startRest = false)
        val value = newest("pull.weighted_pull_up")
        assertEquals(BenchmarkSource.AUTO, value.source)
        assertEquals(25.0, value.loadKg!!, 1e-9)
        assertEquals(5, value.reps)
    }

    @Test
    fun `a test is the truth, also when it is lower`() {
        manual("finger.max_hang", 30.0, seconds = 10.0, edge = 20.0)
        val id = service.startTest("finger.max_hang")!!
        val test = repo.setsFor(id).first { it.setType == SetType.TEST }
        service.completeSetDetailed(test.copy(loadKg = 20.0, durationS = 10.0), startRest = false)
        val value = newest("finger.max_hang")
        assertEquals(BenchmarkSource.TEST, value.source)
        assertEquals(20.0, value.loadKg!!, 1e-9)
    }

    @Test
    fun `the first set without a value sets it, reserve counted`() {
        val set = work(RoutineItem("pull.pull_up", sets = 2, repsMin = 5, repsMax = 8)).first()
        service.completeSetDetailed(set.copy(reps = 8, loadKg = 0.0, rir = 1), startRest = false)
        assertEquals(9, newest("pull.pull_up").reps)
    }
}
