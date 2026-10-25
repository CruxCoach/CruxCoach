package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.exercises.ExerciseCatalogScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseCatalogViewModel
import com.cruxcoach.android.ui.training.exercises.ExerciseDetailScreen
import com.cruxcoach.android.ui.training.exercises.ExerciseDetailViewModel
import com.cruxcoach.android.ui.training.workout.RoutinesScreen
import com.cruxcoach.android.ui.training.workout.RoutinesViewModel
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Warn, never block (owner 2026-10-25, F1): the coach's suggestions leave out
 * what an open injury rules out, but an exercise the athlete adds or keeps in
 * their own workout is only warned about, with "Add anyway".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AddDespiteInjuryTest : AthleteScreenTest() {

    private val maxHang = "finger.max_hang"
    private val pullUp = "pull.pull_up"
    private val own = Routine("own", "Mine", items = listOf(
        RoutineItem(maxHang, sets = 3, durationS = 10, restS = 180),
        RoutineItem(pullUp, sets = 3, repsMin = 5, repsMax = 5, restS = 120),
    ))

    @Before
    fun injuredFinger() {
        repo.saveInjury(Injury("f", InjuryRegion.FINGER, InjurySide.LEFT, "A2", 4, startedOn = service.today().toString(), updatedAt = 1))
        repo.saveRoutine(own)
    }

    private fun slugsOfOpenTraining() = repo.openWorkout()?.let { w -> repo.setsFor(w.id).map { it.exerciseSlug }.toSet() }.orEmpty()

    @Test
    fun `an exercise the injury rules out is added only when the athlete says so`() {
        val id = service.startWorkout(null, "Free")
        assertFalse(service.addExercise(id, maxHang))
        assertTrue(repo.setsFor(id).none { it.exerciseSlug == maxHang })

        assertTrue(service.addExercise(id, maxHang, despiteInjury = true))
        assertTrue(repo.setsFor(id).any { it.exerciseSlug == maxHang })
    }

    @Test
    fun `starting an own workout asks first, and without them starts the rest`() {
        assertEquals(listOf(maxHang), service.ruledOutByInjury(own).map { it.slug })
        val vm = loaded(RoutinesViewModel(service))
        render { RoutinesScreen({}, {}, viewModel = vm) }
        vm.start(own, "Mine")

        waitForTag("injury_start_dialog")
        assertTrue(repo.openWorkout() == null)
        click("injury_start_without")

        compose.waitUntil(WAIT_MS) { repo.openWorkout() != null }
        assertEquals(setOf(pullUp), slugsOfOpenTraining())
    }

    @Test
    fun `add anyway keeps the exercise in the own workout`() {
        val vm = loaded(RoutinesViewModel(service))
        render { RoutinesScreen({}, {}, viewModel = vm) }
        vm.start(own, "Mine")

        waitForTag("injury_start_dialog")
        click("injury_start_anyway")

        compose.waitUntil(WAIT_MS) { repo.openWorkout() != null }
        assertEquals(setOf(maxHang, pullUp), slugsOfOpenTraining())
    }

    @Test
    fun `the exercise page warns and adds anyway`() {
        val vm = loaded(ExerciseDetailViewModel(service).also { it.load(maxHang) })
        render { ExerciseDetailScreen(maxHang, {}, {}, {}, viewModel = vm) }
        click("exercise_detail_add")

        clickText("Add anyway")
        compose.waitUntil(WAIT_MS) { maxHang in slugsOfOpenTraining() }
    }

    @Test
    fun `picking it for the running training warns and adds anyway`() {
        val id = service.startWorkout(null, "Free")
        val vm = loaded(ExerciseCatalogViewModel(service))
        vm.setQuery("Max Hangs")
        render { ExerciseCatalogScreen(id, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("exercise_list", "exercise_row_$maxHang")
        click("exercise_row_$maxHang")

        clickText("Add anyway")
        compose.waitUntil(WAIT_MS) { repo.setsFor(id).any { it.exerciseSlug == maxHang } }
    }
}
