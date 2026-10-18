package com.cruxcoach.app.backup

import com.cruxcoach.data.CruxCoachBackup.Category
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Port of Android's `CruxCoachCsvArchiveTest` (export side) plus ZIP compatibility checks. */
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
    fun `the stored zip is read by ZipFile too and carries correct crcs`() {
        val archive = CruxCoachCsvArchive.fromJson(source, CruxCoachCsvArchive.ALL_CATEGORIES)
        val streamed = unzipStream(archive)
        val file = File.createTempFile("cruxcoach-csv", ".zip")
        try {
            file.writeBytes(archive)
            ZipFile(file).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(listOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv"), names)
                for (entry in zip.entries()) {
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
