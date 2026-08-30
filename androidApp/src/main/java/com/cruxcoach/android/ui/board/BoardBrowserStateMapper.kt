package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.domain.board.ActiveSessionState
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardBrowserScreenState
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BrowserBoardContext
import com.cruxcoach.domain.board.BrowserClimb
import com.cruxcoach.domain.board.BrowserConnection
import com.cruxcoach.domain.board.BrowserEmptyKind
import com.cruxcoach.domain.board.BrowserIssue
import com.cruxcoach.domain.board.BrowserLoadingKind

/**
 * Projects the current Android orchestrator state into the portable browser
 * contract. Raw exceptions and Android BLE types stop at this boundary.
 */
fun BoardBrowserState.toPortableState(
    activeSession: ActiveSessionState? = null,
): BoardBrowserScreenState {
    val boardContext = BrowserBoardContext(
        brand = BoardBrand.fromWire(filter.boardBrand),
        layoutId = filter.layoutId.toLong(),
        productName = boardSize?.name,
        angle = filter.angle,
    )
    val connection = BrowserConnection(
        state = ble.connectionState.toPortableState(),
        boardName = ble.connectedBoardName,
    )

    if (isLoading && climbs.isEmpty()) {
        val kind = when {
            !hasBoardData -> BrowserLoadingKind.PREPARING_DATABASE
            activeBrandImporting -> BrowserLoadingKind.CATALOGUE
            else -> BrowserLoadingKind.RESULTS
        }
        return BoardBrowserScreenState.Loading(kind, boardContext)
    }
    if (issue != null && climbs.isEmpty()) {
        return BoardBrowserScreenState.Error(issue, boardContext)
    }
    if (climbs.isEmpty()) {
        return BoardBrowserScreenState.Empty(
            kind = if (!activeBrandHasCatalogue || !hasBoardData) {
                BrowserEmptyKind.CATALOGUE_MISSING
            } else {
                BrowserEmptyKind.NO_RESULTS
            },
            board = boardContext,
            connection = connection,
            query = filter.searchQuery,
            activeFilterCount = filter.activeFilterCount(),
        )
    }
    return BoardBrowserScreenState.Content(
        board = boardContext,
        connection = connection,
        query = filter.searchQuery,
        activeFilterCount = filter.activeFilterCount(),
        climbs = climbs.map { climb ->
            BrowserClimb(
                uuid = climb.uuid,
                name = climb.name,
                setterName = climb.setterUsername,
                difficultyAverage = climb.difficultyAverage,
                qualityAverage = climb.qualityAverage,
                ascensionistCount = climb.ascensionistCount,
                isBenchmark = climb.benchmarkDifficulty > 0.0,
                isRoute = climb.isRoute,
            )
        },
        totalResultCount = filteredCount.takeIf { it >= 0 },
        canLoadMore = canLoadMore,
        activeSession = activeSession,
        transientIssue = issue,
    )
}

private fun BrowserFilterState.activeFilterCount(): Int = listOf(
    minGradeIndex != BrowserFilterState.DEFAULT_MIN_GRADE_INDEX ||
        maxGradeIndex != BrowserFilterState.DEFAULT_MAX_GRADE_INDEX,
    minAscensionists > 0,
    statusFilter.isNotEmpty(),
    benchmarkOnly,
    originFilter != OriginFilter.ALL,
    quantumRuleMask != 0L,
    quantumOverlapFilter != com.cruxcoach.domain.board.QuantumOverlapFilter.OFF,
    myClimbsOnly,
    ungradedOnly,
).count { it }

private fun ConnectionState.toPortableState(): BoardConnectionState = when (this) {
    ConnectionState.DISCONNECTED -> BoardConnectionState.DISCONNECTED
    ConnectionState.CONNECTING -> BoardConnectionState.CONNECTING
    ConnectionState.CONNECTED, ConnectionState.SENDING -> BoardConnectionState.CONNECTED
}
