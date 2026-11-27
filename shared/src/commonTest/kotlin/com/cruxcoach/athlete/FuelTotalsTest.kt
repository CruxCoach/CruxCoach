package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.RecentFoods
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelTotalsTest {

    private fun entry(id: String, meal: Meal, p: Double?, c: Double?, f: Double?, kcal: Double? = null) =
        FoodLogEntry(id, "2026-10-05", 1, meal, name = id, proteinG = p, carbsG = c, fatG = f, kcal = kcal)

    @Test
    fun dayAndMealTotalsCountMissingValuesAsZero() {
        val log = listOf(
            entry("pasta", Meal.LUNCH, 12.0, 70.0, 2.0, 350.0),
            entry("sauce", Meal.LUNCH, 3.0, 10.0, 8.0, 120.0),
            entry("shake", Meal.POST_TRAINING, 25.0, null, null),
            entry("toast", Meal.BREAKFAST, 4.0, 30.0, 6.0),
        )
        val day = MacroTotals.of(log)
        // 350 + 120 logged, shake 25 g protein → 100 kcal, toast 16 + 120 + 54 = 190 kcal estimated.
        assertEquals(MacroTotals(kcal = 760.0, protein = 44.0, carbs = 110.0, fat = 16.0, entries = 4, kcalEstimated = true), day)

        val meals = MacroTotals.byMeal(log)
        // Order follows the Meal enum, not the log order; empty meals are left out.
        assertEquals(listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.POST_TRAINING), meals.map { it.first })
        assertEquals(MacroTotals(470.0, 15.0, 80.0, 10.0, 2), meals.single { it.first == Meal.LUNCH }.second)
        assertEquals(MacroTotals.Energy(100.0, estimated = true), MacroTotals.energy(log[2]))
        assertEquals(MacroTotals.Energy(350.0, estimated = false), MacroTotals.energy(log[0]))
        assertNull(MacroTotals.energy(FoodLogEntry("x", "2026-10-05", 1, Meal.SNACK, name = "x")))
    }

    @Test
    fun fatShareComesFromTheMacros() {
        // 20 g fat = 180 kcal of 180 + 4*(25+70) = 560 kcal → 32 %.
        val share = MacroTotals(0.0, 25.0, 70.0, 20.0, 1).fatEnergyShare!!
        assertEquals(0.32, share, 0.005)
        assertNull(MacroTotals(0.0, 0.0, 0.0, 0.0, 0).fatEnergyShare)
    }
}

class RecentFoodsLogicTest {

    private fun e(id: String, name: String, meal: Meal, at: Long, food: String? = null, grams: Double? = null) =
        FoodLogEntry(id = id, day = "2026-10-1${at % 10}", loggedAt = at, meal = meal, foodItemId = food, name = name, amountG = grams)

    @Test
    fun aMealOffersWhatItHoldsMostOftenWithTheNewestAmount() {
        val log = listOf(
            e("1", "Haferflocken", Meal.BREAKFAST, 1, "oats", 60.0), e("2", "Haferflocken", Meal.BREAKFAST, 2, "oats", 80.0),
            e("3", "Skyr", Meal.BREAKFAST, 3, "skyr", 150.0), e("4", "Kaffee", Meal.BREAKFAST, 4),
            e("5", "kaffee ", Meal.BREAKFAST, 5), e("6", "Reis", Meal.LUNCH, 6, "rice", 250.0),
        )
        val breakfast = RecentFoods.forMeal(log, Meal.BREAKFAST)
        // Two each for oats and coffee (one typed with another case), the newer first; then skyr; lunch stays out.
        assertEquals(listOf("5", "2", "3"), breakfast.map { it.id })
        assertEquals(80.0, breakfast.first { it.foodItemId == "oats" }.amountG)
        // What the shown day already holds at that meal is not offered again.
        val shown = listOf(e("t", "Kaffee", Meal.BREAKFAST, 9))
        assertEquals(listOf("2", "3"), RecentFoods.forMeal(log, Meal.BREAKFAST, shown).map { it.id })
        assertEquals(80.0, RecentFoods.lastByFood(log)["oats"]?.amountG)
    }
}

