package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_FREE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.isoDayNumber
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Follow-up to the device walk of 2026-11-22: a climbing day taken back goes back to what the standard week plans
 * (not always to "free"), every way of taking it back can be undone, and the card never shows the new kind with the
 * climbing day still behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClimbingDayBackAppTest : AthleteScreenTest() {

    private val fingers = "builtin:" + BuiltinRoutines.FINGER_BASICS
    private val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry

    /** Waits with the main looper running: the ViewModel's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun `the second tap brings the standard week's training back and can be undone`() {
        val today = service.today()
        val day = today.toString()
        repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) }
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion?.plannedEntry == fingers }
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { vm.state.value.suggestion?.plannedEntry == volume }
        vm.noClimbToday()
        // Not "free": the finger training the standard week plans for the day is back, and no entry of the day is left.
        until { DayPlans.entry(repo.profile(), today) == fingers && vm.state.value.suggestion?.plannedEntry == fingers && vm.state.value.noClimbTodayUndo != null }
        assertTrue(repo.profile().datePlan.isEmpty(), "${repo.profile().datePlan}")
        // "Rückgängig" has the climbing day.
        val before = vm.state.value.noClimbTodayUndo!!
        assertEquals(volume, before[day])
        vm.consumeNoClimbTodayUndo()
        until { vm.state.value.noClimbTodayUndo == null }
        vm.undoNoClimbToday(before)
        until { repo.profile().datePlan[day] == volume && vm.state.value.suggestion?.plannedEntry == volume }
    }

    @Test
    fun `where the standard week plans the climbing the second tap frees today only`() {
        val today = service.today()
        val day = today.toString()
        val iso = today.dayOfWeek.isoDayNumber
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        repo.updateProfile { it.copy(weekPlan = mapOf(iso to limit)) }
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion?.plannedEntry == limit }
        vm.noClimbToday()
        // Removing an entry of the day would leave the week's climbing day standing: today is planned free instead.
        until { repo.profile().datePlan[day] == PLAN_FREE && vm.state.value.suggestion?.plannedEntry == null && vm.state.value.noClimbTodayUndo != null }
        assertEquals(limit, repo.profile().weekPlan[iso])
        val before = vm.state.value.noClimbTodayUndo!!
        vm.consumeNoClimbTodayUndo()
        vm.undoNoClimbToday(before)
        // Back with "Rückgängig": the limit day of the standard week, as it was – never a volume day.
        until { repo.profile().datePlan[day] == null && vm.state.value.suggestion?.plannedEntry == limit }
    }

    @Test
    fun `another kind chosen takes the climbing day off the card at once and can be undone`() {
        val today = service.today()
        val day = today.toString()
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { repo.profile().datePlan[day] == volume && vm.state.value.suggestion?.plannedEntry == volume }
        // Every state of the card from here on: the new kind never stands with the climbing day still behind it (the
        // first refresh after the choice read the plan from before it).
        val mixed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.state.collect { s ->
                if (s.wish == SuggestionFocus.PULL_PUSH && DayPlans.isClimb(s.suggestion?.plannedEntry)) mixed += "${s.suggestion?.focus} over ${s.suggestion?.plannedEntry}"
            }
        }
        vm.chooseFocus(SuggestionFocus.PULL_PUSH)
        until {
            val s = vm.state.value
            DayPlans.entry(repo.profile(), today) == null && s.wish == SuggestionFocus.PULL_PUSH && s.noClimbTodayUndo != null &&
                s.suggestion?.let { it.focus == SuggestionFocus.PULL_PUSH && it.plannedEntry == null } == true
        }
        watcher.cancel()
        assertTrue(mixed.isEmpty(), "$mixed")
        // "Rückgängig": the climbing day is back, and the kind chosen over it gives way to it.
        val before = vm.state.value.noClimbTodayUndo!!
        assertEquals(volume, before[day])
        vm.consumeNoClimbTodayUndo()
        vm.undoNoClimbToday(before)
        until {
            val s = vm.state.value
            repo.profile().datePlan[day] == volume && s.wish == null && s.suggestion?.plannedEntry == volume &&
                s.suggestion?.reasons?.contains(SuggestionReason.PLANNED_CLIMB) == true
        }
    }

    @Test
    fun `a day without climbing planned offers nothing to undo`() {
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        vm.chooseFocus(SuggestionFocus.LEGS_CORE)
        until { vm.state.value.wish == SuggestionFocus.LEGS_CORE && vm.state.value.suggestion?.focus == SuggestionFocus.LEGS_CORE }
        assertEquals(null, vm.state.value.noClimbTodayUndo)
        assertTrue(repo.profile().datePlan.isEmpty(), "${repo.profile().datePlan}")
    }
}
