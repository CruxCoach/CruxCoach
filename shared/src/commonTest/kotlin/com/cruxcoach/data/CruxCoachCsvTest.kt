package com.cruxcoach.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CruxCoachCsvTest {
    private val json = Json

    @Test
    fun `json csv json round trip preserves nested export values`() {
        val source = """
            {
              "version":3,
              "app":"CruxCoach",
              "exportedAt":"2026-08-27T10:00:00Z",
              "nostrPubkey":null,
              "boardAscents":[{"uuid":"a","angle":40,"isMirror":false,"comment":"Hard; then fun\n\"send\""}],
              "climbLists":[{"name":"=IMPORTXML(\"bad\")","entries":["a","b"],"playlistEntries":[]}],
              "climbNotes":[],
              "empty":{}
            }
        """.trimIndent()

        val csv = CruxCoachCsv.fromJson(source)

        assertTrue(csv.startsWith("CruxCoach CSV;1\npath;type;value\n"))
        assertTrue(csv.contains("$/boardAscents/0/comment;string;\"Hard; then fun"))
        assertTrue(csv.contains("$/climbLists/0/name;string;\"'=IMPORTXML"))
        assertEquals(
            json.parseToJsonElement(source),
            json.parseToJsonElement(CruxCoachCsv.toJson(csv)),
        )
    }

    @Test
    fun `only branded CruxCoach CSV is detected`() {
        assertTrue(CruxCoachCsv.looksLikeCsv("\uFEFF\nCruxCoach CSV;1\npath;type;value\n"))
        assertEquals(false, CruxCoachCsv.looksLikeCsv("path;type;value\n"))
    }

    @Test
    fun `converted csv is accepted by the CruxCoach import preview`() {
        val source = """
            {"version":3,"app":"CruxCoach","exportedAt":"2026-08-27T10:00:00Z"}
        """.trimIndent()

        val preview = CruxCoachBackup.preview(CruxCoachCsv.toJson(CruxCoachCsv.fromJson(source)))

        assertEquals(emptySet<CruxCoachBackup.Category>(), preview.detectedCategories())
    }
}
