package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.MealTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Typed meals without the model, against the real BLS table (owner 2026-10-19: recognise foods well and above all
 * split them correctly, with as few format rules for the user as possible).
 */
class MealTextSplitTest {

    private val matcher by lazy { FoodMatcher(BlsTable.parse(File("../androidApp/src/main/assets/fuel/bls_4_0_macros.tsv").readText())) }
    private val score: (String) -> Double = { name -> matcher.match(DetectedFood(name, name, 100.0), 1).firstOrNull()?.score ?: 0.0 }

    /** name → grams → best BLS name, in order. */
    private fun read(text: String): List<Triple<String, Int, String?>> = MealTextParser.parse(text, score).foods.map { f ->
        Triple(f.nameDe, f.grams.toInt(), matcher.match(f, 1).firstOrNull()?.food?.nameDe)
    }

    private fun names(text: String) = read(text).map { it.first }
    private fun grams(text: String) = read(text).map { it.second }

    @Test
    fun `amounts start the next food when nothing separates them`() {
        assertEquals(listOf("Eier", "Banane", "Milch"), names("2 eier 1 banane 200ml milch"))
        assertEquals(listOf(120, 120, 200), grams("2 eier 1 banane 200ml milch"))
        assertEquals(listOf("Magerquark", "Banane", "Haferflocken"), names("300g Magerquark 1 Banane 30g Haferflocken"))
        assertEquals(listOf(300, 120, 30), grams("300g Magerquark 1 Banane 30g Haferflocken"))
        // An amount after the last food stays with it.
        assertEquals(listOf(200, 150), grams("Reis 200g, Hähnchen 150g"))
        assertEquals(listOf(1500), grams("Wasser 1,5 l"))
    }

    @Test
    fun `a list of foods without any separator is split, a dish name is not`() {
        assertEquals(listOf("Banane", "Apfel", "Birne"), names("Banane Apfel Birne"))
        assertEquals(listOf("Hähnchen", "Reis", "Gemüse"), names("hähnchen reis gemüse"))
        assertEquals(listOf("Joghurt", "Banane", "Haferflocken"), names("Joghurt Banane Haferflocken"))
        assertEquals(listOf("Chicken", "Rice", "Broccoli"), names("chicken rice broccoli"))
        assertEquals(listOf("Pizza margherita"), names("Pizza Margherita"))
        assertEquals(listOf("Spaghetti bolognese"), names("Spaghetti Bolognese"))
        assertEquals(listOf("Greek yogurt", "Honey", "Walnuts"), names("greek yogurt with honey and walnuts"))
    }

    @Test
    fun `fractions, halves and multipliers`() {
        assertEquals(listOf(60), grams("1/2 Banane"))
        assertEquals(listOf(225), grams("eine halbe Pizza"))
        assertEquals(listOf(120), grams("2 x Ei"))
        assertEquals(listOf("Spiegelei", "Toast"), names("2x Spiegelei und Toast"))
        assertEquals(120, grams("2x Spiegelei und Toast").first())
        assertEquals(listOf(120, 170), grams("1/2 cup rice and 6 oz salmon"))
    }

    @Test
    fun `what goes into a coffee is a splash and a spoon, toppings are spoonfuls`() {
        assertEquals(listOf(150, 30, 6), grams("Kaffee mit Milch und Zucker"))
        assertEquals("Zucker weiß (Raffinadezucker/Weißzucker)", read("Kaffee mit Milch und Zucker")[2].third)
        assertEquals(listOf(150, 125, 20), grams("Skyr mit Heidelbeeren und Honig"))
        assertEquals(listOf(150, 10), grams("Quark mit Leinöl"))
        assertEquals(listOf(350, 80, 15), grams("Spaghetti mit Tomatensoße und Parmesan"))
        // A glass of milk on its own is still a glass.
        assertEquals(200, grams("Käsebrot und ein Glas Milch").last())
    }

    @Test
    fun `pieces, plurals and topped breads`() {
        assertEquals(listOf(60, 150), grams("Croissant und Kaffee"))
        assertEquals(listOf(150, 200, 150), grams("Lachs mit Kartoffeln und Spinat"))
        assertEquals(listOf(10), grams("ein Stück Schokolade"))
        assertEquals(listOf("Brot", "Käse", "Milch"), names("Käsebrot und ein Glas Milch"))
        assertEquals(listOf(50, 30), grams("Käsebrot").take(2))
        assertEquals(listOf("Rührei"), names("Rührei aus 3 Eiern"))
        assertEquals(listOf(180), grams("Rührei aus 3 Eiern"))
        assertEquals(listOf("Pommes", "Ketchup", "Mayonnaise"), names("Pommes rot weiß"))
    }

    @Test
    fun `everyday words find the plain food, supplements find nothing rather than something wrong`() {
        assertEquals("Banane roh", read("2 Bananen, 1 Apfel")[0].third)
        assertEquals("Beerenmischung mit Sauerkirschen, ungesüßt, roh", read("Müsli mit Joghurt und Beeren")[2].third)
        assertEquals("Gurke roh", read("Vollkornbrot mit Gurke")[1].third)
        assertEquals("Kuchen aus Sandmasse/Sandkuchen", read("1 Stück Kuchen")[0].third)
        assertTrue(read("oatmeal with banana")[0].third!!.startsWith("Porridge"))
        assertEquals(null, read("1 Scoop Whey in 300 ml Milch")[0].third)
        assertEquals(listOf("Whey", "Milch"), names("1 Scoop Whey in 300 ml Milch"))
        assertEquals(null, read("Proteinshake mit Wasser")[0].third)
        assertEquals(null, read("a protein bar")[0].third)
    }
}
