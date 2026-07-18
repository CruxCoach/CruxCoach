package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardControllerProfilesTest {
    @Test
    fun `Aurora protocol level alone does not imply connection capacity`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.KILTER)

        assertEquals(BoardConnectionCapacity.UNKNOWN, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT, profile.projectionLifetime)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `Aurora controller observed advertising while connected is multi-connect`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.TENSION,
            advertisesWhileConnected = true,
        )

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `Aurora controller not observed while connected is relay eligible`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.KILTER,
            advertisesWhileConnected = false,
        )

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertTrue(profile.relaySupported)
    }

    @Test
    fun `MoonBoard capacity is observed independently from projection lifetime`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.MOONBOARD,
            advertisesWhileConnected = true,
        )

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.UNTIL_LAST_CONNECTION, profile.projectionLifetime)
        assertFalse(profile.relaySupported)
    }

    @Test
    fun `CruxRelay is a multi-client endpoint and cannot be nested`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.KILTER,
            isCruxRelay = true,
        )

        assertEquals(BoardConnectionCapacity.MULTIPLE, profile.connectionCapacity)
        assertFalse(profile.relaySupported)
    }
}
