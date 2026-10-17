package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_BOARD
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holistic audit W4 / P1-4: the morning before a planned board session is not a rest day – today's planned
 * training sets today's targets until it is done, and says so; earlier days and the energy card stay real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlannedDayTargetsTest : AthleteScreenTest() {

    private val today get() = service.today()

    private fun body() {
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, 175.0, "cm", 1))
    }

    private fun boardToday() = repo.updateProfile {
        it.copy(fuelIntroAccepted = true, weekPlan = mapOf(today.dayOfWeek.isoDayNumber to PLAN_BOARD))
    }

    private fun fuelScreen(vm: FuelViewModel) {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = vm, photoViewModel = photo) }
        waitForTag("fuel_list")
    }

    @Test
    fun `a planned board day sets today's targets before anything is logged and says so`() {
        boardToday()
        body()
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.planned != null && vm.state.value.targets != null }
        val s = vm.state.value
        // Before Today has computed its board plan: the week plan's board day, 60 min hard.
        assertEquals(PlannedTraining.Kind.BOARD, s.planned!!.kind)
        assertEquals(60, s.planned!!.minutes)
        assertEquals(DayLoad.HARD, s.dayLoad)
        assertEquals(420, s.targets!!.carbsG)
        // The goal sheet plans from the same today as the rings.
        assertEquals(s.need, s.weekNeeds.last())
        // The energy card stays on what was logged: today is no training day there.
        val real = service.activities(days = 1)[today.toString()]
        assertNull(real)
        fuelScreen(vm)
        waitForText("Hard training day (planned: board 60 min)")
        click("fuel_carbs")
        waitForTag("fuel_explain_carbs")
        waitForText("Planned today: board 60 min.")
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("fuel_explain_carbs").fetchSemanticsNodes().isEmpty() }
        // The energy sheet's training line says it is the planned training, not one already done.
        click("fuel_energy")
        waitForTag("energy_training_planned")
        waitForText("Planned, not done yet: board 60 min.")

        // Logging the climbing ends the plan: the real day counts.
        service.saveClimbingDay(ClimbingDayEntry("cd1", today.toString(), ClimbingDayKind.GYM_BOULDER, 30, ClimbIntensity.LIGHT))
        compose.waitUntil(WAIT_MS) { vm.state.value.planned == null }
        assertEquals(DayLoad.LIGHT, vm.state.value.dayLoad)
        assertEquals(280, vm.state.value.targets!!.carbsG)
    }

    @Test
    fun `Today's suggestion is the plan nutrition uses, and an earlier day keeps its real data`() {
        boardToday()
        body()
        val hub = loaded(TodayViewModel(service))
        compose.waitUntil(WAIT_MS) { hub.state.value.suggestion?.boardPlan != null }
        val board = hub.state.value.suggestion!!.boardPlan!!
        compose.waitUntil(WAIT_MS) {
            PlannedTraining.decode(repo.setting(PlannedTraining.SETTING_KEY))?.let { it.day == today.toString() && it.kind == PlannedTraining.Kind.BOARD } == true
        }
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.planned != null && vm.state.value.targets != null }
        assertEquals(board.minutes, vm.state.value.planned!!.minutes)
        // Today's food and water tiles target the same day as the nutrition tab.
        compose.waitUntil(WAIT_MS) { hub.state.value.fuelTargets?.carbsG == vm.state.value.targets!!.carbsG }
        assertEquals(vm.state.value.targets!!.waterMl, hub.state.value.fuelTargets!!.waterMl)
        // Yesterday is a rest day as logged.
        vm.previousDay()
        compose.waitUntil(WAIT_MS) { vm.state.value.day == today.minus(DatePeriod(days = 1)) && vm.state.value.targets != null }
        assertNull(vm.state.value.planned)
        assertEquals(DayLoad.REST, vm.state.value.dayLoad)
    }

    @Test
    fun `the day before a planned board day the carbohydrate info says so, the target stays`() {
        val tomorrow = (today.dayOfWeek.isoDayNumber % 7) + 1
        repo.updateProfile { it.copy(fuelIntroAccepted = true, weekPlan = mapOf(tomorrow to PLAN_BOARD)) }
        body()
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.tomorrowHard && vm.state.value.targets != null }
        // Today is a rest day and keeps its target.
        assertEquals(DayLoad.REST, vm.state.value.dayLoad)
        assertEquals(210, vm.state.value.targets!!.carbsG)
        fuelScreen(vm)
        click("fuel_carbs")
        waitForTag("fuel_explain_carbs")
        waitForText("Hard day tomorrow: a carb-rich dinner tonight helps.")
        // Only today looks at tomorrow.
        vm.previousDay()
        compose.waitUntil(WAIT_MS) { vm.state.value.day == today.minus(DatePeriod(days = 1)) }
        assertEquals(false, vm.state.value.tomorrowHard)
    }

    @Test
    fun `two Today screens do not overwrite each other's plan in turn`() {
        boardToday()
        val board = com.cruxcoach.athlete.logic.SessionSuggestion(
            com.cruxcoach.athlete.logic.SuggestionFocus.BOARD_DAY, com.cruxcoach.athlete.model.Routine("r", "board", items = emptyList()),
            listOf(com.cruxcoach.athlete.logic.SuggestionReason.WEEK_PLAN_BOARD), 15, PLAN_BOARD,
            boardPlan = com.cruxcoach.athlete.logic.BoardPlan(com.cruxcoach.domain.playlist.GeneratorType.LIMIT, 60),
        )
        val longer = board.copy(boardPlan = com.cruxcoach.athlete.logic.BoardPlan(com.cruxcoach.domain.playlist.GeneratorType.LIMIT, 90))
        // The first screen stores its plan, the second its own (the athlete chose 90 min there) …
        val first = service.rememberPlannedToday(board, null)
        val second = service.rememberPlannedToday(longer, null)
        assertEquals(second, repo.setting(PlannedTraining.SETTING_KEY))
        // … and the first, re-deriving on that write with its plan unchanged, leaves the newer one alone.
        assertEquals(first, service.rememberPlannedToday(board, first))
        assertEquals(90, PlannedTraining.decode(repo.setting(PlannedTraining.SETTING_KEY))!!.minutes)
    }

    @Test
    fun `a free day's offer leaves a rest day a rest day`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        body()
        val hub = loaded(TodayViewModel(service))
        compose.waitUntil(WAIT_MS) { hub.state.value.suggestion != null }
        compose.waitUntil(WAIT_MS) { PlannedTraining.decode(repo.setting(PlannedTraining.SETTING_KEY))?.day == today.toString() }
        assertNull(PlannedTraining.decode(repo.setting(PlannedTraining.SETTING_KEY))!!.kind)
        val vm = loaded(FuelViewModel(service))
        compose.waitUntil(WAIT_MS) { vm.state.value.targets != null }
        assertNull(vm.state.value.planned)
        assertEquals(DayLoad.REST, vm.state.value.dayLoad)
        assertEquals(210, vm.state.value.targets!!.carbsG)
    }
}
