package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClimbDetailContractTest {
    @Test
    fun disconnected_and_connected_fixtures_change_only_delivery_context() {
        val disconnected = detailFixture(BoardConnectionState.DISCONNECTED)
        val connected = detailFixture(BoardConnectionState.CONNECTED)

        assertEquals(disconnected.identity, connected.identity)
        assertEquals(disconnected.holds, connected.holds)
        assertEquals(BoardDetailLampMode.CONNECT, lampMode(disconnected))
        assertEquals(BoardDetailLampMode.LIGHT, lampMode(connected))
    }

    @Test
    fun content_keeps_board_identity_and_attempt_context_portable() {
        val content = detailFixture(BoardConnectionState.CONNECTED)

        assertEquals(BoardBrand.KILTER, content.identity.boardBrand)
        assertEquals(listOf(30, 35, 40, 45), content.availableAngles)
        assertEquals(listOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH), content.holds.map { it.roleId })
        assertEquals(2, content.loggedAscentCount)
        assertTrue(content.isFavorited)
        assertFalse(content.hasPersonalNote)
    }

    @Test
    fun fallback_and_error_are_not_collapsed_into_empty_content() {
        val fallback: ClimbDetailScreenState = ClimbDetailScreenState.LogbookOnly("old-climb", 3)
        val error: ClimbDetailScreenState = ClimbDetailScreenState.Error(ClimbDetailIssue.NOT_FOUND)

        assertEquals(3, assertIs<ClimbDetailScreenState.LogbookOnly>(fallback).loggedAscentCount)
        assertEquals(ClimbDetailIssue.NOT_FOUND, assertIs<ClimbDetailScreenState.Error>(error).issue)
    }

    @Test
    fun actions_do_not_embed_navigation_ble_or_compose_types() {
        val actions: List<ClimbDetailAction> = listOf(
            ClimbDetailAction.ChooseAngle(40),
            ClimbDetailAction.ConnectBoard,
            ClimbDetailAction.DeliverToBoard,
            ClimbDetailAction.LogAttempt,
            ClimbDetailAction.LogSend,
        )

        assertEquals(5, actions.size)
    }
}

private fun detailFixture(connection: BoardConnectionState): ClimbDetailScreenState.Content {
    val boardConnected = connection == BoardConnectionState.CONNECTED
    val decision = ClimbDeliveryPolicy.resolve(
        sendMode = ClimbDeliveryMode.EXPLICIT,
        boardBrand = BoardBrand.KILTER,
        sessionRole = SharedBoardSessionRole.NONE,
        boardConnected = boardConnected,
        hasDirectPayload = true,
    )
    return ClimbDetailScreenState.Content(
        identity = ClimbDetailIdentity(
            uuid = "quiet-riot",
            name = "Quiet Riot",
            setterName = "Alex",
            boardBrand = BoardBrand.KILTER,
            layoutId = 1,
            angle = 40,
            difficultyAverage = 21.0,
            qualityAverage = 4.4,
            isBenchmark = false,
            isRoute = false,
            isMirrored = false,
            isMirrorable = false,
        ),
        holds = listOf(
            BoardHold(1, HoldRole.START),
            BoardHold(2, HoldRole.HAND),
            BoardHold(3, HoldRole.FINISH),
        ),
        availableAngles = listOf(30, 35, 40, 45),
        delivery = ClimbDetailDeliveryState(connection, decision),
        isFavorited = true,
        isIgnored = false,
        hasPersonalNote = false,
        loggedAscentCount = 2,
    )
}

private fun lampMode(content: ClimbDetailScreenState.Content): BoardDetailLampMode =
    ClimbDeliveryPolicy.lampMode(
        decision = content.delivery.decision,
        hasDirectPayload = content.holds.isNotEmpty(),
        boardConnected = content.delivery.connection == BoardConnectionState.CONNECTED,
        boardOwnedByOthers = content.delivery.sessionOwned,
        countdownRunning = false,
    )
