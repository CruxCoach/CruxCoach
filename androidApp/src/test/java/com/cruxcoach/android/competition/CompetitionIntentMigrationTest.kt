package com.cruxcoach.android.competition

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompetitionIntentMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences("competition_nonces", Context.MODE_PRIVATE)
    }
    private val pubkey = "12345678" + "ab".repeat(28)
    private val compId = "0123456789abcdef"
    private val scope = "climb_choice"

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun `legacy short identity nonce and floor migrate atomically to full identity keys`() {
        val legacy = legacyCompetitionIntentLaneKey(pubkey, compId, scope)
        val current = competitionIntentLaneKey(pubkey, compId, scope)
        preferences.edit()
            .putString(legacy, "a1b2c3d4")
            .putLong("$legacy:created_at", 1234L)
            .commit()
        assertEquals("a1b2c3d4", migratedCompetitionIntentNonce(preferences, current, legacy))
        assertEquals(1234L, migratedCompetitionIntentCreatedAt(preferences, current, legacy))

        assertEquals("a1b2c3d4", preferences.getString(current, null))
        assertEquals(1234L, preferences.getLong("$current:created_at", 0L))
        assertFalse(preferences.contains(legacy))
        assertFalse(preferences.contains("$legacy:created_at"))
        assertTrue(preferences.contains(current))
        assertTrue(preferences.contains("$current:created_at"))
    }
}
