package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nokia walk 2026-11-22: "save as a meal" twice made two dishes of the same name, and the button stayed although the
 * meal already was a dish. A meal is recognised as the dish it was saved as.
 */
class RecipeSameDishTest {

    private fun dinner(day: String = "2026-11-22", prefix: String = "") = listOf(
        FoodLogEntry("${prefix}a", day, 1, Meal.DINNER, foodItemId = "bls:rice", name = "Rice", amountG = 200.0, kcal = 260.0,
            proteinG = 5.0, carbsG = 56.0, fatG = 0.6),
        FoodLogEntry("${prefix}b", day, 2, Meal.DINNER, foodItemId = "bls:tofu", name = "Tofu", amountG = 150.0, kcal = 180.0,
            proteinG = 21.0, carbsG = 3.0, fatG = 10.5),
        // A quick entry: no saved food, no weight.
        FoodLogEntry("${prefix}c", day, 3, Meal.DINNER, name = "Sauce", portions = 1.0, kcal = 60.0, proteinG = 1.0, carbsG = 8.0, fatG = 2.0),
    )

    /** As the repository keeps it: through JSON and back. */
    private fun stored(recipe: Recipe): Recipe = Json.decodeFromString(Recipe.serializer(), Json.encodeToString(Recipe.serializer(), recipe))

    @Test
    fun aMealSavedAsADishIsRecognised() {
        val dishes = mapOf("other" to stored(Recipe.fromEntries("Porridge", dinner().take(2))), "bowl" to stored(Recipe.fromEntries("Rice bowl", dinner())))
        assertEquals("bowl", Recipe.savedDish(dinner(), dishes))
    }

    @Test
    fun theSameMealOnAnotherDayInAnotherOrderIsTheSameDish() {
        val dishes = mapOf("bowl" to stored(Recipe.fromEntries("Rice bowl", dinner())))
        // Entered again the next day: new entries, the quick entry first.
        val again = dinner(day = "2026-11-23", prefix = "next-").reversed()
        assertEquals("bowl", Recipe.savedDish(again, dishes))
    }

    @Test
    fun anotherAmountOrAnotherFoodIsAnotherDish() {
        val dishes = mapOf("bowl" to stored(Recipe.fromEntries("Rice bowl", dinner())))
        val moreRice = dinner().map { if (it.name == "Rice") it.copy(amountG = 250.0, kcal = 325.0, proteinG = 6.25, carbsG = 70.0, fatG = 0.75) else it }
        assertNull(Recipe.savedDish(moreRice, dishes))
        assertNull(Recipe.savedDish(dinner().take(2), dishes))
        assertNull(Recipe.savedDish(dinner() + FoodLogEntry("d", "2026-11-22", 4, Meal.DINNER, name = "Apple", amountG = 150.0, kcal = 78.0), dishes))
        // The same foods with other values (a quick entry typed anew) are another dish.
        val otherSauce = dinner().map { if (it.name == "Sauce") it.copy(kcal = 120.0) else it }
        assertNull(Recipe.savedDish(otherSauce, dishes))
        assertNull(Recipe.savedDish(emptyList(), dishes))
        assertNull(Recipe.savedDish(dinner(), emptyMap()))
    }

    @Test
    fun aDishChangedSinceIsNoLongerTheMeal() {
        val saved = Recipe.fromEntries("Rice bowl", dinner())
        assertTrue(saved.sameDishAs(Recipe.fromEntries("Dinner", dinner())))
        // Split into two portions, or weighed after cooking: one tap would log something else than this meal.
        assertFalse(saved.copy(portions = 2).sameDishAs(Recipe.fromEntries("Dinner", dinner())))
        assertFalse(saved.copy(cookedWeightG = 400.0).sameDishAs(Recipe.fromEntries("Dinner", dinner())))
        assertNull(Recipe.savedDish(dinner(), mapOf("bowl" to saved.copy(portions = 2))))
    }

    @Test
    fun aNameIsTakenWhateverItsCapitals() {
        val names = mapOf("bowl" to "Rice bowl", "porridge" to "Porridge")
        assertEquals("bowl", Recipe.namesake("  rice BOWL ", names))
        assertNull(Recipe.namesake("Rice bowl 2", names))
        assertNull(Recipe.namesake("  ", names))
        // The dish being changed keeps its own name.
        assertNull(Recipe.namesake("Rice bowl", names, except = "bowl"))
        assertEquals("porridge", Recipe.namesake("porridge", names, except = "bowl"))
    }
}
