package com.cruxcoach.app.nutrition

import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.model.FoodItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Device test D43: database foods people count offer pieces. The shared rule (Android's
 * MealTextParserTest case, run here on the JVM through appcore) and the food items built from it.
 */
class FoodPiecesTest {

    @Test
    fun `database foods people count get a piece and only those`() {
        // Device test D43: "Banane roh" offered only grams.
        assertEquals(120.0, MealTextParser.pieceGramsOf("Banane roh"))
        assertEquals(150.0, MealTextParser.pieceGramsOf("Apfel roh"))
        assertEquals(60.0, MealTextParser.pieceGramsOf("Hühnerei roh"))
        assertEquals(70.0, MealTextParser.pieceGramsOf("Karotte/Möhre, roh"))
        assertEquals(100.0, MealTextParser.pieceGramsOf("Kartoffeln geschält, gegart"))
        // Compounds, processed forms and parts are not pieces.
        assertNull(MealTextParser.pieceGramsOf("Eierteigwaren gegart"))
        assertNull(MealTextParser.pieceGramsOf("Apfel getrocknet"))
        assertNull(MealTextParser.pieceGramsOf("Bananenchips frittiert, gesüßt"))
        assertNull(MealTextParser.pieceGramsOf("Hühnerei Eigelb, roh"))
        assertNull(MealTextParser.pieceGramsOf("Orangensaft"))
        assertNull(MealTextParser.pieceGramsOf("Kartoffelstärke (Kartoffelmehl)"))
        assertNull(MealTextParser.pieceGramsOf("Haferflocken"))
    }

    private fun bls(code: String, de: String, en: String) =
        BlsFood(code = code, nameDe = de, nameEn = en, kcal = 79.0, protein = 1.3, fat = 0.4, carbs = 15.9)

    @Test
    fun `a BLS food people count gets one piece as its portion in the app language`() {
        val banana = bls("F503100", "Banane roh", "Banana raw")
        val de = FoodItems.blsFoodItem(banana, german = true)
        assertEquals("Banane roh", de.name)
        assertEquals(120.0, de.servingG)
        assertEquals("1 Stück (120 g)", de.servingLabel)
        // The piece comes from the German name, whatever the app language.
        val en = FoodItems.blsFoodItem(banana, german = false)
        assertEquals("Banana raw", en.name)
        assertEquals(120.0, en.servingG)
        assertEquals("1 piece (120 g)", en.servingLabel)
        // Oat flakes are weighed, not counted.
        val oats = FoodItems.blsFoodItem(bls("C133000", "Haferflocken", "Oat flakes"), german = true)
        assertNull(oats.servingG)
        assertNull(oats.servingLabel)
    }

    @Test
    fun `only a saved BLS food without a portion gets its piece`() {
        val saved = FoodItem(id = "bls:F503100", name = "Banane roh", source = FuelRules.SOURCE_BLS, kcalPer100 = 79.0)
        val withPiece = FoodItems.withPiece(saved, german = true)
        assertEquals(120.0, withPiece.servingG)
        assertEquals("1 Stück (120 g)", withPiece.servingLabel)
        assertEquals(saved.copy(servingG = 120.0, servingLabel = "1 Stück (120 g)"), withPiece)
        // A portion the athlete or the database already set stays.
        val own = saved.copy(servingG = 100.0, servingLabel = "1 kleine")
        assertSame(own, FoodItems.withPiece(own, german = true))
        // Other sources and foods nobody counts stay as they are.
        val user = saved.copy(id = "u1", source = FuelRules.SOURCE_USER)
        assertSame(user, FoodItems.withPiece(user, german = true))
        val oats = saved.copy(id = "bls:C133000", name = "Haferflocken")
        assertSame(oats, FoodItems.withPiece(oats, german = true))
    }
}
