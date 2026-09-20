package com.cruxcoach.app.imports

import com.cruxcoach.app.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MoonBoardCsvParserTest {

    private fun ok(csv: String): MoonBoardCsvExport =
        when (val r = MoonBoardCsvParser.parse(csv)) {
            is MoonBoardCsvParseResult.Ok -> r.export
            is MoonBoardCsvParseResult.Failed -> fail("expected success, got ${r.code} at row ${r.row}")
        }

    private fun failure(csv: String): MoonBoardCsvParseResult.Failed =
        when (val r = MoonBoardCsvParser.parse(csv)) {
            is MoonBoardCsvParseResult.Failed -> r
            is MoonBoardCsvParseResult.Ok -> fail("expected failure, parsed ${r.export.entries.size} entries")
        }

    private val header = "ProblemId,Grade,Tries,Attempts,Rating,Date\n"

    @Test
    fun `parses the official export shape`() {
        val export = ok(Fixtures.text("imports/moonboard_export.csv"))
        assertEquals(
            mapOf("Logbook" to "MoonBoard 2016", "User" to "synthetic-tester", "Problems" to "4"),
            export.metadata,
        )
        assertEquals(4, export.entries.size)

        val flash = export.entries[0]
        assertEquals(316526L, flash.problemId)
        assertEquals("6B+", flash.grade)
        assertEquals(1, flash.attempts)
        assertEquals(3, flash.rating)
        assertEquals("2024-01-15T12:00:00Z", flash.climbedAt)
        assertTrue(flash.isSend)
        assertEquals(6, flash.sourceRow)

        val second = export.entries[1]
        assertEquals(2, second.attempts)
        assertEquals(null, second.rating)
        assertEquals("2023-12-03T12:00:00Z", second.climbedAt)
        assertTrue(second.isSend)

        val moreThanThree = export.entries[2]
        assertEquals(9, moreThanThree.attempts)
        assertEquals(2, moreThanThree.rating)
        assertTrue(moreThanThree.isSend)

        val project = export.entries[3]
        assertEquals(5, project.attempts)
        assertEquals("2024-02-29T12:00:00Z", project.climbedAt)
        assertTrue(!project.isSend)
    }

    @Test
    fun `maps every tries label Android maps`() {
        fun entry(tries: String, attempts: Int): MoonBoardCsvEntry =
            ok("${header}1,6A,$tries,$attempts,,1/1/24").entries.single()

        listOf("Flashed", "Flash", "Session Flash").forEach { label ->
            val e = entry(label, 7)
            assertTrue(e.isSend, label)
            assertEquals(1, e.attempts, label)
        }
        assertEquals(1, entry("1st try", 9).attempts)
        assertEquals(3, entry("3rd try", 9).attempts)
        assertEquals(12, entry("12th try", 1).attempts)
        // "more than 3 tries" floors at 4 but keeps a bigger file value.
        assertEquals(4, entry("more than 3 tries", 2).attempts)
        assertEquals(17, entry("more than 3 tries", 17).attempts)
        listOf("Project", "Fail", "Failed").forEach { label ->
            val e = entry(label, 0)
            assertTrue(!e.isSend, label)
            assertEquals(1, e.attempts, label)
        }
        assertEquals(6, entry("Project", 6).attempts)
    }

    @Test
    fun `rating zero becomes no rating and out of range is rejected`() {
        assertEquals(null, ok("${header}1,6A,Flashed,1,0,1/1/24").entries.single().rating)
        assertEquals(5, ok("${header}1,6A,Flashed,1,5,1/1/24").entries.single().rating)
        assertEquals(ImportCodes.ROW_BAD_RATING, failure("${header}1,6A,Flashed,1,6,1/1/24").code)
        // Non-numeric ratings are dropped, matching Android's toIntOrNull.
        assertEquals(null, ok("${header}1,6A,Flashed,1,good,1/1/24").entries.single().rating)
    }

    @Test
    fun `malformed rows are rejected whole and name the row`() {
        assertEquals(ImportCodes.HEADER_MISSING, failure("a,b\nc,d\n").code)
        assertEquals(ImportCodes.NO_ENTRIES, failure(header).code)

        val tooFew = failure("${header}1,6A,Flashed,1,3\n")
        assertEquals(ImportCodes.ROW_TOO_FEW_COLUMNS, tooFew.code)
        assertEquals(2, tooFew.row)

        assertEquals(ImportCodes.ROW_BAD_PROBLEM_ID, failure("${header}abc,6A,Flashed,1,,1/1/24").code)
        assertEquals(ImportCodes.ROW_BAD_PROBLEM_ID, failure("${header}0,6A,Flashed,1,,1/1/24").code)
        assertEquals(ImportCodes.ROW_BAD_ATTEMPTS, failure("${header}1,6A,Flashed,x,,1/1/24").code)
        assertEquals(ImportCodes.ROW_BAD_ATTEMPTS, failure("${header}1,6A,Flashed,-1,,1/1/24").code)
        assertEquals(ImportCodes.ROW_BAD_TRIES, failure("${header}1,6A,Sent somehow,1,,1/1/24").code)
        assertEquals(ImportCodes.ROW_BAD_DATE, failure("${header}1,6A,Flashed,1,,notadate").code)
        assertEquals(ImportCodes.ROW_BAD_DATE, failure("${header}1,6A,Flashed,1,,31/2/24").code)
        assertEquals(ImportCodes.ROW_BAD_DATE, failure("${header}1,6A,Flashed,1,,1/13/24").code)
        assertEquals(ImportCodes.ROW_BAD_DATE, failure("${header}1,6A,Flashed,1,,2024-01-15").code)
        assertEquals(ImportCodes.UNTERMINATED_QUOTE, failure("${header}1,\"6A,Flashed,1,,1/1/24").code)
    }

    @Test
    fun `quoted commas byte order mark and CRLF survive`() {
        val csv = CsvReader.BOM + "ProblemId,Grade,Tries,Attempts,Rating,Date\r\n" +
            "1,\"6A, maybe 6A+\",Flashed,1,,1/1/24\r\n"
        val entry = ok(csv).entries.single()
        assertEquals("6A, maybe 6A+", entry.grade)
        assertEquals(1L, entry.problemId)
    }

    @Test
    fun `blank rows between entries are skipped not rejected`() {
        val export = ok("${header}1,6A,Flashed,1,,1/1/24\n\n2,6B,Flashed,1,,2/1/24\n")
        assertEquals(listOf(1L, 2L), export.entries.map { it.problemId })
        assertEquals(listOf(2, 4), export.entries.map { it.sourceRow })
    }

    @Test
    fun `hostile input is bounded rather than consumed`() {
        val tooManyRows = header + (0 until MoonBoardCsvParser.MAX_ROWS + 64)
            .joinToString("\n") { "1,6A,Flashed,1,,1/1/24" }
        assertEquals(ImportCodes.TOO_MANY_ROWS, failure(tooManyRows).code)

        val hugeField = "${header}1,${"A".repeat(20_000)},Flashed,1,,1/1/24"
        assertEquals(ImportCodes.FIELD_TOO_LONG, failure(hugeField).code)
    }

    /** A 50 MB file of junk must come back as a rejection, not an OOM or a hang. */
    @Test
    fun `fifty megabytes of junk is rejected`() {
        val junk = buildString(50 * 1024 * 1024) {
            val line = "x".repeat(199) + "\n"
            while (length < 50 * 1024 * 1024) append(line)
        }
        val failed = failure(junk)
        assertTrue(
            failed.code == ImportCodes.TOO_MANY_ROWS || failed.code == ImportCodes.HEADER_MISSING,
            "unexpected code ${failed.code}",
        )
    }
}
