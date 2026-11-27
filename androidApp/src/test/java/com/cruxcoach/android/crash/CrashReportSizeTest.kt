package com.cruxcoach.android.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A crash report fits one NIP-17 message below 32 KB: start and end of the trace stay, the middle goes. */
class CrashReportSizeTest {

    private val header = listOf(
        "--- CruxCoach Crash Report ---",
        "Time: 2026-11-20T00:10:00Z",
        "App: 0.2.5 (1000060)",
        "Android: API 35",
        "Device: low-end",
        "Locale: de",
        "",
        "--- Stack Trace ---",
        "java.lang.IllegalStateException: Größe passt nicht",
    )

    private fun frames(n: Int, from: String) = List(n) { "\tat com.cruxcoach.android.$from.Frame$it.invoke(Frame.kt:${it + 1})" }

    private fun report(frameCount: Int) = (
        header + frames(frameCount, "ui") +
            "Caused by: java.io.IOException: root cause" + frames(12, "data") + "\t... 40 more"
        ).joinToString("\n") + "\n"

    @Test
    fun `a normal report stays as it is`() {
        val small = report(60)
        assertTrue(jsonBytes(small) < CRASH_REPORT_MAX_JSON_BYTES)
        assertSame(small, fitCrashReport(small))
    }

    @Test
    fun `a long report fits and keeps the header, the top frames and the root cause`() {
        val big = report(2_000)
        assertTrue(jsonBytes(big) > 5 * CRASH_REPORT_MAX_JSON_BYTES)

        val fitted = fitCrashReport(big)

        assertTrue("${jsonBytes(fitted)}", jsonBytes(fitted) <= CRASH_REPORT_MAX_JSON_BYTES)
        val lines = fitted.lines()
        assertEquals(header + frames(5, "ui"), lines.take(header.size + 5))
        assertTrue(lines.contains("Caused by: java.io.IOException: root cause"))
        assertEquals(frames(12, "data") + "\t... 40 more" + "", lines.takeLast(14))
        val mark = lines.single { it.startsWith("… (") }
        val kept = lines.size - 1
        assertEquals(crashReportCutMark(big.lines().size - kept), mark)
    }

    @Test
    fun `whole lines are kept or cut, never half a frame`() {
        val big = report(800)
        val original = big.lines().toSet()

        fitCrashReport(big).lines().filterNot { it.startsWith("… (") }.forEach {
            assertTrue(it, it in original)
        }
    }

    @Test
    fun `one huge line is cut to its start`() {
        val oneLine = "java.lang.RuntimeException: " + "x".repeat(40_000)

        val fitted = fitCrashReport(oneLine)

        assertTrue(jsonBytes(fitted) <= CRASH_REPORT_MAX_JSON_BYTES)
        assertTrue(fitted.startsWith("java.lang.RuntimeException: xxx"))
        assertTrue(fitted.endsWith(crashReportCutMark()))
    }

    @Test
    fun `non-ASCII text and escapes count with their worst-case JSON size`() {
        assertEquals(1, jsonBytes("a"))
        assertEquals(2, jsonBytes("\n"))
        assertEquals(2, jsonBytes("\t"))
        assertEquals(2, jsonBytes("\""))
        assertEquals(6, jsonBytes("ö"))
        assertEquals(6, jsonBytes("\u0001"))
        assertEquals(12, jsonBytes("🧗"))

        val umlauts = List(3_000) { "\tat Größe.prüfe(Ü.kt:$it)" }.joinToString("\n")
        assertTrue(jsonBytes(fitCrashReport(umlauts)) <= CRASH_REPORT_MAX_JSON_BYTES)
    }
}
