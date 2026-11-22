package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.FoodItem
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

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

    /**
     * What the dish is made of, whatever it is called and in whatever order: every ingredient by its name, its weight
     * and what it brings, in tenths – so values that went through the database and back still match.
     */
    private fun contents(): List<String> = ingredients.map { i ->
        fun tenths(per100: Double?) = per100?.let { (it * i.grams / 10.0).roundToLong().toString() } ?: "-"
        listOf(i.name.trim().lowercase(), (i.grams * 10).roundToLong().toString(), tenths(i.kcalPer100), tenths(i.proteinPer100),
            tenths(i.carbsPer100), tenths(i.fatPer100)).joinToString("|")
    }.sorted()

    /** The same dish under any name: the same ingredients in the same amounts, split and weighed the same way. */
    fun sameDishAs(other: Recipe): Boolean =
        portions == other.portions && (totalWeightG * 10).roundToLong() == (other.totalWeightG * 10).roundToLong() &&
            contents() == other.contents()

    companion object {
        /** food_item.source of recipes; the ingredients are kept under [settingKey]. */
        const val SOURCE = "recipe"
        fun settingKey(foodItemId: String) = "recipe:$foodItemId"

        /**
         * The saved dish that holds exactly these [entries] – the key of one of [dishes], which are saved dishes by
         * their food id – or null. A meal that already is a dish is not offered for saving again: saving twice made
         * two dishes of the same name (Nokia walk 2026-11-22). The foods count by name, so the same meal entered
         * again on another day is recognised too.
         */
        fun savedDish(entries: List<com.cruxcoach.athlete.model.FoodLogEntry>, dishes: Map<String, Recipe>): String? {
            if (entries.isEmpty() || dishes.isEmpty()) return null
            val meal = fromEntries("", entries)
            return dishes.entries.firstOrNull { it.value.sameDishAs(meal) }?.key
        }

        /** The dish among [names] (food id to name) that is called [name], capitals and outer blanks aside; not [except]. */
        fun namesake(name: String, names: Map<String, String>, except: String? = null): String? {
            val wanted = name.trim().lowercase()
            if (wanted.isEmpty()) return null
            return names.entries.firstOrNull { it.key != except && it.value.trim().lowercase() == wanted }?.key
        }

        /**
         * A logged meal as one dish (owner 2026-11-22: four foods entered for dinner are one tap next time): every
         * entry an ingredient with what was eaten of it, one portion in all. An entry without a weight counts as 100 g
         * carrying its logged nutrients, so the totals stay what was logged.
         */
        fun fromEntries(name: String, entries: List<com.cruxcoach.athlete.model.FoodLogEntry>): Recipe = Recipe(
            name = name, portions = 1,
            ingredients = entries.map { e ->
                val grams = e.amountG?.takeIf { it > 0 } ?: 100.0
                fun per100(total: Double?) = total?.let { it * 100.0 / grams }
                Ingredient(foodItemId = e.foodItemId ?: e.id, name = e.name, grams = grams, kcalPer100 = per100(e.kcal),
                    proteinPer100 = per100(e.proteinG), carbsPer100 = per100(e.carbsG), fatPer100 = per100(e.fatG))
            },
        )

        fun ingredient(item: FoodItem, grams: Double) = Ingredient(
            foodItemId = item.id, name = item.name, grams = grams, kcalPer100 = item.kcalPer100,
            proteinPer100 = item.proteinPer100, carbsPer100 = item.carbsPer100, fatPer100 = item.fatPer100,
        )
    }
}
