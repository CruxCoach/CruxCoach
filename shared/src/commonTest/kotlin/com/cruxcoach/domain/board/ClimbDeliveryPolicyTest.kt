package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClimbDeliveryPolicyTest {
    @Test
    fun session_ownership_is_explicit_and_suppresses_automatic_delivery() {
        SharedBoardSessionRole.entries.filter { it != SharedBoardSessionRole.NONE }.forEach { role ->
            val decision = ClimbDeliveryPolicy.resolve(
                ClimbDeliveryMode.AUTOMATIC,
                BoardBrand.QUANTUM,
                role,
                boardConnected = true,
                hasDirectPayload = true,
            )
            assertEquals(BoardDeliveryTarget.SHARED_QUEUE, decision.target)
            assertFalse(decision.dispatchAutomatically)
            assertTrue(decision.showAction)
        }
    }

    @Test
    fun automatic_delivery_respects_independent_board_layers() {
        BoardBrand.entries.filter { it.isInteractive }.forEach { brand ->
            val decision = ClimbDeliveryPolicy.resolve(
                ClimbDeliveryMode.AUTOMATIC,
                brand,
                SharedBoardSessionRole.NONE,
                boardConnected = true,
                hasDirectPayload = true,
            )
            assertEquals(!brand.supportsIndependentClimbLayers, decision.dispatchAutomatically)
            assertEquals(brand.supportsIndependentClimbLayers, decision.showAction)
        }
    }

    @Test
    fun disconnected_payload_maps_to_connect_without_dispatching() {
        val decision = ClimbDeliveryPolicy.resolve(
            ClimbDeliveryMode.EXPLICIT,
            BoardBrand.KILTER,
            SharedBoardSessionRole.NONE,
            boardConnected = false,
            hasDirectPayload = true,
        )
        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertEquals(
            BoardDetailLampMode.CONNECT,
            ClimbDeliveryPolicy.lampMode(
                decision,
                hasDirectPayload = true,
                boardConnected = false,
                boardOwnedByOthers = false,
                countdownRunning = false,
            ),
        )
    }

    @Test
    fun participant_releases_only_an_unpinned_single_connection_board() {
        assertTrue(
            ClimbDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SharedBoardSessionRole.PARTICIPANT,
                SharedBoardSessionRole.NONE,
                BoardConnectionState.CONNECTED,
                BoardConnectionCapacity.SINGLE,
            ),
        )
        assertFalse(
            ClimbDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SharedBoardSessionRole.PARTICIPANT,
                SharedBoardSessionRole.NONE,
                BoardConnectionState.CONNECTED,
                BoardConnectionCapacity.SINGLE,
                connectionPinnedByAnotherFeature = true,
            ),
        )
        assertFalse(
            ClimbDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SharedBoardSessionRole.PARTICIPANT,
                SharedBoardSessionRole.NONE,
                BoardConnectionState.CONNECTED,
                BoardConnectionCapacity.MULTIPLE,
            ),
        )
    }
}
