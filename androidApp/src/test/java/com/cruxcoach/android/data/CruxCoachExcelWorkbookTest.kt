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
              "boardAscents":[{"climbUuid":"climb-1","comment":"Felt <easy> & fun","difficulty":18,"difficultyAverage":20.5}],
              "boardBids":[{"climbUuid":"climb-2","comment":"=not a formula","difficultyAverage":24}],
              "climbNotes":[{"climbUuid":"climb-1","note":"Use heel","updatedAt":"2026-08-27T10:01:00Z"}],
              "climbLists":[{"name":"Projects","entries":["climb-1"],"playlistEntries":[]}],
              "boardClimbs":[{"uuid":"climb-3","name":"My climb"}],
              "boardClimbStats":[{"climbUuid":"climb-3","angle":40,"ascents":2,"displayDifficulty":22,"difficultyAverage":24}]
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
                "xl/sharedStrings.xml",
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

        val sharedStrings = files.getValue("xl/sharedStrings.xml")
        assertTrue(sharedStrings.contains("Felt &lt;easy&gt; &amp; fun"))
        assertTrue(sharedStrings.contains("=not a formula"))
        assertTrue(sharedStrings.contains("Use heel"))
        assertTrue(sharedStrings.contains("stat_angle"))
        assertTrue(sharedStrings.contains("difficultyFb"))
        assertTrue(sharedStrings.contains("difficultyV"))
        assertTrue(sharedStrings.contains("difficultyAverageFb"))
        assertTrue(sharedStrings.contains("difficultyAverageV"))
        assertTrue(sharedStrings.contains("stat_displayDifficultyFb"))
        assertTrue(sharedStrings.contains("stat_displayDifficultyV"))
        assertTrue(sharedStrings.contains("6b"))
        assertTrue(sharedStrings.contains("V4"))
        assertTrue(sharedStrings.contains("6c+"))
        assertTrue(sharedStrings.contains("V5"))
        assertTrue(files.getValue("xl/worksheets/sheet1.xml").contains("t=\"s\""))
        assertFalse(files.values.any { it.contains("<f>") })
        assertFalse(files.values.any { it.contains("inlineStr") })
        assertTrue(files.getValue("xl/styles.xml").contains("name=\"Normal\""))

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
