package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BoardDeliveryDecision
import com.cruxcoach.domain.board.BoardDeliveryTarget
import com.cruxcoach.domain.board.ClimbDetailIssue
import com.cruxcoach.domain.board.ClimbDetailScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClimbDetailStateMapperTest {
    @Test
    fun `loading fallback and typed error remain distinct`() {
        assertIs<ClimbDetailScreenState.Loading>(ClimbDetailState().toPortableState())
        assertEquals(
            0,
            assertIs<ClimbDetailScreenState.LogbookOnly>(
                ClimbDetailState(
                    isLoading = false,
                    logbookOnly = LogbookOnlyState("historic-climb", emptyList()),
                ).toPortableState(),
            ).loggedAscentCount,
        )
        assertEquals(
            ClimbDetailIssue.NOT_FOUND,
            assertIs<ClimbDetailScreenState.Error>(
                ClimbDetailState(
                    isLoading = false,
                    issue = ClimbDetailIssue.NOT_FOUND,
                ).toPortableState(),
            ).issue,
        )
    }

    @Test
    fun `content maps climb identity angles and disconnected delivery`() {
        val portable = detailState(ConnectionState.DISCONNECTED).toPortableState()

        val content = assertIs<ClimbDetailScreenState.Content>(portable)
        assertEquals("mapped-detail", content.identity.uuid)
        assertEquals(40, content.identity.angle)
        assertEquals(listOf(30, 40), content.availableAngles)
        assertEquals(BoardConnectionState.DISCONNECTED, content.delivery.connection)
        assertTrue(content.isFavorited)
    }

    @Test
    fun `connected sending state keeps portable delivery decision and flags`() {
        val decision = BoardDeliveryDecision(
            BoardDeliveryTarget.DIRECT_BOARD,
            dispatchAutomatically = false,
            showAction = true,
        )
        val portable = detailState(ConnectionState.SENDING).copy(
            ble = BoardSendState(
                connectionState = ConnectionState.SENDING,
                isSending = true,
                success = true,
                warning = com.cruxcoach.android.R.string.board_send_warning_holds_not_lit,
                connectedViaRelay = true,
            ),
        ).toPortableState(decision, sessionOwned = true)

        val delivery = assertIs<ClimbDetailScreenState.Content>(portable).delivery
        assertEquals(BoardConnectionState.CONNECTED, delivery.connection)
        assertEquals(decision, delivery.decision)
        assertTrue(delivery.isSending)
        assertTrue(delivery.isSent)
        assertTrue(delivery.hasWarning)
        assertTrue(delivery.connectedViaRelay)
        assertTrue(delivery.sessionOwned)
    }
}

private fun detailState(connection: ConnectionState) = ClimbDetailState(
    isLoading = false,
    climb = TestClimb.stats(uuid = "mapped-detail", name = "Mapped Detail"),
    angle = 40,
    availableAngles = listOf(
        AngleOption(30, null, null, null, 0.0),
        AngleOption(40, 21.0, 4.5, 12, 0.0),
    ),
    isFavorited = true,
    ble = BoardSendState(connectionState = connection),
)
