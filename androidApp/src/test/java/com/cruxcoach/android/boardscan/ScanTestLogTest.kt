package com.cruxcoach.android.boardscan

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanTestLogTest {

    private val kilter = ScanBoard("kilter", 1, 7, 40)
    private val moon = ScanBoard("moonboard", 2, 0, 40)

    private fun rec(
        board: ScanBoard = kilter, rank: Int = 1, sure: Boolean = true, climber: Boolean = false, ms: Long = 900,
        answerName: String? = "Pocket, \"the\" one", truth: String? = "a1",
    ) = ScanTestRecord(
        timeMs = 1_794_000_000_000, board = board, climberInFront = climber,
        answerUuid = "a1", answerName = answerName, answerMirrored = false, sure = sure,
        truthUuid = truth, truthName = truth?.let { "Truth\nname" }, truthMirrored = false, truthRank = rank,
        msToAnswer = ms, frames = 1, leds = 12, device = "Nokia 6.1", appVersion = "0.2.5",
    )

    @Test
    fun rowsRoundTripWithCommasQuotesAndLineBreaks() {
        val dir = Files.createTempDirectory("scanlog").toFile()
        val log = ScanTestLog(File(dir, "x/test_scans.csv"))
        log.append(rec())
        log.append(rec(truth = null, rank = 0, sure = false, answerName = null))
        val lines = File(dir, "x/test_scans.csv").readLines()
        assertEquals(ScanTestLog.HEADER, lines[0])
        assertEquals(3, lines.size, "a line break in a name must not split the row")
        val back = log.read()
        assertEquals(2, back.size)
        assertEquals("Pocket, \"the\" one", back[0].answerName)
        assertEquals("Truth name", back[0].truthName)
        assertEquals(1_794_000_000_000, back[0].timeMs)
        assertEquals(kilter, back[0].board)
        assertNull(back[1].truthUuid)
        assertNull(back[1].answerName)
        assertTrue(lines[1].contains(",free,") && lines[1].contains(",1,900,1,12,"))
    }

    @Test
    fun aNameThatLooksLikeASpreadsheetFormulaIsNeverRunAndReadsBackUnchanged() {
        val dir = Files.createTempDirectory("scanlog").toFile()
        val log = ScanTestLog(File(dir, "test_scans.csv"))
        log.append(rec(answerName = "=HYPERLINK(\"x\")"))
        val row = File(dir, "test_scans.csv").readLines()[1]
        assertTrue(row.contains("\"'=HYPERLINK(\"\"x\"\")\""))
        assertEquals("=HYPERLINK(\"x\")", log.read()[0].answerName)
    }

    @Test
    fun theSummaryCountsFreeViewScansPerBoardAndEverySureWrongAnswer() {
        val records = listOf(
            rec(rank = 1, sure = true, ms = 800),
            rec(rank = 2, sure = false, ms = 1200),
            rec(rank = 0, sure = false, ms = 3000),
            rec(rank = 2, sure = true, climber = true),
            rec(board = kilter.copy(angle = 45), rank = 1, sure = true, ms = 1000),
            rec(board = moon, rank = 1, sure = false),
        )
        val s = ScanTestLog.summarise(records)
        assertEquals(2, s.size)
        val k = s.first { it.board.brand == "kilter" }
        assertEquals(5, k.scans)
        assertEquals(4, k.freeView)
        assertEquals(2, k.freeTop1)
        assertEquals(3, k.freeTop3)
        assertEquals(2, k.freeSure)
        assertEquals(1, k.sureWrong, "a sure answer that was wrong counts with a climber in front too")
        assertEquals(1200, k.medianMs)
    }

    @Test
    fun exportCopiesTheLogAndNothingWhenEmpty() {
        val dir = Files.createTempDirectory("scanlog").toFile()
        val log = ScanTestLog(File(dir, "test_scans.csv"))
        assertNull(log.exportTo(File(dir, "share"), 0))
        log.append(rec())
        val out = assertNotNull(log.exportTo(File(dir, "share"), 1_794_000_000_000))
        assertTrue(out.name.startsWith("board-scan-tests-") && out.name.endsWith(".csv"))
        assertEquals(File(dir, "test_scans.csv").readText(), out.readText())
        log.clear()
        assertTrue(log.read().isEmpty())
    }

    @Test
    fun diagnosticsRoundTripAndNegativeNumbersStayNumbers() {
        val dir = Files.createTempDirectory("scanlog").toFile()
        val log = ScanTestLog(File(dir, "test_scans.csv"))
        val d = ScanDiagnostics(
            palette = "kilter", answerHolds = 11, answerMatched = 9, answerMissing = 2, answerExtra = 1, answerResid = 0.123,
            answerMargin = -1.5, truthHolds = 12, truthRankAll = 7, truthMatched = 6, truthMissing = 5, truthExtra = 3,
            distanceM = 4.26, pitchDeg = -12.4, brightness = 97, ledPeakMedian = 231, ledChromaMedian = 140,
            detectMs = 150, matchMs = 812, tiers = 2, frameW = 720, frameH = 1280,
        )
        log.append(rec().copy(diagnostics = d))
        log.append(rec())
        val lines = File(dir, "test_scans.csv").readLines()
        val cols = ScanTestLog.HEADER.split(",").size
        assertTrue(lines.all { it.split(",").size >= cols }, "every row has every column")
        assertTrue(lines[1].contains(",0.12,-1.50,") && lines[1].contains(",4.3,-12,97,"), lines[1])
        val back = log.read()
        assertEquals(d.copy(answerResid = 0.12, distanceM = 4.3, pitchDeg = -12.0), back[0].diagnostics)
        assertEquals(ScanDiagnostics(), back[1].diagnostics)
    }

    @Test
    fun aLogFromAnEarlierBuildGetsTheNewColumns() {
        val dir = Files.createTempDirectory("scanlog").toFile()
        val file = File(dir, "test_scans.csv")
        val oldHeader = ScanTestLog.HEADER.split(",").take(20).joinToString(",")
        val oldRow = ScanTestLog.toCsv(rec(answerName = "Pocket")).split(",").take(20).joinToString(",")
        file.writeText("$oldHeader\n$oldRow\n")
        val log = ScanTestLog(file)
        log.append(rec(rank = 2, sure = false))
        val lines = file.readLines()
        assertEquals(ScanTestLog.HEADER, lines[0])
        assertEquals(3, lines.size)
        val cols = ScanTestLog.HEADER.split(",").size
        assertEquals(cols, lines[1].split(",").size, "the old row is padded")
        val back = log.read()
        assertEquals(listOf(1, 2), back.map { it.truthRank })
        assertEquals(ScanDiagnostics(), back[0].diagnostics)
    }
}
