package com.cruxcoach.data.repository

/**
 * Personal board data — ascents, bids, sessions, climb lists.
 * Backed by the per-key SecureDatabase. No cross-DB JOINs;
 * climb metadata is denormalized at insert time.
 */
interface PersonalBoardRepository {

    // ── Ascent queries ──────────────────────────────────────────

    fun insertAscent(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, attemptId: Long, bidCount: Long,
        quality: Long?, difficulty: Long?, isBenchmark: Boolean,
        comment: String?, climbedAt: String, synced: Boolean,
        gymUuid: String? = null, wallUuid: String? = null, productLayoutUuid: String? = null,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long
    )

    fun deleteAscent(uuid: String)
    fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?)

    fun getUserAscentsAll(): List<AuroraAscentWithClimb>
    fun getUserAscentsBetween(from: String, to: String): List<AuroraAscentWithClimb>
    fun getUserSentClimbUuids(): Set<String>
    fun getUserAttemptedClimbUuids(): Set<String>
    fun getUserSendDifficulties(since: String): List<Double>
    fun getUserLogbookPage(limit: Int = 50, offset: Int = 0): List<AuroraAscentWithClimb>
    fun getUserLogbookAllLight(): List<AuroraAscentWithClimb>
    fun getUserHistoryForClimb(climbUuid: String): List<AuroraAscentWithClimb>
    fun countUserLogbook(): Long

    /** Map of climb_uuid → repeat count (for browse sort-by-repeats). */
    fun getRepeatCounts(): Map<String, Long>

    fun getUnsyncedAscents(): List<RawAscent>

    /**
     * Stamp `synced = 1` only if [expectedRowVersion] still matches the
     * current row. Returns `true` when the stamp applied, `false` if a
     * concurrent edit bumped `row_version` in the meantime — in that case
     * the caller should leave `synced = 0` so the next sync re-uploads
     * the newer data.
     */
    fun markAscentSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean

    // ── Bid queries ─────────────────────────────────────────────

    fun insertBid(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean,
        gymUuid: String? = null, wallUuid: String? = null, productLayoutUuid: String? = null,
        climbName: String, difficultyAverage: Double?
    )

    fun deleteBid(uuid: String)
    fun getUserBidDifficulties(since: String): List<Double>

    fun getUnsyncedBids(): List<RawBid>

    /** See [markAscentSyncedIfUnchanged]. */
    fun markBidSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean
    fun getRawBidsForUser(): List<RawBid>

    // ── Board session queries ───────────────────────────────────

    fun insertBoardSession(
        startedAt: String, endedAt: String?,
        totalDurationSeconds: Long, pauseDurationSeconds: Long,
        ascentCount: Long, bidCount: Long
    ): Long

    fun getRecentBoardSessions(limit: Int = 20): List<BoardSession>
    fun getActiveSession(): BoardSession?
    fun updateActiveSession(id: Long, ascentCount: Long, bidCount: Long, pauseDurationSeconds: Long, totalDurationSeconds: Long)
    fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long)
    fun getAllBoardSessions(): List<BoardSession>

    // ── Climb list queries ──────────────────────────────────────

    fun ensureFavoritesListExists(): Long
    fun getAllClimbLists(): List<ClimbList>
    fun getClimbListById(id: Long): ClimbList?
    fun createClimbList(name: String): Long
    fun renameClimbList(id: Long, name: String)
    fun deleteClimbList(id: Long)
    fun addClimbToList(listId: Long, climbUuid: String)
    fun removeClimbFromList(listId: Long, climbUuid: String)
    /** Returns (climbUuid, addedAt) pairs for two-phase lookup. */
    fun getClimbListEntryUuids(listId: Long, limit: Int = 50, offset: Int = 0): List<Pair<String, String>>
    fun countClimbListEntries(listId: Long): Long
    fun getListIdsForClimb(climbUuid: String): Set<Long>
    fun isClimbFavorited(climbUuid: String): Boolean
    fun toggleFavorite(climbUuid: String): Boolean
    fun getClimbListEntriesRaw(): List<RawClimbListEntry>

    // ── Denormalization refresh ─────────────────────────────────

    /** Returns all distinct (climbUuid, angle) pairs across ascents and bids. */
    fun getAllClimbKeys(): List<Pair<String, Long>>

    /** Batch-update denormalized fields after a board sync. */
    fun updateAscentDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, climbFrames: String, framesCount: Long)
    fun updateBidDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?)

    // ── Bulk operations ─────────────────────────────────────────

    fun deleteAllUserBoardData()
    fun runInTransaction(block: () -> Unit)
}
