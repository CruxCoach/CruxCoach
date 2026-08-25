package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesNearbyClimbSharingTest {

    @Test
    fun `nearby climb sharing defaults on and preserves explicit opt out`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertTrue(preferences.nearbyClimbSharing.first())

        preferences.setNearbyClimbSharing(false)

        assertFalse(preferences.nearbyClimbSharing.first())
    }
}
