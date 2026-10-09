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

    private class Indexed(val food: BlsFood, val de: Words, val en: Words) {
        val drink = FuelUnits.isDrink("bls:${food.code}", food.nameDe) || FuelUnits.isDrink(null, food.nameEn)
    }

    /**
     * [ingredients]: candidate words named only after "mit"/"with"/"aus" (see [ingredientsOf]);
     * [modifiers]: the leading parts of hyphenated compounds (see [modifiersOf]).
     */
    private class Words(
        val tokens: List<String>,
        val ingredients: Set<String> = emptySet(),
        val modifiers: Set<String> = emptySet(),
    ) {
        /** What the food is without ("Kartoffelgratin ohne Käse"): never a match. */
        val negated: Set<String> = tokens.zipWithNext().filter { (a, _) -> a in NEGATIONS }.map { it.second }.toSet()
        val pairs: List<String> = tokens.zipWithNext { a, b -> a + b }
        val joined: String = tokens.joinToString("")
        val content: List<String> = tokens.filter { it !in STOP_WORDS }
        /** Each content word plus its BLS synonyms; only read for queries. */
        val alternatives: List<List<String>> by lazy { content.map { listOf(it) + synonymsOf(it) } }
    }

    private val index = foods.map {
        Indexed(it, candidateWords(it.nameDe), candidateWords(it.nameEn))
    }

    /** Best candidates for a detected food, best first. */
    fun match(food: DetectedFood, limit: Int = 5): List<Match> {
        val de = Words(normalize(food.nameDe))
        val en = Words(normalize(food.nameEn))
        val drink = FuelUnits.isDrink(null, food.nameDe) || FuelUnits.isDrink(null, food.nameEn)
        // Typed text has one name for both slots; its look-alikes in the other language
        // ("Paprika" in "Paprika bacon sausage") are coincidence, not agreement.
        val typed = de.tokens == en.tokens
        val everyday = (everydayCodes(de) + everydayCodes(en)).distinct()
        return rank(limit) {
            // A long name in the other language is no reason to rank lower than no match at all.
            val a = max(0.0, score(de, it.de, it.food.code))
            val b = max(0.0, score(en, it.en, it.food.code))
            everyday(it, everyday, (if (typed) max(a, b) else max(a, b) + 0.5 * min(a, b)) - drinkMismatch(drink, it))
        }
    }

    /** Free-text search over German and English names. */
    fun search(query: String, limit: Int = 20): List<Match> {
        val q = Words(normalize(query))
        if (q.content.isEmpty()) return emptyList()
        val drink = FuelUnits.isDrink(null, query)
        val everyday = everydayCodes(q)
        return rank(limit) {
            everyday(it, everyday, max(score(q, it.de, it.food.code), score(q, it.en, it.food.code)) - drinkMismatch(drink, it))
        }
    }

    /** The usual product for a bare everyday word goes first, even where its name does not contain the word ("Käse" → Gouda). */
    private fun everyday(candidate: Indexed, codes: List<String>, score: Double): Double {
        val rank = codes.indexOf(candidate.food.code)
        return if (rank < 0) score else max(score, 1.0) + EVERYDAY_BONUS - 0.01 * rank
    }

    /**
     * "Milch" asks for a glass of milk, not "Milchschokolade" or "Knäckebrot mit Milch"
     * (device test 2026-10-09).
     */
    private fun drinkMismatch(queryIsDrink: Boolean, candidate: Indexed): Double =
        if (queryIsDrink && !candidate.drink) DRINK_MISMATCH else 0.0

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
            var bestTokens: List<String> = emptyList()
            for (word in words) {
                for (token in candidate.content) {
                    if (token in candidate.negated) continue
                    val s = wordSimilarity(word, token)
                    if (s > best) { best = s; bestTokens = listOf(token) }
                }
                // "Haferflocken" against "Hafer Flocken": both words are used up, so
                // they do not count as extra words against the candidate.
                if (best < 0.95) {
                    val k = candidate.pairs.indexOf(word)
                    if (k >= 0) { best = 0.95; bestTokens = listOf(candidate.tokens[k], candidate.tokens[k + 1]) }
                }
                if (best < 0.5 && word.length >= 5 && candidate.joined.contains(word)) best = 0.5
            }
            if (best >= 0.6) used += bestTokens
            // "Milch" against "Erdbeermilch mit Milch" or "Knäckebrot mit Milch": the asked-for food
            // is only what the candidate is made with (device test 2026-10-09).
            if (i == 0 && bestTokens.isNotEmpty() && bestTokens.all { it in candidate.ingredients }) best *= 0.6
            // "Käse" against "Käse-Grießnockerl" or "Joghurt" against "Joghurt-Dip": in a German
            // compound the last part names the food, the first only describes it.
            else if (i == 0 && bestTokens.size == 1 && bestTokens.single() in candidate.modifiers) best *= 0.6
            // The head noun matters most: weight the first query word double.
            sum += if (i == 0) best * 2 else best
        }
        var score = sum / (q.size + 1)

        // Fat contents ("1,5 % Fett") are numbers and "frisch"/"pasteurisiert" describe the same
        // food; neither are extra words naming another one.
        val extra = candidate.content.count {
            it !in used && it !in PREPARATION_WORDS && it !in QUALIFIER_WORDS && !it.all(Char::isDigit)
        }
        score -= min(0.4, 0.08 * extra)
        val head = candidate.content.first()
        if (head !in candidate.modifiers && query.alternatives.first().any { first ->
                wordSimilarity(first, head) >= 0.9 || candidate.pairs.firstOrNull() == first
            }) score += 0.1

        val group = code.firstOrNull()
        val queryAsksRaw = q.any { it in RAW_WORDS || it in PROCESSED_WORDS }
        val queryNamesPreparation = q.any { it in PREPARATION_WORDS }
        if (!queryAsksRaw && candidate.content.any { it in PROCESSED_WORDS }) score -= 0.2
        if (!queryAsksRaw && group in RAW_RARELY_EATEN && candidate.content.any { it in RAW_WORDS }) score -= 0.15
        if (!queryNamesPreparation && group == VEGETABLES && candidate.content.any { it in COOKED_WORDS }) score += 0.02
        // "Erdbeeren mit Schlagsahne" is a dish; plain "Erdbeeren" asks for the fruit.
        if (candidate.ingredients.isNotEmpty() && candidate.ingredients.none { it in used || it.all(Char::isDigit) || it in PREPARATION_WORDS }) score -= 0.1
        // Fruit is eaten raw unless the name says otherwise ("Kiwi" is not "Kiwi gedünstet").
        if (!queryNamesPreparation && group == FRUIT && candidate.content.any { it in COOKED_WORDS }) score -= 0.1
        if (q.none { it in SWEETENED_WORDS } && candidate.content.any { it in SWEETENED_WORDS }) score -= 0.1
        return score
    }

    companion object {
        /** Below this, a candidate is noise rather than a guess. */
        const val MIN_SCORE = 0.35

        /** From here on the first candidate is preselected in the review. */
        const val CONFIDENT_SCORE = 0.55

        private const val DRINK_MISMATCH = 0.3

        /** Lifts the usual product for a bare everyday word above specialities that share the word. */
        private const val EVERYDAY_BONUS = 0.6

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
        private const val FRUIT = 'F'
        private val COOKED_WORDS = setOf(
            "gekocht", "gegart", "gedaempft", "geduenstet", "gebraten", "gebacken", "gegrillt", "zubereitet",
            "boiled", "cooked", "steamed", "stewed", "fried", "baked", "grilled", "prepared", "roasted",
        )
        private val PREPARATION_WORDS = COOKED_WORDS + RAW_WORDS + PROCESSED_WORDS + setOf(
            "geschaelt", "ungeschaelt", "peeled", "unpeeled", "frittiert", "deep", "pan", "pfanne", "ofen", "oven",
            "fett", "fat", "salz", "salt", "konserve", "canned", "abgetropft", "drained",
        )

        /** Words that describe the same food rather than name another one. */
        private val QUALIFIER_WORDS = setOf(
            "frisch", "fresh", "pasteurisiert", "pasteurised", "pasteurized", "ultrahocherhitzt", "ultra", "heated",
            "natur", "plain", "mild", "fettarm", "low", "min", "max", "bis", "to",
        )
        private val NEGATIONS = setOf("ohne", "without")
        private val SWEETENED_WORDS = setOf("gezuckert", "sugared", "gesuesst", "sweetened", "zucker", "sugar")

        /**
         * The product a bare everyday word means (normalised query → BLS codes). The BLS has no
         * plain "Brot", "Käse" or "Schinken", so "Russisch-Brot", "Käse-Grießnockerl" or
         * "Schinkentorte" would otherwise share the top with the bread, cheese or ham people mean
         * (device test 2026-10-09).
         */
        private val EVERYDAY: Map<String, List<String>> = buildMap {
            fun put(codes: List<String>, vararg words: String) = words.forEach { put(it, codes) }
            put(listOf("B251000", "B271000", "B311000"), "brot", "bread")
            put(listOf("B511000"), "broetchen", "semmel", "schrippe", "weckle", "roll", "bread roll", "bun")
            put(listOf("B314000", "B254000"), "toast", "toastbrot")
            put(listOf("B6A2100", "B6A2000"), "knaeckebrot", "crispbread")
            put(listOf("B723000", "B723100"), "brezel", "breze", "laugenbrezel", "pretzel")
            put(listOf("M402600"), "kaese", "cheese")
            put(listOf("W424000"), "schinken", "kochschinken", "ham")
            put(listOf("Q630000"), "butter")
            put(listOf("M173800", "M173900"), "sahne", "schlagsahne", "cream", "whipping cream")
            put(listOf("M710700", "M710800"), "frischkaese", "cream cheese")
            put(listOf("M713100", "M713300"), "quark", "magerquark")
            put(listOf("M141300", "M141200"), "joghurt", "jogurt", "naturjoghurt", "yogurt", "yoghurt", "natural yogurt")
            put(listOf("E111132", "Y740162"), "ei", "eier", "egg", "eggs")
            put(listOf("M111300", "M111200", "M113300", "M113200"), "milch", "milk")
            put(listOf("C512000", "C512300"), "muesli", "musli")
            put(listOf("C660000"), "hafermilch", "haferdrink", "oat milk", "oat drink")
            put(listOf("H841100"), "sojamilch", "sojadrink", "soy milk", "soya milk", "soy drink")
            put(listOf("S145000"), "nutella", "nuss nougat creme")
            put(listOf("Y332212", "Y231322"), "schnitzel")
            put(listOf("Y562032", "V416172"), "haehnchen", "huhn", "chicken", "haehnchenbrust", "chicken breast")
            put(listOf("X912033"), "pizza")
            put(listOf("Y921162", "Y921062"), "doener", "doner", "kebab", "doener kebab", "doner kebab")
            put(listOf("Y911060"), "burger", "hamburger")
            put(listOf("H130100", "H120100", "H210100"), "nuesse", "nuts")
            put(listOf("C133000"), "oats", "rolled oats", "oat flakes")
            put(listOf("K110132", "K120134"), "kartoffeln", "kartoffel", "potatoes", "potato")
            put(listOf("G543100", "G541100", "G542100"), "paprika", "bell pepper", "sweet pepper")
            put(listOf("X201160", "G105100"), "salat", "salad")
            put(listOf("H730132"), "linsen", "lentils")
            put(listOf("H210100"), "mandeln", "almonds")
            put(listOf("N110000", "N120000", "N128000"), "wasser", "water")
            put(listOf("N630000"), "tee", "tea")
            put(listOf("N330000"), "cola")
            put(listOf("N256000"), "apfelschorle")
            put(listOf("P163000", "P161000"), "bier", "beer")
            put(listOf("P2A3000", "P210000"), "wein", "wine")
        }

        private fun everydayCodes(query: Words): List<String> =
            EVERYDAY[query.content.joinToString(" ")].orEmpty()

        /** US English and two-word names the BLS spells differently (applied to whole names). */
        private val PHRASES = listOf(
            "bell pepper" to "sweet pepper", "ground beef" to "beef mince", "ground pork" to "pork mince",
            "ground meat" to "mince", "minced meat" to "mince", "green onion" to "spring onion",
            "scallion" to "spring onion", "garbanzo bean" to "chickpea", "garbanzo" to "chickpea",
        )
        private val PHRASE_PATTERNS = PHRASES.map { (from, to) -> Triple(from, Regex("\\b$from"), to) }
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

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

        /** Words that name what is in or on a food. */
        private val CONNECTORS = setOf("mit", "with", "in", "im", "auf", "on", "aus", "from")
        private val WORD_BREAKS = Regex("""[\s,;()/]+""")
        private val ALIAS_BRACKETS = Regex("""\((?!\s*(?:mit|with|in|im|auf|on|aus|from)\b)[^)]*\)""", RegexOption.IGNORE_CASE)

        /**
         * Words that only follow the first [CONNECTORS] word – what the food is made with. Brackets
         * that repeat the name are left out ("Spätzle mit Käse (Käsespätzle)"), not those that list
         * what is in it ("Kaiserschmarren (mit Milch 3,5 % Fett)").
         */
        private fun candidateWords(name: String): Words {
            val tokens = normalize(name)
            return Words(tokens, ingredientsOf(name, tokens), modifiersOf(name))
        }

        private fun ingredientsOf(name: String, normalized: List<String>): Set<String> {
            val tokens = if ('(' in name) normalize(name.replace(ALIAS_BRACKETS, " ")) else normalized
            val at = tokens.indexOfFirst { it in CONNECTORS }
            if (at < 0) return emptySet()
            return tokens.drop(at + 1).toSet() - tokens.take(at).toSet() - STOP_WORDS
        }

        /**
         * The leading parts of hyphenated compounds: "Käse" in "Käse-Grießnockerl", "Cola" and
         * "Misch" in "Cola-Misch-Getränk". A trailing hyphen ("milch- und sojahaltig") also
         * marks one.
         */
        private fun modifiersOf(name: String): Set<String> =
            if ('-' !in name) emptySet() else name.split(WORD_BREAKS).filter { '-' in it }.flatMap { word ->
                val parts = word.split('-')
                parts.dropLast(1).flatMap { normalize(it) }
            }.toSet()

        private fun synonymsOf(word: String): List<String> = SYNONYMS[word] ?: SYNONYMS[stem(word)] ?: emptyList()

        fun normalize(text: String): List<String> {
            var t = text.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace(NON_ALPHANUMERIC, " ")
            // Compiled once: the index normalises some 30 000 names.
            PHRASE_PATTERNS.forEach { (from, pattern, to) -> if (from in t) t = t.replace(pattern, to) }
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

        // Called for every query word against every candidate word, so no allocations on the way.
        internal fun wordSimilarity(a: String, b: String): Double = when {
            a == b -> 1.0
            a.length >= 4 && b.length >= 4 && cachedStem(a) == cachedStem(b) -> 0.9
            // English plurals of short words: "egg"/"eggs", "oat"/"oats".
            min(a.length, b.length) >= 3 && (isPlural(a, b) || isPlural(b, a)) -> 0.9
            // Two typos only in longer words: "brokkoli"/"broccoli", but not "milch"/"witch".
            // … and not between two compounds that only start alike ("frischkaese"/"fleischkaese").
            min(a.length, b.length) >= 7 && a[0] == b[0] && a[1] == b[1] && editDistanceAtMost(a, b, 2) -> 0.75
            min(a.length, b.length) >= 5 && oneEditAway(a, b) -> 0.75
            // A compound that ends in the word is a kind of it ("Roggenbrot", "Naturjoghurt") –
            // but "Schwein" is no wine and "Fleischkäse" no cheese.
            a.length >= 4 && b.length > a.length && b.endsWith(a) && b !in FALSE_HEADS -> 0.8
            // A longer compound that starts with it is usually another food ("Bananenquark").
            min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)) -> 0.6
            min(a.length, b.length) >= 5 && (a.contains(b) || b.contains(a)) -> 0.5
            else -> 0.0
        }

        private val FALSE_HEADS = setOf("schwein", "fleischkaese", "leberkaese")

        private fun isPlural(word: String, plural: String) =
            plural.length == word.length + 1 && plural.last() == 's' && plural.startsWith(word)

        /** Stems of the few thousand distinct words, shared by the BLS and USDA matchers. */
        private val stems = java.util.concurrent.ConcurrentHashMap<String, String>()
        private fun cachedStem(word: String): String = stems.getOrPut(word) { stem(word) }

        /** Levenshtein distance ≤ 1, without a table. */
        private fun oneEditAway(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            val (s, l) = if (a.length <= b.length) a to b else b to a
            var i = 0
            var j = 0
            var edited = false
            while (i < s.length && j < l.length) {
                if (s[i] != l[j]) {
                    if (edited) return false
                    edited = true
                    if (s.length == l.length) i++
                    j++
                } else { i++; j++ }
            }
            return true
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
