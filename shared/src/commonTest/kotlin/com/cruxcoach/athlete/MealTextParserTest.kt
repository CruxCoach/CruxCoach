package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.model.Meal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MealTextParserTest {

    private fun parse(text: String) = MealTextParser.parse(text)
    private fun items(text: String) = parse(text).foods.map { it.nameDe to it.grams.toInt() }

    @Test
    fun gramsMillilitresAndPiecesWithMealHint() {
        val r = parse("Zum Frühstück 80 g Haferflocken mit 200 ml Milch und eine Banane")
        assertEquals(Meal.BREAKFAST, r.meal)
        assertEquals(listOf("Haferflocken" to 80, "Milch" to 200, "Banane" to 120), r.foods.map { it.nameDe to it.grams.toInt() })
    }

    @Test
    fun slicesGlassesAndCompounds() {
        assertEquals(
            listOf("Vollkornbrot" to 100, "Butter" to 10, "Käse" to 30, "Orangensaft" to 200),
            items("2 Scheiben Vollkornbrot mit Butter und Käse, dazu ein Glas Orangensaft"),
        )
    }

    @Test
    fun platesSizesAndTypicalPortions() {
        val r = parse("Mittags ein Teller Spaghetti Bolognese und ein kleiner gemischter Salat")
        assertEquals(Meal.LUNCH, r.meal)
        assertEquals(listOf("Spaghetti bolognese" to 350, "Gemischter salat" to 105), r.foods.map { it.nameDe to it.grams.toInt() })
    }

    @Test
    fun aFoodNamedTwiceKeepsTheStatedAmount() {
        val r = parse("Abends Reis mit Hähnchencurry, ungefähr halber Teller Reis")
        assertEquals(Meal.DINNER, r.meal)
        assertEquals(listOf("Reis" to 175, "Hähnchencurry" to 250), r.foods.map { it.nameDe to it.grams.toInt() })
    }

    @Test
    fun postTrainingShake() {
        val r = parse("Proteinshake mit 30 g Whey und 300 ml Wasser nach dem Training")
        assertEquals(Meal.POST_TRAINING, r.meal)
        assertEquals(listOf("Proteinshake" to 300, "Whey" to 30, "Wasser" to 300), r.foods.map { it.nameDe to it.grams.toInt() })
    }

    @Test
    fun countsFillerAndDecimalCommas() {
        assertEquals(listOf("Nüsse" to 30, "Apfel" to 150), items("Eine Handvoll Nüsse und ein Apfel"))
        assertEquals(listOf("Döner" to 400), items("Döner mit allem"))
        assertEquals(listOf("Eier" to 120, "Toast" to 50, "Kaffee" to 150, "Milch" to 200), items("2 Eier, 2 Toast und ein Kaffee mit Milch"))
        assertEquals(listOf("Wasser" to 1500), items("1,5 l Wasser"))
        assertEquals(listOf("Skyr" to 250), items("250g Skyr"))
    }

    @Test
    fun englishWorksToo() {
        val r = parse("For lunch two eggs and a slice of bread")
        assertEquals(Meal.LUNCH, r.meal)
        assertEquals(listOf("Eggs" to 120, "Bread" to 50), r.foods.map { it.nameDe to it.grams.toInt() })
    }

    @Test
    fun emptyOrFillerOnlyGivesNothing() {
        assertEquals(emptyList(), parse("").foods)
        assertEquals(emptyList(), parse("ich habe heute etwas gegessen").foods)
        assertNull(parse("Müsli").meal)
    }
}
