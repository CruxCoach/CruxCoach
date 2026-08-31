package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDeliveryPolicyTest {

    @Test
    fun `only a newly elected session host auto-connects to the physical board`() {
        assertTrue(
            BoardDeliveryPolicy.shouldAutoConnectSessionHost(
                SessionRole.HOST,
                SessionRole.PARTICIPANT,
                ConnectionState.DISCONNECTED,
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldAutoConnectSessionHost(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.DISCONNECTED,
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldAutoConnectSessionHost(
                SessionRole.HOST,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
            )
        )
    }

    @Test
    fun `a newly joined participant releases any local physical board connection`() {
        assertTrue(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
                BoardConnectionCapacity.SINGLE,
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.HOST,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
                BoardConnectionCapacity.SINGLE,
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.DISCONNECTED,
                BoardConnectionCapacity.SINGLE,
            )
        )
    }

    @Test
    fun `session participant keeps a multi-connect board connection`() {
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
                BoardConnectionCapacity.MULTIPLE,
            )
        )
    }

    @Test
    fun `session participant cannot release a board pinned by relay`() {
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
                BoardConnectionCapacity.SINGLE,
                connectionPinnedByAnotherFeature = true,
            )
        )
    }

    @Test
    fun `automatic detail delivery keeps a visible relight action on every board`() {
        BoardBrand.entries.filter { it.isInteractive }.forEach { brand ->
            val decision = BoardDeliveryPolicy.resolve(
                sendMode = BoardSendMode.AUTOMATIC,
                boardBrand = brand,
                sessionRole = SessionRole.NONE,
                boardConnected = true,
                hasDirectPayload = true,
            )

            assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
            assertEquals(
                "$brand automatic dispatch",
                !brand.supportsIndependentClimbLayers,
                decision.dispatchAutomatically,
            )
            assertEquals(
                "$brand visible action",
                true,
                decision.showAction,
            )
        }
    }

    @Test
    fun `explicit mode exposes direct board action without auto dispatch`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            boardBrand = BoardBrand.KILTER,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
        assertFalse(decision.dispatchAutomatically)
        assertTrue(decision.showAction)
    }

    @Test
    fun `a non-Quantum relay guest keeps the resolved send mode`() {
        val automatic = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            boardBrand = BoardBrand.KILTER,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaRelay = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, automatic.target)
        assertTrue(automatic.dispatchAutomatically)
        assertTrue(automatic.showAction)

        val explicit = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            boardBrand = BoardBrand.KILTER,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaRelay = true,
        )

        assertFalse(explicit.dispatchAutomatically)
        assertTrue(explicit.showAction)
    }

    @Test
    fun `non-Quantum automatic mode still dispatches after upstream hosting policy resolves it`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            boardBrand = BoardBrand.KILTER,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertTrue(decision.dispatchAutomatically)
        assertTrue(decision.showAction)
    }

    @Test
    fun `shared session always requires an explicit queue action`() {
        BoardSendMode.entries.forEach { mode ->
            SessionRole.entries.filter { it != SessionRole.NONE }.forEach { role ->
                val decision = BoardDeliveryPolicy.resolve(
                    sendMode = mode,
                    boardBrand = BoardBrand.QUANTUM,
                    sessionRole = role,
                    boardConnected = true,
                    hasDirectPayload = true,
                )

                assertEquals(BoardDeliveryTarget.SHARED_QUEUE, decision.target)
                assertFalse(decision.dispatchAutomatically)
                assertTrue(decision.showAction)
            }
        }
    }

    @Test
    fun `private local playlist keeps the detail lamp on direct board delivery`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            boardBrand = BoardBrand.QUANTUM,
            sessionRole = SessionRole.HOST,
            localPlaylist = true,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
        assertFalse(decision.dispatchAutomatically)
        assertTrue(decision.showAction)
        assertEquals(
            BoardDetailLampMode.LIGHT,
            BoardDeliveryPolicy.lampMode(
                decision = decision,
                hasDirectPayload = true,
                boardConnected = true,
                boardOwnedByOthers = false,
                countdownRunning = false,
            ),
        )
    }

    @Test
    fun `shared queue does not depend on participant having board payload`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            boardBrand = BoardBrand.QUANTUM,
            sessionRole = SessionRole.PARTICIPANT,
            boardConnected = false,
            hasDirectPayload = false,
        )

        assertEquals(BoardDeliveryTarget.SHARED_QUEUE, decision.target)
        assertTrue(decision.showAction)
    }

    @Test
    fun `joining session suppresses direct and queue actions until GATT is ready`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            boardBrand = BoardBrand.QUANTUM,
            sessionRole = SessionRole.NONE,
            sessionConnecting = true,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertFalse(decision.dispatchAutomatically)
        assertFalse(decision.showAction)
    }

    @Test
    fun `direct action stays hidden without connection or payload`() {
        listOf(false to true, true to false).forEach { (connected, payload) ->
            val decision = BoardDeliveryPolicy.resolve(
                sendMode = BoardSendMode.EXPLICIT,
                boardBrand = BoardBrand.KILTER,
                sessionRole = SessionRole.NONE,
                boardConnected = connected,
                hasDirectPayload = payload,
            )

            assertEquals(BoardDeliveryTarget.NONE, decision.target)
            assertFalse(decision.showAction)
        }
    }
}
