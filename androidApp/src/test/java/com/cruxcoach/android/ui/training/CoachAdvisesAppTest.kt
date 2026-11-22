package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithTag
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_BOARD
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coach advises, the athlete decides (owner 2026-11-22), on the app's side: what the athlete decided on today's
 * card holds after a restart, "anyway" is on the card wherever the coach held something back and goes with the day
 * it was for, and "Heute klettere ich" changes only what was asked for: a second tap takes the day back, another kind
 * of training ends it, and no tap makes a volume day of a planned hard one. The lighter week is in
 * [LighterWeekOfferTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CoachAdvisesAppTest : AthleteScreenTest() {

    @Before
    fun gear() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD,
            EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL, EquipmentV2.BANDS, EquipmentV2.BOARD, EquipmentV2.WALL)) }
    }

    /** Waits with the main looper running: the ViewModel's writes come back on Main. */
    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun kindsChosen(day: String) =
        repo.suggestionEventsForDay(day).count { it.focus.orEmpty().startsWith(PreferenceLearning.CHOSEN_KIND) }

    // ── "Anyway" ─────────────────────────────────────────────────────

    @Test
    fun `anyway for a kind the coach held back holds after a restart`() {
        val day = service.today().toString()
        // Tired fingers in the check-in: a chosen finger day becomes pull and push, with "anyway" on the card.
        repo.saveCheckin(Checkin(day, 1, fingers = 1))
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        vm.chooseFocus(SuggestionFocus.FINGER_STRENGTH)
        until { vm.state.value.suggestion?.heldBack == SuggestionFocus.FINGER_STRENGTH }
        assertEquals(SuggestionFocus.PULL_PUSH, vm.state.value.suggestion!!.focus)
        vm.insistToday()
        // Wait for the ledger too: the restart reads what was written.
        until {
            vm.state.value.suggestion?.focus == SuggestionFocus.FINGER_STRENGTH && vm.state.value.suggestion?.heldBack == null &&
                repo.suggestionEventsForDay(day).any { it.focus == PreferenceLearning.INSIST }
        }
        val again = loaded(TodayViewModel(service))
        until { again.state.value.suggestion != null }
        val s = again.state.value.suggestion!!
        assertEquals(SuggestionFocus.FINGER_STRENGTH, s.focus, "${s.reasons}")
        assertNull(s.heldBack)
        // The card still says what the coach sees.
        assertTrue(SuggestionReason.FINGERS_TIRED in s.reasons, "${s.reasons}")
        // "Anyway" was for that kind: another kind the coach advises against is held back again, also after a restart.
        again.chooseFocus(SuggestionFocus.BOARD_DAY)
        until { again.state.value.suggestion?.heldBack == SuggestionFocus.BOARD_DAY && kindsChosen(day) == 2 }
        val third = loaded(TodayViewModel(service))
        until { third.state.value.suggestion != null }
        assertEquals(SuggestionFocus.BOARD_DAY, third.state.value.suggestion!!.heldBack, "${third.state.value.suggestion!!.reasons}")
    }

    @Test
    fun `anyway is on the card of a board day held back to a light session`() {
        val today = service.today()
        // A limit day yesterday: the board day planned for today is the light session, with "anyway" for the planned one.
        val yesterday = today.minus(DatePeriod(days = 1)).toString()
        service.saveClimbingDay(ClimbingDayEntry("c-$yesterday", yesterday, ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.LIMIT))
        repo.updateProfile { DayPlans.withDay(it, today, PLAN_BOARD, today) }
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion?.let { it.boardPlan != null && it.heldBack == SuggestionFocus.BOARD_DAY } == true }
        assertTrue(SuggestionReason.BOARD_LIGHT in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
        // The board card has its own buttons: "anyway" must be among them (it was on the other layout only).
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", "today_insist")
        click("today_insist")
        until { vm.state.value.suggestion?.let { it.focus == SuggestionFocus.BOARD_DAY && it.heldBack == null && SuggestionReason.BOARD_LIGHT !in it.reasons } == true }
        assertTrue(SuggestionReason.RECOVERY_AFTER_LIMIT in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("today_insist", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a planned board day held back for tired fingers comes back with anyway`() {
        val today = service.today()
        repo.saveCheckin(Checkin(today.toString(), 1, fingers = 1))
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        // "Heute klettere ich" → board: the day is planned, the coach advises against it, and the card offers "anyway".
        vm.climbToday(null)
        until { vm.state.value.suggestion?.plannedEntry == PLAN_BOARD && vm.state.value.suggestion?.heldBack == SuggestionFocus.BOARD_DAY }
        assertTrue(vm.state.value.suggestion!!.focus != SuggestionFocus.BOARD_DAY, "${vm.state.value.suggestion!!.focus}")
        vm.insistToday()
        until { vm.state.value.suggestion?.focus == SuggestionFocus.BOARD_DAY && vm.state.value.suggestion?.heldBack == null }
        assertTrue(SuggestionReason.FINGERS_TIRED in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
        // The second tap takes the board day back, and "anyway" with it: planned again, the coach advises again –
        // also after a restart.
        vm.noClimbToday()
        until { DayPlans.entry(repo.profile(), today) == null && vm.state.value.suggestion?.let { it.plannedEntry == null && it.heldBack == null } == true }
        vm.climbToday(null)
        until { vm.state.value.suggestion?.plannedEntry == PLAN_BOARD && vm.state.value.suggestion?.heldBack == SuggestionFocus.BOARD_DAY }
        val again = loaded(TodayViewModel(service))
        until { again.state.value.suggestion != null }
        assertEquals(SuggestionFocus.BOARD_DAY, again.state.value.suggestion!!.heldBack, "${again.state.value.suggestion!!.reasons}")
    }

    // ── "Heute klettere ich" ─────────────────────────────────────────

    @Test
    fun `a second tap takes the climbing day back and no tap makes a volume day of a planned limit day`() {
        val today = service.today()
        val day = today.toString()
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { repo.profile().datePlan[day] == volume && vm.state.value.climbTodayUndo != null }
        // "Rückgängig" holds the plan before: nothing.
        assertTrue(vm.state.value.climbTodayUndo!!.isEmpty(), "${vm.state.value.climbTodayUndo}")
        vm.consumeClimbTodayUndo()
        until { vm.state.value.climbTodayUndo == null && vm.state.value.suggestion?.plannedEntry == volume }
        // The second tap on the chip (the sheet asks for this once the chip is the selected one) takes the day back.
        vm.noClimbToday()
        until { DayPlans.entry(repo.profile(), today) == null && vm.state.value.suggestion?.plannedEntry == null }
        // A limit day from the planner, with a board session chosen over it – the one kind that leaves the climbing
        // day standing. A tap that asks for the kind the day already is gives the day back to the plan and leaves the
        // plan alone: no volume day out of the limit day, no undo for a change that is none.
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        repo.updateProfile { DayPlans.withDay(it, today, limit, today) }
        until { vm.state.value.suggestion?.plannedEntry == limit }
        vm.chooseFocus(SuggestionFocus.BOARD_DAY)
        until { vm.state.value.wish == SuggestionFocus.BOARD_DAY && vm.state.value.suggestion?.focus == SuggestionFocus.BOARD_DAY }
        assertEquals(limit, repo.profile().datePlan[day])
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { vm.state.value.wish == null && vm.state.value.suggestion?.reasons?.contains(SuggestionReason.PLANNED_CLIMB) == true }
        assertEquals(limit, repo.profile().datePlan[day])
        assertNull(vm.state.value.climbTodayUndo)
        // Another kind is a change: the rope day replaces it, and "Rückgängig" has the limit day.
        val rope = DayPlans.Climb(ClimbingDayKind.GYM_ROPE, ClimbIntensity.VOLUME).entry
        vm.climbToday(ClimbingDayKind.GYM_ROPE)
        until { repo.profile().datePlan[day] == rope && vm.state.value.climbTodayUndo != null }
        assertEquals(limit, vm.state.value.climbTodayUndo!![day])
        vm.undoRestToday(vm.state.value.climbTodayUndo!!)
        until { repo.profile().datePlan[day] == limit && vm.state.value.suggestion?.plannedEntry == limit }
        // The second tap takes a planned limit day back too: to a free day, never to a volume day.
        vm.noClimbToday()
        until { DayPlans.entry(repo.profile(), today) == null && vm.state.value.suggestion?.plannedEntry == null }
    }

    @Test
    fun `another kind of training chosen for today ends the climbing day`() {
        val today = service.today()
        val day = today.toString()
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { repo.profile().datePlan[day] == volume && vm.state.value.suggestion?.plannedEntry == volume }
        vm.chooseFocus(SuggestionFocus.PULL_PUSH)
        // The card is the pull session, without the climbing day behind it.
        until {
            DayPlans.entry(repo.profile(), today) == null &&
                vm.state.value.suggestion?.let { it.focus == SuggestionFocus.PULL_PUSH && it.plannedEntry == null } == true
        }
        // And the other way round: climbing today ends the chosen kind.
        vm.climbToday(ClimbingDayKind.OUTDOOR)
        val outdoor = DayPlans.Climb(ClimbingDayKind.OUTDOOR, ClimbIntensity.VOLUME).entry
        until { vm.state.value.wish == null && vm.state.value.suggestion?.plannedEntry == outdoor }
        assertTrue(SuggestionReason.PLANNED_CLIMB in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
    }

    @Test
    fun `the chip under climbing today sets the day and its second tap takes it back`() {
        val today = service.today()
        val day = today.toString()
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        fun tapBoulderChip() {
            scrollTo("today_list", "today_suggestion_next")
            click("today_suggestion_next")
            click("today_climb_gym_boulder")
        }
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        tapBoulderChip()
        until { repo.profile().datePlan[day] == volume && vm.state.value.suggestion?.plannedEntry == volume }
        // The sheet closed with the tap. Opened again, the chip is the selected one: its tap takes the day back.
        tapBoulderChip()
        until { DayPlans.entry(repo.profile(), today) == null && vm.state.value.suggestion?.plannedEntry == null }
        // A limit day from the planner shows the same chip as selected: its tap takes the day back as well. It never
        // becomes the volume day a first tap would plan.
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        repo.updateProfile { DayPlans.withDay(it, today, limit, today) }
        until { vm.state.value.suggestion?.plannedEntry == limit }
        tapBoulderChip()
        until { DayPlans.entry(repo.profile(), today) == null && vm.state.value.suggestion?.plannedEntry == null }
        assertNull(repo.profile().datePlan[day])
    }

    @Test
    fun `a kind chosen before climbing today does not come back after a restart`() {
        val today = service.today()
        val day = today.toString()
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        vm.chooseFocus(SuggestionFocus.PULL_PUSH)
        until { vm.state.value.wish == SuggestionFocus.PULL_PUSH && vm.state.value.suggestion?.focus == SuggestionFocus.PULL_PUSH && kindsChosen(day) == 1 }
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        // The climbing day is the later decision: the card shows it, and the ledger knows the kind no longer stands.
        until {
            val s = vm.state.value
            s.wish == null && s.suggestion?.plannedEntry == volume && s.suggestion?.reasons?.contains(SuggestionReason.PLANNED_CLIMB) == true &&
                kindsChosen(day) == 2
        }
        val again = loaded(TodayViewModel(service))
        until { again.state.value.suggestion != null }
        val s = again.state.value
        assertNull(s.wish)
        assertEquals(volume, s.suggestion!!.plannedEntry)
        assertTrue(SuggestionReason.PLANNED_CLIMB in s.suggestion!!.reasons && SuggestionReason.CHOSEN_TODAY !in s.suggestion!!.reasons,
            "${s.suggestion!!.focus} ${s.suggestion!!.reasons}")
    }
}
