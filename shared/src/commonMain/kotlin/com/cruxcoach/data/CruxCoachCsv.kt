package com.cruxcoach.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Spreadsheet-friendly, lossless representation of a CruxCoach JSON export.
 *
 * A conventional wide CSV cannot represent the different logbook, playlist,
 * climb and note records in one file without either losing fields or embedding
 * opaque JSON. This long form uses one JSON-pointer-like path per value:
 *
 * `$/boardAscents/0/climbName;string;My project`
 *
 * Container rows preserve empty objects/arrays, while the explicit type column
 * keeps numbers, booleans, strings and null distinct during a round trip.
 */
object CruxCoachCsv {
    const val MIME_TYPE = "text/csv"
    const val HEADER = "CruxCoach CSV;1"
    private const val COLUMNS = "path;type;value"
    private const val MAX_CHARS = 16 * 1024 * 1024
    private const val MAX_ROWS = 250_000
    private const val MAX_DEPTH = 64

    private val json = Json { ignoreUnknownKeys = false }

    fun fromJson(jsonString: String): String {
        require(jsonString.length <= MAX_CHARS) { "CruxCoach export is too large" }
        val root = json.parseToJsonElement(jsonString)
        require(root is JsonObject) { "CruxCoach export root must be an object" }

        val rows = mutableListOf(HEADER, COLUMNS)
        flatten(root, ROOT_PATH, rows, depth = 0)
        require(rows.size <= MAX_ROWS) { "CruxCoach CSV contains too many rows" }
        return rows.joinToString("\n", postfix = "\n")
    }

    fun toJson(csvString: String): String {
        require(csvString.length <= MAX_CHARS) { "CruxCoach CSV is too large" }
        val normalized = csvString.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val rows = parseRows(normalized)
        require(rows.size in 3..MAX_ROWS) { "Invalid CruxCoach CSV row count" }
        require(rows[0] == listOf("CruxCoach CSV", "1")) { "Not a CruxCoach CSV export" }
        require(rows[1] == listOf("path", "type", "value")) { "Invalid CruxCoach CSV header" }

        val entries = linkedMapOf<String, Entry>()
        rows.drop(2).forEachIndexed { index, row ->
            require(row.size == 3) { "Invalid CruxCoach CSV row ${index + 3}" }
            val path = row[0]
            require(path == ROOT_PATH || path.startsWith("$ROOT_PATH/")) {
                "Invalid CruxCoach CSV path on row ${index + 3}"
            }
            require(entries.put(path, Entry(row[1], row[2])) == null) {
                "Duplicate CruxCoach CSV path on row ${index + 3}"
            }
        }

        entries.forEach { (path, _) ->
            if (path == ROOT_PATH) return@forEach
            val parent = entries[parentPath(path)]
            require(parent?.type == TYPE_OBJECT || parent?.type == TYPE_ARRAY) {
                "Missing container for CruxCoach CSV path $path"
            }
        }

        require(entries[ROOT_PATH]?.type == TYPE_OBJECT) { "CruxCoach CSV root must be an object" }
        val children = entries.keys
            .filter { it != ROOT_PATH }
            .groupBy(::parentPath)
        val root = build(ROOT_PATH, entries, children, depth = 0)
        require(root is JsonObject) { "CruxCoach CSV root must be an object" }
        return root.toString()
    }

    fun looksLikeCsv(value: String): Boolean =
        value.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith(HEADER)

    private fun flatten(element: JsonElement, path: String, rows: MutableList<String>, depth: Int) {
        require(depth <= MAX_DEPTH) { "CruxCoach export is nested too deeply" }
        when (element) {
            is JsonObject -> {
                rows += row(path, TYPE_OBJECT, "")
                element.forEach { (key, value) ->
                    flatten(value, "$path/${encodeSegment(key)}", rows, depth + 1)
                }
            }
            is JsonArray -> {
                rows += row(path, TYPE_ARRAY, "")
                element.forEachIndexed { index, value ->
                    flatten(value, "$path/$index", rows, depth + 1)
                }
            }
            JsonNull -> rows += row(path, TYPE_NULL, "")
            is JsonPrimitive -> when {
                element.isString -> rows += row(path, TYPE_STRING, spreadsheetSafe(element.content))
                element.booleanOrNull != null -> rows += row(path, TYPE_BOOLEAN, element.content)
                else -> rows += row(path, TYPE_NUMBER, element.content)
            }
        }
    }

    private fun build(
        path: String,
        entries: Map<String, Entry>,
        children: Map<String, List<String>>,
        depth: Int,
    ): JsonElement {
        require(depth <= MAX_DEPTH) { "CruxCoach CSV is nested too deeply" }
        val entry = requireNotNull(entries[path]) { "Missing CruxCoach CSV path $path" }
        val childPaths = children[path].orEmpty()
        return when (entry.type) {
            TYPE_OBJECT -> JsonObject(childPaths.associate { childPath ->
                decodeSegment(childPath.substringAfterLast('/')) to
                    build(childPath, entries, children, depth + 1)
            })
            TYPE_ARRAY -> {
                val indexed = childPaths.map { childPath ->
                    val index = childPath.substringAfterLast('/').toIntOrNull()
                    requireNotNull(index) { "Invalid array index at $childPath" }
                    index to childPath
                }.sortedBy { it.first }
                indexed.forEachIndexed { expected, (actual, _) ->
                    require(actual == expected) { "Non-contiguous array at $path" }
                }
                JsonArray(indexed.map { (_, childPath) ->
                    build(childPath, entries, children, depth + 1)
                })
            }
            TYPE_STRING -> scalar(entry, childPaths) { JsonPrimitive(fromSpreadsheetSafe(entry.value)) }
            TYPE_BOOLEAN -> scalar(entry, childPaths) {
                when (entry.value) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> error("Invalid boolean at $path")
                }
            }
            TYPE_NUMBER -> scalar(entry, childPaths) {
                val parsed = json.parseToJsonElement(entry.value)
                require(parsed is JsonPrimitive && !parsed.isString && parsed.booleanOrNull == null) {
                    "Invalid number at $path"
                }
                parsed
            }
            TYPE_NULL -> scalar(entry, childPaths) {
                require(entry.value.isEmpty()) { "Invalid null at $path" }
                JsonNull
            }
            else -> error("Unknown CruxCoach CSV type '${entry.type}' at $path")
        }
    }

    private inline fun scalar(entry: Entry, children: List<String>, value: () -> JsonElement): JsonElement {
        require(children.isEmpty()) { "Scalar CSV row has children" }
        return value()
    }

    private fun row(path: String, type: String, value: String): String =
        listOf(path, type, value).joinToString(";") { escape(it) }

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value

    /** Prevent a user-authored name or note from becoming a spreadsheet formula. */
    private fun spreadsheetSafe(value: String): String =
        if (value.firstOrNull() in FORMULA_PREFIXES) "'$value" else value

    private fun fromSpreadsheetSafe(value: String): String =
        if (value.startsWith("'") && value.getOrNull(1) in FORMULA_PREFIXES) value.drop(1) else value

    private fun parseRows(value: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            rows += row
            row = mutableListOf()
        }

        while (index < value.length) {
            val char = value[index]
            when {
                quoted && char == '"' && index + 1 < value.length && value[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ';' -> finishField()
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < value.length && value[index + 1] == '\n') index++
                    finishRow()
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "Unterminated quoted field in CruxCoach CSV" }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun parentPath(path: String): String = path.substringBeforeLast('/')
    private fun encodeSegment(value: String): String = value.replace("~", "~0").replace("/", "~1")
    private fun decodeSegment(value: String): String = value.replace("~1", "/").replace("~0", "~")

    private data class Entry(val type: String, val value: String)

    private const val ROOT_PATH = "$"
    private const val TYPE_OBJECT = "object"
    private const val TYPE_ARRAY = "array"
    private const val TYPE_STRING = "string"
    private const val TYPE_NUMBER = "number"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_NULL = "null"
    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')
}
