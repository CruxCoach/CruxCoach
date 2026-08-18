package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardProjectionPolicyTest {
    @Test
    fun `active mesh disables auto disconnect setting`() {
        assertTrue(
            BoardProjectionPolicy.autoDisconnectUnavailable(
                activeMesh = true,
                featureKeepAlive = false,
                connectionCapacity = BoardConnectionCapacity.SINGLE,
            ),
        )
    }

    @Test
    fun `shared feature and multi client controller disable auto disconnect setting`() {
        assertTrue(BoardProjectionPolicy.autoDisconnectUnavailable(
            activeMesh = false,
            featureKeepAlive = true,
            connectionCapacity = BoardConnectionCapacity.SINGLE,
        ))
        assertTrue(BoardProjectionPolicy.autoDisconnectUnavailable(
            activeMesh = false,
            featureKeepAlive = false,
            connectionCapacity = BoardConnectionCapacity.MULTIPLE,
        ))
    }

    @Test
    fun `exclusive solo controller keeps auto disconnect setting available`() {
        assertFalse(BoardProjectionPolicy.autoDisconnectUnavailable(
            activeMesh = false,
            featureKeepAlive = false,
            connectionCapacity = BoardConnectionCapacity.SINGLE,
        ))
    }

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
                projectionSurvivesDisconnect = false,
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
                projectionSurvivesDisconnect = true,
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
                projectionSurvivesDisconnect = true,
            )
        )
    }

    @Test
    fun `exclusive volatile controller keeps connection to preserve projection`() {
        assertFalse(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectionCapacity = BoardConnectionCapacity.SINGLE,
                projectionSurvivesDisconnect = false,
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
