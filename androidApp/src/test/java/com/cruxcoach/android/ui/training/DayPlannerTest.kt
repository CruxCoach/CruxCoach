package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workouts.DayPlannerSheet
import com.cruxcoach.android.ui.training.workouts.DayPlannerViewModel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_REST
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner 2026-10-19: plan climbing days, build the rest around them, move days – "no time today: rest; a volume
 * boulder day tomorrow, a limit day two days later" – and the rest adjusts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DayPlannerTest : AthleteScreenTest() {

    private val fingers = "builtin:" + BuiltinRoutines.FINGER_BASICS

    @Test
    fun `the owner's example is three taps per day and the days around it follow`() {
        val today = service.today()
        val d1 = today.plus(DatePeriod(days = 1))
        val d3 = today.plus(DatePeriod(days = 3))
        repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) }
        val vm = loaded(DayPlannerViewModel(service))
        render { DayPlannerSheet(onDismiss = {}, viewModel = vm) }

        click("day_planner_day_$today")
        click("week_plan_option_$PLAN_REST")
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[today.toString()] == PLAN_REST }
        // The finger training that gave way gets a free day offered; nothing moves unasked.
        compose.waitUntil(WAIT_MS) { vm.state.value.moveOffer != null }
        assertEquals(fingers, vm.state.value.moveOffer!!.entry)

        click("day_planner_day_$d1")
        click("week_plan_climb_kind_gym_boulder")
        click("week_plan_climb_intensity_volume")
        click("week_plan_climb_apply")
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[d1.toString()] == volume }

        click("day_planner_day_$d3")
        click("week_plan_climb_intensity_limit")
        click("week_plan_climb_apply")
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[d3.toString()] == limit }

        // The free day before the limit day keeps fingers and pulling fresh; the day after it recovers.
        compose.waitUntil(WAIT_MS) { vm.state.value.days.getOrNull(2)?.hint == DayPlans.Hint.BEFORE_LIMIT }
        assertEquals(DayPlans.Hint.AFTER_HARD, vm.state.value.days[4].hint)
        waitForText("legs and core")
        waitForText("Gym bouldering · Limit")

        // "Undo" takes back the last change only.
        click("day_planner_undo")
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[d3.toString()] == null }
        assertEquals(volume, repo.profile().datePlan[d1.toString()])
    }

    @Test
    fun `a training that gave way moves on one tap, and a swap moves both days`() {
        val today = service.today()
        repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) }
        val vm = loaded(DayPlannerViewModel(service))
        render { DayPlannerSheet(onDismiss = {}, viewModel = vm) }
        click("day_planner_day_$today")
        click("week_plan_option_$PLAN_REST")
        compose.waitUntil(WAIT_MS) { vm.state.value.moveOffer != null }
        val target = vm.state.value.moveOffer!!.to
        click("day_planner_move_yes")
        compose.waitUntil(WAIT_MS) { DayPlans.entry(repo.profile(), target) == fingers }

        val later = target.plus(DatePeriod(days = 1))
        click("day_planner_day_$target")
        scrollTo("week_plan_chooser", "week_plan_swap_$later")
        click("week_plan_swap_$later")
        compose.waitUntil(WAIT_MS) { DayPlans.entry(repo.profile(), later) == fingers }
        assertNull(DayPlans.entry(repo.profile(), target))
    }

    @Test
    fun `rest day today from the card, with Undo`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        val vm = loaded(TodayViewModel(service))
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion != null }
        scrollTo("today_list", hasTestTag("today_suggestion_next"))
        click("today_suggestion_next")
        click("today_rest_today")
        val today = service.today().toString()
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[today] == PLAN_REST }
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion?.focus == SuggestionFocus.REST }
        waitForText("Today is now a rest day.")
        clickText("Undo")
        compose.waitUntil(WAIT_MS) { repo.profile().datePlan[today] == null }
        compose.waitUntil(WAIT_MS) { vm.state.value.suggestion?.focus != SuggestionFocus.REST }
    }
}
