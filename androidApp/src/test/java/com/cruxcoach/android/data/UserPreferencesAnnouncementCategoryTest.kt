package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesAnnouncementCategoryTest {

    /** Fresh DataStore per test, scoped to the TestScope's backgroundScope so
     *  DataStore's writer is cancelled with the test (prevents UncaughtExceptionsBeforeTest). */
    private fun TestScope.prefs() = createTestUserPreferences(backgroundScope)

    // ── Defaults ─────────────────────────────────────────────────

    @Test
    fun `all category preferences default to true`() = runTest {
        val p = prefs()
        assertTrue(p.announcementCatRelease.first())
        assertTrue(p.announcementCatIssue.first())
        assertTrue(p.announcementCatTip.first())
        assertTrue(p.announcementCatGeneral.first())
    }

    @Test
    fun `announcementsEnabled defaults to true`() = runTest {
        assertTrue(prefs().announcementsEnabled.first())
    }

    // ── setAnnouncementCategoryEnabled ───────────────────────────

    @Test
    fun `disable release category`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("release", false)
        assertFalse(p.announcementCatRelease.first())
        // Other categories unchanged
        assertTrue(p.announcementCatIssue.first())
        assertTrue(p.announcementCatTip.first())
        assertTrue(p.announcementCatGeneral.first())
    }

    @Test
    fun `disable issue category`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("issue", false)
        assertFalse(p.announcementCatIssue.first())
    }

    @Test
    fun `disable tip category`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("tip", false)
        assertFalse(p.announcementCatTip.first())
    }

    @Test
    fun `disable general category`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("general", false)
        assertFalse(p.announcementCatGeneral.first())
    }

    @Test
    fun `re-enable category after disabling`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("release", false)
        assertFalse(p.announcementCatRelease.first())

        p.setAnnouncementCategoryEnabled("release", true)
        assertTrue(p.announcementCatRelease.first())
    }

    @Test
    fun `unknown category is silently ignored`() = runTest {
        val p = prefs()
        p.setAnnouncementCategoryEnabled("unknown", false)
        assertTrue(p.announcementCatRelease.first())
        assertTrue(p.announcementCatIssue.first())
        assertTrue(p.announcementCatTip.first())
        assertTrue(p.announcementCatGeneral.first())
    }

    // ── Master toggle ────────────────────────────────────────────

    @Test
    fun `setAnnouncementsEnabled persists false`() = runTest {
        val p = prefs()
        p.setAnnouncementsEnabled(false)
        assertFalse(p.announcementsEnabled.first())
    }

    @Test
    fun `master toggle does not affect individual categories`() = runTest {
        val p = prefs()
        p.setAnnouncementsEnabled(false)
        assertTrue(p.announcementCatRelease.first())
        assertTrue(p.announcementCatIssue.first())
    }
}
