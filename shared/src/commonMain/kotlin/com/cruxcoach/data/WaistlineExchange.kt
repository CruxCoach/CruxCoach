package com.cruxcoach.data

import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.domain.model.BodyStat
import com.cruxcoach.domain.model.StatRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Handles import/export of body stats in Waistline-compatible JSON format.
 *
 * Waistline diary format:
 * {
 *   "version": <int>,
 *   "diary": [
 *     {
 *       "dateTime": "2024-03-14T00:00:00.000Z",
 *       "stats": { "weight": 75.5, "waist": 85.2, "body fat": 22.5 },
 *       "items": [...]
 *     }
 *   ]
 * }
 */
object WaistlineExchange {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Export body stats as Waistline-compatible JSON string.
     * Only exports the diary with stats (no food items).
     */
    fun exportToJson(repository: BodyStatRepository): String {
        val allStats = repository.getAll().filter { it.statName in StatRegistry.waistlineKeys }
        val byDate = allStats.groupBy { it.date }

        val diaryEntries = byDate.map { (date, stats) ->
            buildJsonObject {
                // Waistline uses ISO-8601 with time at UTC midnight
                put("dateTime", "${date}T00:00:00.000Z")
                putJsonObject("stats") {
                    stats.forEach { stat ->
                        put(stat.statName, stat.value)
                    }
                }
                putJsonArray("items") { /* empty - no food data */ }
            }
        }.sortedByDescending {
            it["dateTime"]?.jsonPrimitive?.content ?: ""
        }

        val root = buildJsonObject {
            put("version", 1)
            putJsonArray("diary") {
                diaryEntries.forEach { add(it) }
            }
        }

        return json.encodeToString(JsonElement.serializer(), root)
    }

    /**
     * Export body stats as semicolon-delimited CSV (Waistline diary export format).
     */
    fun exportToCsv(repository: BodyStatRepository): String {
        val allStats = repository.getAll().filter { it.statName in StatRegistry.waistlineKeys }
        val byDate = allStats.groupBy { it.date }

        // Collect all stat names that appear
        val statNames = allStats.map { it.statName }.distinct().sorted()
        if (statNames.isEmpty()) return ""

        val sb = StringBuilder()

        // Header row
        sb.append("Date")
        statNames.forEach { name ->
            val unit = StatRegistry.unit(name)
            sb.append(";")
            sb.append(name.replaceFirstChar { it.uppercaseChar() })
            if (unit.isNotEmpty()) sb.append(" ($unit)")
        }
        sb.appendLine()

        // Data rows (sorted by date ascending)
        byDate.toSortedMap().forEach { (date, stats) ->
            sb.append(date)
            val statsMap = stats.associateBy { it.statName }
            statNames.forEach { name ->
                sb.append(";")
                statsMap[name]?.let { stat ->
                    sb.append(formatFixed2(stat.value))
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Import body stats from Waistline JSON format.
     * Returns the number of entries imported.
     */
    fun importFromJson(jsonString: String, repository: BodyStatRepository): Int {
        val root = json.parseToJsonElement(jsonString).jsonObject
        val diary = root["diary"]?.jsonArray ?: return 0

        var count = 0
        for (entry in diary) {
            val obj = entry.jsonObject
            val dateTime = obj["dateTime"]?.jsonPrimitive?.contentOrNull ?: continue
            // Extract date part (YYYY-MM-DD) from ISO datetime
            val date = dateTime.substringBefore("T")
            if (date.length != 10) continue

            val stats = obj["stats"]?.jsonObject ?: continue
            for ((statName, valueElement) in stats) {
                val value = valueElement.jsonPrimitive.doubleOrNull ?: continue
                val unit = StatRegistry.unit(statName)
                repository.upsert(
                    BodyStat(
                        date = date,
                        statName = statName,
                        value = value,
                        unit = unit.ifEmpty { "kg" }
                    )
                )
                count++
            }
        }
        return count
    }

    /**
     * Import body stats from Waistline CSV format.
     * Returns the number of entries imported.
     */
    fun importFromCsv(csvString: String, repository: BodyStatRepository): Int {
        val lines = csvString.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return 0

        val headers = lines[0].split(";")
        if (headers.isEmpty() || headers[0].lowercase().trim() != "date") return 0

        // Parse header: extract stat names and units
        val statColumns = headers.drop(1).map { header ->
            val name = header.trim()
                .replace(Regex("\\s*\\(.*\\)"), "")
                .replaceFirstChar { it.lowercaseChar() }
            name
        }

        var count = 0
        for (line in lines.drop(1)) {
            val parts = line.split(";")
            val date = parts.firstOrNull()?.trim() ?: continue
            // Validate date format
            if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) continue

            for (i in statColumns.indices) {
                val valueStr = parts.getOrNull(i + 1)?.trim() ?: continue
                if (valueStr.isEmpty()) continue
                val value = valueStr.replace(",", ".").toDoubleOrNull() ?: continue
                val statName = statColumns[i]
                val unit = StatRegistry.unit(statName)

                repository.upsert(
                    BodyStat(
                        date = date,
                        statName = statName,
                        value = value,
                        unit = unit.ifEmpty { "kg" }
                    )
                )
                count++
            }
        }
        return count
    }
}

/** Locale-independent fixed-two representation for the CSV interchange format. */
internal fun formatFixed2(value: Double): String {
    val scaled = kotlin.math.round(value * 100.0).toLong()
    val sign = if (scaled < 0) "-" else ""
    val absolute = kotlin.math.abs(scaled)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}
