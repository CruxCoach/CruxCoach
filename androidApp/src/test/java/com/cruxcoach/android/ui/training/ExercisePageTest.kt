package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.player.PlayerPhase
import com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEventKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The training page (owner 2026-10-17): one screen per exercise with all its sets – logging as
 * planned in one tap, fewer or more in one tap, the rest as a bar with the sets still in view,
 * ending an exercise or adding a set in one tap, moving between exercises without logging a skip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExercisePageTest : AthleteScreenTest() {

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(
            com.cruxcoach.athlete.catalog.EquipmentV2.NONE, com.cruxcoach.athlete.catalog.EquipmentV2.MAT,
            com.cruxcoach.athlete.catalog.EquipmentV2.HANGBOARD, com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR,
            com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL, com.cruxcoach.athlete.catalog.EquipmentV2.PLATES)) }
    }

    private fun player(routine: Routine): Pair<String, WorkoutPlayerViewModel> {
        val id = service.startWorkout(routine, null)
        val vm = WorkoutPlayerViewModel(service).tracked()
        until { vm.state.value.phase == PlayerPhase.SET }
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        return id to vm
    }

    /** Waits with the main looper running: the player's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun work(id: String, slug: String? = null) = repo.setsFor(id)
        .filter { it.setType == SetType.WORK && (slug == null || it.exerciseSlug == slug) }.sortedBy { it.setIndex }

    /** Rows below the fold are not composed in the lazy page: scroll to them first. */
    private fun tap(tag: String) {
        scrollTo("player_set_view", tag)
        click(tag)
    }

    private val pushUps = Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 4, repsMin = 10, repsMax = 10, restS = 60)))

    @Test
    fun `one tap logs the set as planned and the next row is due`() {
        val (id, vm) = player(pushUps)
        click("player_done")
        until { work(id).first().isCompleted }
        assertEquals(10, work(id).first().reps)
        until { vm.state.value.current?.set?.id == work(id)[1].id }
        scrollTo("player_set_view", "player_row_active")
        scrollTo("player_set_view", "player_row_${work(id).first().id}")
        waitForTag("player_outcome_${work(id).first().id}")
    }

    @Test
    fun `a result chip logs fewer reps in one tap`() {
        val (id, vm) = player(pushUps)
        tap("player_result_8")
        until { work(id).first().isCompleted }
        assertEquals(8, work(id).first().reps)
        until { vm.state.value.phase == PlayerPhase.REST }
    }

    @Test
    fun `missed reps with a load move the next sets, and the rows show it`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(
            RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 120))))
        tap("player_result_3")
        until { work(id).first().isCompleted && work(id).first().reps == 3 }
        until { work(id).drop(1).all { (it.loadKg ?: 20.0) < 20.0 } }
        scrollTo("player_set_view", "player_adjustment")
        scrollTo("player_set_view", "player_moved_${work(id)[1].id}")
    }

    @Test
    fun `a chip during the rest ends it and logs the next set`() {
        val (id, vm) = player(pushUps)
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && vm.state.value.restArmed }
        waitForTag("player_rest")
        // The next set is the active row during the rest: one tap logs it.
        tap("player_result_9")
        until { work(id)[1].isCompleted && work(id)[1].reps == 9 }
        until { vm.state.value.lastDone?.set?.id == work(id)[1].id }
    }

    @Test
    fun `the reserve answer sits on the done row`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(
            RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 120))))
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.REST && vm.state.value.restArmed }
        tap("player_rir_2")
        until { work(id).first().rir == 2 }
    }

    @Test
    fun `ending the exercise after two sets takes the rest, is noted as ended early and can be undone`() {
        val (id, vm) = player(Routine("r", "Two", items = listOf(
            RoutineItem("push.push_up", sets = 4, repsMin = 10, repsMax = 10, restS = 0),
            RoutineItem("core.dead_bug", sets = 2, repsMin = 10, repsMax = 10))))
        click("player_done")
        until { work(id, "push.push_up")[0].isCompleted }
        click("player_done")
        until { work(id, "push.push_up")[1].isCompleted }
        tap("player_end_exercise")
        until { work(id, "push.push_up").size == 2 }
        // Not a dislike: ended early, never a skip.
        until { repo.suggestionEventsForDay(service.today().toString()).any { it.kind == SuggestionEventKind.FEEDBACK &&
            it.focus == com.cruxcoach.athlete.logic.PreferenceLearning.ENDED_EARLY } }
        assertTrue(repo.suggestionEventsForDay(service.today().toString()).none { it.kind == SuggestionEventKind.SKIPPED_SET })
        until { vm.state.value.current?.set?.exerciseSlug == "core.dead_bug" }
        vm.undoEnd()
        until { work(id, "push.push_up").size == 4 }
    }

    @Test
    fun `plus set adds one more with the plan, not the missed result`() {
        val (id, vm) = player(Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 1, repsMin = 10, repsMax = 10))))
        tap("player_result_8")
        until { work(id).first().isCompleted }
        tap("player_add_set")
        until { work(id).size == 2 }
        assertEquals(10, work(id)[1].targetReps)  // the plan, not the 8 done
        assertEquals(10, work(id)[1].reps)
        until { vm.state.value.current?.set?.id == work(id)[1].id }
    }

    @Test
    fun `moving to another exercise in the strip logs no skip`() {
        val (id, vm) = player(Routine("r", "Two", items = listOf(
            RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10),
            RoutineItem("core.dead_bug", sets = 2, repsMin = 10, repsMax = 10))))
        click("player_strip_1")
        until { vm.state.value.current?.set?.exerciseSlug == "core.dead_bug" }
        click("player_done")
        until { work(id, "core.dead_bug").first().isCompleted }
        assertTrue(repo.suggestionEventsForDay(service.today().toString()).none { it.kind == SuggestionEventKind.SKIPPED_SET })
        // The skipped-over exercise is still there and comes back.
        assertTrue(work(id, "push.push_up").none { it.isCompleted })
    }

    @Test
    fun `back in the training it goes on after the set done last`() {
        // Device test 2026-10-17: after reopening, the page jumped to an exercise skipped earlier.
        val (id, vm) = player(Routine("r", "Two", items = listOf(
            RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10),
            RoutineItem("core.dead_bug", sets = 2, repsMin = 10, repsMax = 10, restS = 0))))
        click("player_strip_1")
        until { vm.state.value.current?.set?.exerciseSlug == "core.dead_bug" }
        click("player_done")
        until { work(id, "core.dead_bug").first().isCompleted }
        val again = WorkoutPlayerViewModel(service).tracked()
        until { again.state.value.phase == PlayerPhase.SET && again.state.value.current != null }
        assertEquals(work(id, "core.dead_bug")[1].id, again.state.value.current?.set?.id)
    }

    @Test
    fun `the end page asks how hard it was and finishes in one tap`() {
        val (id, vm) = player(Routine("r", "One", items = listOf(RoutineItem("push.push_up", sets = 1, repsMin = 10, repsMax = 10))))
        click("player_done")
        until { vm.state.value.phase == PlayerPhase.COMPLETE }
        // The last exercise stays on screen ("+ set", corrections) until the athlete wraps up.
        click("player_all_done")
        waitForTag("player_complete")
        click("player_rpe_7")
        click("player_complete_finish")
        until { repo.workout(id)?.endedAt != null }
        assertEquals(7, repo.workout(id)?.sessionRpe)
    }
}
