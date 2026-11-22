package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Owner 2026-11-22: several foods logged for one meal become one dish that is logged with one tap next time. */
class RecipeFromEntriesTest {

    @Test
    fun aLoggedMealBecomesOneDishWithTheSameTotals() {
        val entries = listOf(
            FoodLogEntry("a", "2026-11-22", 1, Meal.DINNER, name = "Rice", amountG = 200.0, kcal = 260.0, proteinG = 5.0, carbsG = 56.0, fatG = 0.6),
            FoodLogEntry("b", "2026-11-22", 2, Meal.DINNER, name = "Tofu", amountG = 150.0, kcal = 180.0, proteinG = 21.0, carbsG = 3.0, fatG = 10.5),
            // Logged without a weight: its nutrients still count in full.
            FoodLogEntry("c", "2026-11-22", 3, Meal.DINNER, name = "Sauce", kcal = 60.0, proteinG = 1.0, carbsG = 8.0, fatG = 2.0),
        )
        val dish = Recipe.fromEntries("Rice bowl", entries).toFoodItem("id", 1)
        assertEquals("Rice bowl", dish.name)
        // One portion is the whole meal: 200 + 150 + 100 g.
        assertEquals(450.0, dish.servingG!!, 1e-6)
        fun portion(per100: Double?) = per100!! * dish.servingG!! / 100.0
        assertEquals(500.0, portion(dish.kcalPer100), 1e-6)
        assertEquals(27.0, portion(dish.proteinPer100), 1e-6)
        assertEquals(67.0, portion(dish.carbsPer100), 1e-6)
        assertEquals(13.1, portion(dish.fatPer100), 1e-6)
    }

    @Test
    fun aWeightOfZeroIsNoWeightAndWhatNobodyLoggedStaysUnknown() {
        val entries = listOf(
            // Typed by hand with energy and protein only; a weight of zero is no weight.
            FoodLogEntry("a", "2026-11-22", 1, Meal.LUNCH, name = "Shake", amountG = 0.0, kcal = 150.0, proteinG = 25.0),
            FoodLogEntry("b", "2026-11-22", 2, Meal.LUNCH, foodItemId = "bls:B100", name = "Bread", amountG = 50.0, kcal = 120.0, proteinG = 4.0),
        )
        val recipe = Recipe.fromEntries("Lunch", entries)
        assertEquals(1, recipe.portions)
        assertEquals(listOf(100.0, 50.0), recipe.ingredients.map { it.grams })
        // An ingredient keeps the food it came from; an entry without one stands for itself.
        assertEquals(listOf("a", "bls:B100"), recipe.ingredients.map { it.foodItemId })
        val dish = recipe.toFoodItem("id", 1)
        assertEquals(150.0, dish.servingG!!, 1e-6)
        assertEquals(270.0, dish.kcalPer100!! * 1.5, 1e-6)
        assertEquals(29.0, dish.proteinPer100!! * 1.5, 1e-6)
        // Carbohydrates and fat were never logged: unknown, not zero.
        assertNull(dish.carbsPer100)
        assertNull(dish.fatPer100)
    }
}
