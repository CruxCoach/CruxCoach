package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.ui.training.player.PlayerPhase
import com.cruxcoach.android.ui.training.player.WorkoutPlayerScreen
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workouts.RoutineEditorScreen
import com.cruxcoach.android.ui.training.workouts.RoutineEditorViewModel
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comparison review #13: where the week stands for an area shows where exercises are left out, removed or edited –
 * a soft hint ("Pulling this week: 0/6 sets"), never a lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeeklyHintTest : AthleteScreenTest() {

    @Before
    fun athlete() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(
            com.cruxcoach.athlete.catalog.EquipmentV2.NONE, com.cruxcoach.athlete.catalog.EquipmentV2.MAT,
            com.cruxcoach.athlete.catalog.EquipmentV2.HANGBOARD, com.cruxcoach.athlete.catalog.EquipmentV2.PULL_UP_BAR,
            com.cruxcoach.athlete.catalog.EquipmentV2.DUMBBELL, com.cruxcoach.athlete.catalog.EquipmentV2.BANDS)) }
    }

    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun `the editor says where the week stands and what the workout adds`() {
        val pull = service.weeklyFor("pull.pull_up")!!
        assertEquals(VolumeArea.PULL, pull.area)
        assertTrue(pull.goal > 0)
        repo.saveRoutine(Routine("own", "Pull", items = listOf(RoutineItem("pull.pull_up", sets = 4), RoutineItem("pull.dumbbell_row", sets = 3)),
            createdAt = 1, updatedAt = 1))
        val vm = RoutineEditorViewModel(service).tracked()
        render { RoutineEditorScreen(routineId = "own", from = null, onBack = {}, onSaved = {}, viewModel = vm) }
        scrollTo("routine_editor_list", "routine_editor_weekly")
        // 4 pull-ups + 3 one-arm rows on each side = 10 sets of pulling.
        val line = context.getString(com.cruxcoach.android.R.string.trv_hint_plus,
            context.getString(com.cruxcoach.android.R.string.trv_hint_sets, context.getString(com.cruxcoach.android.R.string.trv_area_pull),
                formatNumber(pull.effective), pull.goal), 10)
        waitForText(line)
    }

    @Test
    fun `leaving out on the card shows the week`() {
        val today = loaded(TodayViewModel(service))
        today.saveQuickCheckin(null, emptySet())
        until { today.state.value.suggestion != null && today.state.value.weekly.isNotEmpty() }
        val first = today.state.value.suggestion!!.routine.items.first { !it.warmup }.slug
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = today) }
        scrollTo("today_list", hasTestTag("today_item_$first"))
        click("today_item_$first")
        waitForTag("today_swap_weekly")
    }

    @Test
    fun `removing an exercise in the training shows the week first`() {
        service.startWorkout(Routine("r", "Pull", items = listOf(RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 5),
            RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10))), null)
        val vm = WorkoutPlayerViewModel(service).tracked()
        until { vm.state.value.phase == PlayerPhase.SET }
        render { WorkoutPlayerScreen({}, {}, {}, {}, viewModel = vm, learningBadge = { _, _ -> }) }
        until { vm.state.value.current?.set?.exerciseSlug == "pull.pull_up" }
        click("player_exercise_menu")
        click("player_remove_exercise")
        waitForTag("player_remove_weekly")
        // Only a hint: removing still works.
        click("player_remove_confirm")
        until { repo.setsFor(vm.state.value.workout!!.id).none { it.exerciseSlug == "pull.pull_up" } }
        compose.onNodeWithTag("player_remove_weekly").assertDoesNotExist()
    }
}
