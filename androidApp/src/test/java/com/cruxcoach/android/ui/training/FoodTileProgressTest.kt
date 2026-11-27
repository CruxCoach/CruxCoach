package com.cruxcoach.android.ui.training

import com.cruxcoach.android.ui.training.today.foodTileProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bar of Today's food tile belongs to its big number (device walk 2026-11-22: energy above, protein's bar below). */
class FoodTileProgressTest {

    @Test
    fun `energy of its goal where the tile shows energy`() {
        assertEquals(0.5f, foodTileProgress(showCalories = true, kcal = 1200, energyGoal = 2400, protein = 130, proteinTarget = 140)!!, 1e-6f)
        // More than the goal is more than full; the tile caps it.
        assertEquals(1.25f, foodTileProgress(true, 3000, 2400, 0, 140)!!, 1e-6f)
        assertEquals(0f, foodTileProgress(true, 500, 0, 80, 140)!!, 0f)
    }

    @Test
    fun `protein of its target where calories are hidden`() {
        assertEquals(80f / 140f, foodTileProgress(showCalories = false, kcal = 1200, energyGoal = 2400, protein = 80, proteinTarget = 140)!!, 1e-6f)
        assertEquals(0f, foodTileProgress(false, 0, null, 10, 0)!!, 0f)
    }

    @Test
    fun `no bar where the number has nothing to be measured against`() {
        // Energy without a goal (no weight yet): the bar was protein's.
        assertNull(foodTileProgress(showCalories = true, kcal = 1200, energyGoal = null, protein = 80, proteinTarget = 140))
        assertNull(foodTileProgress(showCalories = false, kcal = 0, energyGoal = null, protein = 0, proteinTarget = null))
    }
}
