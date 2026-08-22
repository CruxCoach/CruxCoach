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
     * A group's board keeps one route to the wall — the group's controller,
     * in the group's order — but the climb page is allowed to use it.
     *
     * This reverses the rule the merge shipped with, on the owner's decision:
     * making a climber queue a climb and then walk to another screen to press
     * it was protecting the wall from the person standing in front of it. What
     * lands there still becomes an occurrence everybody can see, committed by
     * the same sequencer as the lamp on the list.
     */
    @Test
    fun `a board with a group on it routes through the group's list`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaMesh = true,
            boardCellActive = true,
        )

        assertEquals(BoardDeliveryTarget.BOARD_PLAYLIST, decision.target)
        assertTrue(decision.showAction)
        assertFalse(decision.dispatchAutomatically)
    }

    @Test
    fun `automatic send mode still cannot light a shared board by itself`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            boardCellActive = true,
        )

        // Paging through climbs must not project onto a wall the group is
        // working on, whatever this device's own preference says. The button
        // came back; the automatic dispatch did not.
        assertFalse(decision.dispatchAutomatically)
        assertEquals(BoardDeliveryTarget.BOARD_PLAYLIST, decision.target)
    }

    @Test
    fun `a group's board outranks the legacy session queue`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.PARTICIPANT,
            boardConnected = true,
            hasDirectPayload = true,
            connectedViaMesh = true,
            boardCellActive = true,
        )

        // Both exist only in the middle of a migration; the canonical list is
        // the one that owns the wall.
        assertEquals(BoardDeliveryTarget.BOARD_PLAYLIST, decision.target)
        assertTrue(decision.showAction)
    }

    // ── The dock's middle seat ─────────────────────────────────────────────
    //
    // The dock renders lampMode(); it does not decide anything itself. What it
    // has to get right is that "no path to a board" and "somebody else is
    // mid-join" are different answers, and that a mesh or relay path is a path.

    private fun lamp(
        decision: BoardDeliveryDecision,
        hasDirectPayload: Boolean = true,
        reachability: BoardReachability = BoardReachability.DIRECT,
        boardOwnedByOthers: Boolean = false,
    ) = BoardDeliveryPolicy.lampMode(
        decision = decision,
        hasDirectPayload = hasDirectPayload,
        reachability = reachability,
        boardOwnedByOthers = boardOwnedByOthers,
    )

    @Test
    fun `a connected board gets a lamp`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDetailLampMode.LIGHT, lamp(decision))
    }

    @Test
    fun `a shared session gets the queue action, not a lamp`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.PARTICIPANT,
            boardConnected = true,
            hasDirectPayload = true,
        )

        assertEquals(BoardDetailLampMode.SHARED_QUEUE, lamp(decision))
    }

    /** Nothing reachable and the climb would fit on a wall: an invitation. */
    @Test
    fun `no board at all offers to resolve that`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = false,
            hasDirectPayload = true,
        )

        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertEquals(
            BoardDetailLampMode.CONNECT,
            lamp(decision, reachability = BoardReachability.NO_BOARD),
        )
    }

    /**
     * A group's board is a board this climb can reach — through the group.
     *
     * The owner reversed the older rule that a shared board offered no route
     * from a climb page at all: making somebody queue a climb and then walk to
     * another screen to press it was protecting the wall from the person
     * standing in front of it.
     */
    @Test
    fun `a group's board offers the lamp, through the group`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = false,
            hasDirectPayload = true,
            boardCellActive = true,
        )

        assertEquals(BoardDeliveryTarget.BOARD_PLAYLIST, decision.target)
        assertTrue(decision.showAction)
        assertFalse("paging is still not asking for anything", decision.dispatchAutomatically)
        assertEquals(
            BoardDetailLampMode.LIGHT,
            lamp(decision, reachability = BoardReachability.MESH),
        )
    }

    @Test
    fun `automatic cannot light a group's board either`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.AUTOMATIC,
            sessionRole = SessionRole.NONE,
            boardConnected = true,
            hasDirectPayload = true,
            boardCellActive = true,
        )

        assertFalse(decision.dispatchAutomatically)
    }

    @Test
    fun `a climb with nothing to send offers nothing`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            boardConnected = false,
            hasDirectPayload = false,
        )

        assertEquals(
            BoardDetailLampMode.HIDDEN,
            lamp(decision, hasDirectPayload = false, reachability = BoardReachability.NO_BOARD),
        )
    }

    /**
     * A join in progress keeps the middle position and names what is going on.
     *
     * It used to hide the action outright, which loses the status *and* resizes
     * the two actions beside it under the user's thumb mid-tap — the harm the
     * disabled-not-removed rule elsewhere on this dock exists to prevent. The
     * contract asks for a visible, non-sendable state whose tap opens the
     * status sheet.
     */
    @Test
    fun `a joining session keeps a visible connecting action`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            sessionConnecting = true,
            boardConnected = false,
            hasDirectPayload = true,
        )

        assertFalse("nothing may be sent while joining", decision.showAction)
        assertEquals(BoardDeliveryTarget.NONE, decision.target)
        assertEquals(
            BoardDetailLampMode.CONNECTING,
            lamp(decision, reachability = BoardReachability.NO_BOARD, boardOwnedByOthers = true),
        )
    }

    /** And it stays a connecting state whatever the board path looks like. */
    @Test
    fun `a joining session connects rather than offering to connect a board`() {
        val decision = BoardDeliveryPolicy.resolve(
            sendMode = BoardSendMode.EXPLICIT,
            sessionRole = SessionRole.NONE,
            sessionConnecting = true,
            boardConnected = false,
            hasDirectPayload = true,
        )

        assertEquals(
            "the session owns delivery; offering a direct connect would be a second route",
            BoardDetailLampMode.CONNECTING,
            lamp(decision, reachability = BoardReachability.CONNECTING, boardOwnedByOthers = true),
        )
    }
}
