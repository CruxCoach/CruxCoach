package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup.Category
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CruxCoachCsvArchiveTest {
    private val json = Json

    @Test
    fun `archive separates climb notes from logbook entries and restores all record types`() {
        val source = """
            {
              "version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z",
              "boardAscents":[{"climbUuid":"climb-1","angle":40,"listed":true,"comment":"Felt easy"}],
              "boardBids":[{"climbUuid":"climb-2","angle":35,"comment":"Missed the top"}],
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
              "boardClimbStats":[{"climbUuid":"climb-4","angle":40,"ascents":2}]
            }
        """.trimIndent()

        val archive = CruxCoachCsvArchive.fromJson(
            source,
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS, Category.OWN_CLIMBS),
        )
        val files = unzip(archive)

        assertEquals(
            setOf(
                "manifest.json",
                "board_logbook.csv",
                "climb_notes.csv",
                "climb_lists.csv",
                "own_climbs.csv",
            ),
            files.keys,
        )
        assertEquals(4, files.keys.count { it.endsWith(".csv") })
        assertTrue(files.getValue("board_logbook.csv").contains("entryType"))
        assertTrue(files.getValue("board_logbook.csv").contains("comment"))
        assertTrue(!files.getValue("board_logbook.csv").contains("personalNote"))
        assertTrue(files.getValue("climb_notes.csv").contains("updatedAt"))
        assertTrue(files.getValue("climb_notes.csv").contains("Try left foot"))
        assertTrue(files.getValue("climb_lists.csv").contains("rowType"))
        assertTrue(files.getValue("climb_lists.csv").contains("'=Power; plan"))
        assertTrue(files.getValue("own_climbs.csv").contains("stat_angle"))

        val restored = json.parseToJsonElement(CruxCoachCsvArchive.toJson(archive)) as JsonObject
        assertEquals(1, restored.array("boardAscents").size)
        assertEquals(1, restored.array("boardBids").size)
        assertEquals(2, restored.array("climbNotes").size)
        val restoredList = restored.array("climbLists").single() as JsonObject
        assertEquals("=Power; plan", restoredList.getValue("name").jsonPrimitive.content)
        assertEquals(1, restoredList.array("entries").size)
        assertEquals(1, restoredList.array("playlistEntries").size)
        assertEquals(1, restored.array("boardClimbs").size)
        assertEquals(1, restored.array("boardClimbStats").size)
    }

    @Test
    fun `archive contains only the selected category CSV`() {
        val source = """{"version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z"}"""
        val names = unzip(CruxCoachCsvArchive.fromJson(source, setOf(Category.CLIMB_LISTS))).keys

        assertEquals(setOf("manifest.json", "climb_lists.csv"), names)
    }

    @Test
    fun `import rejects unexpected archive paths`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            CruxCoachCsvArchive.toJson(output.toByteArray())
        }
    }

    private fun JsonObject.array(name: String): JsonArray = getValue(name) as JsonArray

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
