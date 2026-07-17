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
            ),
        )
    }

    @Test
    fun `MoonBoard host releases connection for successor`() {
        assertTrue(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = true,
                projectionSurvivesDisconnect = false,
            ),
        )
    }

    @Test
    fun `retaining controller can always be released after session`() {
        assertTrue(
            BoardProjectionPolicy.shouldReleaseBoardAfterHosting(
                hasSuccessor = false,
                projectionSurvivesDisconnect = true,
            ),
        )
    }

    @Test
    fun `active MoonBoard projection suppresses idle disconnect`() {
        assertFalse(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectedBrand = BoardBrand.MOONBOARD,
                hasActiveMoonBoardProjection = true,
            )
        )
    }

    @Test
    fun `MoonBoard without successful projection still uses idle disconnect`() {
        assertTrue(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectedBrand = BoardBrand.MOONBOARD,
                hasActiveMoonBoardProjection = false,
            )
        )
    }

    @Test
    fun `Aurora boards retain their existing idle disconnect behavior`() {
        assertTrue(
            BoardProjectionPolicy.shouldArmIdleDisconnect(
                seconds = 60,
                connectionState = ConnectionState.CONNECTED,
                explicitlySuppressed = false,
                connectedBrand = BoardBrand.KILTER,
                hasActiveMoonBoardProjection = true,
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
