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

    /**
     * [foodScore] (optional) rates how well a name matches a known food – the app passes its food table's matcher.
     * With it, a list typed without any separators ("Banane Apfel Birne") is split where every word is a food on its
     * own but the whole phrase is none.
     */
    fun parse(text: String, foodScore: ((String) -> Double)? = null): Result {
        var t = " " + prepare(text.lowercase()) + " "
        val meal = MEAL_HINTS.firstOrNull { (words, _) -> words.any { Regex("\\b$it\\b").containsMatchIn(t) } }?.second
        MEAL_HINTS.flatMap { it.first }.sortedByDescending { it.length }.forEach { t = t.replace(Regex("\\b$it\\b"), " ") }
        val segments = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        // A food named twice ("Reis …, halber Teller Reis") keeps the stated amount.
        val parsed = mutableListOf<Parsed>()
        for (raw in segments) {
            // "Chicken breast, about 6 oz": an amount on its own belongs to the food before it.
            val amount = amountOnly(raw)
            val last = parsed.lastOrNull()
            if (amount != null) {
                if (last != null && !last.explicit) parsed[parsed.lastIndex] = Parsed(last.food.copy(grams = amount), explicit = true, last.nameWords)
                continue
            }
            // "2 Eier 1 Banane 200 ml Milch": a new amount followed by a food starts the next item.
            val parts = splitAtAmounts(wordsOf(raw))
            var i = 0
            while (i < parts.size) {
                val part = parts[i]
                // "Rührei aus 3 Eiern": the amount after "aus" weighs the food before it.
                if (part.size > 1 && part.last() in FROM && i + 1 < parts.size) {
                    val head = segment(part.dropLast(1), parsed.lastOrNull())
                    val weight = segment(parts[i + 1], null)
                    if (head != null && weight != null && weight.explicit) {
                        parsed += Parsed(head.food.copy(grams = weight.food.grams), explicit = true, head.nameWords)
                        i += 2
                        continue
                    }
                }
                segment(part, parsed.lastOrNull())?.let { parsed += splitByLexicon(it, foodScore) }
                i++
            }
        }
        // groupBy keeps the order of first mentions.
        val foods = parsed.groupBy { FoodMatcher.normalize(it.food.nameDe).joinToString(" ") }.values
            .map { same -> (same.firstOrNull { it.explicit } ?: same.first()).food }
            .take(MAX_ITEMS)
        return Result(foods, meal)
    }

    private class Parsed(val food: DetectedFood, val explicit: Boolean, val nameWords: List<String> = emptyList()) {
        val key: String get() = FoodMatcher.normalize(nameWords.joinToString(" ")).joinToString(" ")
    }

    private fun wordsOf(raw: String): List<String> =
        com.cruxcoach.util.replaceNonWordRuns(raw, "½¼¾.,", " ").trim().split(' ').filter { it.isNotEmpty() }

    /** Fractions, "eine halbe", "2 x", and a few dish idioms, before the text is split. */
    private fun prepare(text: String): String {
        var t = text.replace('\n', ',').replace('×', 'x')
        t = MIXED_FRACTION.replace(t) { m ->
            decimal(m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / m.groupValues[3].toDouble().coerceAtLeast(1.0))
        }
        t = FRACTION.replace(t) { m -> decimal(m.groupValues[1].toDouble() / m.groupValues[2].toDouble().coerceAtLeast(1.0)) }
        REWRITES.forEach { (pattern, to) -> t = pattern.replace(t, to) }
        t = TOPPED_BREAD.replace(t) { m ->
            val topping = TOPPING_NAMES[m.groupValues[1]] ?: m.groupValues[1]
            val bread = m.groupValues[2]
            if (bread.startsWith("br\u00f6tchen") || bread.startsWith("broetchen")) "brötchen mit $topping" else "scheibe brot mit $topping"
        }
        return t
    }

    private fun decimal(d: Double): String {
        val r = (d * 100).roundToInt() / 100.0
        return if (r == r.toInt().toDouble()) " ${r.toInt()} " else " $r "
    }

    /**
     * Splits a segment where a new amount starts after a food and is followed by one ("magerquark 1 banane 30g
     * haferflocken"); an amount after the last food stays with it ("reis 200g", "wasser 1,5 l").
     */
    private fun splitAtAmounts(words: List<String>): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        var named = false
        words.forEachIndexed { i, w ->
            if (isQuantity(w) && named && words.drop(i + 1).any(::isNameWord)) {
                parts += mutableListOf<String>()
                named = false
            }
            parts.last() += w
            if (isNameWord(w)) named = true
        }
        return parts.filter { it.isNotEmpty() }
    }

    private fun isQuantity(w: String) = numberOf(w) != null || w in NUMBER_WORDS
    private fun isNameWord(w: String) = !isQuantity(w) && w !in UNITS && w !in SIZE && w !in FILLER && w !in TRAILING

    /**
     * "Banane Apfel Birne": several words that are each a food, while the whole phrase is none, are several foods.
     * "Pizza Margherita" or "griechischer Joghurt" stay one: not every word is a food on its own.
     */
    private fun splitByLexicon(p: Parsed, score: ((String) -> Double)?): List<Parsed> {
        if (score == null || p.explicit || p.nameWords.size !in 2..5) return listOf(p)
        val parts = p.nameWords.map { it to score(it) }
        val weakest = parts.minOf { it.second }
        if (weakest < LEXICON_SPLIT_MIN || score(p.nameWords.joinToString(" ")) >= weakest - 0.05) return listOf(p)
        return parts.mapNotNull { (w, _) -> segment(listOf(w), null) }
    }

    private fun segment(words: List<String>, previous: Parsed?): Parsed? {
        var count: Double? = null
        var unitGrams: Double? = null
        var sizeFactor = 1.0
        val nameWords = mutableListOf<String>()
        var afterAmount = false
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val number = numberOf(w)
            val amountWord = number != null || w in NUMBER_WORDS
            when {
                number != null && count == null -> {
                    count = number
                    // "80g" written together
                    UNIT_SUFFIX.matchEntire(w)?.groupValues?.get(2)?.let { u -> UNITS[u]?.let { unitGrams = it } }
                }
                // "half a pound": the second number word adds nothing.
                w in NUMBER_WORDS -> if (count == null) count = NUMBER_WORDS.getValue(w)
                // A unit right after an amount or before the food ("ein Glas Milch"); after the food it is part of
                // the name ("a protein bar").
                unitGrams == null && w in UNITS && (afterAmount || nameWords.isEmpty()) -> unitGrams = UNITS.getValue(w)
                w in SIZE -> sizeFactor = SIZE.getValue(w)
                w in FILLER -> Unit
                else -> nameWords += w
            }
            afterAmount = amountWord
            i++
        }
        // "Whey in", "Brot mit": a word that only linked to the next food is no part of the name.
        while (nameWords.isNotEmpty() && nameWords.last() in TRAILING) nameWords.removeAt(nameWords.lastIndex)
        while (nameWords.isNotEmpty() && nameWords.first() in TRAILING) nameWords.removeAt(0)
        val name = nameWords.joinToString(" ").trim().trim('.', ',')
        if (name.isEmpty() || name in FILLER) return null
        val key = FoodMatcher.normalize(name).joinToString(" ")
        val unit = unitGrams
        // "Kaffee mit Milch und Zucker": what goes into a coffee or tea is a splash and a spoon, not a glass and a bowl.
        val intoDrink = previous != null && previous.key.split(' ').any { it in DRINKS_WITH_ADDITIONS }
        val grams = when {
            unit == UNIT_SLICE || unit == UNIT_PIECE ->
                (count ?: 1.0) * (pieceWeight(key, slice = unit == UNIT_SLICE) ?: DEFAULT_PIECE_G)
            unit != null -> (count ?: 1.0) * unit
            count != null -> count * (pieceWeight(key, slice = false) ?: portionOf(key) ?: DEFAULT_PIECE_G)
            intoDrink && exact(ADDITIONS, key) != null -> exact(ADDITIONS, key)!!
            // Without an amount: the usual portion; a food people count is one piece (a croissant, not 150 g).
            else -> exact(SMALL_PORTIONS, key) ?: portionOf(key)
                // "Kartoffeln", "Eier" without a number: a couple, not one.
                ?: pieceWeight(key, slice = false)?.let { it * if (isPlural(key)) 2 else 1 } ?: DEFAULT_PORTION_G
        } * sizeFactor
        val label = name.replaceFirstChar { it.uppercase() }
        val food = DetectedFood(label, label, grams.roundToInt().toDouble().coerceIn(1.0, FoodVisionParser.MAX_GRAMS))
        return Parsed(food, explicit = count != null || unit != null, nameWords.toList())
    }

    /** Grams of a segment that is only an amount ("about 6 oz", "200 g"), else null. */
    private fun amountOnly(raw: String): Double? {
        val words = com.cruxcoach.util.replaceNonWordRuns(raw, "½¼¾.,", " ").trim().split(' ').filter { it.isNotEmpty() }
        var count: Double? = null
        var unit: Double? = null
        for (w in words) {
            val number = numberOf(w)
            when {
                number != null && count == null -> {
                    count = number
                    UNIT_SUFFIX.matchEntire(w)?.groupValues?.get(2)?.let { u -> UNITS[u]?.let { unit = it } }
                }
                w in NUMBER_WORDS && count == null -> count = NUMBER_WORDS.getValue(w)
                unit == null && w in UNITS -> unit = UNITS.getValue(w)
                w in FILLER || w in SIZE || w in TRAILING -> Unit
                else -> return null
            }
        }
        val grams = unit?.takeIf { it > 0 } ?: return null
        return ((count ?: 1.0) * grams).takeIf { it in 1.0..FoodVisionParser.MAX_GRAMS }
    }

    private val UNIT_SUFFIX = Regex("([0-9]+(?:[.,][0-9]+)?)([a-z]+)")

    private val MIXED_FRACTION = Regex("(\\d+)\\s+(\\d+)\\s*/\\s*(\\d+)")
    private val FRACTION = Regex("(?<![\\d.,])(\\d+)\\s*/\\s*(\\d+)(?!\\d)")

    /** Rewritten before splitting: halves, multipliers, dish idioms. */
    private val REWRITES = listOf(
        Regex("\\b(eine|ein|einen|einem|einer)\\s+halbe[nsrm]?\\b") to " 0.5 ",
        Regex("\\b(anderthalb|eineinhalb)\\w*\\b") to " 1.5 ",
        Regex("\\bone and a half\\b") to " 1.5 ",
        Regex("\\bhalf an?\\b") to " 0.5 ",
        // "2x Ei", "2 x Ei": the x only multiplies.
        Regex("(\\d+(?:[.,]\\d+)?)\\s*x(?=\\s|$)") to "$1 ",
        Regex("\\bpommes\\s+(rot[ -]?wei(ß|ss)|schranke)\\b") to "pommes, ketchup, mayonnaise",
        Regex("\\b(aus der|aus einer) (dose|büchse|buechse)\\b") to " konserve ",
    )

    /** "Käsebrot", "2 Marmeladenbrötchen": the bread and what is on it, so both get their own nutrients. */
    private val TOPPED_BREAD = Regex("\\b(käse|kaese|butter|wurst|salami|schinken|marmeladen|marmelade|nutella|honig|frischkäse|" +
        "frischkaese|quark|leberwurst|teewurst|mett|avocado|ei|eier)(brot|brote|broten|brötchen|broetchen)\\b")
    private val TOPPING_NAMES = mapOf("marmeladen" to "marmelade", "eier" to "ei", "kaese" to "käse", "frischkaese" to "frischkäse")

    /** Words that only link a food to the next ("Whey in Milch", "Rührei aus 3 Eiern"). */
    private val TRAILING = setOf("in", "im", "auf", "on", "into", "zu", "to", "from", "aus", "mit", "with", "und", "and")
    private val FROM = setOf("aus", "from")

    /** A word must at least match a food this well for a separator-less list to be split at it. */
    private const val LEXICON_SPLIT_MIN = 1.0

    /** Drinks that take a splash of milk and a spoon of sugar. */
    private val DRINKS_WITH_ADDITIONS = setOf("kaffee", "tee", "coffee", "tea", "espresso", "filterkaffee", "schwarztee", "gruentee")

    /** What goes into a coffee or tea when no amount is given (exact normalised name → grams). */
    private val ADDITIONS = mapOf(
        "milch" to 30.0, "milk" to 30.0, "hafermilch" to 30.0, "sojamilch" to 30.0, "haferdrink" to 30.0, "oat milk" to 30.0,
        "sahne" to 20.0, "cream" to 20.0, "kaffeesahne" to 15.0, "zucker" to 6.0, "sugar" to 6.0, "honig" to 10.0,
        "honey" to 10.0, "suessstoff" to 1.0, "sweetener" to 1.0,
    )

    /**
     * Toppings and condiments without an amount: a spoonful, not the 150 g default (exact normalised name → grams).
     * 150 g of honey or oil would add hundreds of kcal that were never eaten.
     */
    private val SMALL_PORTIONS = mapOf(
        "zucker" to 6.0, "sugar" to 6.0, "honig" to 20.0, "honey" to 20.0, "marmelade" to 25.0, "konfituere" to 25.0,
        "jam" to 25.0, "gelee" to 25.0, "sirup" to 20.0, "ahornsirup" to 20.0, "maple syrup" to 20.0, "agavendicksaft" to 15.0,
        "pesto" to 30.0, "oel" to 10.0, "olivenoel" to 10.0, "leinoel" to 10.0, "rapsoel" to 10.0, "kokosoel" to 10.0,
        "oil" to 10.0, "olive oil" to 10.0, "ketchup" to 20.0, "mayonnaise" to 15.0, "mayo" to 15.0, "senf" to 10.0,
        "mustard" to 10.0, "zimt" to 2.0, "cinnamon" to 2.0, "salz" to 1.0, "salt" to 1.0, "parmesan" to 15.0,
        "feta" to 50.0, "mozzarella" to 60.0, "schinken" to 40.0, "ham" to 40.0, "salami" to 30.0, "speck" to 30.0,
        "beeren" to 125.0, "berries" to 125.0, "heidelbeeren" to 125.0, "blueberries" to 125.0, "himbeeren" to 125.0,
        "raspberries" to 125.0, "erdbeeren" to 150.0, "strawberries" to 150.0, "rosinen" to 20.0, "raisins" to 20.0,
        "kakao" to 10.0, "kakaopulver" to 10.0, "sahne" to 30.0, "erdnussbutter" to 20.0, "peanut butter" to 20.0,
        "nussmus" to 20.0, "tahin" to 15.0, "hummus" to 50.0, "dressing" to 30.0, "sosse" to 80.0, "sauce" to 80.0,
        "kaesebrot" to 80.0, "butterbrot" to 60.0, "wurstbrot" to 80.0,
        // A scoop of powder (device test 2026-10-19: "Whey" alone became 150 g).
        "whey" to 30.0, "proteinpulver" to 30.0, "eiweisspulver" to 30.0, "protein powder" to 30.0, "whey protein" to 30.0,
    )

    /** [table]'s grams for the whole normalised name or its singular/plural, else null (no prefix guessing). */
    private fun exact(table: Map<String, Double>, key: String): Double? =
        table[key] ?: table[key.removeSuffix("n")] ?: table[key.removeSuffix("en")] ?: table[key.removeSuffix("s")]

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

    /**
     * Grams of one piece for a database food people count ("Banane roh" → 120, "Hühnerei roh" → 60), so its amount
     * dialog can offer pieces (device test D43). Strict on purpose: the name's first word must be the counted food
     * itself (or its plural), never a compound ("Eierteigwaren") or a processed form ("Apfel getrocknet").
     */
    fun pieceGramsOf(foodName: String): Double? {
        val words = FoodMatcher.normalize(foodName)
        val first = words.firstOrNull() ?: return null
        if (words.any { w -> NOT_A_PIECE.any { w.startsWith(it) } }) return null
        val key = DATABASE_PIECES[first] ?: PIECES.keys.firstOrNull { k ->
            first == k || listOf("n", "en", "e", "s").any { first == k + it }
        }?.let { PIECES[it] }
        return key
    }

    /** Words that make a food not countable by the piece: dried, powdered, juiced, parts of it. */
    private val NOT_A_PIECE = listOf("getrocknet", "dried", "pulver", "powder", "trocken", "chips", "saft", "juice",
        "konserve", "canned", "eigelb", "eiklar", "yolk", "white", "mehl", "flour", "staerke", "starch", "pueree", "mus",
        "frittiert", "fried", "kompott", "konfituere", "marmelade", "jam")

    /** Database names for counted foods that the text tables spell differently. */
    private val DATABASE_PIECES = mapOf("huehnerei" to 60.0, "huehnereier" to 60.0)

    /** Weight of one piece or slice for foods people count. */
    private fun pieceWeight(key: String, slice: Boolean): Double? =
        if (slice) lookup(SLICES, key) else exact(EXACT_PIECES, key) ?: lookup(PIECES, key)

    /** Pieces matched only by the whole name: "ein Stück Schokolade" is a square, but "Schokoladenkuchen" is no 10 g. */
    private val EXACT_PIECES = mapOf(
        "schokolade" to 10.0, "chocolate" to 10.0, "spiegelei" to 60.0, "spiegeleier" to 60.0, "fried egg" to 60.0,
        "fried eggs" to 60.0, "praline" to 12.0, "pralinen" to 12.0,
    )

    /** The plural of a counted food ("kartoffeln", "eggs", "kiwis"), not the food itself. */
    private fun isPlural(key: String): Boolean {
        if (key in PLURALS) return true
        val singular = listOf("en", "n", "s", "e").firstNotNullOfOrNull { suffix ->
            key.takeIf { it.length > suffix.length + 2 && it.endsWith(suffix) }?.removeSuffix(suffix)
        } ?: return false
        return singular in PIECES || singular in EXACT_PIECES
    }

    private val PLURALS = setOf("eier", "aepfel", "eggs", "apples", "bananas", "nuesse")

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
        listOf("zum frühstück", "zum fruehstueck", "heute morgen", "frühstück", "morgens", "for breakfast", "breakfast") to Meal.BREAKFAST,
        listOf("zum mittagessen", "zu mittag", "zum mittag", "heute mittag", "mittagessen", "mittags", "mittag",
            "for lunch", "lunch") to Meal.LUNCH,
        listOf("zum abendessen", "zu abend", "zum abend", "heute abend", "abendessen", "abendbrot", "abends",
            "for dinner", "dinner") to Meal.DINNER,
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
        // A German "Tasse" is a coffee cup, an English "cup" the US measure.
        "tasse" to 150.0, "tassen" to 150.0, "cup" to 240.0, "cups" to 240.0, "mug" to 300.0, "mugs" to 300.0,
        "oz" to 28.35, "ounce" to 28.35, "ounces" to 28.35, "lb" to 453.6, "lbs" to 453.6, "pound" to 453.6,
        "pounds" to 453.6, "pint" to 500.0, "pints" to 500.0,
        "glas" to 200.0, "gläser" to 200.0, "glass" to 200.0, "glasses" to 200.0,
        "becher" to 150.0, "schüssel" to 250.0, "schale" to 250.0, "bowl" to 250.0, "bowls" to 250.0,
        "teller" to 350.0, "plate" to 350.0, "plates" to 350.0,
        "portion" to 150.0, "portionen" to 150.0, "serving" to 150.0, "servings" to 150.0,
        "handvoll" to 30.0, "handful" to 30.0, "prise" to 1.0, "pinch" to 1.0,
        "dose" to 330.0, "can" to 330.0, "flasche" to 500.0, "bottle" to 500.0,
        "riegel" to 40.0, "bar" to 40.0, "schuss" to 20.0, "splash" to 20.0, "spritzer" to 20.0,
        "scoop" to 30.0, "scoops" to 30.0, "messlöffel" to 30.0, "bag" to 40.0, "bags" to 40.0,
    )

    private val SIZE = mapOf(
        "klein" to 0.7, "kleine" to 0.7, "kleiner" to 0.7, "kleines" to 0.7, "kleinen" to 0.7, "small" to 0.7,
        "groß" to 1.3, "große" to 1.3, "großer" to 1.3, "großes" to 1.3, "großen" to 1.3, "large" to 1.3, "big" to 1.3,
    )

    private val FILLER = setOf(
        "ich", "hatte", "habe", "hab", "gegessen", "getrunken", "noch", "außerdem", "etwa", "ca", "ca.", "circa",
        "ungefähr", "rund", "so", "zum", "zur", "am", "heute", "dem", "den", "der", "die", "das", "von", "vom",
        "allem", "alles", "bisschen", "etwas", "i", "had", "ate", "drank", "about", "around", "some", "the", "of",
        "fl", "approx", "roughly", "my", "for",
    )

    /** Normalised word prefix → grams of one piece. */
    private val PIECES = linkedMapOf(
        "knaeckebrot" to 10.0, "crispbread" to 10.0, "zwieback" to 10.0, "rusk" to 10.0, "reiswaffel" to 8.0,
        "eier" to 60.0, "ei" to 60.0, "egg" to 60.0, "banane" to 120.0, "banana" to 120.0, "apfel" to 150.0, "aepfel" to 150.0,
        "apple" to 150.0, "birne" to 160.0, "pear" to 160.0, "orange" to 150.0, "mandarine" to 70.0, "kiwi" to 75.0,
        "pfirsich" to 130.0, "peach" to 130.0, "broetchen" to 60.0, "semmel" to 60.0, "roll" to 60.0,
        "croissant" to 60.0, "brezel" to 80.0, "laugen" to 80.0, "pretzel" to 80.0, "toast" to 25.0,
        "kartoffel" to 100.0, "potato" to 100.0, "tomate" to 80.0, "tomato" to 80.0, "karotte" to 70.0,
        "moehre" to 70.0, "carrot" to 70.0, "wuerstchen" to 50.0, "sausage" to 50.0, "frikadelle" to 100.0,
        "muffin" to 80.0, "keks" to 10.0, "cookie" to 10.0, "riegel" to 40.0, "joghurt" to 150.0,
        "yogurt" to 150.0, "pizza" to 450.0, "doener" to 400.0, "kebab" to 400.0, "burger" to 250.0,
        "bagel" to 100.0, "tortilla" to 40.0, "wrap" to 60.0, "pancake" to 40.0, "waffle" to 40.0,
        "avocado" to 150.0, "clementine" to 70.0, "plum" to 60.0, "pflaume" to 60.0, "donut" to 70.0,
    )

    /** Normalised word prefix → grams of one slice. */
    private val SLICES = linkedMapOf(
        // Before "brot": a crispbread slice weighs a fifth of a bread slice.
        "knaeckebrot" to 10.0, "crispbread" to 10.0, "brot" to 50.0, "bread" to 50.0, "vollkornbrot" to 50.0, "toast" to 25.0, "kaese" to 20.0,
        "cheese" to 20.0, "schinken" to 20.0, "ham" to 20.0, "wurst" to 15.0, "salami" to 10.0, "pizza" to 110.0,
        "bacon" to 10.0, "speck" to 10.0,
    )

    /** Normalised word prefix → typical portion when no amount is given. */
    private val PORTIONS = linkedMapOf(
        "knaeckebrot" to 20.0, "crispbread" to 20.0, "toast" to 50.0,
        "doener" to 400.0, "kebab" to 400.0, "pizza" to 450.0, "burger" to 250.0, "kaffee" to 150.0,
        "coffee" to 150.0, "tee" to 250.0, "tea" to 250.0, "salat" to 150.0, "salad" to 150.0,
        "spaghetti" to 350.0, "nudeln" to 350.0, "pasta" to 350.0, "reis" to 200.0, "rice" to 200.0,
        "curry" to 250.0, "suppe" to 300.0, "soup" to 300.0, "muesli" to 60.0, "haferflocken" to 60.0,
        "oats" to 60.0, "butter" to 10.0, "kaese" to 30.0, "cheese" to 30.0, "milch" to 200.0, "milk" to 200.0,
        "wasser" to 300.0, "water" to 300.0, "saft" to 200.0, "juice" to 200.0, "shake" to 300.0,
        "nuesse" to 30.0, "nuts" to 30.0, "oatmeal" to 250.0, "porridge" to 250.0, "cereal" to 40.0,
        "smoothie" to 300.0, "sandwich" to 200.0, "steak" to 200.0, "fries" to 150.0, "pommes" to 150.0,
        "bier" to 500.0, "beer" to 500.0, "wein" to 150.0, "wine" to 150.0, "bacon" to 30.0, "speck" to 30.0,
        "chips" to 30.0,
    )
}
