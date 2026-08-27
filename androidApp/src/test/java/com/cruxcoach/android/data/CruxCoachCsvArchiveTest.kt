package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CruxCoachCsvArchiveTest {
    private val json = Json

    @Test
    fun `archive separates record types and round trips nested list data`() {
        val source = """
            {
              "version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z",
              "boardAscents":[],"boardBids":[],"climbNotes":[],
              "climbLists":[{
                "name":"=Power; plan","isBuiltin":false,"createdAt":"2026-08-27T10:00:00Z",
                "entries":["aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"],
                "playlistEntries":[{"climbUuid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","entryType":"climb","restSeconds":null,"angle":40}]
              }],
              "boardClimbs":[],"boardClimbStats":[]
            }
        """.trimIndent()

        val archive = CruxCoachCsvArchive.fromJson(
            source,
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS, Category.OWN_CLIMBS),
        )
        val files = unzip(archive)
        val names = files.keys

        assertTrue("board_sends.csv" in names)
        assertTrue("climb_lists.csv" in names)
        assertTrue("climb_list_entries.csv" in names)
        assertTrue("playlist_entries.csv" in names)
        assertTrue(files.getValue("climb_lists.csv").lineSequence().first().contains("createdAt"))
        assertTrue(!files.getValue("climb_lists.csv").lineSequence().first().contains("entries"))
        assertTrue(files.getValue("climb_lists.csv").contains("'=Power; plan"))
        assertEquals(
            json.parseToJsonElement(source),
            json.parseToJsonElement(CruxCoachCsvArchive.toJson(archive)),
        )
        assertEquals(1, CruxCoachBackup.preview(CruxCoachCsvArchive.toJson(archive)).climbLists)
    }

    @Test
    fun `archive contains only selected category tables`() {
        val source = """{"version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z"}"""
        val names = unzip(CruxCoachCsvArchive.fromJson(source, setOf(Category.CLIMB_LISTS))).keys

        assertEquals(
            setOf("metadata.csv", "climb_lists.csv", "climb_list_entries.csv", "playlist_entries.csv"),
            names,
        )
    }

    @Test
    fun `import rejects unexpected archive paths`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../metadata.csv"))
            zip.write("field;type;value\n".toByteArray())
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            CruxCoachCsvArchive.toJson(output.toByteArray())
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}
