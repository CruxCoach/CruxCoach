package com.cruxcoach.app.backup

import com.cruxcoach.app.zip.Crc32
import com.cruxcoach.app.zip.ZipWriter
import com.cruxcoach.data.CruxCoachBackup.Category
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Port of Android's `CruxCoachCsvArchiveTest` (export and import) plus ZIP compatibility checks both ways. */
class CsvArchiveTest {
    private val source = """
        {
          "version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z",
          "boardAscents":[{"climbUuid":"climb-1","angle":40,"listed":true,"comment":"Felt easy","difficulty":18,"difficultyAverage":20.5}],
          "boardBids":[{"climbUuid":"climb-2","angle":35,"comment":"Missed the top","difficultyAverage":24}],
          "climbNotes":[
            {"climbUuid":"climb-1","note":"Hard start","updatedAt":"2026-08-27T10:01:00Z"},
            {"climbUuid":"climb-3","note":"Try left foot","updatedAt":"2026-08-27T10:02:00Z"}
          ],
          "climbLists":[{
            "name":"=Power; plan","isBuiltin":false,"createdAt":"2026-08-27T10:00:00Z",
            "entries":["climb-1"],
            "playlistEntries":[{"climbUuid":"climb-2","entryType":"climb","restSeconds":null,"angle":40}]
          }],
          "boardClimbs":[{"uuid":"climb-4","name":"My climb"}],
          "boardClimbStats":[{"climbUuid":"climb-4","angle":40,"ascents":2,"displayDifficulty":22,"difficultyAverage":24}]
        }
    """.trimIndent()

    @Test
    fun `archive separates climb notes from logbook entries as on Android`() {
        val archive = CruxCoachCsvArchive.fromJson(
            source,
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS, Category.OWN_CLIMBS),
        )
        val files = unzipStream(archive)
        assertEquals(
            setOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv"),
            files.keys,
        )
        val logbook = files.getValue("board_logbook.csv")
        for (expected in listOf("entryType", "comment", "difficultyFb", "difficultyV", "difficultyAverageFb", "difficultyAverageV", "6b", "V4", "6c+", "V5")) {
            assertTrue(logbook.contains(expected), expected)
        }
        assertTrue(!logbook.contains("personalNote"))
        assertTrue(files.getValue("climb_notes.csv").contains("updatedAt"))
        assertTrue(files.getValue("climb_notes.csv").contains("Try left foot"))
        assertTrue(files.getValue("climb_lists.csv").contains("rowType"))
        // Formula guard and quoting of the separator.
        assertTrue(files.getValue("climb_lists.csv").contains("\"'=Power; plan\""))
        for (expected in listOf("stat_angle", "stat_displayDifficultyFb", "stat_displayDifficultyV", "stat_difficultyAverageFb", "stat_difficultyAverageV")) {
            assertTrue(files.getValue("own_climbs.csv").contains(expected), expected)
        }
        assertEquals("""{"version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z"}""", files.getValue("manifest.json"))
    }

    @Test
    fun `the csv layout follows Android's encoder`() {
        val files = unzipStream(CruxCoachCsvArchive.fromJson(source, setOf(Category.BOARD_LOGBOOK)))
        assertEquals(
            "_row;angle;climbUuid;comment;difficulty;difficultyFb;difficultyV;difficultyAverage;difficultyAverageFb;difficultyAverageV;entryType;listed\n" +
                "number;number;string;string;number;string;string;number;string;string;string;boolean\n",
            files.getValue("board_logbook.csv").lines().take(2).joinToString("\n", postfix = "\n"),
        )
        assertEquals(
            "_row;climbUuid;note;updatedAt\n" +
                "number;string;string;string\n" +
                "0;climb-1;Hard start;2026-08-27T10:01:00Z\n" +
                "1;climb-3;Try left foot;2026-08-27T10:02:00Z\n",
            files.getValue("climb_notes.csv"),
        )
    }

    @Test
    fun `archive contains only the selected category csv`() {
        val names = unzipStream(
            CruxCoachCsvArchive.fromJson("""{"version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z"}""", setOf(Category.CLIMB_LISTS)),
        ).keys
        assertEquals(setOf("manifest.json", "climb_lists.csv"), names)
    }

    @Test
    fun `stats without their climb and a non-object root are refused`() {
        assertFailsWith<IllegalArgumentException> {
            CruxCoachCsvArchive.fromJson(
                """{"boardClimbs":[],"boardClimbStats":[{"climbUuid":"x","angle":40}]}""",
                setOf(Category.OWN_CLIMBS),
            )
        }
        assertFailsWith<IllegalStateException> { CruxCoachCsvArchive.fromJson("[]", CruxCoachCsvArchive.ALL_CATEGORIES) }
    }

    @Test
    fun `the deflated zip is read by ZipFile too and carries correct crcs`() {
        val archive = CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)
        val streamed = unzipStream(archive)
        val file = File.createTempFile("cruxcoach-csv", ".zip")
        try {
            file.writeBytes(archive)
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv"), names)
                for (entry in zip.entries()) {
                    assertEquals(ZipEntry.DEFLATED, entry.method, entry.name)
                    val bytes = zip.getInputStream(entry).readBytes()
                    assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                    assertContentEquals(streamed.getValue(entry.name).encodeToByteArray(), bytes)
                }
            }
        } finally {
            file.delete()
        }
        assertEquals(CRC32().apply { update("123456789".encodeToByteArray()) }.value.toInt(), Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun `archive separates climb notes from logbook entries and restores all record types`() {
        // Android's test, unchanged apart from kotlin.test.
        val archive = CruxCoachCsvArchive.fromJson(
            source,
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS, Category.OWN_CLIMBS),
        )
        val restored = Json.parseToJsonElement(CruxCoachCsvArchive.toJson(archive)) as JsonObject
        assertEquals(1, restored.array("boardAscents").size)
        assertEquals(1, restored.array("boardBids").size)
        assertTrue((restored.array("boardAscents").single() as JsonObject).keys.none {
            it.endsWith("Fb") || it.endsWith("V")
        })
        assertEquals(2, restored.array("climbNotes").size)
        val restoredList = restored.array("climbLists").single() as JsonObject
        assertEquals("=Power; plan", restoredList.getValue("name").jsonPrimitive.content)
        assertEquals(1, restoredList.array("entries").size)
        assertEquals(1, restoredList.array("playlistEntries").size)
        assertEquals(1, restored.array("boardClimbs").size)
        assertEquals(1, restored.array("boardClimbStats").size)
        assertTrue((restored.array("boardClimbStats").single() as JsonObject).keys.none {
            it.endsWith("Fb") || it.endsWith("V")
        })
    }

    @Test
    fun `import rejects unexpected archive paths`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(output.toByteArray()) }
    }

    @Test
    fun `the round trip restores the export apart from null fields and readable grades`() {
        // What both apps' importers produce: null cells dropped from logbook, list and stat rows.
        val expected = Json.parseToJsonElement(source.replace("\"restSeconds\":null,", ""))
        val restored = Json.parseToJsonElement(
            CruxCoachCsvArchive.toJson(CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)),
        )
        assertEquals(expected, restored)
        assertTrue(CruxCoachCsvArchive.looksLikeZip(CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)))
        assertTrue(!CruxCoachCsvArchive.looksLikeZip(source.encodeToByteArray()))
    }

    @Test
    fun `an archive written the way Android writes it imports`() {
        // Android's writeEntry: ZipOutputStream, default (deflate) method, data descriptors.
        val ours = CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)
        val android = androidZip(unzipStream(ours).toList())
        assertTrue(android.contentEquals(ours).not())
        assertEquals(CruxCoachCsvArchive.toJson(ours), CruxCoachCsvArchive.toJson(android))

        // Hand-written Android-format tables: quoting, the formula guard, an escaped "\N"
        // string, CRLF line ends (a spreadsheet re-save) and readable grade columns.
        val logbook = "_row;angle;climbUuid;comment;difficulty;difficultyFb;difficultyV;entryType;listed\r\n" +
            "number;number;string;string;number;string;string;string;boolean\r\n" +
            "0;40;climb-1;\"Felt; \"\"easy\"\"\";18;6b;V4;send;true\r\n" +
            "1;35;climb-2;'=SUM(A1);\\N;\\N;\\N;attempt;\\N\r\n" +
            "2;30;climb-3;\\\\N;\\N;\\N;\\N;attempt;false\r\n"
        val lists = "_row;climbUuid;listRow;name;position;rowType\n" +
            "number;string;number;string;number;string\n" +
            "0;\\N;0;Projects;\\N;list\n" +
            "1;climb-2;0;\\N;1;member\n" +
            "2;climb-1;0;\\N;0;member\n"
        val archive = androidZip(listOf(
            "manifest.json" to """{"version":3,"app":"CruxCoach","nostrPubkey":"abcd"}""",
            "board_logbook.csv" to logbook,
            "climb_lists.csv" to lists,
        ))
        assertEquals(
            Json.parseToJsonElement(
                """
                {"version":3,"app":"CruxCoach","nostrPubkey":"abcd",
                 "boardAscents":[{"angle":40,"climbUuid":"climb-1","comment":"Felt; \"easy\"","difficulty":18,"listed":true}],
                 "boardBids":[{"angle":35,"climbUuid":"climb-2","comment":"=SUM(A1)"},
                              {"angle":30,"climbUuid":"climb-3","comment":"\\N","listed":false}],
                 "climbNotes":[],
                 "climbLists":[{"name":"Projects","entries":["climb-1","climb-2"],"playlistEntries":[]}],
                 "boardClimbs":[],"boardClimbStats":[]}
                """.trimIndent(),
            ),
            Json.parseToJsonElement(CruxCoachCsvArchive.toJson(archive)),
        )
    }

    @Test
    fun `Android's importer reads the iOS archive`() {
        val archive = CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)
        val files = androidUnzip(archive)
        assertEquals(
            listOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv"),
            files.keys.toList(),
        )
        assertEquals(unzipStream(archive), files)
    }

    @Test
    fun `malformed archives and tables are refused`() {
        val manifest = "manifest.json" to """{"version":3,"app":"CruxCoach"}"""
        // No manifest.
        assertFails { CruxCoachCsvArchive.toJson(androidZip(listOf("climb_notes.csv" to "_row\nnumber\n"))) }
        // A manifest with nested data.
        assertFails { CruxCoachCsvArchive.toJson(androidZip(listOf("manifest.json" to """{"boardAscents":[]}"""))) }
        // A directory entry.
        assertFailsWith<IllegalArgumentException> {
            CruxCoachCsvArchive.toJson(androidZip(listOf(manifest, "climb_lists/" to "")))
        }
        // The same file twice (ZipOutputStream refuses to write that, so ZipWriter does).
        val duplicate = ZipWriter().apply {
            add("manifest.json", manifest.second.encodeToByteArray())
            add("manifest.json", manifest.second.encodeToByteArray())
        }.finish()
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(duplicate) }
        // Android's readable XLSX is a ZIP too, but export-only.
        val workbook = CruxCoachExcelWorkbook.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)
        assertTrue(CruxCoachCsvArchive.looksLikeZip(workbook))
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(workbook) }
        // Rows out of order, an unknown entry type, a boolean in a number column, an unterminated quote.
        val header = "_row;climbUuid;entryType;angle\nnumber;string;string;number\n"
        for (rows in listOf("1;c;send;40\n", "0;c;flash;40\n", "0;c;send;true\n", "0;\"c;send;40\n")) {
            assertFails(rows) { CruxCoachCsvArchive.toJson(androidZip(listOf(manifest, "board_logbook.csv" to header + rows))) }
        }
        // A list member pointing at no list.
        val lists = "_row;climbUuid;listRow;position;rowType\nnumber;string;number;number;string\n0;c;3;0;member\n"
        assertFails { CruxCoachCsvArchive.toJson(androidZip(listOf(manifest, "climb_lists.csv" to lists))) }
    }

    @Test
    fun `Android's size and row limits hold`() {
        val manifest = "manifest.json" to """{"version":3,"app":"CruxCoach"}"""
        // The archive itself.
        assertFailsWith<IllegalArgumentException> {
            CruxCoachCsvArchive.toJson(ByteArray(CruxCoachCsvArchive.MAX_ARCHIVE_BYTES + 1).also { it[0] = 0x50; it[1] = 0x4b; it[2] = 3; it[3] = 4 })
        }
        // One entry that inflates past 64 MiB (a few KB compressed): refused before inflating.
        val bomb = androidZipBytes(listOf(manifest.first to manifest.second.encodeToByteArray(), "climb_notes.csv" to ByteArray(CruxCoachCsvArchive.MAX_ARCHIVE_BYTES + 1)))
        assertTrue(bomb.size < 200_000)
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(bomb) }
        // Two entries that together pass the limit.
        val half = ByteArray(CruxCoachCsvArchive.MAX_ARCHIVE_BYTES / 2 + 1)
        val pair = androidZipBytes(listOf(manifest.first to manifest.second.encodeToByteArray(), "climb_notes.csv" to half, "board_logbook.csv" to half))
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(pair) }
        // More than 250 000 rows in one table.
        val rows = buildString {
            append("_row;climbUuid;note;updatedAt\nnumber;string;string;string\n")
            repeat(250_001) { append("x\n") }
        }
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(androidZip(listOf(manifest, "climb_notes.csv" to rows))) }
        // Six files, one more than the format has.
        val six = androidZip(listOf(manifest, "board_logbook.csv" to "", "climb_notes.csv" to "", "climb_lists.csv" to "", "own_climbs.csv" to "", "extra.csv" to ""))
        assertFailsWith<IllegalArgumentException> { CruxCoachCsvArchive.toJson(six) }
    }

    /** Android's `CruxCoachCsvArchive.unzip`, verbatim apart from the test's names. */
    private fun androidUnzip(archive: ByteArray): Map<String, String> {
        val allowedFiles = setOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv")
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
                require(++entryCount <= 5) { "Too many files in CruxCoach CSV archive" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= CruxCoachCsvArchive.MAX_ARCHIVE_BYTES) { "CruxCoach CSV archive expands too large" }
                    output.write(buffer, 0, count)
                }
                files[entry.name] = output.toString(Charsets.UTF_8.name())
                zip.closeEntry()
            }
        }
        return files
    }

    /** Android's `writeEntry` for each file, in order. */
    private fun androidZip(files: List<Pair<String, String>>): ByteArray =
        androidZipBytes(files.map { it.first to it.second.toByteArray(Charsets.UTF_8) })

    private fun androidZipBytes(files: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun JsonObject.array(name: String): JsonArray = getValue(name) as JsonArray

    /** What Android's importer uses: `ZipInputStream`. */
    private fun unzipStream(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
