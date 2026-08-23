package com.cruxcoach.android.fakes

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import com.cruxcoach.data.repository.ListPlaybackStepRow
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
import com.cruxcoach.data.repository.RawClimbListEntry
import com.cruxcoach.data.repository.RawListPlaybackStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fake of [PersonalBoardRepository] for unit tests.
 */
class FakePersonalBoardRepository : PersonalBoardRepository {

    val sentUuids = mutableSetOf<String>()
    val attemptedUuids = mutableSetOf<String>()
    val ignoredUuids = mutableSetOf<String>()
    val climbNotes = mutableMapOf<String, String>()
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
        externalId: String?,
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
    override fun getAscentsForBackup(): List<com.cruxcoach.data.repository.AscentBackupRow> = emptyList()
    override fun getBidsForBackup(): List<com.cruxcoach.data.repository.BidBackupRow> = emptyList()
    override fun markAscentSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean = true

    // -- Bid queries --

    override fun insertBid(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?,
        externalId: String?,
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
    override fun createClimbList(name: String, generatorParams: String?): Long {
        val id = nextListId++
        playbackSteps[id] = mutableListOf()
        listMeta[id] = name to generatorParams
        return id
    }
    override fun renameClimbList(id: Long, name: String) {}
    override fun deleteClimbList(id: Long) {}
    override fun addClimbToList(listId: Long, climbUuid: String) {}
    override fun addClimbToListAndExtendPlayback(listId: Long, climbUuid: String, angle: Long?) {
        addClimbToList(listId, climbUuid)
    }
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
    override fun getClimbNote(climbUuid: String): String? = climbNotes[climbUuid]
    override fun saveClimbNote(climbUuid: String, note: String) {
        val normalized = note.trim()
        if (normalized.isEmpty()) climbNotes.remove(climbUuid)
        else climbNotes[climbUuid] = normalized
    }
    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> = emptyList()
    override fun getListPlaybackStepsRaw(): List<RawListPlaybackStep> = emptyList()
    override fun getClimbListsForBackup(): List<com.cruxcoach.data.repository.ClimbListBackupRow> = emptyList()
    override fun restoreClimbList(
        name: String, createdAt: String,
        description: String?, color: String?, externalId: String?,
    ): Long = 1L

    // -- Optional training plans (functional in-memory implementation) --

    val playbackSteps = mutableMapOf<Long, MutableList<ListPlaybackStepRow>>()
    val listMeta = mutableMapOf<Long, Pair<String, String?>>()
    private var nextListId = 100L
    private var nextEntryId = 1L

    override fun updateGeneratorParams(listId: Long, generatorParams: String?) {
        listMeta[listId] = (listMeta[listId]?.first ?: "") to generatorParams
    }

    override fun updatePlaybackSettings(
        listId: Long,
        order: ListPlaybackOrder,
        advance: ListPlaybackAdvance,
        restSeconds: Long,
    ) = Unit

    override fun addPlaybackClimb(listId: Long, climbUuid: String, angle: Long?): Long {
        val entries = playbackSteps.getOrPut(listId) { mutableListOf() }
        val id = nextEntryId++
        entries.add(ListPlaybackStepRow(id, listId, entries.size.toLong(), "climb", climbUuid, null, angle))
        return id
    }

    override fun addPlaybackRest(listId: Long, restSeconds: Long): Long {
        val entries = playbackSteps.getOrPut(listId) { mutableListOf() }
        val id = nextEntryId++
        entries.add(ListPlaybackStepRow(id, listId, entries.size.toLong(), "rest", null, restSeconds, null))
        return id
    }

    override fun getPlaybackSteps(listId: Long): List<ListPlaybackStepRow> =
        playbackSteps[listId]?.toList() ?: emptyList()

    override fun removePlaybackStep(stepId: Long) {
        playbackSteps.values.forEach { it.removeAll { e -> e.id == stepId } }
    }

    override fun removePlaybackSteps(stepIds: Collection<Long>) {
        stepIds.toSet().forEach(::removePlaybackStep)
    }

    override fun updatePlaybackRestSeconds(stepId: Long, restSeconds: Long) {
        playbackSteps.values.forEach { entries ->
            val i = entries.indexOfFirst { it.id == stepId }
            if (i >= 0) entries[i] = entries[i].copy(restSeconds = restSeconds)
        }
    }

    override fun updatePlaybackRestSeconds(stepIds: Collection<Long>, restSeconds: Long) {
        stepIds.toSet().forEach { updatePlaybackRestSeconds(it, restSeconds) }
    }

    override fun movePlaybackStep(listId: Long, fromIndex: Int, toIndex: Int) {
        val entries = playbackSteps[listId] ?: return
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return
        val moved = entries.removeAt(fromIndex)
        entries.add(toIndex, moved)
        for (i in entries.indices) entries[i] = entries[i].copy(position = i.toLong())
    }

    override fun reorderPlaybackSteps(listId: Long, orderedStepIds: List<Long>): Boolean {
        val entries = playbackSteps[listId] ?: return orderedStepIds.isEmpty()
        if (
            orderedStepIds.toSet().size != orderedStepIds.size ||
            orderedStepIds.toSet() != entries.map { it.id }.toSet()
        ) {
            return false
        }
        val byId = entries.associateBy { it.id }
        entries.clear()
        entries.addAll(
            orderedStepIds.mapIndexed { index, id ->
                requireNotNull(byId[id]).copy(position = index.toLong())
            }
        )
        return true
    }

    override fun replacePlaybackSteps(listId: Long, steps: List<NewListPlaybackStep>) {
        val target = playbackSteps.getOrPut(listId) { mutableListOf() }
        target.clear()
        steps.forEachIndexed { index, e ->
            val id = nextEntryId++
            target.add(
                ListPlaybackStepRow(
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
    override fun getAllListEntryClimbUuids(): Set<String> = emptySet()
    override fun deleteUserBoardDataForBrands(brands: Set<String>, listEntryClimbUuids: Collection<String>) {}
    override fun runInTransaction(block: () -> Unit) { block() }
}
