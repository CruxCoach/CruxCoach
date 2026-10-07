package com.cruxcoach.athlete.logic

import kotlin.math.max
import kotlin.math.min

/**
 * One generic food: a row of the bundled BLS 4.0 extract or, with an empty
 * German name, of the USDA SR Legacy extract. Nutrients per 100 g edible
 * portion; micronutrients null where the table has no value.
 */
data class BlsFood(
    val code: String,
    val nameDe: String,
    val nameEn: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val ironMg: Double? = null,
    val calciumMg: Double? = null,
    val vitaminDUg: Double? = null,
    /** Household measures with grams per one unit ("cup" to 81 g); USDA only. */
    val portions: List<Portion> = emptyList(),
) {
    data class Portion(val label: String, val grams: Double)
}

/**
 * Parser for `assets/fuel/bls_4_0_macros.tsv` and `usda_sr_legacy.tsv`: `#`
 * comment lines (source and licence), then code, German name, English name,
 * kcal, protein, fat, carbohydrate per 100 g, optionally iron mg, calcium mg,
 * vitamin D µg and portions ("cup=81|tbsp=5.1"), tab separated.
 */
object BlsTable {
    fun parse(text: String): List<BlsFood> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            BlsFood(
                code = f[0], nameDe = f[1], nameEn = f[2],
                kcal = f[3].toDoubleOrNull() ?: return@mapNotNull null,
                protein = f[4].toDoubleOrNull() ?: return@mapNotNull null,
                fat = f[5].toDoubleOrNull() ?: return@mapNotNull null,
                carbs = f[6].toDoubleOrNull() ?: return@mapNotNull null,
                ironMg = f.getOrNull(7)?.toDoubleOrNull(),
                calciumMg = f.getOrNull(8)?.toDoubleOrNull(),
                vitaminDUg = f.getOrNull(9)?.toDoubleOrNull(),
                portions = f.getOrNull(10).orEmpty().split('|').mapNotNull { part ->
                    val label = part.substringBefore('=', "").trim()
                    val grams = part.substringAfter('=', "").toDoubleOrNull()
                    if (label.isEmpty() || grams == null || grams <= 0) null else BlsFood.Portion(label, grams)
                },
            )
        }
        .toList()
}

/**
 * USDA names put the food first and stack qualifiers after commas ("Milk,
 * whole, 3.25% milkfat, with added vitamin D"); a search for "whole milk"
 * would otherwise prefer "Cheese, mozzarella, whole milk". The matcher
 * therefore also sees the core – the first two parts without parentheses –
 * in the otherwise empty German slot; the full English name stays for display
 * and for queries that name a qualifier ("oats regular quick").
 */
object UsdaNames {
    fun core(name: String): String =
        name.replace(Regex("\\([^)]*\\)"), " ").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .take(2).joinToString(", ")

    fun indexed(food: BlsFood): BlsFood = if (food.nameDe.isEmpty()) food.copy(nameDe = core(food.nameEn)) else food
}

/**
 * Finds BLS entries for a model's food name (FEAT-069) and for manual search.
 *
 * BLS names put the food first and the preparation after it ("Reis poliert,
 * gekocht"), while the model writes compounds ("Hähnchenbrust gebraten").
 * The score therefore compares words, adjacent word pairs and compound
 * parts. A detection is scored in German and English and both count, so a
 * wrong German word ("Brühlchen") is rescued by the English one while an
 * English-only coincidence ("Banana nectar") does not win.
 *
 * The BLS calls noodles "Teigwaren"/"pasta" and writes British English
 * ("aubergine", "courgette", "mince"), so query words are widened with
 * synonyms first; otherwise "Nudeln" lands on "Schupfnudeln" (finger-shaped
 * pasta) and "eggplant" finds nothing.
 *
 * Raw and processed forms lose unless the name asks for them, but raw only
 * where it changes the numbers per 100 g a lot and is rarely eaten: grains,
 * potatoes, eggs and pasta, meat and fish. Fruit stays raw; for vegetables
 * a cooked variant gets a small edge.
 */
class FoodMatcher(foods: List<BlsFood>) {

    data class Match(val food: BlsFood, val score: Double)

    private class Indexed(val food: BlsFood, val de: Words, val en: Words)

    private class Words(val tokens: List<String>) {
        val pairs: List<String> = tokens.zipWithNext { a, b -> a + b }
        val joined: String = tokens.joinToString("")
        val content: List<String> = tokens.filter { it !in STOP_WORDS }
        /** Each content word plus its BLS synonyms; only read for queries. */
        val alternatives: List<List<String>> by lazy { content.map { listOf(it) + synonymsOf(it) } }
    }

    private val index = foods.map { Indexed(it, Words(normalize(it.nameDe)), Words(normalize(it.nameEn))) }

    /** Best candidates for a detected food, best first. */
    fun match(food: DetectedFood, limit: Int = 5): List<Match> {
        val de = Words(normalize(food.nameDe))
        val en = Words(normalize(food.nameEn))
        return rank(limit) {
            val a = score(de, it.de, it.food.code)
            val b = score(en, it.en, it.food.code)
            max(a, b) + 0.5 * min(a, b)
        }
    }

    /** Free-text search over German and English names. */
    fun search(query: String, limit: Int = 20): List<Match> {
        val q = Words(normalize(query))
        if (q.content.isEmpty()) return emptyList()
        return rank(limit) { max(score(q, it.de, it.food.code), score(q, it.en, it.food.code)) }
    }

    private fun rank(limit: Int, scoreOf: (Indexed) -> Double): List<Match> =
        index.asSequence()
            .map { Match(it.food, scoreOf(it)) }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.food.nameDe.length })
            .take(limit)
            .toList()

    private fun score(query: Words, candidate: Words, code: String): Double {
        val q = query.content
        if (q.isEmpty() || candidate.content.isEmpty()) return 0.0
        val used = mutableSetOf<String>()
        var sum = 0.0
        query.alternatives.forEachIndexed { i, words ->
            var best = 0.0
            var bestToken: String? = null
            for (word in words) {
                for (token in candidate.content) {
                    val s = wordSimilarity(word, token)
                    if (s > best) { best = s; bestToken = token }
                }
                for (pair in candidate.pairs) {
                    if (pair == word && 0.95 > best) { best = 0.95; bestToken = null }
                }
                if (best < 0.5 && word.length >= 5 && candidate.joined.contains(word)) best = 0.5
            }
            if (bestToken != null && best >= 0.6) used += bestToken
            // The head noun matters most: weight the first query word double.
            sum += if (i == 0) best * 2 else best
        }
        var score = sum / (q.size + 1)

        val extra = candidate.content.count { it !in used && it !in PREPARATION_WORDS }
        score -= min(0.4, 0.08 * extra)
        if (query.alternatives.first().any { first ->
                wordSimilarity(first, candidate.content.first()) >= 0.9 || candidate.pairs.firstOrNull() == first
            }) score += 0.1

        val group = code.firstOrNull()
        val queryAsksRaw = q.any { it in RAW_WORDS || it in PROCESSED_WORDS }
        val queryNamesPreparation = q.any { it in PREPARATION_WORDS }
        if (!queryAsksRaw && candidate.content.any { it in PROCESSED_WORDS }) score -= 0.2
        if (!queryAsksRaw && group in RAW_RARELY_EATEN && candidate.content.any { it in RAW_WORDS }) score -= 0.15
        if (!queryNamesPreparation && group == VEGETABLES && candidate.content.any { it in COOKED_WORDS }) score += 0.02
        return score
    }

    companion object {
        /** Below this, a candidate is noise rather than a guess. */
        const val MIN_SCORE = 0.35

        /** From here on the first candidate is preselected in the review. */
        const val CONFIDENT_SCORE = 0.55

        private val STOP_WORDS = setOf(
            "mit", "und", "oder", "ohne", "in", "im", "auf", "aus", "vom", "von", "der", "die", "das", "ein", "eine",
            "with", "and", "or", "without", "of", "the", "a", "an", "on", "from", "i", "tr", "mind", "ca", "z", "b",
        )
        private val RAW_WORDS = setOf("roh", "raw")
        private val PROCESSED_WORDS = setOf(
            "getrocknet", "dried", "pulver", "powder", "instantpulver", "instant", "trockenprodukt",
            "konzentrat", "concentrate", "mehl", "flour", "griess", "semolina", "grits", "staerke", "starch",
        )

        /** BLS main groups (first letter of the code): grains, eggs and pasta, potatoes, fish, meat. */
        private val RAW_RARELY_EATEN = setOf('C', 'E', 'K', 'T', 'U', 'V')
        private const val VEGETABLES = 'G'
        private val COOKED_WORDS = setOf(
            "gekocht", "gegart", "gedaempft", "geduenstet", "gebraten", "gebacken", "gegrillt", "zubereitet",
            "boiled", "cooked", "steamed", "stewed", "fried", "baked", "grilled", "prepared", "roasted",
        )
        private val PREPARATION_WORDS = COOKED_WORDS + RAW_WORDS + PROCESSED_WORDS + setOf(
            "geschaelt", "ungeschaelt", "peeled", "unpeeled", "frittiert", "deep", "pan", "pfanne", "ofen", "oven",
            "fett", "fat", "salz", "salt", "konserve", "canned", "abgetropft", "drained",
        )

        /** US English and two-word names the BLS spells differently (applied to whole names). */
        private val PHRASES = listOf(
            "bell pepper" to "sweet pepper", "ground beef" to "beef mince", "ground pork" to "pork mince",
            "ground meat" to "mince", "minced meat" to "mince", "green onion" to "spring onion",
            "scallion" to "spring onion", "garbanzo bean" to "chickpea", "garbanzo" to "chickpea",
        )

        /** Query word → the words the BLS uses for it (normalised spelling). */
        private val SYNONYMS: Map<String, List<String>> = buildMap {
            val pasta = listOf("teigwaren", "pasta")
            listOf(
                "nudeln", "nudel", "pasta", "spaghetti", "penne", "fusilli", "makkaroni", "maccheroni", "macaroni",
                "tagliatelle", "farfalle", "rigatoni", "linguine", "spirelli", "bandnudeln", "hoernchennudeln",
                "noodles", "noodle",
            ).forEach { put(it, pasta) }
            put("eggplant", listOf("aubergine"))
            put("melanzani", listOf("aubergine"))
            put("eierfrucht", listOf("aubergine"))
            put("zucchini", listOf("courgette"))
            put("cilantro", listOf("coriander", "koriander"))
            put("arugula", listOf("rocket", "rucola"))
            put("yoghurt", listOf("yogurt"))
            put("jogurt", listOf("joghurt"))
            put("paradeiser", listOf("tomate"))
            put("erdaepfel", listOf("kartoffel"))
            put("erdapfel", listOf("kartoffel"))
            put("semmel", listOf("broetchen"))
            put("topfen", listOf("quark"))
            put("schlagobers", listOf("sahne"))
            put("obers", listOf("sahne"))
            put("karfiol", listOf("blumenkohl"))
            put("kohlsprossen", listOf("rosenkohl"))
            put("fisolen", listOf("bohnen"))
            put("faschiertes", listOf("hackfleisch"))
            put("porree", listOf("lauch"))
            put("huhn", listOf("haehnchen"))
            put("truthahn", listOf("pute"))
        }

        private fun synonymsOf(word: String): List<String> = SYNONYMS[word] ?: SYNONYMS[stem(word)] ?: emptyList()

        fun normalize(text: String): List<String> {
            var t = text.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(Regex("[^a-z0-9]+"), " ")
            PHRASES.forEach { (from, to) -> t = t.replace(Regex("\\b$from"), to) }
            return t.trim()
                .split(' ')
                .filter { it.isNotEmpty() }
                // The BLS writes both "Soße" and "Sauce"; treat them as one word.
                .map { it.replace("sosse", "sauce") }
        }

        private fun stem(word: String): String {
            for (suffix in listOf("en", "es", "n", "e", "s")) {
                if (word.length > suffix.length + 3 && word.endsWith(suffix)) return word.dropLast(suffix.length)
            }
            return word
        }

        internal fun wordSimilarity(a: String, b: String): Double = when {
            a == b -> 1.0
            a.length >= 4 && b.length >= 4 && stem(a) == stem(b) -> 0.9
            min(a.length, b.length) >= 5 && editDistanceAtMost(a, b, 2) -> 0.75
            // A longer compound is usually another food ("Bananenquark").
            min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)) -> 0.6
            min(a.length, b.length) >= 5 && (a.contains(b) || b.contains(a)) -> 0.5
            else -> 0.0
        }

        /** Levenshtein distance ≤ [limit]; "brokkoli" vs "broccoli" is 2. */
        private fun editDistanceAtMost(a: String, b: String, limit: Int): Boolean {
            if (kotlin.math.abs(a.length - b.length) > limit) return false
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                    rowMin = min(rowMin, current[j])
                }
                if (rowMin > limit) return false
                previous = current
            }
            return previous[b.length] <= limit
        }
    }
}
