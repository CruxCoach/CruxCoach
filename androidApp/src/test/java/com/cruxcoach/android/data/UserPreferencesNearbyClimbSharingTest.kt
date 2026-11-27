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

    @Test
    fun `release requests follow nearby sharing until set themselves`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        // Sharing is on by default and says others can ask to release the board.
        assertTrue(preferences.allowRemoteDisconnect.first())

        preferences.setNearbyClimbSharing(false)
        assertFalse(preferences.allowRemoteDisconnect.first())

        preferences.setNearbyClimbSharing(true)
        assertTrue(preferences.allowRemoteDisconnect.first())

        preferences.setAllowRemoteDisconnect(false)
        assertFalse(preferences.allowRemoteDisconnect.first())
        assertTrue(preferences.nearbyClimbSharing.first())
    }
}
