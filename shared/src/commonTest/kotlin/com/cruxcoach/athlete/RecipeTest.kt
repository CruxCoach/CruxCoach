package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecipeTest {

    private val oats = FoodItem(id = "bls:C133000", name = "Haferflocken", kcalPer100 = 348.0, proteinPer100 = 13.2,
        carbsPer100 = 53.3, fatPer100 = 6.65)
    private val milk = FoodItem(id = "milk", name = "Milch", kcalPer100 = 64.0, proteinPer100 = 3.3, carbsPer100 = 4.8,
        fatPer100 = 3.5)

    @Test
    fun portionsSplitTheRawWeightWithoutACookedWeight() {
        val recipe = Recipe("Porridge", 2, listOf(Recipe.ingredient(oats, 100.0), Recipe.ingredient(milk, 300.0)))
        val item = recipe.toFoodItem("r1", 5)
        assertEquals(Recipe.SOURCE, item.source)
        assertEquals(200.0, item.servingG!!, 1e-9)
        // (13.2 + 9.9) g protein in 400 g.
        assertEquals(23.1 / 4, item.proteinPer100!!, 1e-9)
        assertEquals((348.0 + 192.0) / 4, item.kcalPer100!!, 1e-9)
    }

    @Test
    fun aCookedWeightConcentratesTheNutrients() {
        val pasta = FoodItem(id = "p", name = "Nudeln", kcalPer100 = 350.0, proteinPer100 = 12.0)
        val recipe = Recipe("Nudeln gekocht", 1, listOf(Recipe.ingredient(pasta, 100.0)), cookedWeightG = 250.0)
        val item = recipe.toFoodItem("r2", 5)
        assertEquals(250.0, item.servingG!!, 1e-9)
        assertEquals(140.0, item.kcalPer100!!, 1e-9)
        assertEquals(4.8, item.proteinPer100!!, 1e-9)
        // Nobody typed fat or carbohydrate: unknown, not zero.
        assertNull(item.fatPer100)
    }
}
