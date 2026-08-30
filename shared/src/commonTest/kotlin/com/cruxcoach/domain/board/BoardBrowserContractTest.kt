package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BoardBrowserContractTest {
    @Test
    fun content_fixture_exposes_context_before_results_without_platform_types() {
        val content = browserContentFixture()

        assertEquals(BoardBrand.KILTER, content.board.brand)
        assertEquals(40, content.board.angle)
        assertEquals(BoardConnectionState.DISCONNECTED, content.connection.state)
        assertEquals(listOf("Quiet Riot", "Benchmark One", "Project Zero"), content.climbs.map { it.name })
        assertNull(content.activeSession)
    }

    @Test
    fun active_and_resting_fixtures_share_the_portable_session_contract() {
        val active = browserContentFixture(activeSession = activeSessionFixture())
        val resting = browserContentFixture(activeSession = restingSessionFixture())

        assertEquals(ActiveSessionPhase.ACTIVE, active.activeSession?.phase)
        assertEquals(ActiveSessionPhase.RESTING, resting.activeSession?.phase)
        assertEquals(75L, resting.activeSession?.restSecondsRemaining)
    }

    @Test
    fun recovery_states_remain_distinct() {
        val catalogue = BoardBrowserScreenState.Empty(
            kind = BrowserEmptyKind.CATALOGUE_MISSING,
            board = boardContextFixture(),
            connection = disconnectedFixture(),
        )
        val noResults = catalogue.copy(kind = BrowserEmptyKind.NO_RESULTS, activeFilterCount = 2)
        val error: BoardBrowserScreenState = BoardBrowserScreenState.Error(BrowserIssue.QUERY_FAILED)

        assertEquals(BrowserEmptyKind.CATALOGUE_MISSING, catalogue.kind)
        assertEquals(BrowserEmptyKind.NO_RESULTS, noResults.kind)
        assertIs<BoardBrowserScreenState.Error>(error)
    }

    @Test
    fun actions_describe_intent_without_navigation_or_repository_types() {
        val actions: List<BoardBrowserAction> = listOf(
            BoardBrowserAction.ChangeQuery("quiet"),
            BoardBrowserAction.OpenFilters,
            BoardBrowserAction.ChooseClimb("climb-quiet", 40),
            BoardBrowserAction.ContinueSession,
            BoardBrowserAction.Retry,
        )

        assertEquals(5, actions.size)
        assertEquals("quiet", (actions.first() as BoardBrowserAction.ChangeQuery).query)
    }
}

private fun boardContextFixture() = BrowserBoardContext(
    brand = BoardBrand.KILTER,
    layoutId = 1,
    productName = "Original 12x12",
    angle = 40,
)

private fun disconnectedFixture() = BrowserConnection(BoardConnectionState.DISCONNECTED)

private fun browserContentFixture(
    activeSession: ActiveSessionState? = null,
) = BoardBrowserScreenState.Content(
    board = boardContextFixture(),
    connection = disconnectedFixture(),
    query = "",
    activeFilterCount = 0,
    climbs = listOf(
        BrowserClimb("climb-quiet", "Quiet Riot", "Alex", 21.0, 4.3, 142, false, false),
        BrowserClimb("climb-benchmark", "Benchmark One", "Sam", 22.0, 4.8, 98, true, false),
        BrowserClimb("climb-project", "Project Zero", null, null, null, null, false, false),
    ),
    totalResultCount = 312,
    canLoadMore = true,
    activeSession = activeSession,
)

private fun activeSessionFixture() = ActiveSessionState(
    sessionId = "session-2026-08-30",
    startedAt = "2026-08-30T11:30:00Z",
    phase = ActiveSessionPhase.ACTIVE,
    elapsedSeconds = 1_800,
    pausedSeconds = 120,
    sendCount = 3,
    attemptCount = 7,
    currentClimb = ActiveSessionClimb("climb-quiet", "Quiet Riot", 40, false),
    connection = BoardConnectionState.CONNECTED,
)

private fun restingSessionFixture() = activeSessionFixture().copy(
    phase = ActiveSessionPhase.RESTING,
    restSecondsRemaining = 75,
)
