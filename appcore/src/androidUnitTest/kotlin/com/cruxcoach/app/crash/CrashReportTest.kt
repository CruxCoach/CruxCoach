package com.cruxcoach.app.crash

import com.cruxcoach.app.sync.JvmFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The report text and file handling of Android's `CruxCoachCrashHandler`, on the iOS side. */
class CrashReportTest {
    private val dir: File = Files.createTempDirectory("crash").toFile()
    private val files = CrashReportFiles(JvmFileSystem(dir), dir.absolutePath)
    private val env = CrashEnvironment("0.2.4", "7", "iOS", "17.5", "mid-range", "de")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `report has Android's layout with the iOS line in place of the Android one`() {
        val report = CrashReportFormat.build(env, 1_790_000_000L, "kotlin.IllegalStateException: boom\n    at 0 CruxCoach\n")
        assertEquals(
            """
            --- CruxCoach Crash Report ---
            Time: 2026-09-21T14:13:20Z
            App: 0.2.4 (7)
            iOS: 17.5
            Device: mid-range
            Locale: de

            --- Stack Trace ---
            kotlin.IllegalStateException: boom
                at 0 CruxCoach

            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `missing version and build read like Android's fallbacks`() {
        val report = CrashReportFormat.build(CrashEnvironment("", "", "iOS", "18.0", "low-end", "en"), 0, "x")
        assertTrue(report.contains("App: unknown (-1)\n"))
        assertTrue(report.contains("Time: 1970-01-01T00:00:00Z\n"))
    }

    @Test
    fun `paths naming an install or a user are stripped`() {
        val trace = """
            at /var/mobile/Containers/Data/Application/1B2C3D4E-0000-1111-2222-333344445555/Library/x.kt
            at /private/var/containers/Bundle/Application/ABCD-EF/CruxCoach.app/CruxCoach
            at /Users/alice/work/CruxCoach/appcore/src/Foo.kt:12
            at /data/user/0/org.cruxcoach.app/files/y
        """.trimIndent()
        val clean = CrashReportFormat.sanitize(trace)
        assertFalse(clean.contains("1B2C3D4E"))
        assertFalse(clean.contains("ABCD-EF"))
        assertFalse(clean.contains("alice"))
        assertFalse(clean.contains("org.cruxcoach.app"))
        assertTrue(clean.contains("/…/Library/x.kt"))
        assertTrue(clean.contains("/…/CruxCoach.app/CruxCoach"))
        assertTrue(clean.contains("/…/work/CruxCoach/appcore/src/Foo.kt:12"))
        assertTrue(clean.contains("/…/files/y"))
    }

    @Test
    fun `a runaway trace is cut so the report still fits one message`() {
        val report = CrashReportFormat.build(env, 0, "a".repeat(CrashReportFormat.MAX_TRACE_CHARS + 500))
        assertTrue(report.endsWith("… (truncated)\n"))
        assertTrue(report.length < CrashReportFormat.MAX_TRACE_CHARS + 400)
    }

    @Test
    fun `device tier uses Android's whole-GiB buckets`() {
        val gib = 1024L * 1024 * 1024
        assertEquals("high-end", CrashReportFormat.deviceTier(8 * gib))
        assertEquals("mid-range", CrashReportFormat.deviceTier(8 * gib - 1))
        assertEquals("mid-range", CrashReportFormat.deviceTier(4 * gib))
        assertEquals("low-end", CrashReportFormat.deviceTier(4 * gib - 1))
        assertEquals("low-end", CrashReportFormat.deviceTier(0))
        assertEquals("low-end", CrashReportFormat.deviceTier(-5))
    }

    @Test
    fun `UTC timestamps match the calendar including leap days`() {
        assertEquals("2024-02-29T12:00:00Z", CrashReportFormat.isoUtc(1_709_208_000L))
        assertEquals("2000-02-29T00:00:00Z", CrashReportFormat.isoUtc(951_782_400L))
        assertEquals("2099-12-31T23:59:59Z", CrashReportFormat.isoUtc(4_102_444_799L))
    }

    @Test
    fun `a new report keeps exactly one previous report`() {
        assertTrue(files.write("first"))
        assertTrue(files.write("second"))
        assertTrue(files.write("third"))
        assertEquals("third", File(files.reportPath).readText())
        assertEquals("second", File(files.previousPath).readText())
        assertEquals(setOf(CrashReportFiles.REPORT_FILE, CrashReportFiles.PREVIOUS_FILE), dir.list()!!.toSet())
    }

    @Test
    fun `reading returns the report and deleting removes only it`() {
        assertNull(files.readOrDiscard())
        files.write("one")
        files.write("two")
        assertEquals("two", files.readOrDiscard())
        assertTrue(files.delete())
        assertFalse(files.exists())
        assertTrue(File(files.previousPath).exists(), "the previous report stays, as on Android")
    }

    @Test
    fun `an unreadable or empty report is discarded instead of offered`() {
        File(files.reportPath).writeBytes(ByteArray((CrashReportFiles.MAX_REPORT_BYTES + 1).toInt()) { 'a'.code.toByte() })
        assertNull(files.readOrDiscard())
        assertFalse(files.exists())
        File(files.reportPath).writeText("  \n")
        assertNull(files.readOrDiscard())
        assertFalse(files.exists())
    }
}
