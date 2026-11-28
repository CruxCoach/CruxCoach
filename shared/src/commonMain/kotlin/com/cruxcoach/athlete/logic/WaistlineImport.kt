package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.floor

/**
 * Reads the open-source app Waistline — its database backup
 * (`waistline_export.json`) or its diary CSV (`diary_export.csv`) — into
 * canonical metric body measurements and, from the backup, its food diary.
 *
 * Formats as written by Waistline (davidhealey/waistline, master 6a7258b):
 * - JSON (settings.js `writeDatabaseBackupToFile`, db-handler.js `export`):
 *   one array per IndexedDB store (`diary`, `foodList`, `recipes`, `meals`)
 *   plus `settings` and `version`. A `diary` entry carries `dateTime` (UTC
 *   midnight of the local day: diary.js `getNewEntry`, db-handler.js upgrade
 *   31), `stats` {statName → value} and `items` (see [FoodDiary]). Stats are
 *   stored in the stat's storage unit: kg / cm / % for the defaults,
 *   overridden per stat by `settings.bodyStats.units` (custom stats and units).
 * - CSV (settings.js `writeDiaryToCsvFile`): `;`-separated, a localized header
 *   "Name (unit)" per column, values in the user's display unit, dates from
 *   `toLocaleDateString` (so the format depends on the phone's language) and
 *   the locale decimal separator. Its nutrition columns are each day's sums
 *   (`getTotalNutrition` over the whole day), never single foods or meals, so
 *   the CSV gives body data only ([Result.foodTotalsOnly]).
 */
object WaistlineImport {

    const val SOURCE = "waistline"
    const val CUSTOM_PREFIX = "waistline_"
    /** Ids of imported foods ("waistline:<food id>") and entries ("waistline:<day>:<n>"): a second import adds nothing twice. */
    const val ID_PREFIX = "waistline:"
    /** Name of a Waistline "Quick Add" without a description; the app passes its own word. */
    const val QUICK_ADD_NAME = "Quick Add"
    /** [FoodItem.source] whose nutrient fields hold ONE portion instead of 100 g (FuelViewModel.SOURCE_PORTION). */
    const val PER_PORTION_SOURCE = "portion"

    enum class Format { JSON, CSV, UNKNOWN }

    data class ParsedMeasurement(val day: String, val metric: String, val value: Double, val unit: String)

    data class Result(
        val format: Format,
        val measurements: List<ParsedMeasurement>,
        /** Values (cells) that could not be read or were implausible, plus rows with an unreadable date. */
        val skipped: Int,
        val firstDay: String?,
        val lastDay: String?,
        /** CSV dates like 04/05/2026 where day/month order could not be told apart; [dayFirst] was assumed. */
        val ambiguousDates: Boolean = false,
        val dayFirst: Boolean? = null,
        /** Original Waistline name per custom metric key, for display. */
        val customNames: Map<String, String> = emptyMap(),
        /** The food diary of a JSON backup; always empty for a CSV. */
        val food: FoodDiary = FoodDiary.EMPTY,
        /** A CSV with nutrition columns: they hold only each day's sums, so no food is read from it. */
        val foodTotalsOnly: Boolean = false,
    ) {
        val isEmpty: Boolean get() = measurements.isEmpty() && food.entries.isEmpty()
        fun countsByMetric(): Map<String, Int> = measurements.groupingBy { it.metric }.eachCount()
    }

    private val EMPTY = Result(Format.UNKNOWN, emptyList(), 0, null, null)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param dayFirst forces the order of day and month for CSV dates written
     *   with "/" or "-" (null = detect; ambiguous files default to day first).
     * @param quickAddName name of a Waistline "Quick Add" entry that has no description.
     */
    fun parse(text: String, dayFirst: Boolean? = null, quickAddName: String = QUICK_ADD_NAME): Result {
        val clean = text.removePrefix("﻿").trim()
        if (clean.isEmpty()) return EMPTY
        return if (clean.startsWith("{")) parseJson(clean, quickAddName) ?: EMPTY else parseCsv(clean, dayFirst)
    }

    // ── Metric names and units ───────────────────────────────────────

    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "weight" to listOf("weight", "body weight", "gewicht", "körpergewicht", "poids", "peso", "peso corporeo", "vikt", "vægt", "waga"),
        "body_fat" to listOf("body fat", "bodyfat", "body fat percentage", "fat percentage", "körperfett", "körperfettanteil", "kfa",
            "graisse corporelle", "masse grasse", "grasa corporal", "grasso corporeo", "massa grassa", "lichaamsvet", "vetpercentage"),
        "neck" to listOf("neck", "hals", "nacken", "halsumfang", "cou", "tour de cou", "cuello", "collo", "nek"),
        "waist" to listOf("waist", "taille", "bauchumfang", "taillenumfang", "tour de taille", "cintura", "vita", "middel"),
        "hips" to listOf("hips", "hip", "hüfte", "hüften", "hüftumfang", "hanches", "hanche", "tour de hanches", "cadera", "caderas", "fianchi", "heupen"),
        "chest" to listOf("chest", "brust", "brustumfang", "poitrine", "tour de poitrine", "pecho", "petto", "torace", "borst"),
        "upper_arm" to listOf("arm", "arms", "upper arm", "biceps", "bicep", "bizeps", "oberarm", "oberarmumfang", "bras", "brazo", "braccio", "bovenarm"),
        "forearm" to listOf("forearm", "unterarm", "unterarmumfang", "avant-bras", "antebrazo", "avambraccio", "onderarm"),
        "thigh" to listOf("thigh", "thighs", "oberschenkel", "oberschenkelumfang", "cuisse", "muslo", "coscia", "dij"),
        "calf" to listOf("calf", "calves", "wade", "waden", "wadenumfang", "mollet", "pantorrilla", "gemelo", "polpaccio", "kuit"),
        "height" to listOf("height", "body height", "größe", "groesse", "körpergröße", "koerpergroesse", "hauteur", "stature", "altura", "altezza", "lengte"),
        "arm_span" to listOf("arm span", "armspan", "wingspan", "spannweite", "armspannweite", "envergure", "envergadura", "apertura braccia"),
    )

    private val NAME_TO_METRIC: Map<String, String> =
        SYNONYMS.flatMap { (metric, names) -> names.map { it to metric } }.toMap()

    private fun normalizeName(name: String): String =
        name.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")

    internal fun metricFor(name: String): String? = NAME_TO_METRIC[normalizeName(name)]

    private fun customKey(name: String): String {
        val snake = normalizeName(name).map { c -> if (c.isLetterOrDigit()) c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        return CUSTOM_PREFIX + snake.ifEmpty { "stat" }
    }

    private enum class Dimension { MASS, LENGTH, PERCENT }

    private class UnitInfo(val dimension: Dimension, val factor: Double)

    private fun unitInfo(raw: String?): UnitInfo? = when (raw?.trim()?.lowercase()?.removeSuffix(".")) {
        "kg", "kgs", "kilogram", "kilograms", "kilogramm" -> UnitInfo(Dimension.MASS, 1.0)
        "g", "gram", "gramm" -> UnitInfo(Dimension.MASS, 0.001)
        "lb", "lbs", "pound", "pounds", "pfund" -> UnitInfo(Dimension.MASS, 0.45359237)
        "st", "stone", "stones" -> UnitInfo(Dimension.MASS, 6.35029318)
        "cm", "centimeter", "centimetre", "zentimeter" -> UnitInfo(Dimension.LENGTH, 1.0)
        "mm", "millimeter", "millimetre" -> UnitInfo(Dimension.LENGTH, 0.1)
        "m", "meter", "metre" -> UnitInfo(Dimension.LENGTH, 100.0)
        "in", "inch", "inches", "″", "\"", "zoll" -> UnitInfo(Dimension.LENGTH, 2.54)
        "ft", "feet", "foot" -> UnitInfo(Dimension.LENGTH, 30.48)
        "%", "percent", "prozent" -> UnitInfo(Dimension.PERCENT, 1.0)
        else -> null
    }

    private fun canonicalUnitOf(metric: String): String = when (metric) {
        "weight" -> "kg"
        "body_fat" -> "%"
        else -> "cm"
    }

    private fun defaultStorageUnit(metric: String?): String? = metric?.let { canonicalUnitOf(it) }

    /** Value in canonical unit + unit, or null when implausible / unconvertible. */
    private fun convert(metric: String?, value: Double, rawUnit: String?): Pair<Double, String>? {
        if (!value.isFinite()) return null
        val info = unitInfo(rawUnit)
        if (metric != null) {
            val canonical = canonicalUnitOf(metric)
            val converted = when {
                info == null -> value                                      // unknown/absent unit: assume canonical
                canonical == "kg" && info.dimension == Dimension.MASS -> value * info.factor
                canonical == "cm" && info.dimension == Dimension.LENGTH -> value * info.factor
                canonical == "%" && info.dimension == Dimension.PERCENT -> value
                else -> return null                                        // e.g. weight given in cm
            }
            return if (plausible(canonical, converted)) converted to canonical else null
        }
        // Custom stat: convert body dimensions, keep anything else as it is.
        return when (info?.dimension) {
            Dimension.MASS -> (value * info.factor).takeIf { plausible("kg", it) }?.let { it to "kg" }
            Dimension.LENGTH -> (value * info.factor).takeIf { plausible("cm", it) }?.let { it to "cm" }
            Dimension.PERCENT -> value.takeIf { plausible("%", it) }?.let { it to "%" }
            null -> value.takeIf { it > 0 }?.let { it to (rawUnit?.trim().orEmpty()) }
        }
    }

    private fun plausible(canonicalUnit: String, value: Double): Boolean = when (canonicalUnit) {
        "kg" -> value in 20.0..400.0
        "%" -> value in 1.0..75.0
        "cm" -> value in 5.0..300.0
        else -> value > 0
    }

    // ── JSON database backup ─────────────────────────────────────────

    private fun parseJson(text: String, quickAddName: String): Result? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val diary = root["diary"] as? JsonArray ?: return Result(Format.JSON, emptyList(), 0, null, null)
        val settings = settingsObject(root["settings"])
        val storageUnits = storageUnitsFrom(settings)
        val customNames = mutableMapOf<String, String>()
        val out = LinkedHashMap<Pair<String, String>, ParsedMeasurement>()
        var skipped = 0
        for (entry in diary) {
            val obj = entry as? JsonObject ?: continue
            val stats = obj["stats"] as? JsonObject ?: continue
            val values = stats.filterValues { it !is JsonNull && (it as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true }
            if (values.isEmpty()) continue
            val day = dayFromJson(obj["dateTime"])
            if (day == null) { skipped += values.size; continue }
            for ((name, element) in values) {
                val raw = numberOf(element)
                if (raw == null) { skipped++; continue }
                val metric = metricFor(name)
                val unit = storageUnits[name] ?: defaultStorageUnit(metric)
                val converted = convert(metric, raw, unit)
                if (converted == null) { skipped++; continue }
                val key = metric ?: customKey(name).also { customNames[it] = name.trim() }
                out[day to key] = ParsedMeasurement(day, key, converted.first, converted.second)
            }
        }
        return result(Format.JSON, out.values.toList(), skipped, customNames = customNames)
            .copy(food = parseFood(root, diary, mealNamesFrom(settings), quickAddName))
    }

    /** The settings are an object, or a JSON string in some exports. */
    private fun settingsObject(settings: JsonElement?): JsonObject? = when (settings) {
        is JsonObject -> settings
        is JsonPrimitive -> settings.contentOrNull?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }

    /** `settings.bodyStats.units`. */
    private fun storageUnitsFrom(settings: JsonObject?): Map<String, String> {
        val obj = settings ?: return emptyMap()
        val units = (obj["bodyStats"] as? JsonObject)?.get("units") as? JsonObject ?: return emptyMap()
        return units.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }

    private fun dayFromJson(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.contentOrNull?.let { s ->
            Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s.trim())?.let { m ->
                return validDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
        }
        val millis = primitive.longOrNull ?: return null
        return runCatching { LocalDate.fromEpochDays(millis / 86_400_000L).toString() }.getOrNull()
    }

    private fun numberOf(element: JsonElement): Double? {
        val p = element as? JsonPrimitive ?: return null
        return p.doubleOrNull ?: p.contentOrNull?.let(::parseNumber)
    }

    // ── CSV diary export ─────────────────────────────────────────────

    private class Column(val index: Int, val metric: String?, val name: String, val unit: String?)

    private fun parseCsv(text: String, dayFirstOverride: Boolean?): Result {
        val lines = text.split(Regex("\r\n|\n|\r")).filter { it.isNotBlank() }
        if (lines.size < 2) return EMPTY
        val separator = if (lines.first().contains(';')) ';' else ','
        val header = splitCsv(lines.first(), separator)
        if (header.size < 2) return EMPTY
        val columns = header.drop(1).mapIndexedNotNull { i, cell -> bodyColumn(i + 1, cell) }
        // Every other column of Waistline's CSV is a nutriment, summed over the day.
        val foodTotals = header.drop(1).count { it.isNotBlank() } > columns.size
        if (columns.isEmpty()) return Result(Format.CSV, emptyList(), 0, null, null, foodTotalsOnly = foodTotals)

        val rows = lines.drop(1).map { splitCsv(it, separator) }
        val dates = rows.mapNotNull { it.firstOrNull()?.trim() }
        val (dayFirst, ambiguous) = dayOrder(dates, dayFirstOverride)

        val customNames = mutableMapOf<String, String>()
        val out = LinkedHashMap<Pair<String, String>, ParsedMeasurement>()
        var skipped = 0
        for (row in rows) {
            val cells = columns.map { c -> c to row.getOrNull(c.index)?.trim().orEmpty() }.filter { it.second.isNotEmpty() }
            if (cells.isEmpty()) continue
            val day = parseCsvDate(row.firstOrNull()?.trim().orEmpty(), dayFirst)
            if (day == null) { skipped += cells.size; continue }
            for ((column, cell) in cells) {
                val raw = parseNumber(cell)
                val converted = raw?.let { convert(column.metric, it, column.unit) }
                if (converted == null) { skipped++; continue }
                val key = column.metric ?: customKey(column.name).also { customNames[it] = column.name }
                out[day to key] = ParsedMeasurement(day, key, converted.first, converted.second)
            }
        }
        return result(Format.CSV, out.values.toList(), skipped, ambiguous, dayFirst, customNames).copy(foodTotalsOnly = foodTotals)
    }

    /**
     * A column is body data when its name is a known body stat, or when its
     * unit is a body unit (kg, lb, cm, in, %, …) — custom Waistline stats.
     * Nutriment columns (kcal, g, mg …) are ignored.
     */
    private fun bodyColumn(index: Int, headerCell: String): Column? {
        val match = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$").find(headerCell.trim())
        val name = (match?.groupValues?.get(1) ?: headerCell).trim()
        val unit = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
        if (name.isEmpty()) return null
        val metric = metricFor(name)
        if (metric != null) return Column(index, metric, name, unit)
        return if (unitInfo(unit) != null && unit != "g") Column(index, null, name, unit) else null
    }

    /** Day-first decision for "a/b/yyyy" and "a-b-yyyy" dates, made once per file. */
    private fun dayOrder(dates: List<String>, override: Boolean?): Pair<Boolean?, Boolean> {
        val pairs = dates.mapNotNull { Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").find(it) }
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        if (pairs.isEmpty()) return override to false
        if (override != null) return override to false
        if (pairs.any { it.first > 12 }) return true to false
        if (pairs.any { it.second > 12 }) return false to false
        val ambiguous = pairs.any { it.first != it.second }
        return true to ambiguous
    }

    private fun parseCsvDate(raw: String, dayFirst: Boolean?): String? {
        val s = raw.trim().trim('"')
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").find(s)?.let { m ->
            return validDay(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4})$").find(s)?.let { m ->
            return validDay(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").find(s)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            return if (dayFirst != false) validDay(y, b, a) else validDay(y, a, b)
        }
        return null
    }

    private fun splitCsv(line: String, separator: Char): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }

    // ── Food diary (JSON backup) ─────────────────────────────────────

    /**
     * The food diary of a Waistline backup as CruxCoach entries and foods.
     *
     * Waistline (davidhealey/waistline, master 6a7258b) keeps, per `diary`
     * entry, `items`: `{id, type: "food"|"recipe", portion, quantity,
     * category, dateTime}` (foods-meals-recipes.js `flattenItem`, diary.js
     * `addItemToEntry`). `id` points into `foodList` or `recipes`; `category`
     * is the index of the meal in `settings.diary["meal-names"]` (default
     * Breakfast, Lunch, Dinner, Snacks; settings.js defaults). A food or
     * recipe stores its `nutrition` for its own `portion` in its own `unit`
     * (the diary item has no unit: renderItem "Always use item unit from
     * database"), and a diary item counts as
     * `nutrient × (item.portion / food.portion) × item.quantity`, each
     * nutrient rounded to 2 decimals (`getTotalNutrition`). A saved meal is
     * added as its single items, a recipe as one item with the recipe's summed
     * `nutrition` (recipe-editor.js `save`). "Quick Add" is a food with barcode
     * `quick-add`, 1 kcal per portion and the kcal as quantity (diary.js
     * `quickAdd`); activities from intervals.icu are quick adds with a negative
     * quantity (burned energy, not imported). Waistline tracks no water.
     */
    data class FoodDiary(
        val entries: List<FoodLogEntry>,
        /** The foods the entries refer to, so they show up in the food search. */
        val foods: List<FoodItem>,
        /** Entries that are a Waistline recipe: one entry with the recipe's nutrition. */
        val recipeEntries: Int,
        /** Diary items not imported: food or recipe missing from the backup, no amount, or burned energy. */
        val skipped: Int,
        val firstDay: String?,
        val lastDay: String?,
    ) {
        val days: Set<String> get() = entries.map { it.day }.toSet()

        companion object {
            val EMPTY = FoodDiary(emptyList(), emptyList(), 0, 0, null, null)
        }
    }

    private const val QUICK_ADD_BARCODE = "quick-add"

    /** utils.js `convertUnit` kJ → kcal. Waistline always stores kcal; kJ alone only in odd files. */
    private const val KCAL_PER_KJ = 0.23900573614

    private val DEFAULT_MEAL_NAMES = listOf("Breakfast", "Lunch", "Dinner", "Snacks")

    /** Waistline's default meal names in all its languages (locale-*.json `diary.default-meals`), plus common own names. */
    private val MEAL_NAMES: Map<String, Meal> = buildMap<String, Meal> {
        fun add(meal: Meal, vararg names: String) = names.forEach { put(it, meal) }
        add(Meal.BREAKFAST, "breakfast", "frühstück", "fruehstueck", "morgens", "petit déjeuner", "petit dejeuner",
            "desayuno", "colazione", "ontbijt", "śniadanie", "sniadanie", "café da manhã", "cafe da manha",
            "pequeno almoço", "mic dejun", "завтрак", "сніданок", "snídaně", "aamiainen", "frukost", "kahvaltı",
            "доручак", "doručak", "закуска", "ארוחת בוקר")
        add(Meal.LUNCH, "lunch", "mittagessen", "mittag", "mittags", "déjeuner", "dejeuner", "almuerzo", "pranzo",
            "middageten", "obiad", "almoço", "almoco", "prânz", "pranz", "обед", "обід", "oběd", "lounas",
            "öğle yemeği", "ручак", "ručak", "обяд", "ארוחת צהריים")
        add(Meal.DINNER, "dinner", "supper", "abendessen", "abendbrot", "abends", "dîner", "diner", "cena", "avondeten",
            "kolacja", "jantar", "cină", "ужин", "вечеря", "večeře", "päivällinen", "middag", "akşam yemeği",
            "вечера", "večera", "ארוחת ערב")
        add(Meal.SNACK, "snacks", "snack", "zwischenmahlzeit", "encas", "en cas", "refrigerio", "merenda", "przekąski",
            "lanche", "gustări", "закуски", "перекус", "svačiny", "välipala", "atıştırmalık", "грицкалице",
            "grickalice", "похапвания", "חטיפים")
    }

    private fun normalizeMeal(name: String): String =
        name.trim().lowercase().replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ")

    /** A Waistline meal name in any of its languages; any other name is a snack. */
    internal fun mealForName(name: String): Meal = MEAL_NAMES[normalizeMeal(name)] ?: Meal.SNACK

    /** The meal of a diary item: its `category` index into the meal names, a blank name as Waistline's default. */
    internal fun mealFor(category: JsonElement?, mealNames: List<String>): Meal {
        val index = jsParseFloat(category)?.takeIf { it >= 0 && it == floor(it) }?.toInt() ?: return Meal.SNACK
        val name = mealNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: DEFAULT_MEAL_NAMES.getOrNull(index) ?: return Meal.SNACK
        return mealForName(name)
    }

    /** `settings.diary["meal-names"]`; Waistline's defaults when absent. */
    private fun mealNamesFrom(settings: JsonObject?): List<String> {
        val names = ((settings?.get("diary") as? JsonObject)?.get("meal-names") as? JsonArray)
            ?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return if (names.isNullOrEmpty()) DEFAULT_MEAL_NAMES else names
    }

    /** Grams (1 ml counts as 1 g) per Waistline unit, or null for units like "slice" that are counted. */
    private fun gramsPerUnit(unit: String): Double? = when (unit.trim().lowercase().removeSuffix(".")) {
        "g", "gr", "gram", "grams", "gramm", "gramme", "grammes", "grammi", "gramos", "gramas", "г", "гр", "גרם" -> 1.0
        "kg", "кг" -> 1000.0
        "ml", "мл", "מ״ל", "milliliter", "millilitre", "milliliters", "millilitres" -> 1.0
        "cl" -> 10.0
        "dl" -> 100.0
        "l", "л", "liter", "litre", "liters", "litres" -> 1000.0
        "oz" -> 28.349523125
        "fl oz", "fl. oz", "floz" -> 29.5735295625
        else -> null
    }

    private fun isVolume(unit: String): Boolean = unit.trim().lowercase().removeSuffix(".") in
        setOf("ml", "мл", "מ״ל", "milliliter", "millilitre", "milliliters", "millilitres", "cl", "dl", "l", "л",
            "liter", "litre", "liters", "litres", "fl oz", "fl. oz", "floz")

    private class ParsedItem(val entry: FoodLogEntry, val foodId: String?, val recipe: Boolean)

    private fun parseFood(root: JsonObject, diary: JsonArray, mealNames: List<String>, quickAddName: String): FoodDiary {
        val foods = byId(root["foodList"])
        val recipes = byId(root["recipes"])
        val entries = mutableListOf<FoodLogEntry>()
        val usedFoods = LinkedHashMap<String, MutableList<FoodLogEntry>>()
        val perDay = mutableMapOf<String, Int>()
        var recipeEntries = 0
        var skipped = 0
        for (element in diary) {
            val obj = element as? JsonObject ?: continue
            val items = obj["items"] as? JsonArray ?: continue
            if (items.isEmpty()) continue
            val day = dayFromJson(obj["dateTime"])
            if (day == null) { skipped += items.size; continue }
            for (raw in items) {
                // Numbered per day over the whole file: two diary records of one day never share an id.
                val n = perDay[day] ?: 0
                perDay[day] = n + 1
                val parsed = (raw as? JsonObject)?.let { foodEntry(it, day, n, foods, recipes, mealNames, quickAddName) }
                if (parsed == null) { skipped++; continue }
                entries += parsed.entry
                if (parsed.recipe) recipeEntries++
                parsed.foodId?.let { usedFoods.getOrPut(it) { mutableListOf() } += parsed.entry }
            }
        }
        val foodItems = usedFoods.mapNotNull { (id, logged) -> foods[id]?.let { foodItem(id, it, logged) } }
        val linked = foodItems.map { it.id }.toSet()
        val out = entries
            .map { e -> if (e.foodItemId != null && e.foodItemId !in linked) e.copy(foodItemId = null) else e }
            .sortedBy { it.day }
        return FoodDiary(out, foodItems, recipeEntries, skipped, out.firstOrNull()?.day, out.lastOrNull()?.day)
    }

    private fun foodEntry(
        item: JsonObject,
        day: String,
        n: Int,
        foods: Map<String, JsonObject>,
        recipes: Map<String, JsonObject>,
        mealNames: List<String>,
        quickAddName: String,
    ): ParsedItem? {
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "food"
        val id = idOf(item["id"]) ?: return null
        val record = when (type) {
            "food" -> foods[id]
            "recipe" -> recipes[id]
            else -> null
        } ?: return null
        // foods-meals-recipes.js getTotalNutrition: (item.portion / data.portion) * (item.quantity || 0)
        val dataPortion = jsParseFloat(record["portion"]) ?: return null
        val itemPortion = jsParseFloat(item["portion"]) ?: return null
        val quantity = jsParseFloat(item["quantity"]) ?: 0.0
        val multiplier = itemPortion / dataPortion * quantity
        // Nothing eaten, or burned energy (a negative quantity).
        if (!multiplier.isFinite() || multiplier <= 0.0) return null
        val nutrition = record["nutrition"] as? JsonObject
        fun scaled(key: String): Double? = nutrition?.get(key)?.let(::numberOf)?.takeIf { it.isFinite() }
            ?.let { roundTo(it * multiplier, 2) }
        val kcal = scaled("calories")
            ?: nutrition?.get("kilojoules")?.let(::numberOf)?.takeIf { it.isFinite() }?.let { roundTo(it * KCAL_PER_KJ * multiplier, 2) }
        if (kcal != null && kcal < 0) return null
        // Waistline adds negative values only for energy.
        fun macro(key: String) = scaled(key)?.takeIf { it >= 0 }

        val recipe = type == "recipe"
        val quickAdd = !recipe && textOf(record["barcode"]) == QUICK_ADD_BARCODE
        val unit = textOf(record["unit"]).orEmpty()
        val perGram = if (quickAdd) null else gramsPerUnit(unit)
        val amountG = perGram?.let { roundTo(itemPortion * quantity * it, 2) }
        val portions = when {
            amountG != null -> null
            quickAdd -> 1.0
            else -> roundTo(multiplier, 3)
        }
        val name = if (quickAdd) {
            textOf(item["description"]) ?: quickAddName
        } else {
            val base = textOf(record["name"]) ?: SOURCE.replaceFirstChar { it.uppercase() }
            val brand = brandOf(record)
            if (brand == null || base.contains(brand, ignoreCase = true)) base else "$base ($brand)"
        }
        // Foods become CruxCoach foods; quick adds, deleted ("archived") foods and recipes stay entries only.
        val linkable = !recipe && !quickAdd && (record["archived"] as? JsonPrimitive)?.contentOrNull != "true"
        val entry = FoodLogEntry(
            id = "$ID_PREFIX$day:$n",
            day = day,
            loggedAt = epochMillis(item["dateTime"]) ?: (dayStartMillis(day) + n),
            meal = mealFor(item["category"], mealNames),
            foodItemId = if (linkable) ID_PREFIX + id else null,
            name = name,
            amountG = amountG,
            portions = portions,
            kcal = kcal,
            proteinG = macro("proteins"),
            carbsG = macro("carbohydrates"),
            fatG = macro("fat"),
        )
        return ParsedItem(entry, if (linkable) id else null, recipe)
    }

    /**
     * A Waistline food as a CruxCoach food: per 100 g when its unit is a weight
     * or volume, else per portion ([PER_PORTION_SOURCE]) with its portion as label.
     */
    private fun foodItem(id: String, food: JsonObject, logged: List<FoodLogEntry>): FoodItem? {
        val portion = jsParseFloat(food["portion"])?.takeIf { it.isFinite() && it > 0 } ?: return null
        val unit = textOf(food["unit"]).orEmpty()
        val nutrition = food["nutrition"] as? JsonObject
        fun value(key: String) = nutrition?.get(key)?.let(::numberOf)?.takeIf { it.isFinite() && it >= 0 }
        val kcal = value("calories") ?: value("kilojoules")?.let { it * KCAL_PER_KJ }
        val label = (plain(portion) + " " + unit).trim().takeIf { it.isNotEmpty() && unit.isNotEmpty() }
        val perGram = gramsPerUnit(unit)
        val factor = if (perGram != null) 100.0 / (portion * perGram) else 1.0
        fun per(v: Double?) = v?.let { roundTo(it * factor, 2) }
        val grams = perGram?.let { roundTo(portion * it, 2) }
        // 100 g is the basis anyway; a drink keeps its label, which marks it as one (FuelUnits.isDrink).
        val serving = grams?.takeUnless { it == 100.0 && !isVolume(unit) }
        val lastUsed = logged.maxOfOrNull { it.loggedAt }
        return FoodItem(
            id = ID_PREFIX + id,
            name = textOf(food["name"]) ?: SOURCE.replaceFirstChar { it.uppercase() },
            brand = brandOf(food),
            barcode = barcodeOf(food),
            kcalPer100 = per(kcal),
            proteinPer100 = per(value("proteins")),
            carbsPer100 = per(value("carbohydrates")),
            fatPer100 = per(value("fat")),
            servingG = serving,
            servingLabel = if (perGram == null || serving != null) label else null,
            source = if (perGram != null) SOURCE else PER_PORTION_SOURCE,
            useCount = logged.size,
            lastUsedAt = lastUsed,
            updatedAt = epochMillis(food["dateTime"]) ?: lastUsed ?: 0L,
        )
    }

    private fun brandOf(record: JsonObject): String? = textOf(record["brand"])?.takeIf { it != "undefined" }

    /** A real product code only: Waistline also keeps "fdcId_…", "custom_…" and "quick-add" there. */
    private fun barcodeOf(food: JsonObject): String? {
        val barcode = textOf(food["barcode"]) ?: return null
        if (barcode.startsWith("fdcId_") || barcode.startsWith("custom_") || barcode == QUICK_ADD_BARCODE) return null
        val code = textOf(food["originalBarcode"]) ?: barcode
        return code.takeIf { c -> c.length in setOf(8, 12, 13, 14) && c.all { it in '0'..'9' } }
    }

    private fun byId(element: JsonElement?): Map<String, JsonObject> =
        (element as? JsonArray)?.mapNotNull { o -> (o as? JsonObject)?.let { obj -> idOf(obj["id"])?.let { it to obj } } }?.toMap()
            ?: emptyMap()

    private fun idOf(element: JsonElement?): String? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.doubleOrNull?.takeIf { it.isFinite() && it == floor(it) && abs(it) < 1e15 }?.let { return it.toLong().toString() }
        return p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun textOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private val JS_FLOAT = Regex("""^\s*([+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?)""")

    /** JavaScript's parseFloat: a number as it is, a string by its leading number ("150 g" → 150, "1,5" → 1). */
    private fun jsParseFloat(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        val value = if (p.isString) JS_FLOAT.find(p.content)?.groupValues?.get(1)?.toDoubleOrNull() else p.doubleOrNull
        return value?.takeIf { it.isFinite() }
    }

    private fun epochMillis(element: JsonElement?): Long? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) return p.longOrNull
        return runCatching { kotlin.time.Instant.parse(p.content.trim()).toEpochMilliseconds() }.getOrNull()
    }

    private fun dayStartMillis(day: String): Long = LocalDate.parse(day).toEpochDays() * 86_400_000L

    /** JavaScript's Math.round(value × 10^decimals) / 10^decimals, as Waistline rounds each nutrient. */
    private fun roundTo(value: Double, decimals: Int): Double {
        var f = 1.0
        repeat(decimals) { f *= 10.0 }
        return floor(value * f + 0.5) / f
    }

    /** 30.0 → "30", 0.5 → "0.5". */
    private fun plain(value: Double): String {
        val r = roundTo(value, 2)
        return if (r == floor(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
    }


    // ── Shared helpers ───────────────────────────────────────────────

    /** "70,2", "70.2", " 70,25 " → 70.2… ; Waistline writes no digit grouping. */
    internal fun parseNumber(raw: String): Double? {
        val s = raw.trim().trim('"').replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        val normalized = if (s.contains(',') && !s.contains('.')) s.replace(',', '.') else s.replace(",", "")
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun validDay(year: Int, month: Int, day: Int): String? =
        runCatching { LocalDate(year, month, day).toString() }.getOrNull()

    private fun result(
        format: Format,
        measurements: List<ParsedMeasurement>,
        skipped: Int,
        ambiguous: Boolean = false,
        dayFirst: Boolean? = null,
        customNames: Map<String, String> = emptyMap(),
    ): Result {
        val sorted = measurements.sortedWith(compareBy({ it.day }, { it.metric }))
        return Result(format, sorted, skipped, sorted.firstOrNull()?.day, sorted.lastOrNull()?.day, ambiguous, dayFirst,
            customNames.filterKeys { key -> sorted.any { it.metric == key } })
    }
}
