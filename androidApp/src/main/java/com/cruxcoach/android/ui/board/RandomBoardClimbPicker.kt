package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.KilterGradeMapper
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Picks one climb for the dice actions in the Board Playlist.
 *
 * The picker deliberately reads the persisted browser filters at tap time:
 * changing board, angle or grade in the browser therefore immediately changes
 * what the dice can add, without either playlist screen owning a second filter
 * state. SQLite supplies a shuffled candidate batch and the few filters that
 * live above SQL are applied here as well.
 */
class RandomBoardClimbPicker @Inject constructor(
    private val boardRepository: BoardRepository,
    private val personalBoardRepository: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val boardCellManager: BoardCellManager,
) {
    data class Pick(val climbUuid: String, val angle: Int)

    suspend fun pick(): Pick? {
        val filter = userPreferences.getBoardFilterSnapshot()
        // A participant may have browsed another board before joining. The
        // physical BoardCell identity is authoritative for the family; never
        // let that stale local choice add (for example) a MoonBoard problem to
        // a Kilter Board playlist.
        val boardBrand = boardCellManager.snapshot()?.physicalBoardId?.value
            ?.substringBefore(':')
            ?.takeUnless { it == "crux" || it == "legacy" }
            ?: filter.boardBrand
        val french = filter.gradeScale == GradeScale.FRENCH
        val minDifficulty: Double
        val maxDifficulty: Double
        val showUngraded: Boolean
        if (filter.ungradedOnly) {
            minDifficulty = 9999.0
            maxDifficulty = -9999.0
            showUngraded = true
        } else {
            minDifficulty = KilterGradeMapper.indexToFilterMin(filter.minGrade, french)
            maxDifficulty = KilterGradeMapper.indexToFilterMax(filter.maxGrade, french)
            showUngraded = false
        }
        val climbType = runCatching { ClimbTypeFilter.valueOf(filter.climbType) }
            .getOrDefault(ClimbTypeFilter.BOULDER)
        val origin = runCatching { OriginFilter.valueOf(filter.originFilter) }
            .getOrDefault(OriginFilter.ALL)
        val statuses = parseStatusFilter(filter.statusFilter)
        val sent = if (statuses.isEmpty()) emptySet()
            else personalBoardRepository.getUserSentClimbUuids()
        val attempted = if (statuses.isEmpty()) emptySet()
            else personalBoardRepository.getUserAttemptedClimbUuids()
        val ignored = personalBoardRepository.getIgnoredClimbUuids()
        val sizeId = userPreferences.boardProductSizeId.first()

        // A batch keeps the tap fast while still letting client-side filters
        // (status, source, benchmark and ignored climbs) reject candidates.
        // Re-querying RANDOM() gives a fresh batch instead of biasing toward a
        // fixed catalogue prefix.
        repeat(4) {
            val candidates = boardRepository.searchClimbsSorted(
                angle = filter.angle,
                layoutId = filter.layoutId,
                boardBrand = boardBrand,
                minDifficulty = minDifficulty,
                maxDifficulty = maxDifficulty,
                minAscensionists = filter.minAscensionists,
                sortField = ClimbSortField.RANDOM,
                sortDirection = SortDirection.DESC,
                limit = 32,
                climbType = climbType,
                selProductSizeId = sizeId,
                showUngraded = showUngraded,
            )
            val match = BrowserOriginFilter.apply(candidates, origin)
                .asSequence()
                .filterNot { it.uuid in ignored }
                .filter { !filter.benchmarkOnly || it.benchmarkDifficulty > 0.0 }
                .filter { climb ->
                    statuses.isEmpty() || when {
                        climb.uuid in sent -> ClimbStatusFilter.SENT in statuses
                        climb.uuid in attempted -> ClimbStatusFilter.ATTEMPTED in statuses
                        else -> ClimbStatusFilter.NEW in statuses
                    }
                }
                .firstOrNull()
            if (match != null) return Pick(match.uuid, filter.angle)
        }
        return null
    }
}
