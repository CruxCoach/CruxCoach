package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.AttemptOutcome
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardLogbookScreenState
import com.cruxcoach.domain.board.LogbookEntrySummary
import com.cruxcoach.domain.board.LogbookIssue
import com.cruxcoach.domain.board.LogbookSummary

internal fun BoardLogbookState.toPortableLogbookState(): BoardLogbookScreenState {
    if (isLoading && ascents.isEmpty()) return BoardLogbookScreenState.Loading
    if (error != null && ascents.isEmpty()) {
        return BoardLogbookScreenState.Error(LogbookIssue.INITIAL_LOAD_FAILED)
    }
    if (ascents.isEmpty()) return BoardLogbookScreenState.Empty

    val entries = ascents.map(AscentWithClimb::toPortableSummary)
    val visibleIds = entries.mapTo(mutableSetOf()) { it.entryUuid }
    return BoardLogbookScreenState.Content(
        entries = entries,
        summary = LogbookSummary(
            totalEntries = totalCount,
            totalSends = stats.totalSends.toLong(),
            totalAttempts = stats.totalAttempts.toLong(),
            uniqueClimbs = stats.uniqueClimbs.toLong(),
            hardestDifficulty = stats.hardestDifficultyInt.takeIf { stats.totalSends > 0 },
        ),
        selectedEntryUuids = selectedUuids.intersect(visibleIds),
        canLoadMore = canLoadMore,
        isLoadingMore = isLoadingMore,
        pageIssue = if (error != null) LogbookIssue.PAGE_LOAD_FAILED else null,
    )
}

private fun AscentWithClimb.toPortableSummary() = LogbookEntrySummary(
    entryUuid = uuid,
    climbUuid = climbUuid,
    climbName = climbName.trim().ifEmpty { null },
    outcome = if (isSend) AttemptOutcome.SEND else AttemptOutcome.ATTEMPT,
    angle = angle.toInt(),
    isMirrored = isMirror,
    attemptCount = bidCount.coerceAtLeast(1L),
    quality = quality?.takeIf { isSend && it in 1L..5L },
    difficultyAverage = difficultyAverage,
    boardBrand = BoardBrand.fromWire(boardBrand),
    layoutId = layoutId,
    climbedAt = climbedAt,
    comment = comment?.trim()?.ifEmpty { null },
)
