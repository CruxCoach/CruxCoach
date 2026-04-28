package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.BoardClimbParser

enum class ClimbSortField { QUALITY, DIFFICULTY, ASCENSIONISTS, REPEATS, NAME, HOLDS, BENCHMARK_DIFFICULTY }
enum class SortDirection { ASC, DESC }

enum class ClimbTypeFilter {
    BOULDER, ROUTE, ALL;
    fun minFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 2L; ALL -> 1L }
    fun maxFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 999L; ALL -> 999L }
}

data class ClimbWithStats(
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
    val faAt: String? = null,
    /** Pre-computed move count from DB. 0 = not yet computed (fallback to live parse). */
    val storedMoveCount: Long = 0
) {
    /** True when this climb is a multi-frame route (not a boulder). */
    val isRoute: Boolean get() = framesCount > 1

    /** Move count: uses pre-computed DB value, falls back to live parse from frames. */
    val moveCount: Int by lazy {
        if (storedMoveCount > 0) storedMoveCount.toInt()
        else if (frames.isNotEmpty()) BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames))
        else 0
    }
}

data class AscentWithClimb(
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

data class BoardPlacement(
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

data class BoardHole(
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


data class Climb_lists(
    val id: Long,
    val name: String,
    val isBuiltin: Boolean,
    val createdAt: String,
    val climbCount: Long
)

data class Climb_list_entries(
    val addedAt: String,
    val climb: ClimbWithStats
)

data class Board_sessions(
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
    fun searchClimbsByName(query: String, angle: Int, layoutId: Int, sortField: ClimbSortField = ClimbSortField.QUALITY, sortDirection: SortDirection = SortDirection.DESC, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<ClimbWithStats>
    fun searchClimbsSorted(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<ClimbWithStats>
    fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats?
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
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbWithStats>
    /** Fetch climbs by UUID list at a given angle, no additional filters. */
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats>
    /** Find climb UUIDs whose frames contain the given placement ID. */
    fun searchClimbUuidsByHold(holdPattern: String, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<String>
    /** Find climb UUIDs whose frames contain ALL given hold patterns (single DB pass). */
    fun searchClimbUuidsByAllHolds(holdPatterns: List<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Set<String>
    /** Get all frames for heatmap computation within current browse filters. */
    fun getAllFramesForHeatmap(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow>
}

/** Board layout, placement, LED, and product-size queries. */
interface BoardLayoutQueries {
    fun getAllPlacements(): List<BoardPlacement>
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
                    description: String = "", isNomatch: Long = 0, framesPace: Long = 0, hsm: Long = 0,
                    moveCount: Long = 0)
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
    val productLayoutUuid: String? = null,
    /** Optimistic-locking token snapshot at read time. Pass to
     *  [PersonalBoardRepository.markAscentSyncedIfUnchanged]. */
    val rowVersion: Long = 0L
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
    val productLayoutUuid: String? = null,
    /** Optimistic-locking token snapshot at read time. Pass to
     *  [PersonalBoardRepository.markBidSyncedIfUnchanged]. */
    val rowVersion: Long = 0L
)

data class RawClimbListEntry(
    val listId: Long,
    val climbUuid: String,
    val addedAt: String
)

// ── Community-climb support (FEAT-003) ─────────────────────

data class LocalClimbDraft(
    val uuid: String,
    val name: String,
    val description: String,
    val framesText: String,
    val framesHash: String,
    val createdAt: String,
    val createdByPubkey: String?,
    val moveCount: Long,
)

data class CommunityClimbRow(
    val uuid: String,
    val name: String,
    val setterUsername: String?,
    val description: String,
    val framesText: String,
    val source: String,            // 'kilter' | 'nostr' | 'local'
    val syncStatus: String,         // 'draft' | 'synced' | 'published_nostr' | 'failed'
    val createdByPubkey: String?,
    val nostrEventId: String?,
    val nostrDTag: String?,
    val framesHash: String?,
    val createdAt: String?,
    val moveCount: Long,
)

/** Climb-creation + community-climb queries (FEAT-003). */
interface CommunityClimbQueries {
    /** Insert or upsert a local climb draft (source='local'). Re-saving an
     *  already-loaded draft replaces the row in place (same uuid). */
    fun insertLocalDraft(draft: LocalClimbDraft, layoutId: Long, angle: Long, setterGradeId: Int?)
    /** Delete a local draft (drafts user explicitly discards). */
    fun deleteLocalClimb(uuid: String)
    /**
     * Returns (placement_id → normalized 0..1 frequency) for boulders at the
     * given layout+angle, optionally weighted by climbs that contain ALL
     * `seedHolds`. Used by the editor heatmap overlay.
     *
     * - When `seedHolds` is empty → general popularity heatmap.
     * - When `seedHolds` has entries → only counts climbs that include
     *   every seed hold; surfaces "what holds typically follow these".
     * - When `targetRole` is non-null → only placements with that role
     *   in the source climb are counted (role-aware suggestions for the
     *   user's currently active brush). When null → all roles aggregated.
     */
    fun computeEditorHeatmap(
        layoutId: Long,
        angle: Long,
        seedHolds: Set<Int>,
        targetRole: Int? = null,
    ): Map<Int, Float>
    /** Upsert a community climb received from Nostr. */
    fun upsertCommunityClimb(
        uuid: String,
        layoutId: Long,
        setterUsername: String?,
        name: String,
        framesText: String,
        description: String,
        moveCount: Long,
        nostrEventId: String,
        nostrDTag: String,
        createdByPubkey: String,
        framesHash: String,
        createdAt: String,
        angle: Long,
        difficultyAverage: Double?,
        qualityAverage: Double?,
    )
    fun markClimbPublishedNostr(uuid: String, nostrEventId: String, nostrDTag: String)
    fun markClimbPublishFailed(uuid: String)

    // ── Kilter-side publish lifecycle (independent of Nostr sync_status) ──
    /** Mark a climb as enqueued for Kilter publish. Sets `kilter_status='pending'`. */
    fun markKilterPublishPending(uuid: String)
    /**
     * Mark a climb as accepted by Kilter. `via` is 'self' (user account) or
     * 'cruxcoach' (bundled fallback). `syncedAtEpochSeconds` is the moment
     * Kilter accepted; useful for the "veröffentlicht am" UI badge.
     */
    fun markKilterPublishSynced(uuid: String, via: String, syncedAtEpochSeconds: Long)
    /** Mark a climb's Kilter publish as failed; `error` captures the last reason. */
    fun markKilterPublishFailed(uuid: String, error: String)
    /** Climbs with `origin='cruxcoach'`, Nostr-published, awaiting Kilter sync. */
    fun getClimbsAwaitingKilterRetry(): List<CommunityClimbRow>
    fun getDraftClimbs(): List<CommunityClimbRow>
    fun getMyClimbs(pubkey: String): List<CommunityClimbRow>
    fun getCommunityClimbs(): List<CommunityClimbRow>
    /** Look up an existing climb by frames_hash for duplicate detection. */
    fun findClimbByFramesHash(framesHash: String, layoutId: Long): CommunityClimbRow?
    /** Cache the setter-grade entry for a community climb (MVP — no vote aggregation). */
    fun upsertSetterGrade(climbDTag: String, angle: Long, setterGradeId: Int, lastUpdatedEpochMs: Long)
}

// ── Composite interface (backward-compatible) ───────────────

interface BoardRepository :
    BoardClimbQueries,
    BoardLayoutQueries,
    BoardWriteOperations,
    CommunityClimbQueries
