package com.cruxcoach.android.ui.training.fuel

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.foodvision.BlsRepository
import com.cruxcoach.android.foodvision.DeviceFactsReader
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.AthleteScreenTest
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a meal usually holds is one tap away, and a food's amount starts where it was last time
 * (UX review 2026-10-16: the same breakfast was searched and weighed anew every day).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentFoodsTest : AthleteScreenTest() {

    @Before
    fun setUp() {
        repo.updateProfile { it.copy(fuelIntroAccepted = true) }
        context.getDatabasePath(OffRepository.DB_NAME).delete()
    }

    private fun render(vm: FuelViewModel) {
        val photo = FoodPhotoViewModel(context, service, VisionModelStore(context), BlsRepository(context), DeviceFactsReader(context),
            OffRepository(context).apply { source = { "".reader().buffered() }; assetVersion = { "t" } }).tracked()
        render { FuelScreen({}, {}, viewModel = vm, photoViewModel = photo) }
        waitForTag("fuel_list")
    }

    @Test
    fun `a breakfast of the last days is logged again with one tap, a food starts at its last amount`() {
        val today = service.today()
        val yesterday = today.minus(DatePeriod(days = 1)).toString()
        repo.saveFoodItem(FoodItem(id = "oats", name = "Haferflocken", kcalPer100 = 370.0, proteinPer100 = 13.0,
            carbsPer100 = 59.0, fatPer100 = 7.0, updatedAt = 1))
        repo.saveFoodLog(FoodLogEntry(id = "y1", day = yesterday, loggedAt = 1, meal = Meal.BREAKFAST, foodItemId = "oats",
            name = "Haferflocken", amountG = 80.0, kcal = 296.0, proteinG = 10.4, carbsG = 47.2, fatG = 5.6))
        // A quick entry never became one of "my foods" – it comes back all the same.
        repo.saveFoodLog(FoodLogEntry(id = "y2", day = yesterday, loggedAt = 2, meal = Meal.BREAKFAST, name = "Kaffee mit Milch",
            portions = 1.0, kcal = 40.0, proteinG = 2.0))
        val vm = FuelViewModel(service).tracked()
        compose.waitUntil(WAIT_MS) { vm.state.value.recentByMeal[Meal.BREAKFAST]?.size == 2 }
        render(vm)
        scrollTo("fuel_list", "fuel_add_breakfast")
        click("fuel_add_breakfast")
        click("fuel_recent_y2")
        val day = today.toString()
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).any { it.name == "Kaffee mit Milch" } }
        val coffee = repo.foodLog(day).single()
        assertEquals(Meal.BREAKFAST, coffee.meal)
        assertEquals(40.0, coffee.kcal!!, 0.001)
        // Logged today: it leaves the list for breakfast; the oats stay, and their dialog starts at 80 g.
        compose.waitUntil(WAIT_MS) { vm.state.value.recentByMeal[Meal.BREAKFAST]?.map { it.name } == listOf("Haferflocken") }
        assertEquals(80.0, vm.state.value.lastByFood["oats"]?.amountG!!, 0.001)
        click("fuel_add_breakfast")
        waitForTag("fuel_foods_list")
        scrollTo("fuel_foods_list", "fuel_food_oats")
        compose.onNodeWithTag("fuel_food_oats").performSemanticsAction(SemanticsActions.OnClick)
        waitForTag("fuel_amount_grams")
        compose.onNodeWithTag("fuel_amount_confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(WAIT_MS) { repo.foodLog(day).any { it.foodItemId == "oats" } }
        assertEquals(80.0, repo.foodLog(day).first { it.foodItemId == "oats" }.amountG!!, 0.001)
    }
}
