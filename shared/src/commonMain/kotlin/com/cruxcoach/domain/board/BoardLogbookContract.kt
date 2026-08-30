package com.cruxcoach.domain.board

enum class LogbookIssue {
    INITIAL_LOAD_FAILED,
    PAGE_LOAD_FAILED,
}

data class LogbookEntrySummary(
    val entryUuid: String,
    val climbUuid: String,
    val climbName: String?,
    val outcome: AttemptOutcome,
    val angle: Int,
    val isMirrored: Boolean,
    val attemptCount: Long,
    val quality: Long?,
    val difficultyAverage: Double?,
    val boardBrand: BoardBrand,
    val layoutId: Long?,
    val climbedAt: String,
    val comment: String?,
) {
    init {
        require(entryUuid.isNotBlank())
        require(climbUuid.isNotBlank())
        require(climbName == null || climbName.isNotBlank())
        require(attemptCount >= 1L)
        require(quality == null || quality in 1L..5L)
        require(outcome == AttemptOutcome.SEND || quality == null)
        require(climbedAt.isNotBlank())
    }
}

data class LogbookSummary(
    val totalEntries: Long,
    val totalSends: Long,
    val totalAttempts: Long,
    val uniqueClimbs: Long,
    val hardestDifficulty: Int?,
) {
    init {
        require(totalEntries >= 0L)
        require(totalSends >= 0L)
        require(totalAttempts >= 0L)
        require(uniqueClimbs >= 0L)
    }
}

sealed interface BoardLogbookScreenState {
    data object Loading : BoardLogbookScreenState
    data object Empty : BoardLogbookScreenState
    data class Error(val issue: LogbookIssue) : BoardLogbookScreenState
    data class Content(
        val entries: List<LogbookEntrySummary>,
        val summary: LogbookSummary,
        val selectedEntryUuids: Set<String> = emptySet(),
        val canLoadMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val pageIssue: LogbookIssue? = null,
    ) : BoardLogbookScreenState {
        init {
            require(entries.isNotEmpty())
            require(selectedEntryUuids.all { selected -> entries.any { it.entryUuid == selected } })
            require(pageIssue == null || pageIssue == LogbookIssue.PAGE_LOAD_FAILED)
        }
    }
}

sealed interface BoardLogbookAction {
    data object Retry : BoardLogbookAction
    data object LoadMore : BoardLogbookAction
    data object OpenStats : BoardLogbookAction
    data object SelectAll : BoardLogbookAction
    data object ClearSelection : BoardLogbookAction
    data object RequestDeleteSelection : BoardLogbookAction
    data class OpenEntry(val entryUuid: String) : BoardLogbookAction
    data class ToggleSelection(val entryUuid: String) : BoardLogbookAction
    data class EditEntry(val entryUuid: String) : BoardLogbookAction
}
