package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
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
/** The outcome of one dice roll, including the two ways it can decline. */
sealed interface RandomClimbRoll {
    data class Picked(val climbUuid: String, val angle: Int) : RandomClimbRoll

    /** The catalogue holds nothing that fits the filters on that board. */
    data object NoMatch : RandomClimbRoll

    /**
     * A group is on a board this device has never browsed and its list is
     * still empty, so nothing here knows which layout or angle would fit.
     *
     * Kept apart from [NoMatch] because the two need different answers: one
     * is "loosen your filters", the other is "nobody has put a climb on this
     * board yet". Guessing instead — which is what querying the group's brand
     * with the previously browsed board's layout amounted to — produces an
     * impossible combination that returns nothing for as long as the group
     * lasts, under a message blaming filters the user cannot even see.
     */
    data object BoardUnknown : RandomClimbRoll
}

/** Board, layout and angle a dice roll must actually fit. */
internal data class RandomClimbBoard(
    val boardBrand: String,
    val layoutId: Int,
    val angle: Int,
)

class RandomBoardClimbPicker @Inject constructor(
    private val boardRepository: BoardRepository,
    private val personalBoardRepository: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val boardCellManager: BoardCellManager,
) {
    /**
     * Which board a roll is for.
     *
     * The persisted browser filters describe the board this device was last
     * looking at, which is not necessarily the board it is standing in front
     * of. A BoardCell's physical identity settles the family; the layout and
     * the angle have to come from the group as well, because the identity
     * carries neither — so they are read off a climb the group already put on
     * its list. Only when the group has no climbs yet and its family differs
     * from the local filter is there genuinely nothing to go on.
     */
    private fun resolveBoard(filter: com.cruxcoach.android.data.BoardFilterSnapshot): RandomClimbBoard? {
        val local = RandomClimbBoard(filter.boardBrand, filter.layoutId, filter.angle)
        val snapshot = boardCellManager.snapshot() ?: return local
        val cellBrand = snapshot.physicalBoardId.value
            .substringBefore(':')
            .takeUnless { it == "crux" || it == "legacy" }
            ?: return local

        // A climb the group has already agreed on: the occurrence it is
        // pointing at, then whatever the board is showing, then the head of
        // the list. Any of the three was put there by somebody standing in
        // front of this board.
        val playlist = snapshot.playlist
        val reference = playlist.entries.firstOrNull { it.entryId == playlist.currentEntryId }
            ?.let { BoardPlaylistReference(it.climbUuid, it.angle) }
            ?: snapshot.projection?.let { BoardPlaylistReference(it.climbUuid, it.angle) }
            ?: playlist.entries.firstOrNull()
                ?.let { BoardPlaylistReference(it.climbUuid, it.angle) }

        if (reference != null) {
            val climb = boardRepository.getClimbByUuid(reference.climbUuid, reference.angle)
                ?: boardRepository.getClimbByUuidNormalized(reference.climbUuid, reference.angle)
            if (climb != null && climb.boardBrand == cellBrand) {
                return RandomClimbBoard(cellBrand, climb.layoutId.toInt(), reference.angle)
            }
        }
        // Same family: the locally configured layout and angle are the user's
        // own board and the best answer available.
        if (cellBrand == filter.boardBrand) return local.copy(boardBrand = cellBrand)
        return null
    }

    suspend fun roll(): RandomClimbRoll {
        val filter = userPreferences.getBoardFilterSnapshot()
        val board = resolveBoard(filter) ?: return RandomClimbRoll.BoardUnknown
        val boardBrand = board.boardBrand
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
        // The persisted filters were last edited on whatever board the user
        // was browsing. On a board that has no routes, no benchmarks and no
        // BoardSesh provenance, carrying those over would leave the dice
        // silently returning nothing forever — the same trap BoardBrowsePolicy
        // exists to keep out of the browser, so it decides here too.
        val brand = BoardBrand.fromWire(boardBrand)
        val climbType = BoardBrowsePolicy.climbType(
            brand,
            runCatching { ClimbTypeFilter.valueOf(filter.climbType) }
                .getOrDefault(ClimbTypeFilter.BOULDER),
        )
        val origin = BoardBrowsePolicy.origin(
            brand,
            runCatching { OriginFilter.valueOf(filter.originFilter) }
                .getOrDefault(OriginFilter.ALL),
        )
        val benchmarkOnly = BoardBrowsePolicy.benchmarkOnly(brand, filter.benchmarkOnly)
        val statuses = parseStatusFilter(filter.statusFilter)
        val sent = if (statuses.isEmpty()) emptySet()
            else personalBoardRepository.getUserSentClimbUuids()
        val attempted = if (statuses.isEmpty()) emptySet()
            else personalBoardRepository.getUserAttemptedClimbUuids()
        val ignored = personalBoardRepository.getIgnoredClimbUuids()
        // Quantum model membership is authoritative in its own catalogue, so
        // the product-size edge predicate must stay inert there.
        val sizeId = BoardBrowsePolicy.productSizeId(
            brand,
            userPreferences.boardProductSizeId.first(),
        )
        val exclusionMask = BoardBrowsePolicy.exclusionMask(brand, 0L, filter.quantumRuleMask)

        // A batch keeps the tap fast while still letting client-side filters
        // (status, source, benchmark and ignored climbs) reject candidates.
        // Re-querying RANDOM() gives a fresh batch instead of biasing toward a
        // fixed catalogue prefix.
        repeat(4) {
            val candidates = boardRepository.searchClimbsSorted(
                angle = board.angle,
                layoutId = board.layoutId,
                boardBrand = boardBrand,
                minDifficulty = minDifficulty,
                maxDifficulty = maxDifficulty,
                minAscensionists = filter.minAscensionists,
                sortField = ClimbSortField.RANDOM,
                sortDirection = SortDirection.DESC,
                limit = 32,
                climbType = climbType,
                selProductSizeId = sizeId,
                hsmExcludedMask = exclusionMask,
                showUngraded = showUngraded,
            )
            val match = BrowserOriginFilter.apply(candidates, origin)
                .asSequence()
                .filterNot { it.uuid in ignored }
                .filter { !benchmarkOnly || it.benchmarkDifficulty > 0.0 }
                .filter { climb ->
                    statuses.isEmpty() || when {
                        climb.uuid in sent -> ClimbStatusFilter.SENT in statuses
                        climb.uuid in attempted -> ClimbStatusFilter.ATTEMPTED in statuses
                        else -> ClimbStatusFilter.NEW in statuses
                    }
                }
                .firstOrNull()
            if (match != null) return RandomClimbRoll.Picked(match.uuid, board.angle)
        }
        return RandomClimbRoll.NoMatch
    }
}

/** One climb the group has already agreed on, as a board fingerprint. */
private data class BoardPlaylistReference(val climbUuid: String, val angle: Int)
