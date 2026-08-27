package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class CruxCoachExcelWorkbookTest {
    @Test
    fun `workbook contains readable category sheets and safe inline strings`() {
        val source = """
            {
              "version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z",
              "boardAscents":[{"climbUuid":"climb-1","comment":"Felt <easy> & fun"}],
              "boardBids":[{"climbUuid":"climb-2","comment":"=not a formula"}],
              "climbNotes":[{"climbUuid":"climb-1","note":"Use heel","updatedAt":"2026-08-27T10:01:00Z"}],
              "climbLists":[{"name":"Projects","entries":["climb-1"],"playlistEntries":[]}],
              "boardClimbs":[{"uuid":"climb-3","name":"My climb"}],
              "boardClimbStats":[{"climbUuid":"climb-3","angle":40,"ascents":2}]
            }
        """.trimIndent()

        val workbook = CruxCoachExcelWorkbook.fromJson(
            source,
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS, Category.OWN_CLIMBS),
        )
        val files = unzip(workbook)

        assertTrue(CruxCoachCsvArchive.looksLikeZip(workbook))
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet4.xml",
            ),
            files.keys,
        )
        val workbookXml = files.getValue("xl/workbook.xml")
        assertTrue(workbookXml.contains("name=\"Logbook\""))
        assertTrue(workbookXml.contains("name=\"Climb notes\""))
        assertTrue(workbookXml.contains("name=\"Climb lists\""))
        assertTrue(workbookXml.contains("name=\"Own climbs\""))

        val logbookSheet = files.getValue("xl/worksheets/sheet1.xml")
        assertTrue(logbookSheet.contains("Felt &lt;easy&gt; &amp; fun"))
        assertTrue(logbookSheet.contains("=not a formula"))
        assertFalse(logbookSheet.contains("<f>"))
        assertTrue(files.getValue("xl/worksheets/sheet2.xml").contains("Use heel"))
        assertTrue(files.getValue("xl/worksheets/sheet4.xml").contains("stat_angle"))

        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { xml ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
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
