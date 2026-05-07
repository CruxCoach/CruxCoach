package com.cruxcoach.android.fakes

import com.cruxcoach.android.ui.board.boardBrowserSortInKotlin
import com.cruxcoach.data.repository.AngleClimbCount
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbFrameRow
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.LedGridPoint
import com.cruxcoach.data.repository.SortDirection

/** Mirrors the production SQL `framesCount` predicate for ClimbTypeFilter:
 *  BOULDER = single frame, ROUTE = multi-frame, ALL = both. */
private fun ClimbWithStats.matchesClimbType(filter: ClimbTypeFilter): Boolean = when (filter) {
    ClimbTypeFilter.BOULDER -> framesCount == 1L
    ClimbTypeFilter.ROUTE -> framesCount > 1L
    ClimbTypeFilter.ALL -> true
}

/**
 * In-memory fake of [BoardRepository] for ViewModel unit tests.
 * Focuses on methods used by BoardBrowserViewModel; other methods
 * return sensible defaults (empty lists, 0 counts).
 */
class FakeBoardRepository : BoardRepository {

    val climbs = mutableListOf<ClimbWithStats>()

    // -- Test helpers --

    fun addClimb(climb: ClimbWithStats) {
        climbs.add(climb)
    }

    fun addClimbs(vararg climbList: ClimbWithStats) {
        climbs.addAll(climbList)
    }

    // -- BoardClimbQueries --

    override fun searchClimbsByName(
        query: String, angle: Int, layoutId: Int, sortField: ClimbSortField,
        sortDirection: SortDirection, limit: Int, offset: Int,
        climbType: ClimbTypeFilter
    ): List<ClimbWithStats> {
        val filtered = climbs.filter {
            (it.name.contains(query, ignoreCase = true) ||
                it.setterUsername?.contains(query, ignoreCase = true) == true) &&
                it.matchesClimbType(climbType)
        }
        val sorted = boardBrowserSortInKotlin(filtered, sortField, sortDirection)
        return sorted.drop(offset).take(limit)
    }

    override fun searchClimbsSorted(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, sortField: ClimbSortField,
        sortDirection: SortDirection, limit: Int, offset: Int,
        climbType: ClimbTypeFilter
    ): List<ClimbWithStats> {
        val filtered = climbs.filter { climb ->
            val diff = climb.difficultyAverage ?: return@filter false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.matchesClimbType(climbType)
        }
        // Mirror production SQL `ORDER BY <sortField> <sortDirection>`.
        // The fake previously returned insertion order, which silently
        // hid bugs in pagination + sort-direction handling.
        val sorted = boardBrowserSortInKotlin(filtered, sortField, sortDirection)
        return sorted.drop(offset).take(limit)
    }

    override fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats? {
        // Production SQL `WHERE c.uuid = ?` is case-sensitive (no
        // COLLATE NOCASE on the climbs table). The fake must match that
        // — otherwise tests pass on case-folded input that production
        // would silently fail to resolve. ClimbNameResolver explicitly
        // depends on this case-sensitivity to verify its UUID-shape
        // fallback ladder; using a mock there isolated this test class
        // from the bug, but other consumers of the fake would silently
        // mask UUID-case regressions.
        return climbs.firstOrNull { it.uuid == uuid }
    }

    override fun countFilteredClimbs(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter
    ): Long {
        return climbs.count { climb ->
            val diff = climb.difficultyAverage ?: return@count false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.matchesClimbType(climbType)
        }.toLong()
    }

    override fun countFilteredClimbsFast(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int
    ): Long {
        return climbs.count { climb ->
            val diff = climb.difficultyAverage ?: return@count false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists
        }.toLong()
    }

    override fun countBenchmarkFilteredClimbs(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter
    ): Long {
        return climbs.count { climb ->
            val diff = climb.difficultyAverage ?: return@count false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.benchmarkDifficulty > 0.0 &&
                climb.matchesClimbType(climbType)
        }.toLong()
    }

    override fun countSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter): Long {
        return climbs.count {
            (it.name.contains(query, ignoreCase = true) ||
                it.setterUsername?.contains(query, ignoreCase = true) == true) &&
                it.matchesClimbType(climbType)
        }.toLong()
    }

    override fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter): Long {
        return climbs.count {
            (it.name.contains(query, ignoreCase = true) ||
                it.setterUsername?.contains(query, ignoreCase = true) == true) &&
                it.benchmarkDifficulty > 0.0 &&
                it.matchesClimbType(climbType)
        }.toLong()
    }

    override fun getClimbCount(): Long = climbs.size.toLong()
    override fun climbExistsByUuid(uuid: String): Boolean = climbs.any { it.uuid == uuid }
    override fun statExistsByUuid(uuid: String): Boolean = false

    override fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter): List<AngleClimbCount> = emptyList()
    override fun getAnglesForClimb(climbUuid: String): List<AngleOption> = emptyList()

    override fun countNomatchClimbs(): Long = 0L

    override fun getClimbsByUuids(
        uuids: Collection<String>, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<ClimbWithStats> {
        // Mirror the production SQL — narrows the uuid set by the same
        // difficulty/ascensionist/climbType predicates that the browser
        // applies elsewhere. Pre-fix the fake silently returned every
        // climb whose uuid matched, masking out-of-range bugs the
        // SENT/ATTEMPTED browser filters would catch in production.
        return climbs.filter { climb ->
            if (climb.uuid !in uuids) return@filter false
            val diff = climb.difficultyAverage
            (diff == null || diff in minDifficulty..maxDifficulty) &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.matchesClimbType(climbType)
        }
    }

    override fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> {
        return climbs.filter { it.uuid in uuids }
    }

    override fun getStatCount(): Long = 0L

    override fun countOrphanStats(): Long = 0L

    override fun countListedClimbsWithoutStats(): Long = 0L

    override fun hasPostV8ResyncMarker(): Boolean = false

    override fun clearPostV8ResyncMarker() {}

    override fun hasHomewallResyncMarker(): Boolean = false

    override fun clearHomewallResyncMarker() {}

    override fun deleteKilterCatalogData() {}

    override fun searchClimbUuidsByHold(
        holdPattern: String, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<String> {
        return climbs.filter { climb ->
            val diff = climb.difficultyAverage ?: return@filter false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.frames.contains(holdPattern)
        }.map { it.uuid }
    }

    override fun searchClimbUuidsByAllHolds(
        holdPatterns: List<String>, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): Set<String> {
        if (holdPatterns.isEmpty()) return emptySet()
        return climbs.filter { climb ->
            val diff = climb.difficultyAverage ?: return@filter false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                holdPatterns.all { pattern -> climb.frames.contains(pattern) }
        }.map { it.uuid }.toSet()
    }

    override fun getAllFramesForHeatmap(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<ClimbFrameRow> {
        return climbs.filter { climb ->
            val diff = climb.difficultyAverage ?: return@filter false
            diff in minDifficulty..maxDifficulty &&
                (climb.ascensionistCount ?: 0) >= minAscensionists &&
                climb.frames.isNotEmpty()
        }.map { ClimbFrameRow(it.uuid, it.frames) }
    }

    // -- BoardLayoutQueries --

    override fun getAllPlacements(): List<BoardPlacement> = emptyList()
    override fun getProductSize(id: Int): BoardSize? = null
    override fun getAllProductSizes(): List<BoardSize> = emptyList()
    override fun getBoardImages(productSizeId: Int, layoutId: Int): List<BoardImage> = emptyList()
    override fun getPlacementLedMap(productSizeId: Int): Map<Int, Int> = emptyMap()
    override fun getMirrorPlacementMap(productSizeId: Int): Map<Int, Int> = emptyMap()
    override fun countLeds(): Long = 0L
    override fun getLedGrid(productSizeId: Int): List<LedGridPoint> = emptyList()

    // -- BoardWriteOperations --

    override fun upsertClimb(
        uuid: String, layoutId: Long, setter: String?, name: String,
        frames: String, framesCount: Long, isListed: Long,
        edgeLeft: Long?, edgeRight: Long?, edgeBottom: Long?, edgeTop: Long?,
        createdAt: String?, description: String, isNomatch: Long,
        framesPace: Long, hsm: Long, moveCount: Long
    ) {}

    override fun upsertClimbStat(
        climbUuid: String, angle: Long, displayDifficulty: Double?,
        difficultyAverage: Double?, qualityAverage: Double?,
        ascensionistCount: Long?, benchmarkDifficulty: Double?,
        faUsername: String?, faAt: String?,
        officialKilterDifficulty: Long?
    ) {}

    override fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long, ledPosition: Long, placementId: Long) {}
    override fun upsertLed(holeId: Long, productSizeId: Long, position: Long) {}
    override fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?) {}
    override fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long) {}
    override fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long, edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?) {}
    override fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String) {}
    override fun upsertSyncState(tableName: String, lastSynchronizedAt: String) {}
    override fun getSyncState(tableName: String): String? = null
    override fun getAllClimbUuids(): Set<String> = climbs.map { it.uuid }.toSet()
    override fun getAllStatKeys(): Map<Pair<String, Long>, Long?> = emptyMap()
    override fun runInTransaction(block: () -> Unit) { block() }
    override fun deleteAllBoardData() { climbs.clear() }

    override fun insertLocalDraft(draft: com.cruxcoach.data.repository.LocalClimbDraft, layoutId: Long, angle: Long, setterGradeId: Int?, bounds: com.cruxcoach.domain.community.ClimbBounds?) {}
    override fun deleteLocalClimb(uuid: String) {}
    override fun markCommunityClimbDeleted(uuid: String, pubkey: String, tombstoneIso: String) {
        climbs.removeAll { it.uuid == uuid && it.createdByPubkey == pubkey }
    }
    override fun isClimbTombstoned(uuid: String): Boolean = false
    override fun insertTombstoneShell(uuid: String, pubkey: String, dTag: String, tombstoneIso: String) {}
    override fun getCommunityClimbDeleteContext(uuid: String): com.cruxcoach.data.repository.CommunityClimbDeleteContext? = null
    override fun getClimbCreatedAt(uuid: String): String? = null
    override fun getClimbAuthorPubkey(uuid: String): String? = null
    override fun isLocallyAuthored(uuid: String): Boolean = false
    override fun computeEditorHeatmap(layoutId: Long, angle: Long, seedHolds: Set<Int>, targetRole: Int?): Map<Int, Float> = emptyMap()
    override fun upsertCommunityClimb(uuid: String, layoutId: Long, setterUsername: String?, name: String, framesText: String, description: String, moveCount: Long, nostrEventId: String, nostrDTag: String, createdByPubkey: String, framesHash: String, createdAt: String, angle: Long, difficultyAverage: Double?, qualityAverage: Double?, bounds: com.cruxcoach.domain.community.ClimbBounds?) {}
    override fun markClimbPublishedNostr(uuid: String, nostrEventId: String, nostrDTag: String, pubkey: String) {}
    override fun markClimbPublishFailed(uuid: String) {}
    override fun markClimbPublishInFlight(uuid: String) {}
    override fun markKilterPublishPending(uuid: String) {}
    override fun markKilterPublishSynced(uuid: String, via: String, syncedAtEpochSeconds: Long) {}
    override fun markKilterPublishFailed(uuid: String, error: String) {}
    override fun markKilterPublishDiverged(uuid: String, error: String) {}
    override fun markKilterPublishRejected(uuid: String, error: String) {}
    override fun claimKilterPublishSlot(uuid: String): com.cruxcoach.data.repository.KilterClaim =
        com.cruxcoach.data.repository.KilterClaim.Won(null)
    override fun sweepStuckKilterPending(olderThanMs: Long): Long = 0L
    override fun recordKilterPublishAttempt(
        climbUuid: String,
        attemptedAtMs: Long,
        op: com.cruxcoach.data.repository.KilterPublishOp,
        via: String,
        outcome: com.cruxcoach.data.repository.KilterPublishOutcomeKind,
        httpCode: Int?,
        errorExcerpt: String?,
    ) {}
    override fun getKilterPublishAttempts(climbUuid: String, limit: Int): List<com.cruxcoach.data.repository.KilterPublishAttempt> = emptyList()
    override fun getKilterPublishQueueStats(): com.cruxcoach.data.repository.KilterPublishQueueStats =
        com.cruxcoach.data.repository.KilterPublishQueueStats(0, 0, null)
    override fun getKilterPublishState(uuid: String): com.cruxcoach.data.repository.KilterPublishState? = null
    override fun updateSetterUsernameForPubkey(pubkey: String, displayName: String) {}
    override fun getClimbsByPubkey(pubkey: String): List<com.cruxcoach.data.repository.SetterClimbEntry> = emptyList()
    override fun getOwnClimbsForBrowse(pubkey: String, layoutId: Int, preferredAngle: Int): List<com.cruxcoach.data.repository.ClimbWithStats> = emptyList()
    override fun getCommunitySetterStats(): List<com.cruxcoach.data.repository.SetterStat> = emptyList()
    override fun getClimbsAwaitingKilterRetry(): List<com.cruxcoach.data.repository.CommunityClimbRow> = emptyList()
    override fun getClimbsAwaitingNostrRetry(pubkey: String): List<com.cruxcoach.data.repository.CommunityClimbRow> = emptyList()
    override fun getDraftClimbs(pubkey: String?): List<com.cruxcoach.data.repository.CommunityClimbRow> = emptyList()
    override fun getMyClimbs(pubkey: String): List<com.cruxcoach.data.repository.CommunityClimbRow> = emptyList()
    override fun getCommunityClimbs(): List<com.cruxcoach.data.repository.CommunityClimbRow> = emptyList()
    override fun getClimbStatsForUuid(uuid: String): Pair<Int, Int?>? = null
    override fun findClimbByFramesHash(framesHash: String, layoutId: Long): com.cruxcoach.data.repository.CommunityClimbRow? = null
    override fun upsertSetterGrade(climbDTag: String, angle: Long, setterGradeId: Int, lastUpdatedEpochMs: Long) {}
}
