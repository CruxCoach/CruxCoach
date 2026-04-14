package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.BoardClimbParser

enum class ClimbSortField { QUALITY, DIFFICULTY, ASCENSIONISTS, REPEATS, NAME, HOLDS, BENCHMARK_DIFFICULTY }
enum class SortDirection { ASC, DESC }

enum class ClimbTypeFilter {
    BOULDER, ROUTE, ALL;
    fun minFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 2L; ALL -> 1L }
    fun maxFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 999L; ALL -> 999L }
}

data class AuroraClimbWithStats(
    val uuid: String,
    val layoutId: Long,
    val setterUsername: String?,
    val name: String,
    val frames: String,
    val framesCount: Long,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val description: String = "",
    val isNomatch: Boolean = false,
    val framesPace: Long = 0,
    val hsm: Long = 0,
    val benchmarkDifficulty: Double = 0.0,
    val faUsername: String? = null,
    val faAt: String? = null
) {
    /** Lazy-cached move count — avoids regex parsing on every access. */
    val moveCount: Int by lazy {
        if (frames.isNotEmpty()) BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames))
        else framesCount.toInt()
    }

    @Deprecated("Use moveCount property", replaceWith = ReplaceWith("moveCount"))
    fun estimateMoveCount(): Int = moveCount
}

data class AuroraAscentWithClimb(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val bidCount: Long,
    val quality: Long?,
    val difficulty: Long?,
    val comment: String?,
    val climbedAt: String,
    val climbName: String,
    val climbFrames: String,
    val difficultyAverage: Double?,
    val framesCount: Long = 1,
    val isSend: Boolean = true
)

data class HoldPosition(
    val holeId: Long,
    val productSizeId: Long,
    val x: Long,
    val y: Long,
    val ledPosition: Long,
    val placementId: Long
)

data class AngleClimbCount(
    val angle: Long,
    val climbCount: Long
)

data class AngleOption(
    val angle: Int,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val benchmarkDifficulty: Double
)

data class AuroraPlacement(
    val placementId: Long,
    val holeId: Long,
    val setId: Long,
    val x: Long,
    val y: Long
)

data class BoardSize(
    val id: Long,
    val productId: Long,
    val name: String,
    val edgeLeft: Long,
    val edgeRight: Long,
    val edgeBottom: Long,
    val edgeTop: Long,
    val imageFilename: String?
)

data class BoardImage(
    val id: Long,
    val productSizeId: Long,
    val layoutId: Long,
    val setId: Long,
    val imageFilename: String
)

data class AuroraHole(
    val id: Long,
    val productSizeId: Long,
    val x: Long,
    val y: Long,
    val mirroredHoleId: Long?
)

data class LedGridPoint(
    val placementId: Long,
    val x: Long,
    val y: Long,
    val ledPosition: Long
)


data class ClimbList(
    val id: Long,
    val name: String,
    val isBuiltin: Boolean,
    val createdAt: String,
    val climbCount: Long
)

data class ClimbListEntry(
    val addedAt: String,
    val climb: AuroraClimbWithStats
)

data class BoardSession(
    val id: Long,
    val startedAt: String,
    val endedAt: String?,
    val totalDurationSeconds: Long,
    val pauseDurationSeconds: Long,
    val ascentCount: Long,
    val bidCount: Long
)

data class ClimbFrameRow(
    val uuid: String,
    val frames: String
)

// ── Focused sub-interfaces ──────────────────────────────────

/** Climb search, filter, and count queries. All browse/search/count methods require layoutId to scope results to a board type. */
interface BoardClimbQueries {
    fun searchClimbsByName(query: String, angle: Int, layoutId: Int, sortField: ClimbSortField = ClimbSortField.QUALITY, sortDirection: SortDirection = SortDirection.DESC, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<AuroraClimbWithStats>
    fun searchClimbsSorted(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<AuroraClimbWithStats>
    fun getClimbByUuid(uuid: String, angle: Int): AuroraClimbWithStats?
    fun countFilteredClimbsFast(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int): Long
    fun countFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun getClimbCount(): Long
    fun getStatCount(): Long
    fun climbExistsByUuid(uuid: String): Boolean
    fun statExistsByUuid(uuid: String): Boolean
    fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<AngleClimbCount>
    fun getAnglesForClimb(climbUuid: String): List<AngleOption>
    fun countNomatchClimbs(): Long
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<AuroraClimbWithStats>
    /** Fetch climbs by UUID list at a given angle, no additional filters. */
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<AuroraClimbWithStats>
    /** Find climb UUIDs whose frames contain the given placement ID. */
    fun searchClimbUuidsByHold(holdPattern: String, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<String>
    /** Find climb UUIDs whose frames contain ALL given hold patterns (single DB pass). */
    fun searchClimbUuidsByAllHolds(holdPatterns: List<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Set<String>
    /** Get all frames for heatmap computation within current browse filters. */
    fun getAllFramesForHeatmap(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow>
}

/** Board layout, placement, LED, and product-size queries. */
interface BoardLayoutQueries {
    fun getAllPlacements(): List<AuroraPlacement>
    fun getProductSize(id: Int): BoardSize?
    fun getAllProductSizes(): List<BoardSize>
    fun getBoardImages(productSizeId: Int, layoutId: Int): List<BoardImage>
    fun getPlacementLedMap(productSizeId: Int): Map<Int, Int>
    fun getMirrorPlacementMap(productSizeId: Int): Map<Int, Int>
    fun countLeds(): Long
    fun getLedGrid(productSizeId: Int): List<LedGridPoint>
}

/** Write operations: upserts, sync state, transactions (public board data only). */
interface BoardWriteOperations {
    fun upsertClimb(uuid: String, layoutId: Long, setter: String?, name: String, frames: String,
                    framesCount: Long, isListed: Long, edgeLeft: Long?, edgeRight: Long?,
                    edgeBottom: Long?, edgeTop: Long?, createdAt: String?,
                    description: String = "", isNomatch: Long = 0, framesPace: Long = 0, hsm: Long = 0)
    fun upsertClimbStat(climbUuid: String, angle: Long, displayDifficulty: Double?,
                        difficultyAverage: Double?, qualityAverage: Double?,
                        ascensionistCount: Long?, benchmarkDifficulty: Double?,
                        faUsername: String? = null, faAt: String? = null,
                        officialKilterDifficulty: Long? = null)
    fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long,
                           ledPosition: Long, placementId: Long)
    fun upsertLed(holeId: Long, productSizeId: Long, position: Long)
    fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?)
    fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long)
    fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long,
                          edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?)
    fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String)
    fun upsertSyncState(tableName: String, lastSynchronizedAt: String)
    fun getSyncState(tableName: String): String?
    fun getAllClimbUuids(): Set<String>
    fun getAllStatKeys(): Map<Pair<String, Long>, Long?>
    fun runInTransaction(block: () -> Unit)
    fun deleteAllBoardData()
}

data class RawAscent(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val attemptId: Long,
    val bidCount: Long,
    val quality: Long?,
    val difficulty: Long?,
    val isBenchmark: Boolean,
    val comment: String?,
    val climbedAt: String,
    val synced: Boolean,
    val gymUuid: String? = null,
    val wallUuid: String? = null,
    val productLayoutUuid: String? = null
)

data class RawBid(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val bidCount: Long,
    val comment: String?,
    val climbedAt: String,
    val synced: Boolean,
    val gymUuid: String? = null,
    val wallUuid: String? = null,
    val productLayoutUuid: String? = null
)

data class RawClimbListEntry(
    val listId: Long,
    val climbUuid: String,
    val addedAt: String
)

// ── Composite interface (backward-compatible) ───────────────

interface BoardRepository :
    BoardClimbQueries,
    BoardLayoutQueries,
    BoardWriteOperations
