package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.domain.board.BoardBrowserScreenState
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BrowserEmptyKind
import com.cruxcoach.domain.board.BrowserIssue
import com.cruxcoach.domain.board.BrowserLoadingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoardBrowserStateMapperTest {
    @Test
    fun `database preparation is distinct from result loading`() {
        val preparing = BoardBrowserState(isLoading = true, hasBoardData = false).toPortableState()
        val results = BoardBrowserState(isLoading = true, hasBoardData = true).toPortableState()

        assertEquals(BrowserLoadingKind.PREPARING_DATABASE, assertIs<BoardBrowserScreenState.Loading>(preparing).kind)
        assertEquals(BrowserLoadingKind.RESULTS, assertIs<BoardBrowserScreenState.Loading>(results).kind)
    }

    @Test
    fun `catalogue and filtered empty states retain different recovery paths`() {
        val missing = BoardBrowserState(
            isLoading = false,
            hasBoardData = true,
            activeBrandHasCatalogue = false,
        ).toPortableState()
        val filtered = BoardBrowserState(
            isLoading = false,
            hasBoardData = true,
            activeBrandHasCatalogue = true,
        ).toPortableState()

        assertEquals(BrowserEmptyKind.CATALOGUE_MISSING, assertIs<BoardBrowserScreenState.Empty>(missing).kind)
        assertEquals(BrowserEmptyKind.NO_RESULTS, assertIs<BoardBrowserScreenState.Empty>(filtered).kind)
    }

    @Test
    fun `fatal query error is not collapsed into an empty catalogue`() {
        val portable = BoardBrowserState(
            isLoading = false,
            hasBoardData = true,
            error = "SQLite unavailable",
        ).toPortableState()

        assertEquals(BrowserIssue.QUERY_FAILED, assertIs<BoardBrowserScreenState.Error>(portable).issue)
    }

    @Test
    fun `content maps board connection filters and climb identity`() {
        val portable = BoardBrowserState(
            isLoading = false,
            hasBoardData = true,
            climbs = listOf(TestClimb.stats(uuid = "mapped-climb", name = "Mapped")),
            filteredCount = 42,
            canLoadMore = true,
            filter = BrowserFilterState(searchQuery = "map", minAscensionists = 5, benchmarkOnly = true),
            ble = BrowserBleState(ConnectionState.SENDING, "Kilter Board"),
        ).toPortableState()

        val content = assertIs<BoardBrowserScreenState.Content>(portable)
        assertEquals("mapped-climb", content.climbs.single().uuid)
        assertEquals("map", content.query)
        assertEquals(2, content.activeFilterCount)
        assertEquals(42, content.totalResultCount)
        assertEquals(BoardConnectionState.CONNECTED, content.connection.state)
        assertEquals("Kilter Board", content.connection.boardName)
    }

    @Test
    fun `background failure keeps existing content available`() {
        val portable = BoardBrowserState(
            isLoading = false,
            hasBoardData = true,
            climbs = listOf(TestClimb.stats(uuid = "still-visible")),
            error = "next page failed",
        ).toPortableState()

        assertEquals(
            BrowserIssue.LOAD_MORE_FAILED,
            assertIs<BoardBrowserScreenState.Content>(portable).transientIssue,
        )
    }
}
