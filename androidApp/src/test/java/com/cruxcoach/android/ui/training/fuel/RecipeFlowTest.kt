package com.cruxcoach.android.ui.training.fuel

import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import com.cruxcoach.android.ui.training.AthleteScreenTest
import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Writing a recipe from searched ingredients, then changing it (FEAT-069). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecipeFlowTest : AthleteScreenTest() {

    @Before
    fun setUp() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        repo.saveFoodItem(FoodItem(id = "milk", name = "Milch", kcalPer100 = 64.0, proteinPer100 = 3.3,
            carbsPer100 = 4.8, fatPer100 = 3.5, updatedAt = 1))
    }

    private fun addIngredient(search: String?, tag: String, grams: String) {
        click("fuel_recipe_add")
        waitForTag("fuel_foods_search")
        search?.let { compose.onNodeWithTag("fuel_foods_search").performTextInput(it) }
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag(tag)); true }.getOrDefault(false)
        }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_grams")
        compose.onNodeWithTag("fuel_amount_grams").performTextReplacement(grams)
        click("fuel_amount_confirm")
    }

    @Test
    fun `a recipe from a BLS food and an own food is saved and can be changed`() {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        click("fuel_my_foods")
        click("fuel_recipe_new")
        waitForTag("fuel_recipe_name")
        compose.onNodeWithTag("fuel_recipe_name").performTextInput("Porridge")
        addIngredient("haferflocken", "fuel_bls_C133000", "100")
        waitForTag("fuel_recipe_ingredient_0")
        addIngredient(null, "fuel_food_milk", "300")
        waitForTag("fuel_recipe_ingredient_1")
        // Milk is a drink: ml like everywhere else (device test 2026-10-09 showed "250 g").
        waitForText("300 ml")
        compose.onNodeWithTag("fuel_recipe_portions").performTextReplacement("2")
        waitForTag("fuel_recipe_summary")
        click("fuel_recipe_save")

        compose.waitUntil(WAIT_MS) { repo.foodItems().any { it.source == Recipe.SOURCE } }
        val item = repo.foodItems().single { it.source == Recipe.SOURCE }
        assertEquals("Porridge", item.name)
        assertEquals(200.0, item.servingG!!, 1e-9)
        // 13.22 g (BLS oat flakes) + 9.9 g (milk) protein in 400 g.
        assertEquals((13.22 + 9.9) / 4, item.proteinPer100!!, 1e-6)
        assertEquals(listOf("bls:C133000", "milk"), repo.recipe(item.id)!!.ingredients.map { it.foodItemId })

        // Changing it keeps the id: one ingredient less, one portion.
        waitForTag("fuel_foods_sheet")
        click("fuel_recipe_edit_${item.id}")
        waitForTag("fuel_recipe_ingredient_1")
        click("fuel_recipe_remove_1")
        compose.onNodeWithTag("fuel_recipe_portions").performTextReplacement("1")
        click("fuel_recipe_save")
        compose.waitUntil(WAIT_MS) { repo.recipe(item.id)?.ingredients?.size == 1 }
        assertEquals(100.0, repo.foodItem(item.id)!!.servingG!!, 1e-9)
        assertEquals(1, repo.foodItems().count { it.source == Recipe.SOURCE })
    }

    @Test
    fun `deleting an own food asks first, and the ingredient picker cannot delete`() {
        repo.saveFoodItem(FoodItem(id = "cheese", name = "Bergkäse", kcalPer100 = 400.0, proteinPer100 = 28.0,
            carbsPer100 = 0.0, fatPer100 = 32.0, updatedAt = 1))
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        render { FuelScreen({}, {}, viewModel = FuelViewModel(service).tracked(), photoViewModel = photo) }
        waitForTag("fuel_list")
        compose.onNodeWithTag("fuel_list").performScrollToNode(hasTestTag("fuel_my_foods"))
        click("fuel_my_foods")
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag("fuel_food_delete_cheese")); true }.getOrDefault(false)
        }
        click("fuel_food_delete_cheese")
        waitForTag("fuel_food_delete_dialog")
        assertTrue(repo.foodItems().any { it.id == "cheese" })
        click("fuel_food_delete_confirm")
        compose.waitUntil(WAIT_MS) { repo.foodItems().none { it.id == "cheese" } }

        click("fuel_recipe_new")
        click("fuel_recipe_add")
        compose.waitUntil(WAIT_MS) {
            runCatching { compose.onNodeWithTag("fuel_foods_list").performScrollToNode(hasTestTag("fuel_food_milk")); true }.getOrDefault(false)
        }
        compose.onAllNodesWithTag("fuel_food_delete_milk").assertCountEquals(0)
    }

}
