package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Block lifts and pick-ups without any value start with a careful estimated
 * load instead of "0 kg" (owner, device test 2026-10-09); an own routine's
 * load and a known value still win.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartLoadTest : AthleteScreenTest() {

    private fun plannedWork(items: List<RoutineItem>): List<com.cruxcoach.athlete.model.ExerciseSet> {
        val id = service.startWorkout(Routine(id = "r", name = "Finger", items = items), null)
        return repo.setsFor(id).filter { it.setType == SetType.WORK }
    }

    @Test
    fun `a first two-arm pick-up gets a careful load from body weight`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 68.5, "kg", 1))
        val work = plannedWork(listOf(RoutineItem("finger.two_arm_pickup", sets = 4, durationS = 10, edgeMm = 20)))
        assertEquals(4, work.size)
        work.forEach { set ->
            assertTrue("planned ${set.loadKg}", (set.loadKg ?: 0.0) in 25.0..40.0)
            assertEquals(set.loadKg, set.targetLoadKg)
        }
    }

    @Test
    fun `a known max hang sets the pick-up estimate, a routine's own load wins`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        // Max hang 20 mm +30 kg for 10 s: 100 kg total → pick-up 70 kg → careful 56 kg → 90 % for 10 s ≈ 50 kg.
        service.saveBenchmark(Benchmark(id = "b", exerciseSlug = "finger.max_hang", loadKg = 30.0, durationS = 10.0, edgeMm = 20.0,
            source = BenchmarkSource.MANUAL, measuredAt = 1, bodyweightKg = 70.0))
        val fromHang = plannedWork(listOf(RoutineItem("finger.two_arm_pickup", sets = 1, durationS = 10, edgeMm = 20)))
        assertEquals(50.0, fromHang.single().loadKg!!, 1.0)
        service.finishWorkout(repo.openWorkout()!!.id, 5, null)
        val own = plannedWork(listOf(RoutineItem("finger.two_arm_pickup", sets = 1, durationS = 10, edgeMm = 20, loadKg = 20.0)))
        assertEquals(20.0, own.single().loadKg!!, 1e-9)
    }

    @Test
    fun `without a hangboard ramp the block lift ramps up itself`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 68.5, "kg", 1))
        val pickup = RoutineItem("finger.two_arm_pickup", sets = 4, durationS = 10, edgeMm = 20)
        val glides = RoutineItem("warmup.finger_tendon_glides", sets = 1, repsMin = 6, repsMax = 10, warmup = true)
        val id = service.startWorkout(Routine(id = "r1", name = "Finger", items = listOf(glides, pickup)), null)
        val ramp = repo.setsFor(id).filter { it.exerciseSlug == pickup.slug && it.setType == SetType.WARMUP }.sortedBy { it.setIndex }
        val work = repo.setsFor(id).first { it.exerciseSlug == pickup.slug && it.setType == SetType.WORK }
        assertEquals(3, ramp.size)
        assertTrue(ramp.zipWithNext().all { (a, b) -> a.loadKg!! < b.loadKg!! })
        assertTrue(ramp.last().loadKg!! < work.loadKg!!)
        service.finishWorkout(id, 5, null)

        // With the hangboard ramp in the warm-up, and in a routine without any warm-up, nothing is added.
        val hang = RoutineItem("warmup.finger_ramp", sets = 4, durationS = 8, edgeMm = 30, warmup = true)
        val withHang = service.startWorkout(Routine(id = "r2", name = "Finger", items = listOf(hang, pickup)), null)
        assertTrue(repo.setsFor(withHang).none { it.exerciseSlug == pickup.slug && it.setType == SetType.WARMUP })
        service.finishWorkout(withHang, 5, null)
        val bare = service.startWorkout(Routine(id = "r3", name = "Finger", items = listOf(pickup)), null)
        assertTrue(repo.setsFor(bare).none { it.setType == SetType.WARMUP })
    }

    @Test
    fun `pull-ups warm up with an easy set of themselves, mobility not at all`() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 68.5, "kg", 1))
        val circles = RoutineItem("warmup.arm_circles", sets = 1, repsMin = 10, repsMax = 15, warmup = true)
        val pull = RoutineItem("pull.pull_up", sets = 3, repsMin = 8, repsMax = 8)
        val id = service.startWorkout(Routine(id = "p", name = "Zug", items = listOf(circles, pull)), null)
        val warm = repo.setsFor(id).filter { it.exerciseSlug == pull.slug && it.setType == SetType.WARMUP }
        assertEquals(1, warm.size)
        assertEquals(4, warm.single().targetReps)
        service.finishWorkout(id, 5, null)
        val mobility = service.startWorkout(com.cruxcoach.athlete.logic.BuiltinRoutines.byKey(com.cruxcoach.athlete.logic.BuiltinRoutines.MOBILITY_10), null)
        assertTrue(repo.setsFor(mobility).none { it.setType == SetType.WARMUP })
    }

    @Test
    fun `starting another training asks first, then continues or replaces the open one`() {
        val finger = service.startWorkout(com.cruxcoach.athlete.logic.BuiltinRoutines.byKey(com.cruxcoach.athlete.logic.BuiltinRoutines.FINGER_BASICS), "Fingerkraft")
        val mobility = com.cruxcoach.athlete.logic.BuiltinRoutines.byKey(com.cruxcoach.athlete.logic.BuiltinRoutines.MOBILITY_10)!!
        val vm = loaded(com.cruxcoach.android.ui.training.workouts.WorkoutsViewModel(service))
        vm.start(mobility, "Mobility")
        val end = System.currentTimeMillis() + WAIT_MS
        while (vm.pendingStart.value == null) { check(System.currentTimeMillis() < end); Thread.sleep(20) }
        assertEquals(finger, vm.pendingStart.value!!.conflict.open.id)
        assertEquals(0, vm.pendingStart.value!!.conflict.doneSets)
        // Continue: the open finger training stays.
        vm.resolveStart(false)
        while (vm.pendingStart.value != null) Thread.sleep(20)
        Thread.sleep(300)
        assertEquals(finger, repo.openWorkout()!!.id)
        // Replace: nothing was done, so the finger training is dropped and mobility starts.
        vm.start(mobility, "Mobility")
        while (vm.pendingStart.value == null) { check(System.currentTimeMillis() < end); Thread.sleep(20) }
        vm.resolveStart(true)
        while (repo.openWorkout()?.id == finger) { check(System.currentTimeMillis() < end); Thread.sleep(20) }
        val open = repo.openWorkout()!!
        assertEquals(mobility.id, open.routineId)
        assertEquals(null, repo.workout(finger))
        // Starting the open training's own routine again just continues it.
        assertEquals(null, service.openConflict(mobility.id))
    }
}
