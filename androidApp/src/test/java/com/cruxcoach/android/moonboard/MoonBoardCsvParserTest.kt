package com.cruxcoach.android.moonboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonBoardCsvParserTest {
    @Test fun `parses official export metadata and log rows`() {
        val csv = """FirstName,Cathal,,,,
LastName,Tone,,,,
SetterName,"Tone, Cathal",,,,
UserName,cathaltone,,,,
City,Galway,,,,
Country,Ireland,,,,
,,,,,
ProblemId,Grade,Tries,Attempts,Rating,Date
309386,6A+,Flashed,0,4,25/07/20
509834,6B+,Project,7,,01/02/2024
580006,7A,Session Flash,0,0,13/02/26
580007,7A,4th try,0,5,13/02/26
580008,7A,More than 3 tries,7,5,13/02/26
"""
        val parsed = MoonBoardCsvParser.parse(csv).getOrThrow()
        assertEquals("Tone, Cathal", parsed.metadata["SetterName"])
        assertEquals(5, parsed.entries.size)
        assertTrue(parsed.entries[0].isSend)
        assertEquals(1, parsed.entries[0].attempts)
        assertEquals("2020-07-25T12:00:00Z", parsed.entries[0].climbedAt)
        assertFalse(parsed.entries[1].isSend)
        assertEquals(7, parsed.entries[1].attempts)
        assertEquals("2024-02-01T12:00:00Z", parsed.entries[1].climbedAt)
        assertEquals(1, parsed.entries[2].attempts)
        assertEquals(null, parsed.entries[2].rating)
        assertEquals(4, parsed.entries[3].attempts)
        assertEquals(7, parsed.entries[4].attempts)
    }

    @Test fun `rejects unrelated csv`() {
        assertTrue(MoonBoardCsvParser.parse("a,b\n1,2\n").isFailure)
    }

    @Test fun `rejects unknown tries instead of silently importing it as a send`() {
        val csv = "ProblemId,Grade,Tries,Attempts,Rating,Date\n1,6A,Unknown,0,,1/1/24\n"
        assertTrue(MoonBoardCsvParser.parse(csv).isFailure)
    }

    @Test fun `uuid candidates match catalogue builder`() {
        val candidates = MoonBoardUuid.candidates(309386)
        assertEquals("5734a49e-a15e-53a9-97b2-dd3436c13b9b", candidates[0].uuid)
        assertEquals("d1014e76-ca9b-5c16-b7f7-012596cebb0c", candidates[1].uuid)
        assertEquals("790cfa99-287f-5576-85ac-5a4fe93f6e7b", candidates[2].uuid)
    }
}
