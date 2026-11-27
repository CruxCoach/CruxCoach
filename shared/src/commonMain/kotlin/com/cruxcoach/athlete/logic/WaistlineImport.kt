package com.cruxcoach.athlete.logic

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

/**
 * Reads body data from the open-source app Waistline — either its database
 * backup (`waistline_export.json`) or its diary CSV (`diary_export.csv`) —
 * into canonical metric measurements. Food diary entries are not read.
 *
 * Formats as written by Waistline (davidhealey/waistline, master):
 * - JSON: one array per IndexedDB store; `diary` entries carry `dateTime`
 *   (UTC midnight) and `stats` {statName → value}. Values are stored in the
 *   stat's storage unit: kg / cm / % for the defaults, overridden per stat by
 *   `settings.bodyStats.units` (custom stats and custom units).
 * - CSV: `;`-separated, a localized header "Name (unit)" per column, values in
 *   the user's display unit, dates from `toLocaleDateString` (so the format
 *   depends on the phone's language) and the locale decimal separator.
 */
object WaistlineImport {

    const val SOURCE = "waistline"
    const val CUSTOM_PREFIX = "waistline_"

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
    ) {
        val isEmpty: Boolean get() = measurements.isEmpty()
        fun countsByMetric(): Map<String, Int> = measurements.groupingBy { it.metric }.eachCount()
    }

    private val EMPTY = Result(Format.UNKNOWN, emptyList(), 0, null, null)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param dayFirst forces the order of day and month for CSV dates written
     *   with "/" or "-" (null = detect; ambiguous files default to day first).
     */
    fun parse(text: String, dayFirst: Boolean? = null): Result {
        val clean = text.removePrefix("﻿").trim()
        if (clean.isEmpty()) return EMPTY
        return if (clean.startsWith("{")) parseJson(clean) ?: EMPTY else parseCsv(clean, dayFirst)
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

    private fun parseJson(text: String): Result? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val diary = root["diary"] as? JsonArray ?: return Result(Format.JSON, emptyList(), 0, null, null)
        val storageUnits = storageUnitsFrom(root["settings"])
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
    }

    /** `settings.bodyStats.units` — the settings are an object, or a JSON string in some exports. */
    private fun storageUnitsFrom(settings: JsonElement?): Map<String, String> {
        val obj = when (settings) {
            is JsonObject -> settings
            is JsonPrimitive -> settings.contentOrNull?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            else -> null
        } ?: return emptyMap()
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
        if (columns.isEmpty()) return Result(Format.CSV, emptyList(), 0, null, null)

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
        return result(Format.CSV, out.values.toList(), skipped, ambiguous, dayFirst, customNames)
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
