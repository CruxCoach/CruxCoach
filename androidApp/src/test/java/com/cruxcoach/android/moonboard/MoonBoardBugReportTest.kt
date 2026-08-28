package com.cruxcoach.android.moonboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonBoardBugReportTest {
    @Test
    fun `diagnostic includes versions and counters but not private warning labels`() {
        val result = MoonBoardCsvImportResult(
            importedAscents = 2,
            foundEntries = 4,
            notImported = 2,
            sessionsScanned = 1,
            sessionsExpected = 2,
            expectedEntries = 7,
            warnings = listOf("SECRET PROBLEM NAME — Unknown outcome"),
        )

        val report = moonBoardBugReportDescription(MoonBoardAppVersion("1.3.69", 369), result)

        assertTrue(report.contains("Moon app: 1.3.69 (369)"))
        assertTrue(report.contains("Sessions: 1/2"))
        assertTrue(report.contains("Entries: 4/7"))
        assertTrue(report.contains("Warnings: 1"))
        assertFalse(report.contains("SECRET PROBLEM NAME"))
    }

    @Test
    fun `missing Moon metadata remains reportable`() {
        val report = moonBoardBugReportDescription(null, MoonBoardCsvImportResult(error = "failed"))
        assertTrue(report.contains("Moon app: unknown"))
        assertTrue(report.contains("Error present: true"))
    }
}
