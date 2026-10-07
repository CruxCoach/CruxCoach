package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.FoodItem
import kotlinx.serialization.Serializable

/**
 * A recipe: ingredients with their raw weight, split into portions (FEAT-069).
 * It is logged like any food – a food item with nutrients per 100 g of the
 * finished dish and one portion as serving – and keeps its ingredients so it
 * can be changed later.
 */
@Serializable
data class Recipe(
    val name: String,
    val portions: Int,
    val ingredients: List<Ingredient>,
    /** Weight of the finished dish when weighed; cooking changes it (pasta gains, meat loses). */
    val cookedWeightG: Double? = null,
) {
    @Serializable
    data class Ingredient(
        val foodItemId: String,
        val name: String,
        val grams: Double,
        val kcalPer100: Double? = null,
        val proteinPer100: Double? = null,
        val carbsPer100: Double? = null,
        val fatPer100: Double? = null,
    )

    val rawWeightG: Double get() = ingredients.sumOf { it.grams }
    val totalWeightG: Double get() = cookedWeightG?.takeIf { it > 0 } ?: rawWeightG

    /** Total of one nutrient over all ingredients; null when no ingredient has it. */
    private fun total(per100: (Ingredient) -> Double?): Double? =
        ingredients.mapNotNull { i -> per100(i)?.let { it * i.grams / 100.0 } }.takeIf { it.isNotEmpty() }?.sum()

    /** The recipe as a food: per 100 g of the finished dish, one portion as serving. */
    fun toFoodItem(id: String, now: Long): FoodItem {
        val weight = totalWeightG
        fun per100(total: Double?) = total?.let { if (weight > 0) it * 100.0 / weight else null }
        return FoodItem(
            id = id, name = name,
            kcalPer100 = per100(total { it.kcalPer100 }), proteinPer100 = per100(total { it.proteinPer100 }),
            carbsPer100 = per100(total { it.carbsPer100 }), fatPer100 = per100(total { it.fatPer100 }),
            servingG = if (portions > 0 && weight > 0) weight / portions else null,
            source = SOURCE, updatedAt = now,
        )
    }

    companion object {
        /** food_item.source of recipes; the ingredients are kept under [settingKey]. */
        const val SOURCE = "recipe"
        fun settingKey(foodItemId: String) = "recipe:$foodItemId"

        fun ingredient(item: FoodItem, grams: Double) = Ingredient(
            foodItemId = item.id, name = item.name, grams = grams, kcalPer100 = item.kcalPer100,
            proteinPer100 = item.proteinPer100, carbsPer100 = item.carbsPer100, fatPer100 = item.fatPer100,
        )
    }
}
