package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.player.PlayerPhase
import com.cruxcoach.android.ui.training.player.WorkoutPlayerViewModel
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * The player knows a climbing day's training – started from the card of a climbing or board day – from a workout of
 * one's own: only there the warm-up on the wall ends with "time to climb" (device walk 2026-11-22, second look).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClimbingDayPlayerTest : AthleteScreenTest() {

    /** Waits with the main looper running: the ViewModel's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun `a training started from a climbing day's card knows that it is one`() {
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { vm.state.value.suggestion?.reasons?.contains(SuggestionReason.PLANNED_CLIMB) == true }
        val id = service.startSuggestion(vm.state.value.suggestion!!)
        val player = WorkoutPlayerViewModel(service).tracked()
        until { player.state.value.workout?.id == id && player.state.value.climbingDay }
    }

    @Test
    fun `a workout of one's own is no climbing day's training`() {
        val id = service.startWorkout(Routine("r", "Own", items = listOf(RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10, restS = 60))), null)
        val player = WorkoutPlayerViewModel(service).tracked()
        until { player.state.value.workout?.id == id && player.state.value.phase == PlayerPhase.SET }
        assertFalse(player.state.value.climbingDay)
    }
}
