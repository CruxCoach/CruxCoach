package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDeliveryPolicyTest {

    @Test fun `board group remains active while a member is in controller recovery`() {
        assertTrue(isBoardGroupActive("cell-1", localIsMember = true))
        assertFalse(isBoardGroupActive("cell-1", localIsMember = false))
        assertFalse(isBoardGroupActive(null, localIsMember = true))
    }

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
    fun `playlist host inside BoardCell never competes with technical controller`() {
        assertFalse(
            BoardDeliveryPolicy.shouldAutoConnectSessionHost(
                SessionRole.HOST,
                SessionRole.NONE,
                ConnectionState.DISCONNECTED,
                boardRoutedByMesh = true,
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
    fun `legacy automatic preference still requires the visible detail lamp`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, decision.target)
        assertFalse(decision.dispatchAutomatically)
        assertTrue(decision.showAction)
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
    fun `a relay guest also sends only from the visible lamp`() {
        val automatic = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaRelay = true,
        )

        assertEquals(BoardDeliveryTarget.DIRECT_BOARD, automatic.target)
        assertFalse(automatic.dispatchAutomatically)
        assertTrue(automatic.showAction)

        val explicit = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaRelay = true,
        )

        assertFalse(explicit.dispatchAutomatically)
        assertTrue(explicit.showAction)
    }

    @Test
    fun `hosting never turns detail browsing into a board command`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

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

    @Test
    fun `mesh participant uses multi mode without a direct board connection`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = false,
            hasDirectPayload = true,
            connectedViaMesh = true,
        )

        assertEquals(BoardDeliveryTarget.MESH_BOARD, decision.target)
        assertTrue(decision.showAction)
        assertFalse(decision.dispatchAutomatically)
    }

    /**
     * On a board that a group is on, the shared list's lamp is the only route
     * to the wall. A climb page that could light it as well would be a second
     * control reachable without ever having seen the list — and the wall it
     * would take is one somebody else may be standing on.
     */
    @Test
    fun `a board with a group on it offers no direct route to the wall`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaMesh = true,
            boardCellActive = true,
        )

        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertFalse(decision.showAction)
        assertFalse(decision.dispatchAutomatically)
    }

    @Test
    fun `automatic send mode cannot light a shared board either`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            boardCellActive = true,
        )

        // Swiping through climbs must not project onto a wall the group is
        // working on, whatever this device's own preference says.
        assertFalse(decision.dispatchAutomatically)
        assertEquals(BoardDeliveryTarget.NONE, decision.target)
    }

    @Test
    fun `the shared-queue action also gives way to the board list's own add`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.PARTICIPANT,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaMesh = true,
            boardCellActive = true,
        )

        // Add / Add as next sit on the same screen and say which end of the
        // list they mean; a nameless third add button next to them does not.
        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertFalse(decision.showAction)
    }
}
