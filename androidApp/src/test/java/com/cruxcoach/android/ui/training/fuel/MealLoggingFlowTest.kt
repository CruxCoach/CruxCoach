package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nokia walk 2026-11-22: four foods for one meal meant opening the add sheet four times, "save as a meal" twice made
 * two dishes of one name, and the saved dish was not one tap away. The sheet stays open and confirms what was logged,
 * a meal is saved as a dish once, and dishes stand first with one tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MealLoggingFlowTest : AthleteScreenTest() {

    @Before
    fun setUp() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        context.getDatabasePath(OffRepository.DB_NAME).delete()
        repo.saveFoodItem(FoodItem(id = "rice", name = "Rice", kcalPer100 = 130.0, proteinPer100 = 2.5, carbsPer100 = 28.0, fatPer100 = 0.3, updatedAt = 1))
        repo.saveFoodItem(FoodItem(id = "tofu", name = "Tofu", kcalPer100 = 120.0, proteinPer100 = 14.0, carbsPer100 = 2.0, fatPer100 = 7.0, updatedAt = 1))
    }

    private fun render() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
    }

    private fun dinner(day: String, riceG: Double = 200.0) = listOf(
        FoodLogEntry("d-rice", day, 1, Meal.DINNER, foodItemId = "rice", name = "Rice", amountG = riceG, kcal = 1.3 * riceG,
            proteinG = 0.025 * riceG, carbsG = 0.28 * riceG, fatG = 0.003 * riceG),
        FoodLogEntry("d-tofu", day, 2, Meal.DINNER, foodItemId = "tofu", name = "Tofu", amountG = 150.0, kcal = 180.0,
            proteinG = 21.0, carbsG = 3.0, fatG = 10.5),
    )

    private fun logFromSheet(food: String, grams: String) {
        scrollTo("fuel_foods_list", "fuel_food_$food")
        click("fuel_food_$food")
        waitForTag("fuel_amount_grams")
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement(grams)
        click("fuel_amount_confirm")
    }

    private fun dishes() = repo.foodItems().filter { it.source == Recipe.SOURCE }

    @Test
    fun `several foods go into a meal without leaving the sheet, each one confirmed and the last one taken back`() {
        val day = service.today().toString()
        render()
        scrollTo("fuel_list", "fuel_add_dinner")
        click("fuel_add_dinner")
        waitForTag("fuel_foods_sheet")
        // Nothing logged yet: nothing to confirm.
        compose.onAllNodesWithTag("fuel_logged").assertCountEquals(0)

        logFromSheet("rice", "200")
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).size == 1 }
        // The sheet is still there and says what went in.
        waitForTag("fuel_logged")
        compose.onNodeWithTag("fuel_logged_text", useUnmergedTree = true).assertTextContains("Logged: Rice", substring = true)
        compose.onNodeWithTag("fuel_logged_text", useUnmergedTree = true).assertTextContains("200\u00A0g", substring = true)
        compose.onAllNodesWithTag("fuel_foods_sheet").assertCountEquals(1)

        logFromSheet("tofu", "150")
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).size == 2 }
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_logged_text", useUnmergedTree = true).assertTextContains("Logged: Tofu", substring = true) }.isSuccess
        }
        // What the meal holds now: both foods, energy first, then carbohydrates, protein and fat.
        compose.waitUntil(WAIT_MS) {
            runCatching {
                compose.onNodeWithTag("fuel_logged_meal", useUnmergedTree = true)
                    .assertTextContains("Dinner: 2 entries · 440\u00A0kcal · C\u00A059 · P\u00A026 · F\u00A011\u00A0g", substring = true)
            }.isSuccess
        }
        assertEquals(listOf(Meal.DINNER, Meal.DINNER), repo.foodLog(day).map { it.meal })

        // "Undo" takes the last one back and shows the one before again.
        click("fuel_logged_undo")
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).map { it.foodItemId } == listOf("rice") }
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_logged_text", useUnmergedTree = true).assertTextContains("Logged: Rice", substring = true) }.isSuccess
        }

        click("fuel_logged_done")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("fuel_foods_sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(200.0, repo.foodLog(day).single().amountG!!, 0.001)
    }

    @Test
    fun `a meal is saved as a dish once, and the dish is one tap away at the next meal`() {
        val day = service.today().toString()
        dinner(day).forEach { repo.saveFoodLog(it) }
        render()
        scrollTo("fuel_list", "fuel_save_meal_dinner")
        click("fuel_save_meal_dinner")
        waitForTag("fuel_save_meal_name")
        compose.onNodeWithTag("fuel_save_meal_name").performTextReplacement("Rice bowl")
        click("fuel_save_meal_confirm")
        compose.waitUntil(WAIT_MS) { dishes().size == 1 }
        val dish = dishes().single()
        assertEquals("Rice bowl", dish.name)

        // The meal says what it is saved as; the button that made a second "Rice bowl" is gone.
        waitForTag("fuel_meal_saved_dinner")
        waitForText("Saved as a dish: Rice bowl")
        compose.onAllNodesWithTag("fuel_save_meal_dinner").assertCountEquals(0)

        // Lunch: the dish stands first in the add sheet, and one tap logs one portion of it.
        scrollTo("fuel_list", "fuel_add_lunch")
        click("fuel_add_lunch")
        scrollTo("fuel_foods_list", "fuel_dish_${dish.id}")
        click("fuel_dish_${dish.id}")
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).any { it.meal == Meal.LUNCH } }
        val lunch = repo.foodLog(day).single { it.meal == Meal.LUNCH }
        assertEquals(dish.id, lunch.foodItemId)
        assertEquals(1.0, lunch.portions!!, 0.0)
        assertEquals(350.0, lunch.amountG!!, 0.001)
        assertEquals(440.0, lunch.kcal!!, 0.001)
        assertEquals(26.0, lunch.proteinG!!, 0.001)
        waitForTag("fuel_logged")
        compose.onNodeWithTag("fuel_logged_text", useUnmergedTree = true).assertTextContains("Logged: Rice bowl", substring = true)
        assertEquals(1, dishes().size)
    }

    @Test
    fun `saving a meal under the name of another dish replaces that dish`() {
        val day = service.today().toString()
        // An older "Rice bowl" with less rice; today's dinner is another meal, so it can be saved.
        repo.saveRecipe("bowl", Recipe.fromEntries("Rice bowl", dinner(day, riceG = 100.0)))
        dinner(day).forEach { repo.saveFoodLog(it) }
        render()
        scrollTo("fuel_list", "fuel_save_meal_dinner")
        click("fuel_save_meal_dinner")
        waitForTag("fuel_save_meal_name")
        // The first guess is the foods' names; no dish is called that.
        compose.onAllNodesWithTag("fuel_save_meal_taken", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("fuel_save_meal_name").performTextReplacement("rice bowl")
        waitForTag("fuel_save_meal_taken")
        click("fuel_save_meal_confirm")
        compose.waitUntil(WAIT_MS) { repo.recipe("bowl")?.ingredients?.firstOrNull { it.name == "Rice" }?.grams == 200.0 }
        assertEquals(1, dishes().size)
        assertEquals("rice bowl", repo.foodItem("bowl")!!.name)
        waitForTag("fuel_meal_saved_dinner")
    }

    @Test
    fun `one tap logs the amount of last time, else one portion`() {
        val dish = FoodItem(id = "bowl", name = "Rice bowl", kcalPer100 = 100.0, servingG = 350.0, source = Recipe.SOURCE)
        fun last(portions: Double?, grams: Double?) = FoodLogEntry("e", "2026-11-22", 1, Meal.DINNER, foodItemId = "bowl", name = "Rice bowl",
            portions = portions, amountG = grams)
        assertEquals(1.0 to null, FuelViewModel.usualAmount(dish, null))
        // Half a portion last time: half a portion again (its weight is stored with it, the portions count).
        assertEquals(0.5 to null, FuelViewModel.usualAmount(dish, last(0.5, 175.0)))
        // Typed in grams last time: the same grams.
        assertEquals(null to 240.0, FuelViewModel.usualAmount(dish, last(null, 240.0)))
        // The dish was saved anew since (other ingredients, or replaced by a meal): the old amount is not its amount.
        assertEquals(1.0 to null, FuelViewModel.usualAmount(dish.copy(updatedAt = 5), last(null, 240.0)))
        assertEquals(1.0 to null, FuelViewModel.usualAmount(dish.copy(updatedAt = 5), last(0.5, 175.0)))
        // A food kept by the portion has no grams to log.
        val bar = FoodItem(id = "bar", name = "Bar", kcalPer100 = 200.0, source = FuelViewModel.SOURCE_PORTION)
        assertEquals(1.0 to null, FuelViewModel.usualAmount(bar, last(null, 240.0)))
        assertNull(FuelViewModel.scale(dish.copy(servingG = null), 1.0, null))
    }
}
