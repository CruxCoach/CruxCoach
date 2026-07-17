package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole
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
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.HOST,
                SessionRole.NONE,
                ConnectionState.CONNECTED,
            )
        )
        assertFalse(
            BoardDeliveryPolicy.shouldReleaseBoardForSessionParticipant(
                SessionRole.PARTICIPANT,
                SessionRole.NONE,
                ConnectionState.DISCONNECTED,
            )
        )
    }

    @Test
    fun `automatic mode dispatches only to a directly connected board`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
        assertTrue(decision.dispatchAutomatically)
        assertFalse(decision.showAction)
    }

    @Test
    fun `explicit mode exposes direct board action without auto dispatch`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
        assertFalse(decision.dispatchAutomatically)
        assertTrue(decision.showAction)
    }

    @Test
    fun `shared session always requires an explicit queue action`() {
        BoardSendMode.entries.forEach { mode ->
            SessionRole.entries.filter { it != SessionRole.NONE }.forEach { role ->
                val decision = BoardDeliveryPolicy.resolve(
                    sendMode = mode,
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
    fun `shared queue does not depend on participant having board payload`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
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
                sessionRole = SessionRole.NONE,
                boardConnected = connected,
                hasDirectPayload = payload,
            )

            assertEquals(BoardDeliveryTarget.NONE, decision.target)
            assertFalse(decision.showAction)
        }
    }
}
