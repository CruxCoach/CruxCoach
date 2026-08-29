package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.domain.board.KilterGradeMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A spreadsheet-friendly ZIP containing a small set of focused CSV tables. */
object CruxCoachCsvArchive {
    const val MIME_TYPE = "application/zip"
    const val FILE_NAME = "cruxcoach_csv_export.zip"
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    private const val MANIFEST_FILE = "manifest.json"
    private const val LOGBOOK_FILE = "board_logbook.csv"
    private const val NOTES_FILE = "climb_notes.csv"
    private const val LISTS_FILE = "climb_lists.csv"
    private const val CLIMBS_FILE = "own_climbs.csv"
    private const val MAX_ENTRIES = 5
    private const val MAX_ROWS_PER_FILE = 250_000
    private const val NULL_CELL = "\\N"
    private const val ROW_COLUMN = "_row"
    private const val ENTRY_TYPE = "entryType"
    private const val ROW_TYPE = "rowType"
    private const val LIST_ROW = "listRow"
    private const val POSITION = "position"
    private const val CLIMB_ROW = "climbRow"
    private const val STAT_ROW = "statRow"
    private const val STAT_PREFIX = "stat_"

    private val json = Json { allowSpecialFloatingPointValues = true }
    private val allowedFiles = setOf(
        MANIFEST_FILE,
        LOGBOOK_FILE,
        NOTES_FILE,
        LISTS_FILE,
        CLIMBS_FILE,
    )

    internal data class ExportTable(
        val fileName: String,
        val sheetName: String,
        val rows: JsonArray,
    )

    fun fromJson(jsonString: String, categories: Set<Category>): ByteArray {
        val root = json.parseToJsonElement(jsonString) as? JsonObject
            ?: error("CruxCoach export root must be an object")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest = JsonObject(root.filterValues { it !is JsonArray && it !is JsonObject })
            writeEntry(zip, MANIFEST_FILE, manifest.toString())
            tablesFromRoot(root, categories).forEach { table ->
                writeEntry(zip, table.fileName, encodeArray(table.rows))
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive is too large" }
        }
    }

    fun toJson(archive: ByteArray): String {
        require(archive.size <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive is too large" }
        val files = unzip(archive)
        val manifest = json.parseToJsonElement(requireNotNull(files[MANIFEST_FILE]) {
            "CruxCoach CSV archive has no manifest.json"
        }) as? JsonObject ?: error("Invalid CruxCoach CSV manifest")
        require(manifest.values.none { it is JsonArray || it is JsonObject }) {
            "Invalid CruxCoach CSV manifest fields"
        }

        val result = linkedMapOf<String, JsonElement>()
        result.putAll(manifest)
        result.putAll(files[LOGBOOK_FILE]?.let { splitLogbook(decodeArray(it)) } ?: emptyLogbook())
        result["climbNotes"] = files[NOTES_FILE]?.let(::decodeArray) ?: JsonArray(emptyList())
        result["climbLists"] = files[LISTS_FILE]?.let { splitLists(decodeArray(it)) }
            ?: JsonArray(emptyList())
        result.putAll(files[CLIMBS_FILE]?.let { splitOwnClimbs(decodeArray(it)) } ?: emptyOwnClimbs())
        return JsonObject(result).toString()
    }

    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    internal fun tablesFromJson(jsonString: String, categories: Set<Category>): List<ExportTable> {
        val root = json.parseToJsonElement(jsonString) as? JsonObject
            ?: error("CruxCoach export root must be an object")
        return tablesFromRoot(root, categories)
    }

    private fun tablesFromRoot(root: JsonObject, categories: Set<Category>): List<ExportTable> =
        buildList {
            if (Category.BOARD_LOGBOOK in categories) {
                add(ExportTable(LOGBOOK_FILE, "Logbook", combineLogbook(root).withReadableGrades()))
            }
            if (Category.BOARD_LOGBOOK in categories || Category.CLIMB_NOTES in categories) {
                val notes = root["climbNotes"] as? JsonArray ?: JsonArray(emptyList())
                add(ExportTable(NOTES_FILE, "Climb notes", notes))
            }
            if (Category.CLIMB_LISTS in categories) {
                add(ExportTable(LISTS_FILE, "Climb lists", combineLists(root)))
            }
            if (Category.OWN_CLIMBS in categories) {
                add(ExportTable(CLIMBS_FILE, "Own climbs", combineOwnClimbs(root).withReadableGrades()))
            }
        }

    private fun combineLogbook(root: JsonObject): JsonArray {
        val ascents = root["boardAscents"] as? JsonArray ?: JsonArray(emptyList())
        val attempts = root["boardBids"] as? JsonArray ?: JsonArray(emptyList())
        return JsonArray(
            ascents.map { JsonObject((it as JsonObject) + (ENTRY_TYPE to JsonPrimitive("send"))) } +
                attempts.map {
                    JsonObject((it as JsonObject) + (ENTRY_TYPE to JsonPrimitive("attempt")))
                },
        )
    }

    private fun splitLogbook(rows: JsonArray): Map<String, JsonElement> {
        val ascents = mutableListOf<JsonElement>()
        val attempts = mutableListOf<JsonElement>()
        rows.forEach { element ->
            val row = element as JsonObject
            val type = row.getValue(ENTRY_TYPE).asString()
            val clean = JsonObject(
                (row - ENTRY_TYPE)
                    .filterKeys { !isReadableGradeField(it) }
                    .filterValues { it !is JsonNull },
            )
            when (type) {
                "send" -> ascents += clean
                "attempt" -> attempts += clean
                else -> error("Unknown logbook entryType $type")
            }
        }
        return mapOf(
            "boardAscents" to JsonArray(ascents),
            "boardBids" to JsonArray(attempts),
        )
    }

    private fun combineLists(root: JsonObject): JsonArray {
        val lists = root["climbLists"] as? JsonArray ?: JsonArray(emptyList())
        return JsonArray(buildList {
            lists.forEachIndexed { listIndex, element ->
                val list = element as JsonObject
                add(JsonObject((list - "entries" - "playlistEntries") + mapOf(
                    ROW_TYPE to JsonPrimitive("list"),
                    LIST_ROW to JsonPrimitive(listIndex),
                )))
                (list["entries"] as? JsonArray).orEmpty().forEachIndexed { position, climbUuid ->
                    add(JsonObject(mapOf(
                        ROW_TYPE to JsonPrimitive("member"),
                        LIST_ROW to JsonPrimitive(listIndex),
                        POSITION to JsonPrimitive(position),
                        "climbUuid" to climbUuid,
                    )))
                }
                (list["playlistEntries"] as? JsonArray).orEmpty().forEachIndexed { position, entry ->
                    add(JsonObject((entry as JsonObject) + mapOf(
                        ROW_TYPE to JsonPrimitive("playlist"),
                        LIST_ROW to JsonPrimitive(listIndex),
                        POSITION to JsonPrimitive(position),
                    )))
                }
            }
        })
    }

    private fun splitLists(rows: JsonArray): JsonArray {
        val listHeads = linkedMapOf<Int, JsonObject>()
        val members = mutableListOf<JsonObject>()
        val playlist = mutableListOf<JsonObject>()
        rows.forEach { element ->
            val row = element as JsonObject
            val type = row.getValue(ROW_TYPE).asString()
            val listRow = row.getValue(LIST_ROW).asInt()
            when (type) {
                "list" -> {
                    val clean = JsonObject(
                        (row - ROW_TYPE - LIST_ROW - POSITION).filterValues { it !is JsonNull },
                    )
                    require(listHeads.put(listRow, clean) == null) { "Duplicate climb-list row" }
                }
                "member" -> members += row
                "playlist" -> playlist += row
                else -> error("Unknown climb-list rowType $type")
            }
        }
        require(listHeads.keys == (0 until listHeads.size).toSet()) { "Invalid climb-list order" }

        fun grouped(source: List<JsonObject>, type: String): Map<Int, List<JsonObject>> {
            val grouped = source.groupBy { row ->
                row.getValue(LIST_ROW).asInt().also { require(it in listHeads) {
                    "$type row refers to an unknown climb list"
                } }
            }
            grouped.values.forEach { orderedRows(it, type) }
            return grouped
        }

        val membersByList = grouped(members, "member")
        val playlistByList = grouped(playlist, "playlist")
        return JsonArray(listHeads.toSortedMap().map { (listRow, head) ->
            val entries = membersByList[listRow].orEmpty().sortedBy { it.getValue(POSITION).asInt() }
                .map { it.getValue("climbUuid") }
            val steps = playlistByList[listRow].orEmpty().sortedBy { it.getValue(POSITION).asInt() }
                .map {
                    JsonObject(
                        (it - ROW_TYPE - LIST_ROW - POSITION).filterValues { value ->
                            value !is JsonNull
                        },
                    )
                }
            JsonObject(head + mapOf(
                "entries" to JsonArray(entries),
                "playlistEntries" to JsonArray(steps),
            ))
        })
    }

    private fun combineOwnClimbs(root: JsonObject): JsonArray {
        val climbs = root["boardClimbs"] as? JsonArray ?: JsonArray(emptyList())
        val stats = root["boardClimbStats"] as? JsonArray ?: JsonArray(emptyList())
        val indexedStats = stats.mapIndexed { index, element -> index to (element as JsonObject) }
        val statsByClimb = indexedStats.groupBy { it.second.getValue("climbUuid").asString() }
        var matchedStats = 0
        val rows = buildList {
            climbs.forEachIndexed { climbIndex, element ->
                val climb = element as JsonObject
                val climbStats = statsByClimb[climb.getValue("uuid").asString()].orEmpty()
                if (climbStats.isEmpty()) {
                    add(JsonObject(climb + (CLIMB_ROW to JsonPrimitive(climbIndex))))
                } else {
                    climbStats.forEach { (statIndex, stat) ->
                        matchedStats++
                        val statFields = stat.filterKeys { it != "climbUuid" }
                            .mapKeys { (key, _) -> "$STAT_PREFIX$key" }
                        add(JsonObject(climb + statFields + mapOf(
                            CLIMB_ROW to JsonPrimitive(climbIndex),
                            STAT_ROW to JsonPrimitive(statIndex),
                        )))
                    }
                }
            }
        }
        require(matchedStats == stats.size) { "Own-climb stats refer to a missing climb" }
        return JsonArray(rows)
    }

    private fun splitOwnClimbs(rows: JsonArray): Map<String, JsonElement> {
        val grouped = rows.map { it as JsonObject }.groupBy { it.getValue(CLIMB_ROW).asInt() }
        require(grouped.keys == (0 until grouped.size).toSet()) { "Invalid own-climb order" }
        val climbs = mutableListOf<JsonElement>()
        val stats = mutableListOf<Pair<Int, JsonElement>>()
        grouped.toSortedMap().forEach { (_, group) ->
            val bases = group.map { row ->
                JsonObject(row.filterKeys {
                    it != CLIMB_ROW && it != STAT_ROW && !it.startsWith(STAT_PREFIX)
                })
            }
            require(bases.distinct().size == 1) { "Conflicting repeated own-climb data" }
            val climb = bases.first()
            climbs += climb
            group.forEach { row ->
                row[STAT_ROW].nonNull()?.let { statRow ->
                    val statFields = row.filterKeys { it.startsWith(STAT_PREFIX) }
                        .filterKeys { !isReadableGradeField(it) }
                        .filterValues { it !is JsonNull }
                        .mapKeys { (key, _) -> key.removePrefix(STAT_PREFIX) }
                    require("angle" in statFields) { "Own-climb stat has no angle" }
                    stats += statRow.asInt() to JsonObject(
                        mapOf("climbUuid" to climb.getValue("uuid")) + statFields,
                    )
                }
            }
        }
        require(stats.map { it.first }.sorted() == (0 until stats.size).toList()) {
            "Invalid own-climb stat order"
        }
        return mapOf(
            "boardClimbs" to JsonArray(climbs),
            "boardClimbStats" to JsonArray(stats.sortedBy { it.first }.map { it.second }),
        )
    }

    private fun orderedRows(rows: List<JsonObject>, label: String) {
        rows.sortedBy { it.getValue(POSITION).asInt() }.forEachIndexed { expected, row ->
            require(row.getValue(POSITION).asInt() == expected) { "Invalid $label row order" }
        }
    }

    private fun JsonArray.withReadableGrades(): JsonArray = JsonArray(map { element ->
        val row = element as JsonObject
        val readable = buildMap<String, JsonElement> {
            row.forEach { (field, value) ->
                if (!isInternalGradeField(field)) return@forEach
                val difficulty = (value as? JsonPrimitive)
                    ?.takeUnless { it.isString || it.booleanOrNull != null }
                    ?.content
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: return@forEach
                put("${field}Fb", JsonPrimitive(KilterGradeMapper.difficultyToFont(difficulty)))
                put("${field}V", JsonPrimitive(KilterGradeMapper.difficultyToVScale(difficulty)))
            }
        }
        JsonObject(row + readable)
    })

    private fun isInternalGradeField(field: String): Boolean =
        field.removePrefix(STAT_PREFIX) in internalGradeFields

    private fun isReadableGradeField(field: String): Boolean =
        readableGradeSuffixes.any { suffix ->
            field.endsWith(suffix) && isInternalGradeField(field.removeSuffix(suffix))
        }

    internal fun spreadsheetBaseField(field: String): String =
        readableGradeSuffixes.firstNotNullOfOrNull { suffix ->
            field.removeSuffix(suffix).takeIf {
                field.endsWith(suffix) && isInternalGradeField(it)
            }
        } ?: field

    internal val spreadsheetFieldComparator: Comparator<String> =
        compareBy<String> { spreadsheetBaseField(it) }
            .thenBy { field ->
                when {
                    field.endsWith("Fb") && isReadableGradeField(field) -> 1
                    field.endsWith("V") && isReadableGradeField(field) -> 2
                    else -> 0
                }
            }
            .thenBy { it }

    private fun emptyLogbook(): Map<String, JsonElement> = mapOf(
        "boardAscents" to JsonArray(emptyList()),
        "boardBids" to JsonArray(emptyList()),
    )

    private fun emptyOwnClimbs(): Map<String, JsonElement> = mapOf(
        "boardClimbs" to JsonArray(emptyList()),
        "boardClimbStats" to JsonArray(emptyList()),
    )

    private fun encodeArray(array: JsonArray): String {
        val objects = array.map { it as? JsonObject ?: error("CSV row must be an object") }
        val fields = objects.flatMap { it.keys }.distinct().sortedWith(spreadsheetFieldComparator)
        val headers = listOf(ROW_COLUMN) + fields
        val types = listOf("number") + fields.map { field ->
            objects.asSequence().mapNotNull { it[field] }.firstOrNull { it !is JsonNull }
                ?.let(::typeOf) ?: "string"
        }
        val rows = mutableListOf(headers, types)
        objects.forEachIndexed { index, obj ->
            rows += listOf(index.toString()) + fields.map { encodeCell(obj[it] ?: JsonNull) }
        }
        return encodeCsv(rows)
    }

    private fun decodeArray(csv: String): JsonArray {
        val rows = parseCsv(csv)
        require(rows.size in 2..MAX_ROWS_PER_FILE + 2) { "Invalid CruxCoach CSV" }
        val headers = rows[0]
        val types = rows[1]
        require(headers.firstOrNull() == ROW_COLUMN && headers.size == types.size) {
            "Invalid CruxCoach CSV header"
        }
        require(headers.distinct().size == headers.size) { "Duplicate CruxCoach CSV column" }
        return JsonArray(rows.drop(2).mapIndexed { expectedIndex, row ->
            require(row.size == headers.size && row[0].toIntOrNull() == expectedIndex) {
                "Invalid CruxCoach CSV row"
            }
            JsonObject(headers.drop(1).indices.associate { offset ->
                headers[offset + 1] to decodeCell(types[offset + 1], row[offset + 1])
            })
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
                quoted && char == '"' && value.getOrNull(index + 1) == '"' -> {
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

    private fun spreadsheetSafe(value: String): String = when {
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

    private fun JsonElement?.nonNull(): JsonElement? = this?.takeUnless { it is JsonNull }
    private fun JsonElement.asString(): String = (this as JsonPrimitive).contentOrNull
        ?: error("Expected CSV string")
    private fun JsonElement.asInt(): Int = (this as JsonPrimitive).content.toInt()

    private val formulaPrefixes = setOf('=', '+', '-', '@', '\t', '\r')
    private val internalGradeFields = setOf(
        "difficulty",
        "difficultyAverage",
        "displayDifficulty",
        "benchmarkDifficulty",
    )
    private val readableGradeSuffixes = setOf("Fb", "V")
}
