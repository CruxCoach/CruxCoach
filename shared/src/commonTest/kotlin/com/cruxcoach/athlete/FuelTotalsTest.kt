package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.MacroTotals
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
