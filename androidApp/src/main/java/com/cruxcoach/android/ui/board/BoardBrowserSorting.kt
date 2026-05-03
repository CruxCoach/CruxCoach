package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.SortDirection

/**
 * In-memory sort fallback used by [BoardBrowserViewModel] for
 * post-filter pagination — extracted to a top-level `internal` helper
 * so unit tests can exercise the production logic directly instead of
 * maintaining a duplicate copy that drifts (the previous arrangement
 * silently lost the BENCHMARK_DIFFICULTY case).
 */
internal fun boardBrowserSortInKotlin(
    climbs: List<ClimbWithStats>,
    field: ClimbSortField,
    dir: SortDirection,
): List<ClimbWithStats> {
    val comparator = when (field) {
        ClimbSortField.QUALITY -> compareBy<ClimbWithStats> { it.qualityAverage ?: 0.0 }
        ClimbSortField.DIFFICULTY -> compareBy { it.difficultyAverage ?: 0.0 }
        ClimbSortField.ASCENSIONISTS -> compareBy { it.ascensionistCount ?: 0L }
        ClimbSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        ClimbSortField.BENCHMARK_DIFFICULTY -> compareBy { it.benchmarkDifficulty }
        else -> compareBy { it.ascensionistCount ?: 0L }
    }
    return if (dir == SortDirection.DESC) climbs.sortedWith(comparator.reversed())
    else climbs.sortedWith(comparator)
}
