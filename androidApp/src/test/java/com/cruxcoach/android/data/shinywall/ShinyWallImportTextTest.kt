package com.cruxcoach.android.data.shinywall

import android.app.Application
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class ShinyWallImportTextTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun an_empty_logbook_says_so() {
        assertEquals(
            app.getString(R.string.shinywall_import_empty),
            formatShinyWallImportSummary(app, ShinyWallImportResult(0, 0, 0, 0, 0)),
        )
    }

    /** The logbook is not empty when its only entry was deleted in CruxCoach. */
    @Test
    fun entries_deleted_in_cruxcoach_are_counted() {
        assertEquals(
            "Nothing new · 1 won’t be imported again",
            formatShinyWallImportSummary(app, ShinyWallImportResult(0, 0, 0, 0, 0, deleted = 1)),
        )
        assertEquals(
            "Nothing new · 2 lists · 4 already in CruxCoach · 2 won’t be imported again",
            formatShinyWallImportSummary(app, ShinyWallImportResult(0, 0, 4, 0, 2, deleted = 2)),
        )
    }

    /** Nor when its entries could only be skipped. */
    @Test
    fun skipped_entries_are_counted() {
        assertEquals(
            "Nothing new · 3 skipped",
            formatShinyWallImportSummary(app, ShinyWallImportResult(0, 0, 0, 3, 0)),
        )
    }

    @Test
    fun new_entries_come_first() {
        assertEquals(
            "3 ascents · 1 project · 2 lists",
            formatShinyWallImportSummary(app, ShinyWallImportResult(3, 1, 0, 0, 2)),
        )
    }

    @Test
    fun an_expired_sign_in_is_no_message() {
        assertTrue(isShinyWallSessionExpired(ShinyWallApiException(ShinyWallApiException.Reason.NotAuthenticated, "HTTP 401", 401)))
        assertFalse(isShinyWallSessionExpired(ShinyWallApiException(ShinyWallApiException.Reason.Network, "offline")))
        assertFalse(isShinyWallSessionExpired(java.io.IOException("offline")))
    }
}
