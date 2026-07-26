package com.cruxcoach.usecase

import com.cruxcoach.data.repository.*
import com.cruxcoach.domain.model.*

class FakePlanRepository : PlanRepository {
    private val plans = mutableListOf<TrainingPlan>()
    private val sessions = mutableListOf<PlannedSession>()
    private var nextPlanId = 1L
    private var nextSessionId = 1L

    override fun getActivePlan(userId: Long): TrainingPlan? {
        return plans.firstOrNull { it.userId == userId }
    }

    override fun getPlanById(id: Long): TrainingPlan? {
        return plans.firstOrNull { it.id == id }
    }

    override fun getAllPlans(userId: Long): List<TrainingPlan> {
        return plans.filter { it.userId == userId }
    }

    override fun insertPlan(plan: TrainingPlan): Long {
        val id = nextPlanId++
        plans.add(plan.copy(id = id))
        return id
    }

    override fun deletePlan(id: Long) {
        plans.removeAll { it.id == id }
    }

    override fun getSessionsForPlan(planId: Long): List<PlannedSession> {
        return sessions.filter { it.planId == planId }
    }

    override fun getSessionForDay(planId: Long, dayOfWeek: Int): PlannedSession? {
        return sessions.firstOrNull { it.planId == planId && it.dayOfWeek == dayOfWeek }
    }

    override fun getSessionForToday(userId: Long): PlannedSession? {
        val plan = getActivePlan(userId) ?: return null
        return sessions.firstOrNull { it.planId == plan.id }
    }

    override fun insertSession(session: PlannedSession): Long {
        val id = nextSessionId++
        sessions.add(session.copy(id = id))
        return id
    }

    override fun deleteSessionsForPlan(planId: Long) {
        sessions.removeAll { it.planId == planId }
    }

    override fun savePlan(plan: TrainingPlan, sessions: List<PlannedSession>): Long {
        val planId = insertPlan(plan)
        for (session in sessions) {
            insertSession(session.copy(planId = planId))
        }
        return planId
    }

    override fun replaceSessionsForPlan(planId: Long, sessions: List<PlannedSession>) {
        deleteSessionsForPlan(planId)
        for (session in sessions) {
            insertSession(session.copy(planId = planId))
        }
    }

    // Test helpers
    fun getPlanCount() = plans.size
    fun getSessionCount() = sessions.size
    fun getAllSessions() = sessions.toList()
}

class FakeWorkoutRepository : WorkoutRepository {
    private val logs = mutableListOf<WorkoutLog>()

    fun addLog(log: WorkoutLog) { logs.add(log) }

    override fun insertWorkout(log: WorkoutLog): Long {
        logs.add(log)
        return log.id
    }

    override fun getWorkoutById(id: Long): WorkoutLog? = logs.firstOrNull { it.id == id }
    override fun getRecentWorkouts(limit: Int): List<WorkoutLog> = logs.takeLast(limit)
    override fun getWorkoutsForDateRange(startDate: String, endDate: String): List<WorkoutLog> {
        return logs.filter { it.date in startDate..endDate }
    }
    override fun getAvgRpeLastN(n: Int): Double? {
        return logs.takeLast(n).mapNotNull { it.perceivedRpe }.average().takeIf { !it.isNaN() }
    }
    override fun countThisWeek(): Long = logs.size.toLong()
    override fun getAll(): List<WorkoutLog> = logs.toList()
    override fun deleteWorkout(id: Long) { logs.removeAll { it.id == id } }
}

class FakeClimbRepository : ClimbRepository {
    private val climbs = mutableListOf<ClimbLog>()

    fun addClimb(climb: ClimbLog) { climbs.add(climb) }

    override fun insertClimb(log: ClimbLog): Long {
        climbs.add(log)
        return log.id
    }
    override fun getClimbById(id: Long): ClimbLog? = climbs.firstOrNull { it.id == id }
    override fun getClimbsForWorkout(workoutLogId: Long) = climbs.filter { it.workoutLogId == workoutLogId }
    override fun getSendsForDateRange(startDate: String, endDate: String): List<ClimbLog> {
        return climbs.filter { it.sent && it.date in startDate..endDate }
    }
    override fun getGradePyramid(): Map<String, Long> = emptyMap()
    override fun getFlashRate(): Map<String, Double> = emptyMap()
    override fun getStyleDistribution(): Map<String, Long> = emptyMap()
    override fun getRecentHighestGrade(): String? = null
    override fun getAll(): List<ClimbLog> = climbs.toList()
    override fun deleteClimb(id: Long) { climbs.removeAll { it.id == id } }
}

class FakeBoardRepository : BoardRepository {
    data class StoredClimb(
        val uuid: String, val layoutId: Long, val setter: String?, val name: String,
        val frames: String, val framesCount: Long, val isListed: Long,
        val edgeLeft: Long?, val edgeRight: Long?, val edgeBottom: Long?,
        val edgeTop: Long?, val createdAt: String?
    )

    data class StoredClimbStat(
        val climbUuid: String, val angle: Long, val displayDifficulty: Double?,
        val difficultyAverage: Double?, val qualityAverage: Double?,
        val ascensionistCount: Long?, val benchmarkDifficulty: Double?
    )

    val storedClimbs = mutableMapOf<String, StoredClimb>()
    val storedStats = mutableListOf<StoredClimbStat>()
    val syncStates = mutableMapOf<String, String>()

    // -- BoardClimbQueries --

    override fun searchClimbsByName(query: String, angle: Int, layoutId: Int, boardBrand: String, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long): List<ClimbWithStats> = emptyList()
    override fun searchClimbsSorted(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long, showUngraded: Boolean): List<ClimbWithStats> = emptyList()
    override fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats? = null
    override fun countFilteredClimbsFast(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, selProductSizeId: Int, hsmExcludedMask: Long, showUngraded: Boolean): Long = 0L
    override fun countFilteredClimbs(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long, showUngraded: Boolean): Long = 0L
    override fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long, showUngraded: Boolean): Long = 0L
    override fun countSearchClimbs(query: String, angle: Int, layoutId: Int, boardBrand: String, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long): Long = 0L
    override fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, boardBrand: String, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long): Long = 0L
    override fun getClimbCount(): Long = storedClimbs.size.toLong()
    override fun getClimbCountsByBrand(): Map<String, Long> = emptyMap()
    override fun hasAnyClimbs(): Boolean = storedClimbs.isNotEmpty()
    override fun hasClimbsForBrand(boardBrand: String): Boolean = storedClimbs.isNotEmpty()
    override fun getStatCount(): Long = 0L
    override fun countOrphanStats(): Long = 0L
    override fun countListedClimbsWithoutStats(): Long = 0L
    override fun hasPostV8ResyncMarker(): Boolean = false
    override fun clearPostV8ResyncMarker() {}
    override fun hasHomewallResyncMarker(): Boolean = false
    override fun clearHomewallResyncMarker() {}
    override fun deleteKilterCatalogData() {}
    override fun getProductSizesForLayout(layoutId: Int, boardBrand: String): List<Int> = emptyList()
    override fun getDefaultLayoutForBrand(boardBrand: String): Int? = null
    override fun getDefaultProductSizeForBrand(boardBrand: String): Pair<Int, String>? = null
    override fun canRenderClimbOnSize(uuid: String, productSizeId: Int, boardBrand: String): Boolean = true
    override fun getProductSizeForClimbRender(uuid: String, boardBrand: String): Int? = null
    override fun getCruxCoachClimbs(
        layoutId: Int, boardBrand: String, angle: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long,
        showUngraded: Boolean,
    ): List<ClimbWithStats> = emptyList()
    override fun getBoardSeshClimbs(
        layoutId: Int, boardBrand: String, angle: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long,
        showUngraded: Boolean,
    ): List<ClimbWithStats> = emptyList()
    override fun climbExistsByUuid(uuid: String): Boolean = storedClimbs.containsKey(uuid)
    override fun statExistsByUuid(uuid: String): Boolean = false
    override fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter): List<AngleClimbCount> = emptyList()
    override fun getAnglesForClimb(climbUuid: String): List<AngleOption> = emptyList()
    override fun getSupportedAnglesForLayout(layoutId: Int, boardBrand: String): List<Int> = emptyList()
    override fun countNomatchClimbs(): Long = 0L
    override fun getClimbsByUuids(uuids: Collection<String>, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbWithStats> = emptyList()
    override fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> = emptyList()
    override fun getClimbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> = emptyList()
    override fun getClimbsByUuidsForBoard(uuids: Collection<String>, angle: Int, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<ClimbWithStats> = emptyList()
    override fun getClimbsByUuidsForBoardAnyAngle(uuids: Collection<String>, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<ClimbWithStats> = emptyList()
    override fun getAllBrowseMatchingUuids(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int, hsmExcludedMask: Long, showUngraded: Boolean): List<String> = emptyList()
    override fun searchClimbUuidsByHold(holdPattern: String, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<String> = emptyList()
    override fun searchClimbUuidsByAllHolds(holdPatterns: List<String>, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Set<String> = emptySet()
    override fun getAllFramesForHeatmap(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow> = emptyList()
    override fun getAllFramesForHeatmapAllAngles(layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow> = emptyList()

    // -- BoardLayoutQueries --

    override fun getAllPlacements(boardBrand: String): List<BoardPlacement> = emptyList()
    override fun getPlacementsForLayout(productSizeId: Int, layoutId: Int, boardBrand: String): List<BoardPlacement> = emptyList()
    override fun getProductSize(id: Int, boardBrand: String): BoardSize? = null
    override fun getAllProductSizes(productId: Long, boardBrand: String): List<BoardSize> = emptyList()
    override fun getSelectableProductSizesForBrand(boardBrand: String): List<BoardSize> = emptyList()
    override fun getBoardImages(productSizeId: Int, layoutId: Int, boardBrand: String): List<BoardImage> = emptyList()
    override fun getHoldSetIdsForLayout(layoutId: Int, boardBrand: String): List<Long> = emptyList()
    override fun getHoldSetIdsForLayoutSize(layoutId: Int, productSizeId: Int, boardBrand: String): List<Long> = emptyList()
    override fun getPlacementLedMap(productSizeId: Int, boardBrand: String): Map<Int, Int> = emptyMap()
    override fun findClimbCandidatesByFrames(
        boardBrand: String,
        layoutId: Int,
        minLength: Int,
        maxLength: Int,
        anchor1: String?,
        anchor2: String?,
    ): List<com.cruxcoach.data.repository.RelayClimbCandidate> = emptyList()
    override fun ensureRelayLookupIndex(): Boolean = false
    override fun getRoleColorMapForBrand(boardBrand: String): Map<Int, Int> = emptyMap()
    override fun getMirrorPlacementMap(productSizeId: Int, boardBrand: String): Map<Int, Int> = emptyMap()
    override fun countLeds(): Long = 0L
    override fun getLedGrid(productSizeId: Int, boardBrand: String): List<LedGridPoint> = emptyList()

    // -- BoardWriteOperations --

    override fun upsertClimb(uuid: String, layoutId: Long, setter: String?, name: String, frames: String,
                             framesCount: Long, isListed: Long, edgeLeft: Long?, edgeRight: Long?,
                             edgeBottom: Long?, edgeTop: Long?, createdAt: String?,
                             description: String, isNomatch: Long, framesPace: Long, hsm: Long,
                             moveCount: Long) {
        storedClimbs[uuid] = StoredClimb(uuid, layoutId, setter, name, frames, framesCount, isListed, edgeLeft, edgeRight, edgeBottom, edgeTop, createdAt)
    }

    override fun upsertClimbStat(climbUuid: String, angle: Long, displayDifficulty: Double?,
                                 difficultyAverage: Double?, qualityAverage: Double?,
                                 ascensionistCount: Long?, benchmarkDifficulty: Double?,
                                 faUsername: String?, faAt: String?,
                                 officialKilterDifficulty: Long?) {
        storedStats.add(StoredClimbStat(climbUuid, angle, displayDifficulty, difficultyAverage, qualityAverage, ascensionistCount, benchmarkDifficulty))
    }

    override fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long, ledPosition: Long, placementId: Long, boardBrand: String) {}
    override fun upsertLed(holeId: Long, productSizeId: Long, position: Long, boardBrand: String) {}
    override fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?, boardBrand: String) {}
    override fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long, boardBrand: String) {}
    override fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long, edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?, boardBrand: String) {}
    override fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String, boardBrand: String) {}
    override fun upsertPlacementRole(boardBrand: String, id: Long, name: String?, ledColor: String?, screenColor: String?) {}
    override fun upsertSyncState(tableName: String, lastSynchronizedAt: String) { syncStates[tableName] = lastSynchronizedAt }
    override fun getSyncState(tableName: String): String? = syncStates[tableName]
    override fun getAllClimbUuids(): Set<String> = storedClimbs.keys
    override fun getAllStatKeys(): Map<Pair<String, Long>, Long?> = emptyMap()
    override fun runInTransaction(block: () -> Unit) { block() }
    override fun deleteAllBoardData() { storedClimbs.clear(); syncStates.clear() }
    override fun deleteBoardDataForBrands(brands: Set<String>) {}
    override fun getClimbBrandsForUuids(uuids: Collection<String>): Map<String, String> = emptyMap()

    // -- CommunityClimbQueries (FEAT-003) --

    override fun insertLocalDraft(draft: LocalClimbDraft, layoutId: Long, angle: Long, setterGradeId: Int?, bounds: com.cruxcoach.domain.community.ClimbBounds?, boardBrand: String?) {}
    override fun deleteLocalClimb(uuid: String) {}
    override fun markCommunityClimbDeleted(uuid: String, pubkey: String, tombstoneIso: String) {}
    override fun isClimbTombstoned(uuid: String): Boolean = false
    override fun insertTombstoneShell(uuid: String, pubkey: String, dTag: String, tombstoneIso: String) {}
    override fun getCommunityClimbDeleteContext(uuid: String): com.cruxcoach.data.repository.CommunityClimbDeleteContext? = null
    override fun getClimbCreatedAt(uuid: String): String? = null
    override fun getClimbAuthorPubkey(uuid: String): String? = null
    override fun isNonCommunityClimb(uuid: String): Boolean = false
    override fun isLocallyAuthored(uuid: String): Boolean = false
    override fun computeEditorHeatmap(layoutId: Long, angle: Long, seedHolds: Set<Int>, targetRole: Int?): Map<Int, Float> = emptyMap()
    override fun upsertCommunityClimb(uuid: String, layoutId: Long, setterUsername: String?, name: String, framesText: String, description: String, moveCount: Long, nostrEventId: String, nostrDTag: String, createdByPubkey: String, framesHash: String, createdAt: String, angle: Long, difficultyAverage: Double?, qualityAverage: Double?, bounds: com.cruxcoach.domain.community.ClimbBounds?, boardBrand: String) {}
    override fun markClimbPublishedNostr(uuid: String, nostrEventId: String, nostrDTag: String, pubkey: String, createdAtIso: String) {}
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
    override fun getClimbsByPubkeyForBoard(pubkey: String, angle: Int, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<com.cruxcoach.data.repository.SetterClimbEntry> = emptyList()
    override fun getOwnClimbsForBrowse(pubkey: String, layoutId: Int, preferredAngle: Int, boardBrand: String): List<com.cruxcoach.data.repository.ClimbWithStats> = emptyList()
    override fun getCommunitySetterStats(boardBrand: String, layoutId: Int, selProductSizeId: Int): List<com.cruxcoach.data.repository.SetterStat> = emptyList()
    override fun getClimbsAwaitingKilterRetry(pubkey: String): List<CommunityClimbRow> = emptyList()
    override fun getClimbsAwaitingNostrRetry(pubkey: String): List<CommunityClimbRow> = emptyList()
    override fun recordCommunityClimbDeadLetter(uuid: String, eventId: String, eventCreatedAt: Long, rawEventJson: String, nowMs: Long, errorExcerpt: String?) {}
    override fun getRetriableCommunityClimbDeadLetters(maxRetries: Long, limit: Long): List<com.cruxcoach.data.repository.CommunityClimbDeadLetter> = emptyList()
    override fun deleteCommunityClimbDeadLetter(uuid: String) {}
    override fun getCommunityClimbDeadLetterCounts(maxRetries: Long): com.cruxcoach.data.repository.DeadLetterCounts = com.cruxcoach.data.repository.DeadLetterCounts(0L, 0L)
    override fun getDraftClimbs(pubkey: String?, boardBrand: String): List<CommunityClimbRow> = emptyList()
    override fun getMyClimbs(pubkey: String): List<CommunityClimbRow> = emptyList()
    override fun getCommunityClimbs(): List<CommunityClimbRow> = emptyList()
    override fun getClimbStatsForUuid(uuid: String): Pair<Int, Int?>? = null
    override fun getClimbPublishContext(uuid: String): ClimbPublishContext? = null
    override fun findClimbByFramesHash(framesHash: String, layoutId: Long, boardBrand: String): CommunityClimbRow? = null
    override fun upsertSetterGrade(climbDTag: String, angle: Long, setterGradeId: Int, lastUpdatedEpochMs: Long) {}
    override fun getOwnClimbsForBackup(pubkey: String): List<com.cruxcoach.data.repository.OwnClimbBackupRow> = emptyList()
    override fun getOwnClimbStatsForBackup(pubkey: String): List<com.cruxcoach.data.repository.OwnClimbStatBackupRow> = emptyList()
    override fun getOwnClimbAngle(uuid: String): Long? = null
    override fun restoreOwnClimb(row: com.cruxcoach.data.repository.OwnClimbBackupRow): Boolean = true
    override fun restoreOwnClimbStat(row: com.cruxcoach.data.repository.OwnClimbStatBackupRow) {}
}
