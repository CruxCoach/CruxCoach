package com.cruxcoach.android.fakes

import com.cruxcoach.data.repository.AuroraAscentWithClimb
import com.cruxcoach.data.repository.BoardSession
import com.cruxcoach.data.repository.ClimbList
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import com.cruxcoach.data.repository.RawClimbListEntry

/**
 * In-memory fake of [PersonalBoardRepository] for unit tests.
 */
class FakePersonalBoardRepository : PersonalBoardRepository {

    val sentUuids = mutableSetOf<String>()
    val attemptedUuids = mutableSetOf<String>()

    fun markSent(uuid: String) { sentUuids.add(uuid) }
    fun markAttempted(uuid: String) { attemptedUuids.add(uuid) }

    // -- Ascent queries --

    override fun insertAscent(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, attemptId: Long, bidCount: Long,
        quality: Long?, difficulty: Long?, isBenchmark: Boolean,
        comment: String?, climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long
    ) {}

    override fun deleteAscent(uuid: String) {}
    override fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?) {}
    override fun getUserAscentsAll(): List<AuroraAscentWithClimb> = emptyList()
    override fun getUserAscentsBetween(from: String, to: String): List<AuroraAscentWithClimb> = emptyList()
    override fun getUserSentClimbUuids(): Set<String> = sentUuids
    override fun getUserAttemptedClimbUuids(): Set<String> = attemptedUuids - sentUuids
    override fun getUserSendDifficulties(since: String): List<Double> = emptyList()
    override fun getUserLogbookPage(limit: Int, offset: Int): List<AuroraAscentWithClimb> = emptyList()
    override fun getUserLogbookAllLight(): List<AuroraAscentWithClimb> = emptyList()
    override fun getUserHistoryForClimb(climbUuid: String): List<AuroraAscentWithClimb> = emptyList()
    override fun countUserLogbook(): Long = 0L
    override fun getRepeatCounts(): Map<String, Long> = emptyMap()
    override fun getUnsyncedAscents(): List<RawAscent> = emptyList()
    override fun markAscentSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean = true

    // -- Bid queries --

    override fun insertBid(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?
    ) {}

    override fun deleteBid(uuid: String) {}
    override fun getUserBidDifficulties(since: String): List<Double> = emptyList()
    override fun getUnsyncedBids(): List<RawBid> = emptyList()
    override fun markBidSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean = true
    override fun getRawBidsForUser(): List<RawBid> = emptyList()

    // -- Board session queries --

    override fun insertBoardSession(startedAt: String, endedAt: String?, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long): Long = 1L
    override fun getRecentBoardSessions(limit: Int): List<BoardSession> = emptyList()
    override fun getActiveSession(): BoardSession? = null
    override fun updateActiveSession(id: Long, ascentCount: Long, bidCount: Long, pauseDurationSeconds: Long, totalDurationSeconds: Long) {}
    override fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long) {}
    override fun getAllBoardSessions(): List<BoardSession> = emptyList()

    // -- Climb list queries --

    override fun ensureFavoritesListExists(): Long = 1L
    override fun getAllClimbLists(): List<ClimbList> = emptyList()
    override fun getClimbListById(id: Long): ClimbList? = null
    override fun createClimbList(name: String): Long = 1L
    override fun renameClimbList(id: Long, name: String) {}
    override fun deleteClimbList(id: Long) {}
    override fun addClimbToList(listId: Long, climbUuid: String) {}
    override fun removeClimbFromList(listId: Long, climbUuid: String) {}
    override fun getClimbListEntryUuids(listId: Long, limit: Int, offset: Int): List<Pair<String, String>> = emptyList()
    override fun countClimbListEntries(listId: Long): Long = 0L
    override fun getListIdsForClimb(climbUuid: String): Set<Long> = emptySet()
    override fun isClimbFavorited(climbUuid: String): Boolean = false
    override fun toggleFavorite(climbUuid: String): Boolean = false
    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> = emptyList()

    // -- Denormalization --

    override fun getAllClimbKeys(): List<Pair<String, Long>> = emptyList()
    override fun updateAscentDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, climbFrames: String, framesCount: Long) {}
    override fun updateBidDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?) {}

    // -- Bulk operations --

    override fun deleteAllUserBoardData() { sentUuids.clear(); attemptedUuids.clear() }
    override fun runInTransaction(block: () -> Unit) { block() }
}
