package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.coach.CoachSetupScreen
import com.cruxcoach.android.ui.training.coach.CoachSetupViewModel
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.android.ui.training.workouts.WeekPlanScreen
import com.cruxcoach.android.ui.training.workouts.WeekPlanViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.SetupState
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the second walk through the release app on the phone showed (2026-11-22): the arrow in the coach setup
 * dropped the card silently, a gym day said "Home" under its title, a new athlete's proposed week had no climbing
 * days and nowhere to name them, and the week strip read every free day out as a rest day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceWalkTwoTest : AthleteScreenTest() {

    private fun until(condition: () -> Boolean) = compose.waitUntil(WAIT_MS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        condition()
    }

    private fun coachSetup() = loaded(CoachSetupViewModel(service, mockk(relaxed = true), mockk(relaxed = true)))

    @Test
    fun `the arrow in the coach setup keeps the card that was changed`() {
        val vm = coachSetup()
        // To the card "climbing and experience" without storing anything on the way.
        repeat(2) { vm.skip() }
        until { vm.state.value.step == 2 }
        var left = 0
        render { CoachSetupScreen({ left++ }, {}, {}, {}, viewModel = vm) }
        click("coach_experience_over_5")
        until { vm.state.value.draft.experience == ExperienceBand.OVER_5 }
        vm.update { it.copy(currentGrade = "7a") }
        click("training_back")
        until { left == 1 }
        // Years and grade are kept as "Next" would keep them; the cards that were skipped stay as they were.
        until { repo.profile().coach.let { it.experience == ExperienceBand.OVER_5 && it.currentGrade == "7a" } }
        val coach = repo.profile().coach
        assertEquals(SetupState.IN_PROGRESS, coach.setupState)
        assertNull(coach.goal)
        assertNull(coach.trainingDaysPerWeek)
        assertTrue(coach.climbingDays.isEmpty())
    }

    @Test
    fun `leaving the coach setup stores no card that was only looked at or skipped, and every card that was changed`() {
        val before = repo.profile()
        val vm = coachSetup()
        // Opened and left: nothing is confirmed, nothing is written.
        vm.keepChanges()
        // Changed and skipped: "Skip" drops the card's changes, leaving does not bring them back.
        vm.update { it.copy(goal = CoachGoal.BUILD_STRENGTH) }
        vm.skip()
        until { vm.state.value.step == 1 }
        assertNull(vm.state.value.draft.goal)
        vm.keepChanges()
        assertEquals(before, repo.profile())
        // The week card changed, one card back, then the arrow: the week card is kept although it is not the card shown.
        vm.update { it.copy(climbingDays = setOf(2, 4)) }
        vm.back()
        until { vm.state.value.step == 0 }
        vm.keepChanges()
        until { repo.profile().coach.climbingDays == setOf(2, 4) }
        // As "Next" does, the week card takes the training days it shows.
        assertEquals(before.weeklyGoal.coerceIn(1, 7), repo.profile().coach.trainingDaysPerWeek)
        assertNull(repo.profile().coach.goal)
    }

    @Test
    fun `on a planned gym day the place stands with the block for afterwards, not under the day's title`() {
        // A place of their own, so the card has a place to show.
        repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(EquipmentV2.NONE, EquipmentV2.MAT,
            EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL, EquipmentV2.BANDS)) }
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null }
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        val place = hasTestTag("today_place")
        val withAfterBlock = place and hasAnyAncestor(hasTestTag("today_after_climbing"))
        fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size
        // A day of training: the place is in the row under the title.
        scrollTo("today_list", place)
        assertEquals(1, count(place))
        assertEquals(0, count(withAfterBlock))
        // "Today I climb" in the gym: the block for afterwards says where, the row under "Gym bouldering" does not.
        vm.climbToday(ClimbingDayKind.GYM_BOULDER)
        until { vm.state.value.suggestion?.let { s -> DayPlans.isClimb(s.plannedEntry) && !s.addOn && s.routine.items.any { !it.warmup } } == true }
        scrollTo("today_list", hasTestTag("today_after_climbing"))
        until { count(withAfterBlock) == 1 }
        assertEquals(1, count(place))
        // It still decides the equipment: "no equipment" picked there takes the dumbbells away for today.
        click("today_place")
        click("today_place_${com.cruxcoach.athlete.logic.TrainingPlaces.NO_GEAR_ID}")
        until { EquipmentV2.DUMBBELL !in repo.profile().equipment }
    }

    @Test
    fun `the usual climbing days are named right above the week proposal and the proposal follows`() {
        val vm = loaded(WeekPlanViewModel(service))
        fun climbs(entry: String?) = entry == PLAN_BOARD || DayPlans.isClimb(entry)
        // A new athlete: nobody asked for the climbing days, so the proposed week has none.
        until { vm.state.value.proposal.isNotEmpty() }
        assertTrue(vm.state.value.proposal.values.none { climbs(it) })
        assertTrue(vm.state.value.climbingDays.isEmpty())
        render { WeekPlanScreen({}, viewModel = vm) }
        click("week_plan_climbing_day_2")
        until { repo.profile().coach.climbingDays == setOf(2) && vm.state.value.climbingDays == setOf(2) }
        click("week_plan_climbing_day_4")
        until { repo.profile().coach.climbingDays == setOf(2, 4) && vm.state.value.climbingDays == setOf(2, 4) }
        until { vm.state.value.proposal.let { p -> climbs(p[2]) && climbs(p[4]) } }
        assertEquals(2, vm.state.value.proposal.values.count { climbs(it) })
        // Naming the days plans nothing by itself; one tap takes the proposal.
        assertTrue(repo.profile().weekPlan.isEmpty())
        click("week_plan_proposal_apply")
        until { repo.profile().weekPlan.let { w -> w.isNotEmpty() && w == vm.state.value.proposal } }
        // A second tap takes a day back.
        click("week_plan_climbing_day_4")
        until { repo.profile().coach.climbingDays == setOf(2) && vm.state.value.climbingDays == setOf(2) }
    }

    @Test
    fun `the week strip calls a day a rest day only when it is planned as one`() {
        // A Wednesday; Friday is planned as a rest day, Saturday as a climbing day.
        val wednesday = LocalDate.parse("2026-03-04")
        val ms = wednesday.atStartOfDayIn(service.zone).toEpochMilliseconds() + 18 * 3_600_000L
        service.clock = { ms }
        repo.updateProfile { it.copy(datePlan = mapOf("2026-03-06" to PLAN_REST, "2026-03-07" to "climb:gym_boulder:volume")) }
        val vm = loaded(TodayViewModel(service))
        until { vm.state.value.suggestion != null && vm.state.value.week.size == 7 && vm.state.value.today == wednesday }
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        waitForTag("today_week_strip")
        fun text(id: Int, vararg args: Any) = context.getString(id, *args)
        fun weekday(iso: Int) = DayOfWeek.of(iso).getDisplayName(TextStyle.FULL, Locale.getDefault())
        val nothing = text(R.string.trt_day_nothing)
        val open = text(R.string.trt_day_open)
        var days = emptyList<String>()
        until {
            days = compose.onAllNodes(hasAnyAncestor(hasTestTag("today_week_strip")), useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            days.size == 7
        }
        assertEquals("${weekday(1)}: $nothing", days[0])
        assertEquals("${weekday(2)}: $nothing", days[1])
        // Today is what the card suggests.
        val suggested = "${weekday(3)}: " + text(R.string.trt_day_suggested, "")
        assertTrue(days[2], days[2].startsWith(suggested) && days[2].length > suggested.length)
        // A free day ahead is the coach's to fill, not a rest day.
        assertEquals("${weekday(4)}: $open", days[3])
        assertEquals("${weekday(5)}: " + text(R.string.trw_day_planned, text(R.string.trwo_plan_rest)), days[4])
        assertEquals("${weekday(6)}: " + text(R.string.trw_day_planned, text(R.string.trdp_climb)), days[5])
        assertEquals("${weekday(7)}: $open", days[6])
    }
}
