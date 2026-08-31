package com.cruxcoach.domain.board

/** Portable retention choice for device-local climb history. */
enum class HistoryRetentionPeriod(val days: Int?) {
    OFF(null),
    DAYS_30(30),
    DAYS_90(90),
    DAYS_365(365),
}

enum class ProgressHistoryIssue {
    LOAD_FAILED,
    RETENTION_UPDATE_FAILED,
    DELETE_FAILED,
    UNKNOWN,
}

/** Renderer-neutral history row. Platform code owns date and grade formatting. */
data class ProgressHistoryEntry(
    val id: Long,
    val climbUuid: String,
    val climbName: String,
    val angle: Int,
    val boardBrand: BoardBrand,
    val layoutId: Long?,
    val difficultyAverage: Double?,
    val recordedAt: String,
)

sealed interface ProgressHistoryScreenState {
    data object Loading : ProgressHistoryScreenState

    data class Empty(
        val retention: HistoryRetentionPeriod,
        /** A recoverable retention action failure while the empty state stays usable. */
        val transientIssue: ProgressHistoryIssue? = null,
    ) : ProgressHistoryScreenState

    data class Error(
        val issue: ProgressHistoryIssue,
        val retention: HistoryRetentionPeriod?,
        val canRetry: Boolean = true,
    ) : ProgressHistoryScreenState

    data class Content(
        val entries: List<ProgressHistoryEntry>,
        val retention: HistoryRetentionPeriod,
        val selectedIds: Set<Long> = emptySet(),
        /** A recoverable action failure while the existing list stays usable. */
        val transientIssue: ProgressHistoryIssue? = null,
    ) : ProgressHistoryScreenState {
        val hasSelection: Boolean get() = selectedIds.isNotEmpty()
        val allSelected: Boolean
            get() = entries.isNotEmpty() && selectedIds == entries.mapTo(mutableSetOf()) { it.id }
    }
}

/** User intent only; repositories, clocks, and navigation stay platform-owned. */
sealed interface ProgressHistoryAction {
    data class ChooseRetention(val retention: HistoryRetentionPeriod) : ProgressHistoryAction
    data class OpenClimb(val climbUuid: String, val angle: Int) : ProgressHistoryAction
    data class ToggleSelection(val entryId: Long) : ProgressHistoryAction
    data object ToggleSelectAll : ProgressHistoryAction
    data object ConfirmDeleteSelection : ProgressHistoryAction
    data object DismissDeleteConfirmation : ProgressHistoryAction
    data object Retry : ProgressHistoryAction
    data object NavigateBack : ProgressHistoryAction
}
