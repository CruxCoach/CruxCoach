package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FoodMatcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the matcher against the real bundled BLS 4.0 extract with names the
 * photo model actually produces (FEAT-069). The expectations are the codes
 * a dietitian would pick for a photographed plate.
 */
class BlsAssetMatchingTest {

    private val foods = BlsTable.parse(
        File("../androidApp/src/main/assets/fuel/bls_4_0_macros.tsv").readText(),
    )
    private val matcher = FoodMatcher(foods)

    private val expected = listOf(
        Triple("Reis gekocht", "Boiled rice", setOf("C352032", "X820162")),
        Triple("Haferflocken", "Oat flakes", setOf("C133000")),
        Triple("Kartoffeln gekocht", "Boiled potatoes", setOf("K110132", "K120134", "X6A1010")),
        Triple("Hähnchenbrust gebraten", "Fried chicken breast", setOf("V4A6162", "V4A6182", "V416162", "V416182", "Y562032")),
        Triple("Brokkoli", "Broccoli", setOf("G312132", "G312152", "X532512")),
        Triple("Brühlchen", "Brussels sprouts", setOf("G332132", "G332152", "X536053")),
        Triple("Banane", "Banana", setOf("F503100")),
        Triple("Apfel", "Apple", setOf("F110100")),
        Triple("Lachs gebraten", "Fried salmon", setOf("T410082", "T410062", "Y640312")),
        Triple("Käsespätzle", "Cheese spaetzle", setOf("X711412")),
        Triple("Rührei", "Scrambled eggs", setOf("Y720143", "Y720163")),
        // Owner's device test 2026-10-05: pasta with aubergine-tomato sauce.
        // "Nudeln" had landed on Schupfnudeln, "eggplant" found nothing.
        Triple("Nudeln", "Pasta", COOKED_PASTA),
        Triple("Nudeln", "Noodles", COOKED_PASTA),
        Triple("Spaghetti", "Spaghetti", COOKED_PASTA),
        Triple("Eggplant", "Eggplant", AUBERGINE),
        Triple("Aubergine", "Eggplant", AUBERGINE),
        Triple("Tomatensoße", "Tomato sauce", setOf("X321163", "X321263", "X331353", "X321813", "X321153")),
        Triple("Zucchini", "Zucchini", setOf("G582132", "G582152", "G582172", "G582142", "G582162", "G582182", "G582100")),
    )

    private companion object {
        val COOKED_PASTA = setOf("E401032", "X432142")
        val MILK = setOf("M111300", "M111200", "M113300", "M113200")
        val AUBERGINE = setOf("G510132", "G510152", "G510172", "G510142", "G510162", "G510182", "G510100", "X573012")
    }

    @Test
    fun tableHasEveryFood() {
        assertEquals(7140, foods.size)
        assertTrue(foods.all { it.kcal >= 0 && it.protein >= 0 && it.fat >= 0 && it.carbs >= 0 })
    }

    @Test
    fun typicalModelNamesFindTheRightBlsEntry() {
        val misses = expected.mapNotNull { (de, en, codes) ->
            val ranked = matcher.match(DetectedFood(de, en, 100.0))
            println("$de / $en → " + ranked.take(3).joinToString(" | ") { "${it.food.code} ${it.food.nameDe} (${"%.2f".format(it.score)})" })
            if (ranked.firstOrNull()?.food?.code in codes) null else de
        }
        assertEquals(emptyList(), misses)
    }

    @Test
    fun everyExpectedEntryIsAmongTheAlternatives() {
        expected.forEach { (de, en, codes) ->
            val ranked = matcher.match(DetectedFood(de, en, 100.0)).map { it.food.code }
            assertTrue(ranked.any { it in codes }, "$de: $ranked")
        }
    }

    @Test
    fun aFoodNamedAloneIsNotTheIngredientOfAnother() {
        // Device test 2026-10-09: "… und ein Glas Milch" became "Roggenvollkornknäckebrot mit Milch".
        listOf(DetectedFood("Milch", "Milch", 200.0), DetectedFood("Milch", "Milk", 200.0)).forEach { food ->
            val ranked = matcher.match(food)
            assertTrue(ranked.first().food.code in MILK, "${food.nameEn}: " + ranked.take(3).map { it.food.nameDe })
        }
        // Typed in the search field, it had listed "Fleischersatz … milch- und sojahaltig" and "Rotzunge" first.
        assertTrue(matcher.search("Milch").first().food.code in MILK, matcher.search("Milch").take(3).map { it.food.nameDe }.toString())
    }

    /**
     * Bare everyday words, typed in the search or the meal description (device test 2026-10-09:
     * "Brot" had found "Russisch-Brot", "Käse" "Käse-Grießnockerl", "Joghurt" "Joghurt-Dip",
     * "Frischkäse" "Fleischkäse", "Egg" "Eierlikör").
     */
    @Test
    fun everydayWordsFindTheUsualFood() {
        val expected = mapOf(
            "Brot" to setOf("B251000", "B271000", "B311000"), "Bread" to setOf("B251000", "B271000", "B311000"),
            "Brötchen" to setOf("B511000"), "Toast" to setOf("B314000", "B254000"), "Knäckebrot" to setOf("B6A2100", "B6A2000"),
            "Butter" to setOf("Q630000"), "Käse" to setOf("M402600"), "Cheese" to setOf("M402600"),
            "Joghurt" to setOf("M141300", "M141200"), "Yogurt" to setOf("M141300", "M141200"), "Magerquark" to setOf("M713100"),
            "Frischkäse" to setOf("M710700", "M710800"), "Sahne" to setOf("M173800", "M173900"),
            "Ei" to setOf("E111132", "Y740162"), "Eier" to setOf("E111132", "Y740162"), "Egg" to setOf("E111132", "Y740162"),
            "Milch" to MILK, "Milk" to MILK, "Hafermilch" to setOf("C660000"), "Oat milk" to setOf("C660000"),
            "Schinken" to setOf("W424000"), "Ham" to setOf("W424000"), "Hähnchen" to setOf("Y562032", "V416172"),
            "Kartoffeln" to setOf("K110132", "K120134"), "Paprika" to setOf("G543100", "G541100", "G542100"),
            "Müsli" to setOf("C512000", "C512300"), "Oats" to setOf("C133000"), "Haferflocken" to setOf("C133000"),
            "Erdbeeren" to setOf("F301100"), "Kiwi" to setOf("F514100"), "Apple" to setOf("F110100"), "Banane" to setOf("F503100"),
            "Tee" to setOf("N630000"), "Bier" to setOf("P163000", "P161000"), "Beer" to setOf("P163000", "P161000"),
            "Wasser" to setOf("N110000", "N120000", "N128000"), "Apfelschorle" to setOf("N256000"),
            "Pizza" to setOf("X912033"), "Döner" to setOf("Y921162", "Y921062"),
        )
        val misses = expected.mapNotNull { (word, codes) ->
            val matched = matcher.match(DetectedFood(word, word, 100.0)).first().food
            val searched = matcher.search(word).first().food
            if (matched.code in codes && searched.code in codes) null else "$word → ${matched.nameDe} / ${searched.nameDe}"
        }
        assertEquals(emptyList(), misses)
    }

    @Test
    fun searchingACompoundFindsItsSplitBlsName() {
        // Device test 2026-10-07: "Haferflocken" listed oat cookies before "Hafer Flocken".
        assertEquals("C133000", matcher.search("Haferflocken").first().food.code)
    }
}
