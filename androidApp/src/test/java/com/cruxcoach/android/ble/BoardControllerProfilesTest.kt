package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardControllerProfilesTest {
    @Test
    fun `Aurora API 2 is exclusive and relay eligible`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.KILTER, apiLevel = 2)

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT, profile.projectionLifetime)
        assertTrue(profile.relaySupported)
    }

    @Test
    fun `Aurora API 3 is multi-connect and does not need relay`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.TENSION, apiLevel = 3)

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `MoonBoard is multi-connect but projection needs a remaining connection`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.MOONBOARD, apiLevel = 0)

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.UNTIL_LAST_CONNECTION, profile.projectionLifetime)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `unknown controller avoids automatic ownership changes`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.KILTER, apiLevel = 0)

        assertEquals(BoardConnectionCapacity.UNKNOWN, profile.connectionCapacity)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `CruxRelay is a multi-client endpoint and cannot be nested`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.KILTER,
            apiLevel = 2,
            isCruxRelay = true,
        )

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertFalse(profile.relaySupported)
    }
}
