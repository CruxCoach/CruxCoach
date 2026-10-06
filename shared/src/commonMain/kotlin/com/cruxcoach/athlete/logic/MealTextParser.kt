package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.Meal
import kotlin.math.roundToInt

/**
 * Turns a typed meal ("2 Scheiben Vollkornbrot mit Käse, ein Glas Saft") into
 * foods with grams without any model (FEAT-069). It runs on every phone and
 * instantly; where the photo model is installed the app asks the model
 * instead and falls back to this parser.
 *
 * Amounts: numbers ("80", "1,5", "½") and number words ("eine", "halber",
 * "two"), units (g, ml, EL, Scheibe, Glas, Teller, Handvoll …) and pieces of
 * common foods (an egg, a banana, a roll). Without an amount a typical
 * portion is assumed; the review always lets the user correct it.
 */
object MealTextParser {

    data class Result(val foods: List<DetectedFood>, val meal: Meal?)

    const val MAX_ITEMS = 12
    private const val DEFAULT_PORTION_G = 150.0
    private const val DEFAULT_PIECE_G = 100.0

    fun parse(text: String): Result {
        var t = " " + text.lowercase().replace('\n', ',') + " "
        val meal = MEAL_HINTS.firstOrNull { (words, _) -> words.any { Regex("\\b$it\\b").containsMatchIn(t) } }?.second
        MEAL_HINTS.flatMap { it.first }.sortedByDescending { it.length }.forEach { t = t.replace(Regex("\\b$it\\b"), " ") }
        val segments = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        // A food named twice ("Reis …, halber Teller Reis") keeps the stated amount.
        val parsed = segments.mapNotNull(::segment)
        // groupBy keeps the order of first mentions.
        val foods = parsed.groupBy { FoodMatcher.normalize(it.food.nameDe).joinToString(" ") }.values
            .map { same -> (same.firstOrNull { it.explicit } ?: same.first()).food }
            .take(MAX_ITEMS)
        return Result(foods, meal)
    }

    private class Parsed(val food: DetectedFood, val explicit: Boolean)

    private fun segment(raw: String): Parsed? {
        val words = raw.replace(Regex("[^\\p{L}\\p{N}½¼¾.,]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        var count: Double? = null
        var unitGrams: Double? = null
        var sizeFactor = 1.0
        val nameWords = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val number = numberOf(w)
            when {
                number != null && count == null -> {
                    count = number
                    // "80g" written together
                    UNIT_SUFFIX.matchEntire(w)?.groupValues?.get(2)?.let { u -> UNITS[u]?.let { unitGrams = it } }
                }
                count == null && w in NUMBER_WORDS -> count = NUMBER_WORDS.getValue(w)
                unitGrams == null && w in UNITS -> unitGrams = UNITS.getValue(w)
                w in SIZE -> sizeFactor = SIZE.getValue(w)
                w in FILLER -> Unit
                else -> nameWords += w
            }
            i++
        }
        val name = nameWords.joinToString(" ").trim().trim('.', ',')
        if (name.isEmpty() || name in FILLER) return null
        val key = FoodMatcher.normalize(name).joinToString(" ")
        val unit = unitGrams
        val grams = when {
            unit == UNIT_SLICE || unit == UNIT_PIECE ->
                (count ?: 1.0) * (pieceWeight(key, slice = unit == UNIT_SLICE) ?: DEFAULT_PIECE_G)
            unit != null -> (count ?: 1.0) * unit
            count != null -> count * (pieceWeight(key, slice = false) ?: portionOf(key) ?: DEFAULT_PIECE_G)
            else -> portionOf(key) ?: DEFAULT_PORTION_G
        } * sizeFactor
        val label = name.replaceFirstChar { it.uppercase() }
        val food = DetectedFood(label, label, grams.roundToInt().toDouble().coerceIn(1.0, FoodVisionParser.MAX_GRAMS))
        return Parsed(food, explicit = count != null || unit != null)
    }

    private val UNIT_SUFFIX = Regex("([0-9]+(?:[.,][0-9]+)?)([a-z]+)")

    private fun numberOf(word: String): Double? {
        when (word) {
            "½" -> return 0.5
            "¼" -> return 0.25
            "¾" -> return 0.75
        }
        val m = UNIT_SUFFIX.matchEntire(word)
        val digits = m?.groupValues?.get(1) ?: word
        return digits.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    }

    /** Weight of one piece or slice for foods people count. */
    private fun pieceWeight(key: String, slice: Boolean): Double? = lookup(if (slice) SLICES else PIECES, key)

    private fun portionOf(key: String): Double? = lookup(PORTIONS, key)

    /**
     * A word matches a table entry by its start ("bananen") or, for entries of
     * three letters or more, by its end, where German puts the head noun of a
     * compound ("Proteinshake", "Orangensaft").
     */
    private fun lookup(table: Map<String, Double>, key: String): Double? {
        val words = key.split(' ')
        return table.entries.firstOrNull { (k, _) -> words.any { it.startsWith(k) || (k.length >= 3 && it.endsWith(k)) } }?.value
    }

    /** A comma between digits is a decimal comma ("1,5 l"), not a separator. */
    private val SEPARATORS = Regex("(?<!\\d),|,(?!\\d)|[;+&]|\\bund\\b|\\bmit\\b|\\bsowie\\b|\\bplus\\b|\\bdazu\\b|\\band\\b|\\bwith\\b")

    private val MEAL_HINTS: List<Pair<List<String>, Meal>> = listOf(
        listOf("vor dem training", "before training", "pre workout", "pre-workout") to Meal.PRE_TRAINING,
        listOf("nach dem training", "after training", "post workout", "post-workout") to Meal.POST_TRAINING,
        listOf("zum frühstück", "zum fruehstueck", "frühstück", "morgens", "for breakfast", "breakfast") to Meal.BREAKFAST,
        listOf("zum mittagessen", "zu mittag", "mittagessen", "mittags", "for lunch", "lunch") to Meal.LUNCH,
        listOf("zum abendessen", "zu abend", "abendessen", "abendbrot", "abends", "for dinner", "dinner") to Meal.DINNER,
        listOf("als snack", "zwischendurch", "snack") to Meal.SNACK,
    )

    private val NUMBER_WORDS = mapOf(
        "ein" to 1.0, "eine" to 1.0, "einen" to 1.0, "einem" to 1.0, "einer" to 1.0, "eins" to 1.0,
        "a" to 1.0, "an" to 1.0, "one" to 1.0,
        "zwei" to 2.0, "two" to 2.0, "drei" to 3.0, "three" to 3.0, "vier" to 4.0, "four" to 4.0,
        "fünf" to 5.0, "five" to 5.0, "sechs" to 6.0, "six" to 6.0, "paar" to 2.0, "couple" to 2.0,
        "halb" to 0.5, "halbe" to 0.5, "halber" to 0.5, "halbes" to 0.5, "halben" to 0.5, "half" to 0.5,
        "anderthalb" to 1.5, "eineinhalb" to 1.5, "viertel" to 0.25,
    )

    private const val UNIT_SLICE = -1.0
    private const val UNIT_PIECE = -2.0

    /** Grams per unit; slices and pieces depend on the food. Drinks: 1 ml ≈ 1 g. */
    private val UNITS = mapOf(
        "g" to 1.0, "gr" to 1.0, "gramm" to 1.0, "gram" to 1.0, "grams" to 1.0, "kg" to 1000.0,
        "ml" to 1.0, "l" to 1000.0, "liter" to 1000.0, "litre" to 1000.0, "cl" to 10.0, "dl" to 100.0,
        "el" to 15.0, "esslöffel" to 15.0, "tbsp" to 15.0, "tablespoon" to 15.0, "tablespoons" to 15.0,
        "tl" to 5.0, "teelöffel" to 5.0, "tsp" to 5.0, "teaspoon" to 5.0, "teaspoons" to 5.0,
        "scheibe" to UNIT_SLICE, "scheiben" to UNIT_SLICE, "slice" to UNIT_SLICE, "slices" to UNIT_SLICE,
        "stück" to UNIT_PIECE, "stk" to UNIT_PIECE, "piece" to UNIT_PIECE, "pieces" to UNIT_PIECE,
        "tasse" to 150.0, "tassen" to 150.0, "cup" to 150.0, "cups" to 150.0,
        "glas" to 200.0, "gläser" to 200.0, "glass" to 200.0, "glasses" to 200.0,
        "becher" to 150.0, "schüssel" to 250.0, "schale" to 250.0, "bowl" to 250.0, "bowls" to 250.0,
        "teller" to 350.0, "plate" to 350.0, "plates" to 350.0,
        "portion" to 150.0, "portionen" to 150.0, "serving" to 150.0, "servings" to 150.0,
        "handvoll" to 30.0, "handful" to 30.0, "prise" to 1.0, "pinch" to 1.0,
        "dose" to 330.0, "can" to 330.0, "flasche" to 500.0, "bottle" to 500.0,
        "riegel" to 40.0, "bar" to 40.0, "schuss" to 20.0, "splash" to 20.0, "spritzer" to 20.0,
    )

    private val SIZE = mapOf(
        "klein" to 0.7, "kleine" to 0.7, "kleiner" to 0.7, "kleines" to 0.7, "kleinen" to 0.7, "small" to 0.7,
        "groß" to 1.3, "große" to 1.3, "großer" to 1.3, "großes" to 1.3, "großen" to 1.3, "large" to 1.3, "big" to 1.3,
    )

    private val FILLER = setOf(
        "ich", "hatte", "habe", "hab", "gegessen", "getrunken", "noch", "außerdem", "etwa", "ca", "ca.", "circa",
        "ungefähr", "rund", "so", "zum", "zur", "am", "heute", "dem", "den", "der", "die", "das", "von", "vom",
        "allem", "alles", "bisschen", "etwas", "i", "had", "ate", "drank", "about", "around", "some", "the", "of",
    )

    /** Normalised word prefix → grams of one piece. */
    private val PIECES = linkedMapOf(
        "eier" to 60.0, "ei" to 60.0, "egg" to 60.0, "banane" to 120.0, "banana" to 120.0, "apfel" to 150.0, "aepfel" to 150.0,
        "apple" to 150.0, "birne" to 160.0, "pear" to 160.0, "orange" to 150.0, "mandarine" to 70.0, "kiwi" to 75.0,
        "pfirsich" to 130.0, "peach" to 130.0, "broetchen" to 60.0, "semmel" to 60.0, "roll" to 60.0,
        "croissant" to 60.0, "brezel" to 80.0, "laugen" to 80.0, "pretzel" to 80.0, "toast" to 25.0,
        "kartoffel" to 100.0, "potato" to 100.0, "tomate" to 80.0, "tomato" to 80.0, "karotte" to 70.0,
        "moehre" to 70.0, "carrot" to 70.0, "wuerstchen" to 50.0, "sausage" to 50.0, "frikadelle" to 100.0,
        "muffin" to 80.0, "keks" to 10.0, "cookie" to 10.0, "riegel" to 40.0, "joghurt" to 150.0,
        "yogurt" to 150.0, "pizza" to 450.0, "doener" to 400.0, "kebab" to 400.0, "burger" to 250.0,
    )

    /** Normalised word prefix → grams of one slice. */
    private val SLICES = linkedMapOf(
        "brot" to 50.0, "bread" to 50.0, "vollkornbrot" to 50.0, "toast" to 25.0, "kaese" to 20.0,
        "cheese" to 20.0, "schinken" to 20.0, "ham" to 20.0, "wurst" to 15.0, "salami" to 10.0, "pizza" to 110.0,
    )

    /** Normalised word prefix → typical portion when no amount is given. */
    private val PORTIONS = linkedMapOf(
        "doener" to 400.0, "kebab" to 400.0, "pizza" to 450.0, "burger" to 250.0, "kaffee" to 150.0,
        "coffee" to 150.0, "tee" to 250.0, "tea" to 250.0, "salat" to 150.0, "salad" to 150.0,
        "spaghetti" to 350.0, "nudeln" to 350.0, "pasta" to 350.0, "reis" to 200.0, "rice" to 200.0,
        "curry" to 250.0, "suppe" to 300.0, "soup" to 300.0, "muesli" to 60.0, "haferflocken" to 60.0,
        "oats" to 60.0, "butter" to 10.0, "kaese" to 30.0, "cheese" to 30.0, "milch" to 200.0, "milk" to 200.0,
        "wasser" to 300.0, "water" to 300.0, "saft" to 200.0, "juice" to 200.0, "shake" to 300.0,
        "nuesse" to 30.0, "nuts" to 30.0,
    )
}
