package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.EnergyBeforeTraining
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holistic audit P2-17: after days with little logged energy, Today's card names it with the numbers before
 * maximal finger work and offers "lighter" as one tap – the suggestion itself stays as it is until the athlete
 * taps; calories and body numbers stay hidden where the athlete hid them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnergyHintTest : AthleteScreenTest() {

    private fun lowEnergyDays() {
        val today = service.today()
        repo.updateProfile {
            it.copy(fuelIntroAccepted = true, checkinEnabled = false, sex = Sex.MALE, birthYear = today.year - 30,
                equipmentConfigured = true, equipment = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES,
                    EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.MAT))
        }
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, 176.0, "cm", 1))
        // Three complete earlier days (two meals each) with 800 kcal: far under any need.
        (1..3).forEach { back ->
            val day = today.minus(DatePeriod(days = back)).toString()
            repo.saveFoodLog(FoodLogEntry("b$back", day, 1, Meal.BREAKFAST, name = "Oats", kcal = 400.0, carbsG = 60.0))
            repo.saveFoodLog(FoodLogEntry("d$back", day, 2, Meal.DINNER, name = "Soup", kcal = 400.0, carbsG = 50.0))
        }
    }

    private fun hardFingerDay(vm: TodayViewModel) {
        vm.chooseFocus(SuggestionFocus.FINGER_STRENGTH)
        compose.waitUntil(WAIT_MS) {
            val s = vm.state.value
            s.suggestion?.focus == SuggestionFocus.FINGER_STRENGTH && RedsSignal.HIGH_DEFICIT in s.energy.signals
        }
        val s = vm.state.value
        assertTrue("max finger work expected: ${s.suggestion?.routine?.items?.map { it.slug }}",
            EnergyBeforeTraining.isHardOnFingers(s.suggestion!!, s.catalog))
    }

    @Test
    fun `little energy before max hangs is named with its numbers and lighter is one tap`() {
        lowEnergyDays()
        val vm = loaded(TodayViewModel(service))
        hardFingerDay(vm)
        val need = vm.state.value.energy.avgNeedKcal!!
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", hasTestTag("today_energy_hint"))
        waitForText("Last 3 logged days: on average 800 of $need kcal – a snack with carbohydrates before hard finger work helps.")
        // Nothing changed by itself: the level is the coach's until the athlete taps "lighter".
        assertEquals(0, vm.state.value.levelShift)
        click("today_energy_hint_lighter")
        compose.waitUntil(WAIT_MS) { vm.state.value.levelShift == -1 }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("today_energy_hint_lighter"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `hidden calories and hidden numbers get the hint without them`() {
        lowEnergyDays()
        repo.updateProfile { it.copy(showCalories = false) }
        val vm = loaded(TodayViewModel(service))
        hardFingerDay(vm)
        render { TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
        scrollTo("today_list", hasTestTag("today_energy_hint"))
        waitForText("Last 3 logged days: on average about")
        // The share of the need instead of the calories.
        val texts = compose.onAllNodesWithText("Last 3 logged days", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
        assertTrue(texts.joinToString(), texts.isNotEmpty() && texts.all { "under your estimated need" in it && "kcal" !in it })
        repo.updateProfile { it.copy(hideBodyNumbers = true) }
        waitForText("Your energy has been on the low side lately – a snack with carbohydrates before hard finger work helps.")
    }
}
