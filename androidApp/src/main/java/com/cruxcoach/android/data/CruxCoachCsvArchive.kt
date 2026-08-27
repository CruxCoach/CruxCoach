package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup.Category
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A spreadsheet-friendly ZIP containing one conventional CSV per record type. */
object CruxCoachCsvArchive {
    const val MIME_TYPE = "application/zip"
    const val FILE_NAME = "cruxcoach_csv_export.zip"
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    private const val MAX_ENTRIES = 16
    private const val MAX_ROWS_PER_FILE = 250_000
    private const val NULL_CELL = "\\N"
    private const val ROW_COLUMN = "_row"
    private val json = Json { allowSpecialFloatingPointValues = true }

    private data class CollectionFile(
        val jsonField: String,
        val fileName: String,
        val category: Category,
        val excludedFields: Set<String> = emptySet(),
    )

    private val collectionFiles = listOf(
        CollectionFile("boardAscents", "board_sends.csv", Category.BOARD_LOGBOOK),
        CollectionFile("boardBids", "board_attempts.csv", Category.BOARD_LOGBOOK),
        CollectionFile("climbNotes", "climb_notes.csv", Category.BOARD_LOGBOOK),
        CollectionFile(
            "climbLists",
            "climb_lists.csv",
            Category.CLIMB_LISTS,
            setOf("entries", "playlistEntries"),
        ),
        CollectionFile("boardClimbs", "own_climbs.csv", Category.OWN_CLIMBS),
        CollectionFile("boardClimbStats", "own_climb_stats.csv", Category.OWN_CLIMBS),
    )

    private val allowedFiles = buildSet {
        add("metadata.csv")
        collectionFiles.forEach { add(it.fileName) }
        add("climb_list_entries.csv")
        add("playlist_entries.csv")
    }

    fun fromJson(jsonString: String, categories: Set<Category>): ByteArray {
        val root = json.parseToJsonElement(jsonString) as? JsonObject
            ?: error("CruxCoach export root must be an object")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "metadata.csv", encodeMetadata(root))
            collectionFiles.filter { it.category in categories }.forEach { spec ->
                val array = root[spec.jsonField] as? JsonArray ?: JsonArray(emptyList())
                writeEntry(zip, spec.fileName, encodeArray(array, spec.excludedFields))
            }

            if (Category.CLIMB_LISTS in categories) {
                val lists = root["climbLists"] as? JsonArray ?: JsonArray(emptyList())
                writeEntry(zip, "climb_list_entries.csv", encodeArray(flattenListEntries(lists)))
                writeEntry(zip, "playlist_entries.csv", encodeArray(flattenPlaylistEntries(lists)))
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive is too large" }
        }
    }

    fun toJson(archive: ByteArray): String {
        require(archive.size <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive is too large" }
        val files = unzip(archive)
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(decodeMetadata(requireNotNull(files["metadata.csv"]) {
            "CruxCoach CSV archive has no metadata.csv"
        }))

        collectionFiles.forEach { spec ->
            result[spec.jsonField] = files[spec.fileName]?.let(::decodeArray)
                ?: JsonArray(emptyList())
        }

        val baseLists = result["climbLists"] as JsonArray
        val listEntries = files["climb_list_entries.csv"]?.let(::decodeArray)
            ?: JsonArray(emptyList())
        val playlistEntries = files["playlist_entries.csv"]?.let(::decodeArray)
            ?: JsonArray(emptyList())
        result["climbLists"] = restoreNestedListRows(baseLists, listEntries, playlistEntries)
        return JsonObject(result).toString()
    }

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun encodeMetadata(root: JsonObject): String {
        val rows = mutableListOf(listOf("field", "type", "value"))
        root.forEach { (field, value) ->
            if (value !is JsonArray && value !is JsonObject) {
                val type = typeOf(value)
                rows += listOf(field, type, encodeCell(value))
            }
        }
        return encodeCsv(rows)
    }

    private fun decodeMetadata(csv: String): Map<String, JsonElement> {
        val rows = parseCsv(csv)
        require(rows.firstOrNull() == listOf("field", "type", "value")) {
            "Invalid CruxCoach metadata.csv"
        }
        val result = linkedMapOf<String, JsonElement>()
        rows.drop(1).forEach { row ->
            require(row.size == 3) { "Invalid CruxCoach metadata row" }
            require(result.put(row[0], decodeCell(row[1], row[2])) == null) {
                "Duplicate CruxCoach metadata field"
            }
        }
        return result
    }

    private fun encodeArray(array: JsonArray, excluded: Set<String> = emptySet()): String {
        val objects = array.map {
            it as? JsonObject ?: error("CruxCoach collection row must be an object")
        }
        val fields = objects.flatMap { it.keys }
            .filterNot { it in excluded }
            .distinct()
            .sorted()
        val headers = listOf(ROW_COLUMN) + fields
        val types = listOf("number") + fields.map { field ->
            objects.asSequence().mapNotNull { it[field] }.firstOrNull { it !is JsonNull }
                ?.let(::typeOf) ?: "string"
        }
        val rows = mutableListOf(headers, types)
        objects.forEachIndexed { index, obj ->
            rows += listOf(index.toString()) + fields.map { field ->
                encodeCell(obj[field] ?: JsonNull)
            }
        }
        return encodeCsv(rows)
    }

    private fun decodeArray(csv: String): JsonArray {
        val rows = parseCsv(csv)
        require(rows.size in 2..MAX_ROWS_PER_FILE + 2) { "Invalid CruxCoach collection CSV" }
        val headers = rows[0]
        val types = rows[1]
        require(headers.firstOrNull() == ROW_COLUMN && headers.size == types.size) {
            "Invalid CruxCoach collection header"
        }
        require(headers.distinct().size == headers.size) { "Duplicate CruxCoach CSV column" }
        val objects = rows.drop(2).mapIndexed { expectedIndex, row ->
            require(row.size == headers.size) { "Invalid CruxCoach collection row" }
            require(row[0].toIntOrNull() == expectedIndex) { "Invalid CruxCoach row order" }
            JsonObject(headers.drop(1).indices.associate { offset ->
                headers[offset + 1] to decodeCell(types[offset + 1], row[offset + 1])
            })
        }
        return JsonArray(objects)
    }

    private fun flattenListEntries(lists: JsonArray): JsonArray = JsonArray(buildList {
        lists.forEachIndexed { listRow, element ->
            val list = element as? JsonObject ?: return@forEachIndexed
            (list["entries"] as? JsonArray).orEmpty().forEachIndexed { position, climbUuid ->
                add(JsonObject(mapOf(
                    "listRow" to JsonPrimitive(listRow),
                    "position" to JsonPrimitive(position),
                    "climbUuid" to climbUuid,
                )))
            }
        }
    })

    private fun flattenPlaylistEntries(lists: JsonArray): JsonArray = JsonArray(buildList {
        lists.forEachIndexed { listRow, element ->
            val list = element as? JsonObject ?: return@forEachIndexed
            (list["playlistEntries"] as? JsonArray).orEmpty().forEachIndexed { position, entry ->
                val obj = entry as? JsonObject ?: return@forEachIndexed
                add(JsonObject(obj + mapOf(
                    "listRow" to JsonPrimitive(listRow),
                    "position" to JsonPrimitive(position),
                )))
            }
        }
    })

    private fun restoreNestedListRows(
        lists: JsonArray,
        entries: JsonArray,
        playlistEntries: JsonArray,
    ): JsonArray {
        fun grouped(rows: JsonArray): Map<Int, List<JsonObject>> {
            val grouped = rows.map { it as JsonObject }.groupBy {
                val listRow = it.getValue("listRow").toString().toInt()
                require(listRow in lists.indices) { "CSV row refers to an unknown climb list" }
                listRow
            }
            grouped.values.forEach { group ->
                group.sortedBy { it.getValue("position").toString().toInt() }
                    .forEachIndexed { expected, row ->
                        require(row.getValue("position").toString().toInt() == expected) {
                            "Invalid nested climb-list row order"
                        }
                    }
            }
            return grouped
        }

        val entriesByList = grouped(entries)
        val playlistByList = grouped(playlistEntries)
        return JsonArray(lists.mapIndexed { listRow, element ->
            val list = element as JsonObject
            val climbUuids = entriesByList[listRow].orEmpty()
                .sortedBy { it.getValue("position").toString().toInt() }
                .map { it.getValue("climbUuid") }
            val playlist = playlistByList[listRow].orEmpty()
                .sortedBy { it.getValue("position").toString().toInt() }
                .map { JsonObject(it - "listRow" - "position") }
            JsonObject(list + mapOf(
                "entries" to JsonArray(climbUuids),
                "playlistEntries" to JsonArray(playlist),
            ))
        })
    }

    private fun typeOf(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonObject, is JsonArray -> "json"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.booleanOrNull != null -> "boolean"
            else -> "number"
        }
    }

    private fun encodeCell(value: JsonElement): String = when (value) {
        JsonNull -> NULL_CELL
        is JsonPrimitive -> if (value.isString) spreadsheetSafe(value.content) else value.content
        else -> value.toString()
    }

    private fun decodeCell(type: String, cell: String): JsonElement {
        if (cell == NULL_CELL) return JsonNull
        return when (type) {
            "string" -> JsonPrimitive(fromSpreadsheetSafe(cell))
            "number" -> json.parseToJsonElement(cell).also {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull == null) {
                    "Invalid number in CruxCoach CSV"
                }
            }
            "boolean" -> when (cell) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> error("Invalid boolean in CruxCoach CSV")
            }
            "json" -> json.parseToJsonElement(cell)
            "null" -> error("Non-null value in null-typed CruxCoach CSV column")
            else -> error("Unknown CruxCoach CSV type $type")
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun unzip(archive: ByteArray): Map<String, String> {
        val files = linkedMapOf<String, String>()
        var totalBytes = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in allowedFiles && '/' !in entry.name) {
                    "Unexpected file in CruxCoach CSV archive"
                }
                require(files[entry.name] == null) { "Duplicate file in CruxCoach CSV archive" }
                require(++entryCount <= MAX_ENTRIES) { "Too many files in CruxCoach CSV archive" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive expands too large" }
                    output.write(buffer, 0, count)
                }
                files[entry.name] = output.toString(Charsets.UTF_8.name())
                zip.closeEntry()
            }
        }
        return files
    }

    private fun encodeCsv(rows: List<List<String>>): String =
        rows.joinToString("\n", postfix = "\n") { row ->
            row.joinToString(";") { value ->
                if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"${value.replace("\"", "\"\"")}\""
                } else value
            }
        }

    private fun parseCsv(value: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() { row += field.toString(); field.setLength(0) }
        fun finishRow() { finishField(); rows += row; row = mutableListOf() }
        while (index < value.length) {
            val char = value[index]
            when {
                quoted && char == '"' && index + 1 < value.length && value[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ';' -> finishField()
                !quoted && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && value.getOrNull(index + 1) == '\n') index++
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

    private fun spreadsheetSafe(value: String): String =
        when {
            value == NULL_CELL -> "\\$value"
            value.firstOrNull() in formulaPrefixes -> "'$value"
            value.startsWith("\\") -> "\\$value"
            else -> value
        }

    private fun fromSpreadsheetSafe(value: String): String = when {
        value.startsWith("'") && value.getOrNull(1) in formulaPrefixes -> value.drop(1)
        value.startsWith("\\\\") -> value.drop(1)
        else -> value
    }

    private val formulaPrefixes = setOf('=', '+', '-', '@', '\t', '\r')
}
