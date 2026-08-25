package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardControllerProfilesTest {
    @Test
    fun `an unobserved Aurora controller counts as exclusive`() {
        // What real boards do, and what their own apps assume: one climber at
        // a time, handed back by disconnecting. Nothing about the advertised
        // protocol level says otherwise, and no observation is needed to
        // arrive at the conservative answer.
        val profile = BoardControllerProfiles.resolve(BoardBrand.KILTER)

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT, profile.projectionLifetime)
        assertTrue(profile.relaySupported)
    }

    @Test
    fun `advertising while connected does not claim multi-connect`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.TENSION,
            advertisesWhileConnected = true,
        )

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertTrue(profile.relaySupported)
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
    fun `MoonBoard advertising does not override exclusive capacity`() {
        val profile = BoardControllerProfiles.resolve(
            BoardBrand.MOONBOARD,
            advertisesWhileConnected = true,
        )

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.UNTIL_LAST_CONNECTION, profile.projectionLifetime)
        assertTrue(profile.relaySupported)
    }

    @Test
    fun `Quantum is exclusive and retains its projection after disconnect`() {
        val profile = BoardControllerProfiles.resolve(BoardBrand.QUANTUM)

        assertEquals(BoardConnectionCapacity.SINGLE, profile.connectionCapacity)
        assertEquals(BoardProjectionLifetime.RETAINED_AFTER_DISCONNECT, profile.projectionLifetime)
        assertTrue(profile.relaySupported)
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
