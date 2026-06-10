package com.cruxcoach.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonalBoardRepositoryImpl(
    private val database: SecureDatabase
) : PersonalBoardRepository {

    @Volatile
    private var cachedFavoritesListId: Long? = null

    // ── Ascent ──────────────────────────────────────────────────

    override fun insertAscent(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, attemptId: Long, bidCount: Long,
        quality: Long?, difficulty: Long?, isBenchmark: Boolean,
        comment: String?, climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long,
        boardBrand: String, layoutId: Long?,
    ) {
        database.ascentsQueries.insertAscent(
            uuid = uuid,
            climb_uuid = climbUuid,
            angle = angle,
            is_mirror = if (isMirror) 1L else 0L,
            attempt_id = attemptId,
            bid_count = bidCount,
            quality = quality,
            difficulty = difficulty,
            is_benchmark = if (isBenchmark) 1L else 0L,
            comment = comment,
            climbed_at = climbedAt,
            synced = if (synced) 1L else 0L,
            gym_uuid = gymUuid,
            wall_uuid = wallUuid,
            product_layout_uuid = productLayoutUuid,
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            climb_frames = climbFrames,
            frames_count = framesCount,
            board_brand = boardBrand,
            layout_id = layoutId,
        )
    }

    override fun deleteAscent(uuid: String) {
        database.ascentsQueries.deleteAscent(uuid)
    }

    override fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?) {
        database.ascentsQueries.updateAscent(
            bid_count = bidCount,
            quality = quality,
            comment = comment,
            uuid = uuid
        )
    }

    override fun getUserAscentsAll(): List<AscentWithClimb> {
        return database.ascentsQueries.getUserAscentsAll().executeAsList().map { row ->
            AscentWithClimb(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                comment = row.comment,
                climbedAt = row.climbed_at,
                climbName = row.climb_name,
                climbFrames = row.climb_frames,
                difficultyAverage = row.difficulty_average,
                framesCount = row.frames_count,
                isSend = true,
                synced = row.synced != 0L,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun getUserAscentsBetween(from: String, to: String): List<AscentWithClimb> {
        return database.ascentsQueries.getUserAscentsBetween(from, to).executeAsList().map { row ->
            AscentWithClimb(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                comment = row.comment,
                climbedAt = row.climbed_at,
                climbName = row.climb_name,
                climbFrames = row.climb_frames,
                difficultyAverage = row.difficulty_average,
                framesCount = row.frames_count,
                isSend = true,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun getUserSentClimbUuids(): Set<String> {
        return database.ascentsQueries.getUserSentClimbUuids().executeAsList().toSet()
    }

    override fun getUserAttemptedClimbUuids(): Set<String> {
        return database.bidsQueries.getUserAttemptedClimbUuids().executeAsList().toSet()
    }

    override fun getUserSendDifficulties(since: String): List<Double> {
        return database.ascentsQueries.getUserSendDifficulties(since).executeAsList()
            .mapNotNull { it.difficulty_average }
    }

    override fun getUserLogbookPage(limit: Int, offset: Int): List<AscentWithClimb> {
        return database.ascentsQueries.getUserLogbookPage(
            limit = limit.toLong(),
            offset = offset.toLong()
        ).executeAsList().map { row ->
            AscentWithClimb(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                comment = row.comment,
                climbedAt = row.climbed_at,
                climbName = row.climb_name,
                climbFrames = row.climb_frames,
                difficultyAverage = row.difficulty_average,
                framesCount = row.frames_count,
                isSend = row.is_send == 1L,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun getUserLogbookAllLight(): List<AscentWithClimb> {
        return database.ascentsQueries.getUserLogbookAllLight().executeAsList().map { row ->
            AscentWithClimb(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                comment = row.comment,
                climbedAt = row.climbed_at,
                climbName = row.climb_name,
                climbFrames = row.climb_frames,
                difficultyAverage = row.difficulty_average,
                framesCount = row.frames_count,
                isSend = row.is_send == 1L,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun getUserHistoryForClimb(climbUuid: String): List<AscentWithClimb> {
        return database.ascentsQueries.getUserHistoryForClimb(climbUuid).executeAsList().map { row ->
            AscentWithClimb(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                comment = row.comment,
                climbedAt = row.climbed_at,
                climbName = row.climb_name,
                climbFrames = row.climb_frames,
                difficultyAverage = row.difficulty_average,
                framesCount = row.frames_count,
                isSend = row.is_send == 1L,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun countUserLogbook(): Long {
        return database.ascentsQueries.countUserLogbook().executeAsOne()
    }

    override fun getRepeatCounts(): Map<String, Long> {
        return database.ascentsQueries.getRepeatCounts().executeAsList()
            .associate { it.climb_uuid to it.repeat_count }
    }

    override fun getUnsyncedAscents(): List<RawAscent> {
        return database.ascentsQueries.getUnsyncedAscents().executeAsList().map { row ->
            RawAscent(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                attemptId = row.attempt_id ?: 0L,
                bidCount = row.bid_count ?: 0L,
                quality = row.quality,
                difficulty = row.difficulty,
                isBenchmark = row.is_benchmark != 0L,
                comment = row.comment,
                climbedAt = row.climbed_at,
                synced = row.synced != 0L,
                gymUuid = row.gym_uuid,
                wallUuid = row.wall_uuid,
                productLayoutUuid = row.product_layout_uuid,
                rowVersion = row.row_version
            )
        }
    }

    override fun markAscentSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean {
        // UPDATE + changes() must be atomic: `changes()` returns rows
        // affected by the last statement on the same connection, so any
        // interleaved write between the two would corrupt the result.
        return database.transactionWithResult {
            database.ascentsQueries.markAscentSyncedIfUnchanged(uuid, expectedRowVersion)
            database.ascentsQueries.lastAscentChangeCount().executeAsOne() > 0L
        }
    }

    // ── Bid ─────────────────────────────────────────────────────

    override fun insertBid(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?,
    ) {
        database.bidsQueries.insertBid(
            uuid = uuid,
            climb_uuid = climbUuid,
            angle = angle,
            is_mirror = if (isMirror) 1L else 0L,
            bid_count = bidCount,
            comment = comment,
            climbed_at = climbedAt,
            synced = if (synced) 1L else 0L,
            gym_uuid = gymUuid,
            wall_uuid = wallUuid,
            product_layout_uuid = productLayoutUuid,
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            board_brand = boardBrand,
            layout_id = layoutId,
        )
    }

    override fun deleteBid(uuid: String) {
        database.bidsQueries.deleteBid(uuid)
    }

    override fun getUserBidDifficulties(since: String): List<Double> {
        return database.bidsQueries.getUserBidDifficulties(since).executeAsList()
            .mapNotNull { it.difficulty_average }
    }

    override fun getUnsyncedBids(): List<RawBid> {
        return database.bidsQueries.getUnsyncedBids().executeAsList().map { row ->
            RawBid(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                comment = row.comment,
                climbedAt = row.climbed_at,
                synced = row.synced != 0L,
                gymUuid = row.gym_uuid,
                wallUuid = row.wall_uuid,
                productLayoutUuid = row.product_layout_uuid,
                rowVersion = row.row_version
            )
        }
    }

    override fun markBidSyncedIfUnchanged(uuid: String, expectedRowVersion: Long): Boolean {
        return database.transactionWithResult {
            database.bidsQueries.markBidSyncedIfUnchanged(uuid, expectedRowVersion)
            database.bidsQueries.lastBidChangeCount().executeAsOne() > 0L
        }
    }

    override fun getRawBidsForUser(): List<RawBid> {
        return database.bidsQueries.getRawBidsForUser().executeAsList().map { row ->
            RawBid(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
                bidCount = row.bid_count ?: 0L,
                comment = row.comment,
                climbedAt = row.climbed_at,
                synced = row.synced != 0L,
                gymUuid = row.gym_uuid,
                wallUuid = row.wall_uuid,
                productLayoutUuid = row.product_layout_uuid,
                rowVersion = row.row_version,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    // ── Board Session ───────────────────────────────────────────

    override fun insertBoardSession(
        startedAt: String, endedAt: String?,
        totalDurationSeconds: Long, pauseDurationSeconds: Long,
        ascentCount: Long, bidCount: Long
    ): Long {
        // INSERT + last_insert_rowid() must run in the same transaction so
        // concurrent inserts can't steal the id.
        return database.transactionWithResult {
            database.boardSessionsQueries.insertBoardSession(
                started_at = startedAt,
                ended_at = endedAt,
                total_duration_seconds = totalDurationSeconds,
                pause_duration_seconds = pauseDurationSeconds,
                ascent_count = ascentCount,
                bid_count = bidCount
            )
            database.boardSessionsQueries.getLastInsertedSessionId().executeAsOne()
        }
    }

    override fun getRecentBoardSessions(limit: Int): List<Board_sessions> {
        return database.boardSessionsQueries.getRecentBoardSessions(limit.toLong()).executeAsList().map { row ->
            Board_sessions(
                id = row.id,
                startedAt = row.started_at,
                endedAt = row.ended_at,
                totalDurationSeconds = row.total_duration_seconds,
                pauseDurationSeconds = row.pause_duration_seconds,
                ascentCount = row.ascent_count,
                bidCount = row.bid_count
            )
        }
    }

    override fun getActiveSession(): Board_sessions? {
        return database.boardSessionsQueries.getActiveSession().executeAsOneOrNull()?.let { row ->
            Board_sessions(
                id = row.id,
                startedAt = row.started_at,
                endedAt = row.ended_at,
                totalDurationSeconds = row.total_duration_seconds,
                pauseDurationSeconds = row.pause_duration_seconds,
                ascentCount = row.ascent_count,
                bidCount = row.bid_count
            )
        }
    }

    override fun updateActiveSession(id: Long, ascentCount: Long, bidCount: Long, pauseDurationSeconds: Long, totalDurationSeconds: Long) {
        database.boardSessionsQueries.updateActiveSession(
            ascent_count = ascentCount,
            bid_count = bidCount,
            pause_duration_seconds = pauseDurationSeconds,
            total_duration_seconds = totalDurationSeconds,
            id = id
        )
    }

    override fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long) {
        database.boardSessionsQueries.endBoardSession(
            ended_at = endedAt,
            total_duration_seconds = totalDurationSeconds,
            pause_duration_seconds = pauseDurationSeconds,
            ascent_count = ascentCount,
            bid_count = bidCount,
            id = id
        )
    }

    override fun getAllBoardSessions(): List<Board_sessions> {
        return database.boardSessionsQueries.getAllBoardSessions().executeAsList().map { row ->
            Board_sessions(
                id = row.id,
                startedAt = row.started_at,
                endedAt = row.ended_at,
                totalDurationSeconds = row.total_duration_seconds,
                pauseDurationSeconds = row.pause_duration_seconds,
                ascentCount = row.ascent_count,
                bidCount = row.bid_count
            )
        }
    }

    // ── Climb Lists ─────────────────────────────────────────────

    override fun ensureFavoritesListExists(): Long {
        cachedFavoritesListId?.let { return it }
        // Check-and-insert + last_insert_rowid() must be atomic. Without the
        // transaction, two rapid callers could both miss the existing list
        // and insert duplicate 'Favoriten' rows.
        val id = database.transactionWithResult {
            val existing = database.climbListsQueries.getBuiltinFavoritesList().executeAsOneOrNull()
            if (existing != null) {
                existing.id
            } else {
                database.climbListsQueries.insertClimbList("Favoriten", 1L, DateTimeUtil.nowIso())
                database.climbListsQueries.getLastInsertedListId().executeAsOne()
            }
        }
        cachedFavoritesListId = id
        return id
    }

    override fun getAllClimbLists(): List<Climb_lists> {
        return database.climbListsQueries.getAllClimbLists().executeAsList().map { row ->
            Climb_lists(
                id = row.id,
                name = row.name,
                isBuiltin = row.is_builtin != 0L,
                createdAt = row.created_at,
                climbCount = row.climb_count
            )
        }
    }

    override fun getClimbListById(id: Long): Climb_lists? {
        return database.climbListsQueries.getClimbListById(id).executeAsOneOrNull()?.let { row ->
            Climb_lists(
                id = row.id,
                name = row.name,
                isBuiltin = row.is_builtin != 0L,
                createdAt = row.created_at,
                climbCount = row.climb_count
            )
        }
    }

    override fun createClimbList(name: String): Long {
        val now = DateTimeUtil.nowIso()
        return database.transactionWithResult {
            database.climbListsQueries.insertClimbList(name, 0L, now)
            database.climbListsQueries.getLastInsertedListId().executeAsOne()
        }
    }

    override fun renameClimbList(id: Long, name: String) {
        database.climbListsQueries.updateClimbListName(name, id)
    }

    override fun deleteClimbList(id: Long) {
        database.climbListsQueries.deleteClimbListEntries(id)
        database.climbListsQueries.deleteClimbList(id)
    }

    override fun addClimbToList(listId: Long, climbUuid: String) {
        val now = DateTimeUtil.nowIso()
        database.climbListsQueries.insertClimbListEntry(listId, climbUuid, now)
    }

    override fun removeClimbFromList(listId: Long, climbUuid: String) {
        database.climbListsQueries.removeClimbListEntry(listId, climbUuid)
    }

    override fun getClimbListEntryUuids(listId: Long, limit: Int, offset: Int): List<Pair<String, String>> {
        return database.climbListsQueries.getClimbListEntryUuids(
            list_id = listId,
            limit = limit.toLong(),
            offset = offset.toLong()
        ).executeAsList().map { it.climb_uuid to it.added_at }
    }

    override fun countClimbListEntries(listId: Long): Long {
        return database.climbListsQueries.countClimbListEntries(listId).executeAsOne()
    }

    override fun getListIdsForClimb(climbUuid: String): Set<Long> {
        return database.climbListsQueries.getListIdsForClimb(climbUuid).executeAsList().toSet()
    }

    override fun isClimbFavorited(climbUuid: String): Boolean {
        val favId = ensureFavoritesListExists()
        return database.climbListsQueries.isClimbInList(favId, climbUuid).executeAsOne() > 0
    }

    override fun toggleFavorite(climbUuid: String): Boolean {
        val favId = ensureFavoritesListExists()
        // Read-modify-write must be atomic: two rapid taps otherwise both
        // observe the same state and either double-insert or double-delete.
        return database.transactionWithResult {
            val isFav = database.climbListsQueries.isClimbInList(favId, climbUuid).executeAsOne() > 0
            if (isFav) {
                database.climbListsQueries.removeClimbListEntry(favId, climbUuid)
            } else {
                database.climbListsQueries.insertClimbListEntry(favId, climbUuid, DateTimeUtil.nowIso())
            }
            !isFav
        }
    }

    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> {
        return database.climbListsQueries.getClimbListEntriesRaw().executeAsList().map { row ->
            RawClimbListEntry(listId = row.list_id, climbUuid = row.climb_uuid, addedAt = row.added_at)
        }
    }

    // ── Denormalization refresh ─────────────────────────────────

    override fun getAllClimbKeys(): List<Pair<String, Long>> {
        val ascentKeys = database.ascentsQueries.getAllAscentClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        val bidKeys = database.bidsQueries.getAllBidClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        return (ascentKeys + bidKeys).distinct()
    }

    override fun updateAscentDenormalized(
        climbUuid: String, angle: Long,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long,
        boardBrand: String, layoutId: Long?
    ) {
        database.ascentsQueries.updateAscentDenormalized(
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            climb_frames = climbFrames,
            frames_count = framesCount,
            board_brand = boardBrand,
            layout_id = layoutId,
            climb_uuid = climbUuid,
            angle = angle
        )
    }

    override fun updateBidDenormalized(
        climbUuid: String, angle: Long,
        climbName: String, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?
    ) {
        database.bidsQueries.updateBidDenormalized(
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            board_brand = boardBrand,
            layout_id = layoutId,
            climb_uuid = climbUuid,
            angle = angle
        )
    }

    // ── Climb history ("Verlauf") ───────────────────────────────

    override suspend fun recordClimbHistory(
        climbUuid: String, climbName: String, angle: Long, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?, climbedAt: String, recordedAt: String,
    ) {
        withContext(Dispatchers.Default) {
            database.climbHistoryQueries.insert(
                climbUuid = climbUuid,
                climbName = climbName,
                angle = angle,
                difficultyAverage = difficultyAverage,
                boardBrand = boardBrand,
                layoutId = layoutId,
                climbedAt = climbedAt,
                recordedAt = recordedAt,
            )
        }
    }

    override fun observeClimbHistory(): Flow<List<ClimbHistoryEntry>> {
        return database.climbHistoryQueries.selectAllRecent()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    ClimbHistoryEntry(
                        id = row.id,
                        climbUuid = row.climb_uuid,
                        climbName = row.climb_name,
                        angle = row.angle.toInt(),
                        difficultyAverage = row.difficulty_average,
                        boardBrand = row.board_brand,
                        layoutId = row.layout_id,
                        climbedAt = row.climbed_at,
                        recordedAt = row.recorded_at,
                    )
                }
            }
    }

    override suspend fun clearClimbHistory() {
        withContext(Dispatchers.Default) {
            database.climbHistoryQueries.deleteAll()
        }
    }

    override suspend fun deleteClimbHistory(ids: List<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.Default) {
            database.climbHistoryQueries.deleteByIds(ids)
        }
    }

    override suspend fun pruneClimbHistory(cutoffIso: String) {
        withContext(Dispatchers.Default) {
            database.climbHistoryQueries.deleteOlderThan(cutoffIso)
        }
    }

    override suspend fun climbHistoryCount(): Long = withContext(Dispatchers.Default) {
        database.climbHistoryQueries.countAll().executeAsOne()
    }

    // ── Bulk operations ─────────────────────────────────────────

    override fun deleteAllUserBoardData() {
        database.transaction {
            database.ascentsQueries.deleteAllAscents()
            database.bidsQueries.deleteAllBids()
            database.boardSessionsQueries.deleteAllBoardSessions()
            database.climbListsQueries.deleteAllClimbListEntries()
            database.climbListsQueries.deleteAllClimbLists()
        }
        cachedFavoritesListId = null
    }

    override fun runInTransaction(block: () -> Unit) {
        database.transaction { block() }
    }
}
