package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel
import com.cruxcoach.android.ui.training.athlete.SettingsSection
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Energy in the open (owner 2026-10-09): the need with its parts, a calorie
 * target while losing weight, and warnings with their numbers – but no
 * function is withheld, not even with a low BMI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnergyTransparencyTest : AthleteScreenTest() {

    private fun body(weightKg: Double, heightCm: Double = 175.0) {
        val today = service.today().toString()
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, weightKg, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, heightCm, "cm", 1))
    }

    private fun fuel() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = loaded(FuelViewModel(service)), photoViewModel = photo) }
        waitForTag("fuel_list")
    }

    @Test
    fun `energy ring counts against the calorie target and opens the breakdown`() {
        val year = service.today().year
        repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = year - 30, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 0.5) }
        body(70.0)
        fuel()
        // Need 1649 + 659/660 everyday (rest day) = 2308/2309, minus 550 for 0.5 kg a week.
        waitForTag("fuel_energy")
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("of 1758 kcal").or(androidx.compose.ui.test.hasText("of 1759 kcal")), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        click("fuel_energy")
        waitForTag("energy_sheet")
        waitForTag("energy_need")
        waitForTag("energy_deficit")
        waitForTag("energy_target")
        compose.onNodeWithText("−550 kcal", useUnmergedTree = true).assertExists()
        // Everyday life is chosen right here and changes the need.
        click("everyday_physical")
        compose.waitUntil(WAIT_MS) { repo.profile().everydayActivity == com.cruxcoach.athlete.model.EverydayActivity.PHYSICAL }
    }

    @Test
    fun `missing height is entered in the energy sheet`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        fuel()
        click("fuel_energy")
        waitForTag("energy_height_input")
        compose.onNodeWithTag("energy_height_input").performTextReplacement("178")
        click("energy_height_save")
        compose.waitUntil(WAIT_MS) { repo.latest(BodyMetric.HEIGHT.key)?.value == 178.0 }
        // With the height the need appears, with its assumptions named and answerable in place.
        waitForTag("energy_need")
        waitForTag("energy_birth_year_input")
        waitForTag("energy_sex_female")
    }

    @Test
    fun `a low BMI does not lock losing weight, it warns with the numbers`() {
        repo.updateProfile { it.copy(sex = Sex.MALE) }
        body(55.0) // BMI 18.0 at 175 cm
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.PROFILE) }
        waitForTag("switch_goal_lose_weight")
        compose.onNodeWithTag("switch_goal_lose_weight").assertIsEnabled()
        click("switch_goal_lose_weight")
        waitForTag("goal_sheet")
        compose.onNodeWithTag("goal_target_input").performTextReplacement("50")
        repeat(5) { click("goal_pace_plus") } // 0.5 → 1.0 kg a week
        compose.onNodeWithTag("goal_pace", useUnmergedTree = true).assertTextEquals("1 kg per week")
        // 1 kg is 1.8 % of 55 kg; 50 kg at 175 cm is BMI 16.3 – both named, neither refused.
        waitForTag("goal_warning_fast_pace")
        waitForTag("goal_warning_low_target_bmi")
        // The pace line and the warning both say it.
        assertEquals(2, compose.onAllNodesWithText("1.8 % of your weight", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        click("goal_save")
        compose.waitUntil(WAIT_MS) { repo.profile().goal == AthleteGoal.LOSE_WEIGHT }
        assertEquals(1.0, repo.profile().weeklyLossKg, 1e-9)
        assertEquals(50.0, repo.profile().targetWeightKg!!, 1e-9)
        // The page keeps the goal with its warnings; switching off keeps target and pace for next time.
        waitForTag("goal_summary")
        waitForTag("settings_warning_fast_pace")
        click("switch_goal_lose_weight")
        compose.waitUntil(WAIT_MS) { repo.profile().goal != AthleteGoal.LOSE_WEIGHT }
        assertEquals(50.0, repo.profile().targetWeightKg!!, 1e-9)
    }

    @Test
    fun `nutrition names a fast plan on the energy card and opens the goal from it`() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.FEMALE, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 1.2) }
        body(60.0, 165.0)
        fuel()
        waitForTag("fuel_reds")
        waitForTag("fuel_reds_fast_pace")
        waitForTag("fuel_reds_large_deficit")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_reds_adjust"))
        click("fuel_reds_adjust")
        waitForTag("goal_sheet")
        waitForTag("goal_stop")
    }
}
