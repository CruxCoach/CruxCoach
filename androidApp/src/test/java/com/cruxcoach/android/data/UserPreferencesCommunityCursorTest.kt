package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserPreferencesCommunityCursorTest {
    @Test
    fun `upgrade removes poisoned future cursor from persisted preferences`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        val maximum = 1_700_003_600L
        preferences.setCommunityClimbSince(maximum + 1)

        assertNull(preferences.sanitizeCommunityClimbSince(maximum))
        assertNull(preferences.communityClimbSince.first())
    }

    @Test
    fun `cursor at future skew boundary remains persisted`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        val maximum = 1_700_003_600L
        preferences.setCommunityClimbSince(maximum)

        assertEquals(maximum, preferences.sanitizeCommunityClimbSince(maximum))
        assertEquals(maximum, preferences.communityClimbSince.first())
    }
}
