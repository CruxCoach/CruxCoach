package com.cruxcoach.android.ui.training.fuel

import com.cruxcoach.android.ui.training.AthleteScreenTest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.navigation.BrowserMainDrawer
import com.cruxcoach.android.ui.training.TrainingRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nutrition is its own main-menu entry and always on (owner 2026-10-05): a
 * fresh profile gets the food log at once, with the guard rails explained
 * once in a card instead of an opt-in screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NutritionAlwaysOnTest : AthleteScreenTest() {

    @Before
    fun setUp() {
        // A brand-new profile: nothing switched on, nothing accepted.
        assertFalse(repo.profile().fuelEnabled)
        assertFalse(repo.profile().fuelIntroAccepted)
    }

    @Test
    fun `fresh profile gets the food log with a one-time intro card`() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        waitForTag("fuel_intro")
        // Adding is the floating button, reachable with the intro card still open.
        waitForTag("fuel_my_foods")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_intro_ok"))
        compose.onNodeWithTag("fuel_intro_ok").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.profile().fuelIntroAccepted }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("fuel_intro").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("fuel_intro").assertCountEquals(0)
    }

    @Test
    fun `logged day shows meal subtotals and the day total against targets`() {
        val today = service.today().toString()
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveMeasurement(com.cruxcoach.athlete.model.BodyMeasurement(today, com.cruxcoach.athlete.model.BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveFoodLog(com.cruxcoach.athlete.model.FoodLogEntry("a", today, 1, com.cruxcoach.athlete.model.Meal.LUNCH,
            name = "Pasta", amountG = 250.0, proteinG = 12.0, carbsG = 70.0, fatG = 2.0))
        repo.saveFoodLog(com.cruxcoach.athlete.model.FoodLogEntry("b", today, 2, com.cruxcoach.athlete.model.Meal.LUNCH,
            name = "Tomato sauce", amountG = 150.0, proteinG = 3.0, carbsG = 10.0, fatG = 8.0))
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context)).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        waitForTag("fuel_targets")
        // 70 kg × 1.6 g/kg protein = 112 g; 15 g logged.
        // Four rings on a narrow screen: the unit sits in the line under the ring.
        compose.onNodeWithText("of 112", substring = true).assertExists()
        compose.onNodeWithText("97 g to go", substring = true).assertExists()
        compose.onNodeWithText("of energy", substring = true).assertExists()
        // Fat has its own ring: without a height, 1 g per kg = 70 g; 10 g logged.
        compose.onNodeWithText("of 70", substring = true).assertExists()
        compose.onNodeWithText("60 g to go", substring = true).assertExists()
        // Order: energy, carbohydrates, protein, fat (owner 2026-10-10).
        val left = listOf("fuel_energy", "fuel_carbs", "fuel_protein", "fuel_fat")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(left.sorted(), left)
        // No kcal logged: calories are on by default and estimated from the macros (4/4/9).
        compose.onNodeWithTag("fuel_kcal", useUnmergedTree = true).assertTextEquals("≈470")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_meal_lunch"))
        compose.onNodeWithTag("fuel_meal_total_lunch", useUnmergedTree = true).assertTextEquals("P 15 · C 80 · F 10 g · ≈ 470 kcal")

        // The day before is "Yesterday", not a bare date (device test 2026-10-09).
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_prev_day"))
        compose.onNodeWithTag("fuel_prev_day").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_day_label", useUnmergedTree = true).assertTextEquals("Yesterday"); true }.getOrDefault(false)
        }
    }

    @Test
    fun `main menu has nutrition next to training`() {
        var selected: String? = null
        compose.setContent { MaterialTheme { BrowserMainDrawer { selected = it } } }
        compose.onNodeWithTag("menu_today").assertExists()
        compose.onNodeWithText("Nutrition").assertExists()
        // The drawer sheet sits outside the small Robolectric window, so a
        // synthetic touch misses it; trigger the item's own click action.
        compose.onNodeWithTag("menu_nutrition").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(TrainingRoutes.FUEL, selected)
    }

}
