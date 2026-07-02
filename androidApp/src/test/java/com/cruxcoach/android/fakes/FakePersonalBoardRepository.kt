package com.cruxcoach.android.fakes

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.NewPlaylistEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.PlaylistEntryRow
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import com.cruxcoach.data.repository.RawClimbListEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fake of [PersonalBoardRepository] for unit tests.
 */
class FakePersonalBoardRepository : PersonalBoardRepository {

    val sentUuids = mutableSetOf<String>()
    val attemptedUuids = mutableSetOf<String>()
    val ignoredUuids = mutableSetOf<String>()
    /** Log uuids (ascent + bid PKs) recorded by inserts, so dedup-counting in
     *  the Kilter import can be exercised. */
    val insertedLogUuids = mutableSetOf<String>()

    fun markSent(uuid: String) { sentUuids.add(uuid) }
    fun markAttempted(uuid: String) { attemptedUuids.add(uuid) }
    fun markIgnored(uuid: String) { ignoredUuids.add(uuid) }

    // -- Ascent queries --

    override fun insertAscent(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, attemptId: Long, bidCount: Long,
        quality: Long?, difficulty: Long?, isBenchmark: Boolean,
        comment: String?, climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long,
        boardBrand: String, layoutId: Long?,
    ) { insertedLogUuids.add(uuid) }

    override fun deleteAscent(uuid: String) {}
    override fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?) {}
    override fun updateBid(uuid: String, bidCount: Long, comment: String?) {}
    override fun getUserAscentsAll(): List<AscentWithClimb> = emptyList()
    override fun getUserAscentsBetween(from: String, to: String): List<AscentWithClimb> = emptyList()
    override fun getUserSentClimbUuids(): Set<String> = sentUuids
    override fun getUserAttemptedClimbUuids(): Set<String> = attemptedUuids - sentUuids
    override fun getUserSendDifficulties(since: String): List<Double> = emptyList()
    override fun getUserLogbookPage(limit: Int, offset: Int): List<AscentWithClimb> = emptyList()
    override fun getUserLogbookAllLight(): List<AscentWithClimb> = emptyList()
    override fun getUserHistoryForClimb(climbUuid: String): List<AscentWithClimb> = emptyList()
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
        climbName: String, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?,
    ) { insertedLogUuids.add(uuid) }

    override fun deleteBid(uuid: String) {}
    override fun getUserBidDifficulties(since: String): List<Double> = emptyList()
    override fun getUnsyncedBids(): List<RawBid> = emptyList()
    override fun markBidSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean = true
    override fun getRawBidsForUser(): List<RawBid> = emptyList()

    // -- Board session queries --

    override fun insertBoardSession(startedAt: String, endedAt: String?, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long): Long = 1L
    override fun getRecentBoardSessions(limit: Int): List<Board_sessions> = emptyList()
    override fun getActiveSession(): Board_sessions? = null
    override fun updateActiveSession(id: Long, ascentCount: Long, bidCount: Long, pauseDurationSeconds: Long, totalDurationSeconds: Long) {}
    override fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long) {}
    override fun getAllBoardSessions(): List<Board_sessions> = emptyList()

    // -- Climb list queries --

    override fun ensureFavoritesListExists(): Long = 1L
    override fun getAllClimbLists(): List<Climb_lists> = emptyList()
    override fun getClimbListById(id: Long): Climb_lists? = null
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
    override fun ensureIgnoredListExists(): Long = 2L
    override fun isClimbIgnored(climbUuid: String): Boolean = climbUuid in ignoredUuids
    override fun toggleIgnored(climbUuid: String): Boolean {
        return if (climbUuid in ignoredUuids) { ignoredUuids.remove(climbUuid); false }
        else { ignoredUuids.add(climbUuid); true }
    }
    override fun getIgnoredClimbUuids(): Set<String> = ignoredUuids
    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> = emptyList()

    // -- Playlists (functional in-memory impl so VM tests can exercise
    //    create/reorder/replace flows) --

    val playlists = mutableMapOf<Long, MutableList<PlaylistEntryRow>>()
    val playlistMeta = mutableMapOf<Long, Pair<String, String?>>()
    private var nextListId = 100L
    private var nextEntryId = 1L

    override fun createPlaylist(name: String, generatorParams: String?): Long {
        val id = nextListId++
        playlists[id] = mutableListOf()
        playlistMeta[id] = name to generatorParams
        return id
    }

    override fun updateGeneratorParams(listId: Long, generatorParams: String?) {
        playlistMeta[listId] = (playlistMeta[listId]?.first ?: "") to generatorParams
    }

    override fun addPlaylistClimb(listId: Long, climbUuid: String, angle: Long?): Long {
        val entries = playlists.getOrPut(listId) { mutableListOf() }
        val id = nextEntryId++
        entries.add(PlaylistEntryRow(id, listId, entries.size.toLong(), "climb", climbUuid, null, angle))
        return id
    }

    override fun addPlaylistRest(listId: Long, restSeconds: Long): Long {
        val entries = playlists.getOrPut(listId) { mutableListOf() }
        val id = nextEntryId++
        entries.add(PlaylistEntryRow(id, listId, entries.size.toLong(), "rest", null, restSeconds, null))
        return id
    }

    override fun getPlaylistEntries(listId: Long): List<PlaylistEntryRow> =
        playlists[listId]?.toList() ?: emptyList()

    override fun removePlaylistEntry(entryId: Long) {
        playlists.values.forEach { it.removeAll { e -> e.id == entryId } }
    }

    override fun updatePlaylistRestSeconds(entryId: Long, restSeconds: Long) {
        playlists.values.forEach { entries ->
            val i = entries.indexOfFirst { it.id == entryId }
            if (i >= 0) entries[i] = entries[i].copy(restSeconds = restSeconds)
        }
    }

    override fun movePlaylistEntry(listId: Long, fromIndex: Int, toIndex: Int) {
        val entries = playlists[listId] ?: return
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return
        val moved = entries.removeAt(fromIndex)
        entries.add(toIndex, moved)
        for (i in entries.indices) entries[i] = entries[i].copy(position = i.toLong())
    }

    override fun replacePlaylistEntries(listId: Long, entries: List<NewPlaylistEntry>) {
        val target = playlists.getOrPut(listId) { mutableListOf() }
        target.clear()
        entries.forEachIndexed { index, e ->
            val id = nextEntryId++
            target.add(
                PlaylistEntryRow(
                    id, listId, index.toLong(),
                    if (e.climbUuid != null) "climb" else "rest",
                    e.climbUuid, e.restSeconds, e.angle,
                )
            )
        }
    }

    // -- Denormalization --

    override fun getAllClimbKeys(): List<Pair<String, Long>> = emptyList()
    override fun getExistingLogUuids(): Set<String> = insertedLogUuids.toSet()
    override fun updateAscentDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, climbFrames: String, framesCount: Long, boardBrand: String, layoutId: Long?) {}
    override fun updateBidDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, boardBrand: String, layoutId: Long?) {}

    // -- Climb history --

    override suspend fun recordClimbHistory(climbUuid: String, climbName: String, angle: Long, difficultyAverage: Double?, boardBrand: String, layoutId: Long?, climbedAt: String, recordedAt: String) {}
    override fun observeClimbHistory(): Flow<List<ClimbHistoryEntry>> = flowOf(emptyList())
    override suspend fun clearClimbHistory() {}
    override suspend fun deleteClimbHistory(ids: List<Long>) {}
    override suspend fun pruneClimbHistory(cutoffIso: String) {}
    override suspend fun climbHistoryCount(): Long = 0L

    // -- Bulk operations --

    override fun deleteAllUserBoardData() { sentUuids.clear(); attemptedUuids.clear(); ignoredUuids.clear() }
    override fun runInTransaction(block: () -> Unit) { block() }
}
