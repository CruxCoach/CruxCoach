package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardProjectionPolicyTest {
    @Test
    fun `solo MoonBoard host keeps connection after ending session`() {
        assertFalse(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = false,
                projectionSurvivesDisconnect = false,
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
            ),
        )
    }

    @Test
    fun `multi-connect host stays connected when a queue successor exists`() {
        assertFalse(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = true,
                projectionSurvivesDisconnect = false,
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
            ),
        )
    }

    @Test
    fun `retaining exclusive controller can be released after session`() {
        assertTrue(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = false,
                projectionSurvivesDisconnect = true,
                connectionCapacity = BoardConnectionCapacity.SINGLE,
            ),
        )
    }

    @Test
    fun `queue cannot release an exclusive connection pinned by relay`() {
        assertFalse(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = true,
                projectionSurvivesDisconnect = true,
                connectionCapacity = BoardConnectionCapacity.SINGLE,
                pinnedByAnotherFeature = true,
            ),
        )
    }

    @Test
    fun `multi-connect controller never uses idle disconnect`() {
        assertFalse(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
            )
        )
    }

    @Test
    fun `unknown controller never uses idle disconnect`() {
        assertFalse(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectionCapacity = BoardConnectionCapacity.UNKNOWN,
            )
        )
    }

    @Test
    fun `legacy exclusive controller retains idle disconnect behavior`() {
        assertTrue(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectionCapacity = BoardConnectionCapacity.SINGLE,
            )
        )
    }

    @Test
    fun `MoonBoard frames are sendable without Aurora holds`() {
        assertTrue(
            BoardProjectionPolicy.hasSendablePayload(
                brand = BoardBrand.MOONBOARD,
                holdCount = 0,
                frames = "p1r42p2r44",
            )
        )
        assertFalse(
            BoardProjectionPolicy.hasSendablePayload(
                brand = BoardBrand.MOONBOARD,
                holdCount = 0,
                frames = " ",
            )
        )
    }

    @Test
    fun `only MoonBoard projections are volatile`() {
        assertFalse(BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.MOONBOARD))
        assertTrue(BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.KILTER))
        assertTrue(BoardProjectionPolicy.projectionSurvivesDisconnect(BoardBrand.TENSION))
    }
}
