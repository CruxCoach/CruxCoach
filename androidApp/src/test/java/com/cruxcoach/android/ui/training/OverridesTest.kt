package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.ui.training.player.PlayerPhase
import com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen
import com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The athlete's word wins (owner 2026-10-15): loads changed during a training
 * carry over, sets can be gone back to and corrected, workouts and their loads
 * can be typed, performance values entered as working sets, and today's
 * suggestion asks first and lets the athlete pick the kind and the place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OverridesTest : AthleteScreenTest() {

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(
            com.cruxcoach.athlete.catalog.EquipmentV2.NONE, com.cruxcoach.athlete.catalog.EquipmentV2.MAT,
            com.cruxcoach.athlete.catalog.EquipmentV2.HANGBOARD, com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR,
            com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL, com.cruxcoach.athlete.catalog.EquipmentV2.BANDS)) }
    }

    private fun player(routine: Routine): Pair<String, WorkoutPlayerViewModel> {
        val id = service.startWorkout(routine, null)
        val vm = WorkoutPlayerViewModel(service).tracked()
        until { vm.state.value.phase == PlayerPhase.SET }
        return id to vm
    }

    /** Waits with the main looper running: the player's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun work(id: String) = repo.setsFor(id).filter { it.setType == SetType.WORK }.sortedBy { it.setIndex }

    @Test
    fun `a load changed in the player carries over to the next sets`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 4, repsMin = 5, repsMax = 5, loadKg = 20.0))))
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        click("player_load_plus")
        until { work(id).all { (it.loadKg ?: 0.0) > 20.0 } }
        val after = work(id)
        assertEquals(1, after.map { it.loadKg }.distinct().size, "${after.map { it.loadKg }}")
        // The later sets take the athlete's load as their plan, so autoregulation goes on from it.
        assertTrue(after.drop(1).all { it.targetLoadKg == it.loadKg })
        waitForTag("player_load_carried")
    }

    @Test
    fun `back shows a done set to correct, and it can be opened again`() {
        val (id, vm) = player(Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 3, repsMin = 10, repsMax = 10, restS = 60))))
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && work(id).first().isCompleted }
        // The rest offers the correction without losing the timer.
        click("player_correct_done")
        waitForTag("player_correct_dialog")
        click("player_reps_minus")
        until { work(id).first().reps == 9 }
        click("player_correct_ok")
        vm.endRest()
        until { vm.state.value.phase == PlayerPhase.SET && vm.state.value.current?.set?.id == work(id)[1].id }
        // Back to the first set: done, its values editable.
        click("player_prev")
        waitForTag("player_done_label")
        assertEquals(work(id).first().id, vm.state.value.current?.set?.id)
        click("player_reps_minus")
        until { work(id).first().reps == 8 }
        // Not done after all: open again, and the training goes on from it.
        click("player_reopen")
        until { !work(id).first().isCompleted }
        waitForTag("player_done")
        // "Next" from a done set and "Continue" bring the athlete back to the open sets.
        assertEquals(work(id).first().id, vm.state.value.current?.set?.id)
    }

    @Test
    fun `the next load set by hand during the rest wins over the reserve answer`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 120))))
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && vm.state.value.restArmed }
        click("player_next_load_minus")
        until { work(id).drop(1).all { (it.loadKg ?: 20.0) < 20.0 } }
        val manual = work(id)[1].loadKg
        // "No reserve" would take a step off – the athlete already decided.
        click("player_rir_0")
        until { work(id).first().rir == 0 }
        Thread.sleep(300)
        assertEquals(manual, work(id)[1].loadKg)
        assertEquals(manual, work(id)[2].loadKg)
    }

    @Test
    fun `a value entered as working sets plans exactly those sets`() {
        // "I can do 5×5 with +20 kg."
        service.saveBenchmark(Benchmark("b", "pull.weighted_pull_up", loadKg = 20.0, reps = 5, bodyweightKg = 70.0,
            source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis(), sets = 5))
        val id = service.startWorkout(Routine("r", "Pull", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 5, repsMin = 5, repsMax = 5))), null)
        assertTrue(work(id).all { it.loadKg == 20.0 }, "${work(id).map { it.loadKg }}")
    }

    @Test
    fun `the value dialog offers working sets first`() {
        val def = service.catalog["pull.weighted_pull_up"]!!
        var saved: Benchmark? = null
        render {
            com.cruxcoach.android.ui.training.benchmarks.BenchmarkEntryDialog(def, repo.profile(), 70.0, null, { "n" }, {}, { saved = it })
        }
        waitForTag("benchmark_mode_sets")
        compose.onNodeWithTag("benchmark_sets").performTextReplacement("5")
        compose.onNodeWithTag("benchmark_load").performTextReplacement("20")
        compose.onNodeWithTag("benchmark_reps").performTextReplacement("5")
        waitForTag("benchmark_preview")
        click("benchmark_save")
        until { saved != null }
        assertEquals(5, saved!!.sets)
        assertEquals(20.0, saved!!.loadKg)
    }

    @Test
    fun `a typed workout adds catalogue exercises, new ones and its name`() {
        val vm = RoutineEditorViewModel(service).tracked()
        render { RoutineEditorScreen(routineId = null, from = null, onBack = {}, onSaved = {}, viewModel = vm) }
        click("routine_editor_type")
        waitForTag("workout_text_input")
        compose.onNodeWithTag("workout_text_input").performTextReplacement("Oberkörper\nKlimmzüge 5x5 +20 kg\nPlank 3x60s\nRussian Twists 3x20 8kg")
        waitForTag("workout_text_line_2")
        waitForTag("workout_text_title")
        click("workout_text_apply")
        until { vm.state.value.items.size == 3 }
        val items = vm.state.value.items.map { it.item }
        assertEquals("pull.weighted_pull_up", items[0].slug)
        assertEquals(20.0, items[0].loadKg)
        assertEquals("core.front_plank", items[1].slug)
        assertEquals(60, items[1].durationS)
        assertTrue(items[2].slug.startsWith("user."), items[2].slug)
        assertEquals("Oberkörper", vm.state.value.name)
        assertTrue(repo.customExercises().any { it.slug == items[2].slug })
        // The editor shows the workout's own load, and "auto" for the others.
        scrollTo("routine_editor_list", hasTestTag("load_${vm.state.value.items[0].key}_value"))
    }

    @Test
    fun `exercises typed into a free training are planned with their numbers`() {
        val id = service.startWorkout(null, null)
        val vm = loaded(com.cruxcoach.android.ui.training.workout.WorkoutViewModel(service))
        render { com.cruxcoach.android.ui.training.workout.WorkoutScreen({}, {}, {}, {}, viewModel = vm) }
        scrollTo("workout_list", hasTestTag("workout_type"))
        click("workout_type")
        waitForTag("workout_text_input")
        compose.onNodeWithTag("workout_text_input").performTextReplacement("Kniebeuge 5x5 60kg\nLiegestütze 3x12")
        waitForTag("workout_text_line_1")
        click("workout_text_apply")
        until { repo.setsFor(id).map { it.exerciseSlug }.distinct().size == 2 }
        val squats = repo.setsFor(id).filter { it.exerciseSlug == "legs.back_squat" && it.setType == SetType.WORK }
        assertEquals(5, squats.size)
        assertTrue(squats.all { it.loadKg == 60.0 && it.targetReps == 5 }, "${squats.map { it.loadKg to it.targetReps }}")
        assertTrue(repo.setsFor(id).filter { it.exerciseSlug == "push.push_up" }.all { it.targetReps == 12 })
    }

    @Test
    fun `today asks how strong and what hurts before the suggestion`() {
        val vm = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", hasTestTag("today_quick_checkin"))
        assertTrue(compose.onAllNodesWithTag("today_suggestion_start").fetchSemanticsNodes().isEmpty())
        click("today_quick_strength_4")
        click("today_quick_hurts_yes")
        click("today_sore_shoulder")
        click("today_quick_show")
        val day = service.today().toString()
        until { repo.checkin(day)?.sore == setOf(InjuryRegion.SHOULDER) }
        assertEquals(4, repo.checkin(day)?.energy)
        // Spared today, not an injury.
        assertTrue(repo.activeInjuries().isEmpty())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        scrollTo("today_list", hasTestTag("today_suggestion_start"))
        val slugs = vm.state.value.suggestion!!.routine.items.map { it.slug }
        assertTrue(slugs.none { service.catalog[it]?.domains?.contains(com.cruxcoach.athlete.catalog.LoadDomain.SHOULDER) == true }, "$slugs")
    }

    @Test
    fun `another suggestion picks the kind of training and the place`() {
        val vm = loaded(TodayViewModel(service))
        vm.saveQuickCheckin(null, emptySet())
        until { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", hasTestTag("today_suggestion_next"))
        click("today_suggestion_next")
        click("today_kind_legs_core")
        until { vm.state.value.suggestion?.focus == SuggestionFocus.LEGS_CORE }
        assertEquals(SuggestionReason.CHOSEN_TODAY, vm.state.value.suggestion!!.reasons.first())
        // A finger day at a place without a hangboard turns into pull and push, with the reason.
        vm.chooseFocus(SuggestionFocus.FINGER_STRENGTH)
        vm.pickPlace(com.cruxcoach.athlete.logic.TrainingPlaces.NO_GEAR_ID)
        until {
            val s = vm.state.value.suggestion
            s != null && s.focus == SuggestionFocus.PULL_PUSH && SuggestionReason.NO_FINGER_EQUIPMENT in s.reasons
        }
        assertNull(vm.state.value.suggestion!!.routine.items.firstOrNull {
            service.catalog[it.slug]?.equipment?.contains(com.cruxcoach.athlete.catalog.EquipmentV2.HANGBOARD) == true
        })
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `what hurts today spares the suggestion but never empties an own workout`() {
        // Device test 2026-10-16: a sore shoulder took pull-ups, bench press and plank out of the athlete's own workout.
        val day = service.today().toString()
        repo.saveCheckin(com.cruxcoach.athlete.model.Checkin(day, 1, sore = setOf(InjuryRegion.SHOULDER)))
        val id = service.startWorkout(Routine("r", "Own", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5),
            RoutineItem("legs.back_squat", sets = 3, repsMin = 5, repsMax = 5, loadKg = 40.0))), null)
        assertEquals(setOf("pull.weighted_pull_up", "legs.back_squat"), repo.setsFor(id).map { it.exerciseSlug }.toSet())
    }

    @Test
    fun `a set done again after not done keeps the athlete's next load and reopens at the plan`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 60))))
        vm.completeCurrent()
        until { vm.state.value.phase == PlayerPhase.REST && vm.state.value.restArmed }
        vm.updateNext(vm.state.value.next!!.set.copy(loadKg = 18.0))
        until { work(id).drop(1).all { it.loadKg == 18.0 } }
        vm.setReserve(0)
        until { work(id).first().rir == 0 }
        vm.endRest()
        until { vm.state.value.phase == PlayerPhase.SET }
        vm.back()
        until { vm.state.value.current?.set?.isCompleted == true }
        vm.update(vm.state.value.current!!.set.copy(reps = 3))
        until { work(id).first().reps == 3 }
        vm.reopen()
        // Open again at the plan: 5 reps, no reserve answer.
        until { !work(id).first().isCompleted && work(id).first().reps == 5 && work(id).first().rir == null }
        vm.update(vm.state.value.current!!.set.copy(reps = 3))
        vm.completeCurrent()
        until { vm.state.value.phase == PlayerPhase.REST && vm.state.value.restArmed }
        Thread.sleep(300); idle()
        // Missed reps would take a step off – the athlete already chose 18 kg for the next sets.
        assertTrue(work(id).drop(1).all { it.loadKg == 18.0 }, "${work(id).map { it.loadKg }}")
    }

    @Test
    fun `an own exercise can be deleted and leaves workouts and history readable`() {
        val slug = kotlinx.coroutines.runBlocking { service.createCustomExercise("Russian Twists", com.cruxcoach.athlete.catalog.ExerciseCategoryV2.CORE,
            com.cruxcoach.athlete.catalog.ExerciseKind.LOAD_REPS, com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL, false, emptyList(), emptyList()) }
        repo.saveRoutine(Routine("r", "Mixed", items = listOf(RoutineItem(slug), RoutineItem("push.push_up"))))
        repo.saveRoutine(Routine("o", "Only", items = listOf(RoutineItem(slug))))
        val vm = com.cruxcoach.android.ui.training.exercises.ExerciseDetailViewModel(service).tracked()
        var left = false
        render { com.cruxcoach.android.ui.training.exercises.ExerciseDetailScreen(slug, onBack = { left = true }, onOpenExercise = {}, onOpenWorkout = {}, viewModel = vm) }
        click("exercise_detail_more")
        click("exercise_detail_delete")
        click("exercise_detail_delete_confirm")
        until { left }
        assertNull(service.catalog[slug])
        assertEquals(listOf("push.push_up"), repo.routines().single { it.id == "r" }.items.map { it.slug })
        assertTrue(repo.routines().none { it.id == "o" })
        assertEquals("Russian twists", service.catalog.fallbackFor(slug).name("de"))
    }
}
