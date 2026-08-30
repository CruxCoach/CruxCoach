package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HistoryRetentionPeriod
import com.cruxcoach.domain.board.ProgressHistoryEntry
import com.cruxcoach.domain.board.ProgressHistoryIssue
import com.cruxcoach.domain.board.ProgressHistoryScreenState

/**
 * Projects the current Android history orchestrator into the portable screen
 * contract. Repository failures are supplied as stable categories rather than
 * leaking exception messages into renderer state.
 */
fun BoardClimbHistoryState.toPortableState(
    isLoading: Boolean = false,
    issue: ProgressHistoryIssue? = null,
): ProgressHistoryScreenState {
    if (isLoading && entries.isEmpty()) return ProgressHistoryScreenState.Loading
    if (issue != null && entries.isEmpty()) {
        return ProgressHistoryScreenState.Error(
            issue = issue,
            retention = retention.toPortablePeriod(),
        )
    }
    if (entries.isEmpty()) {
        return ProgressHistoryScreenState.Empty(retention.toPortablePeriod())
    }
    return ProgressHistoryScreenState.Content(
        entries = entries.map { entry ->
            ProgressHistoryEntry(
                id = entry.id,
                climbUuid = entry.climbUuid,
                climbName = entry.climbName,
                angle = entry.angle,
                boardBrand = BoardBrand.fromWire(entry.boardBrand),
                layoutId = entry.layoutId,
                difficultyAverage = entry.difficultyAverage,
                recordedAt = entry.recordedAt,
            )
        },
        retention = retention.toPortablePeriod(),
        selectedIds = selectedIds,
        transientIssue = issue,
    )
}

private fun HistoryRetention.toPortablePeriod(): HistoryRetentionPeriod = when (this) {
    HistoryRetention.OFF -> HistoryRetentionPeriod.OFF
    HistoryRetention.DAYS_30 -> HistoryRetentionPeriod.DAYS_30
    HistoryRetention.DAYS_90 -> HistoryRetentionPeriod.DAYS_90
    HistoryRetention.DAYS_365 -> HistoryRetentionPeriod.DAYS_365
}
