package com.cruxcoach.app.backup

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

/**
 * The export side of Android's `CruxCoachCsvArchive` (Settings → data export →
 * CSV ZIP): a manifest plus focused CSV tables, derived from the same JSON
 * export. Same file names, columns, typed second header row, `;` separator,
 * `\N` for null and formula-injection guard, so Android's importer reads it.
 *
 * Android writes the ZIP with `java.util.zip` (deflate); this writes STORED
 * entries with CRC-32 and sizes in the local headers, which Android's
 * `ZipInputStream`-based importer and every common unzip tool read. The
 * 64 MiB cap is Android's importer limit, so here it bounds the uncompressed
 * tables — a very large logbook that Android would deflate below it fails here.
 */
object CruxCoachCsvArchive {
    const val MIME_TYPE = "application/zip"
    const val FILE_NAME = "cruxcoach_csv_export.zip"
    const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024

    private const val MANIFEST_FILE = "manifest.json"
    private const val LOGBOOK_FILE = "board_logbook.csv"
    private const val NOTES_FILE = "climb_notes.csv"
    private const val LISTS_FILE = "climb_lists.csv"
    private const val CLIMBS_FILE = "own_climbs.csv"
    private const val NULL_CELL = "\\N"
    private const val ROW_COLUMN = "_row"
    private const val ENTRY_TYPE = "entryType"
    private const val ROW_TYPE = "rowType"
    private const val LIST_ROW = "listRow"
    private const val POSITION = "position"
    private const val CLIMB_ROW = "climbRow"
    private const val STAT_ROW = "statRow"
    private const val STAT_PREFIX = "stat_"

    /** What iOS exports (it has no category picker): every table the CSV format has. */
    val ALL_CATEGORIES: Set<Category> = setOf(
        Category.BOARD_LOGBOOK,
        Category.CLIMB_NOTES,
        Category.CLIMB_LISTS,
        Category.OWN_CLIMBS,
    )

    private val json = Json { allowSpecialFloatingPointValues = true }

    internal class ExportTable(val fileName: String, val sheetName: String, val rows: JsonArray)

    /** The tables shared with [CruxCoachExcelWorkbook], as on Android. */
    internal fun tablesFromJson(jsonString: String, categories: Set<Category>): List<ExportTable> {
        val root = json.parseToJsonElement(jsonString) as? JsonObject
            ?: error("CruxCoach export root must be an object")
        return tablesFromRoot(root, categories)
    }

    /** Throws on malformed export JSON; [FileExchangePresenter] turns that into EXPORT_FAILED. */
    fun fromJson(jsonString: String, categories: Set<Category>): ByteArray {
        val root = json.parseToJsonElement(jsonString) as? JsonObject
            ?: error("CruxCoach export root must be an object")
        val zip = StoredZipWriter()
        val manifest = JsonObject(root.filterValues { it !is JsonArray && it !is JsonObject })
        zip.add(MANIFEST_FILE, manifest.toString().encodeToByteArray())
        tablesFromRoot(root, categories).forEach { table ->
            zip.add(table.fileName, encodeArray(table.rows).encodeToByteArray())
        }
        return zip.finish().also {
            require(it.size <= MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive is too large" }
        }
    }

    // Sheet names are Android's literal (untranslated) ones, so both apps' workbooks match.
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
                attempts.map { JsonObject((it as JsonObject) + (ENTRY_TYPE to JsonPrimitive("attempt"))) },
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
            field.removeSuffix(suffix).takeIf { field.endsWith(suffix) && isInternalGradeField(it) }
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

    private fun encodeCsv(rows: List<List<String>>): String =
        rows.joinToString("\n", postfix = "\n") { row ->
            row.joinToString(";") { value ->
                if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
                    "\"${value.replace("\"", "\"\"")}\""
                } else {
                    value
                }
            }
        }

    private fun spreadsheetSafe(value: String): String = when {
        value == NULL_CELL -> "\\$value"
        value.firstOrNull() in formulaPrefixes -> "'$value"
        value.startsWith("\\") -> "\\$value"
        else -> value
    }

    private fun JsonElement.asString(): String = (this as JsonPrimitive).contentOrNull
        ?: error("Expected CSV string")

    private val formulaPrefixes = setOf('=', '+', '-', '@', '\t', '\r')
    private val internalGradeFields = setOf(
        "difficulty",
        "difficultyAverage",
        "displayDifficulty",
        "benchmarkDifficulty",
    )
    private val readableGradeSuffixes = setOf("Fb", "V")
}

/**
 * Minimal ZIP writer: STORED entries, CRC-32 and sizes in each local header
 * (no data descriptor), central directory, end record. No ZIP64 — the archive
 * is capped far below 4 GiB. Names are ASCII, so the UTF-8 flag is not needed.
 */
internal class StoredZipWriter {
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)

    private val out = ArrayList<ByteArray>()
    private var length = 0
    private val entries = ArrayList<Entry>()

    fun add(name: String, data: ByteArray) {
        require(name.all { it.code in 0x20..0x7e } && name.isNotEmpty())
        val nameBytes = name.encodeToByteArray()
        val crc = Crc32.of(data)
        val header = ByteArray(30 + nameBytes.size)
        writeInt(header, 0, 0x04034b50)
        writeShort(header, 4, 10) // version needed: 1.0 (stored)
        writeShort(header, 6, 0) // flags
        writeShort(header, 8, 0) // method: stored
        writeShort(header, 10, 0) // mod time 00:00:00
        writeShort(header, 12, DOS_DATE_1980_01_01)
        writeInt(header, 14, crc)
        writeInt(header, 18, data.size)
        writeInt(header, 22, data.size)
        writeShort(header, 26, nameBytes.size)
        writeShort(header, 28, 0)
        nameBytes.copyInto(header, 30)
        entries += Entry(nameBytes, crc, data.size, length)
        append(header)
        append(data)
    }

    fun finish(): ByteArray {
        val directoryOffset = length
        for (entry in entries) {
            val record = ByteArray(46 + entry.name.size)
            writeInt(record, 0, 0x02014b50)
            writeShort(record, 4, 20) // made by: MS-DOS, spec 2.0
            writeShort(record, 6, 10)
            writeShort(record, 8, 0)
            writeShort(record, 10, 0)
            writeShort(record, 12, 0)
            writeShort(record, 14, DOS_DATE_1980_01_01)
            writeInt(record, 16, entry.crc)
            writeInt(record, 20, entry.size)
            writeInt(record, 24, entry.size)
            writeShort(record, 28, entry.name.size)
            // extra, comment, disk, internal attributes, external attributes: zero.
            writeInt(record, 42, entry.offset)
            entry.name.copyInto(record, 46)
            append(record)
        }
        val directorySize = length - directoryOffset
        val end = ByteArray(22)
        writeInt(end, 0, 0x06054b50)
        writeShort(end, 8, entries.size)
        writeShort(end, 10, entries.size)
        writeInt(end, 12, directorySize)
        writeInt(end, 16, directoryOffset)
        append(end)
        val result = ByteArray(length)
        var offset = 0
        for (part in out) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }

    private fun append(bytes: ByteArray) {
        require(length.toLong() + bytes.size < Int.MAX_VALUE)
        out += bytes
        length += bytes.size
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        /** 1980-01-01, the DOS epoch: the archive carries no wall-clock time. */
        const val DOS_DATE_1980_01_01 = (0 shl 9) or (1 shl 5) or 1
    }
}

/** CRC-32 (ISO-HDLC, the ZIP polynomial 0xEDB88320). */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Int {
        var crc = -1
        for (b in data) crc = table[(crc xor b.toInt()) and 0xff] xor (crc ushr 8)
        return crc.inv()
    }
}
