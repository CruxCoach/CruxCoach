package com.cruxcoach.domain.board

/** Why the board browser is waiting. Platform renderers own localized copy. */
enum class BrowserLoadingKind {
    PREPARING_DATABASE,
    CATALOGUE,
    RESULTS,
}

enum class BrowserEmptyKind {
    CATALOGUE_MISSING,
    NO_RESULTS,
}

/** Stable issue categories; raw exception messages do not become UI contracts. */
enum class BrowserIssue {
    QUERY_FAILED,
    LOAD_MORE_FAILED,
    UNKNOWN,
}

data class BrowserBoardContext(
    val brand: BoardBrand,
    val layoutId: Long,
    val productName: String?,
    val angle: Int,
)

data class BrowserConnection(
    val state: BoardConnectionState,
    val boardName: String? = null,
)

/** Portable, renderer-neutral projection for one browser result. */
data class BrowserClimb(
    val uuid: String,
    val name: String,
    val setterName: String?,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val isBenchmark: Boolean,
    val isRoute: Boolean,
)

sealed interface BoardBrowserScreenState {
    data class Loading(
        val kind: BrowserLoadingKind,
        val board: BrowserBoardContext? = null,
    ) : BoardBrowserScreenState

    data class FirstRun(
        val selectedBoard: BrowserBoardContext? = null,
        val boardSelectionAvailable: Boolean = true,
        val connectionAvailable: Boolean = true,
    ) : BoardBrowserScreenState

    data class Empty(
        val kind: BrowserEmptyKind,
        val board: BrowserBoardContext,
        val connection: BrowserConnection,
        val query: String = "",
        val activeFilterCount: Int = 0,
    ) : BoardBrowserScreenState

    data class Error(
        val issue: BrowserIssue,
        val board: BrowserBoardContext? = null,
        val canRetry: Boolean = true,
    ) : BoardBrowserScreenState

    data class Content(
        val board: BrowserBoardContext,
        val connection: BrowserConnection,
        val query: String,
        val activeFilterCount: Int,
        val climbs: List<BrowserClimb>,
        val totalResultCount: Long?,
        val canLoadMore: Boolean,
        val activeSession: ActiveSessionState? = null,
    ) : BoardBrowserScreenState
}

/** User intents only. Android and iOS own navigation and side effects. */
sealed interface BoardBrowserAction {
    data class ChangeQuery(val query: String) : BoardBrowserAction
    data class ChooseClimb(val uuid: String, val angle: Int) : BoardBrowserAction
    data object OpenFilters : BoardBrowserAction
    data object SelectBoard : BoardBrowserAction
    data object ConnectBoard : BoardBrowserAction
    data object ContinueSession : BoardBrowserAction
    data object LoadCatalogue : BoardBrowserAction
    data object Retry : BoardBrowserAction
    data object ClearFilters : BoardBrowserAction
    data object RandomClimb : BoardBrowserAction
    data object CreateClimb : BoardBrowserAction
    data object OpenMap : BoardBrowserAction
    data object OpenLogbook : BoardBrowserAction
    data object OpenLists : BoardBrowserAction
    data object OpenSettings : BoardBrowserAction
    data object LoadMore : BoardBrowserAction
}
