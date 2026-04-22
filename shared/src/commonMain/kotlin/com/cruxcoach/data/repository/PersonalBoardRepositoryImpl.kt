package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil

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
        climbFrames: String, framesCount: Long
    ) {
        database.auroraAscentQueries.insertAscent(
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
            frames_count = framesCount
        )
    }

    override fun deleteAscent(uuid: String) {
        database.auroraAscentQueries.deleteAscent(uuid)
    }

    override fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?) {
        database.auroraAscentQueries.updateAscent(
            bid_count = bidCount,
            quality = quality,
            comment = comment,
            uuid = uuid
        )
    }

    override fun getUserAscentsAll(): List<AuroraAscentWithClimb> {
        return database.auroraAscentQueries.getUserAscentsAll().executeAsList().map { row ->
            AuroraAscentWithClimb(
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
                isSend = true
            )
        }
    }

    override fun getUserAscentsBetween(from: String, to: String): List<AuroraAscentWithClimb> {
        return database.auroraAscentQueries.getUserAscentsBetween(from, to).executeAsList().map { row ->
            AuroraAscentWithClimb(
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
                isSend = true
            )
        }
    }

    override fun getUserSentClimbUuids(): Set<String> {
        return database.auroraAscentQueries.getUserSentClimbUuids().executeAsList().toSet()
    }

    override fun getUserAttemptedClimbUuids(): Set<String> {
        return database.auroraBidQueries.getUserAttemptedClimbUuids().executeAsList().toSet()
    }

    override fun getUserSendDifficulties(since: String): List<Double> {
        return database.auroraAscentQueries.getUserSendDifficulties(since).executeAsList()
            .mapNotNull { it.difficulty_average }
    }

    override fun getUserLogbookPage(limit: Int, offset: Int): List<AuroraAscentWithClimb> {
        return database.auroraAscentQueries.getUserLogbookPage(
            limit = limit.toLong(),
            offset = offset.toLong()
        ).executeAsList().map { row ->
            AuroraAscentWithClimb(
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
                isSend = row.is_send == 1L
            )
        }
    }

    override fun getUserLogbookAllLight(): List<AuroraAscentWithClimb> {
        return database.auroraAscentQueries.getUserLogbookAllLight().executeAsList().map { row ->
            AuroraAscentWithClimb(
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
                isSend = row.is_send == 1L
            )
        }
    }

    override fun getUserHistoryForClimb(climbUuid: String): List<AuroraAscentWithClimb> {
        return database.auroraAscentQueries.getUserHistoryForClimb(climbUuid).executeAsList().map { row ->
            AuroraAscentWithClimb(
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
                isSend = row.is_send == 1L
            )
        }
    }

    override fun countUserLogbook(): Long {
        return database.auroraAscentQueries.countUserLogbook().executeAsOne()
    }

    override fun getRepeatCounts(): Map<String, Long> {
        return database.auroraAscentQueries.getRepeatCounts().executeAsList()
            .associate { it.climb_uuid to it.repeat_count }
    }

    override fun getUnsyncedAscents(): List<RawAscent> {
        return database.auroraAscentQueries.getUnsyncedAscents().executeAsList().map { row ->
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
            database.auroraAscentQueries.markAscentSyncedIfUnchanged(uuid, expectedRowVersion)
            database.auroraAscentQueries.lastAscentChangeCount().executeAsOne() > 0L
        }
    }

    // ── Bid ─────────────────────────────────────────────────────

    override fun insertBid(
        uuid: String, climbUuid: String, angle: Long,
        isMirror: Boolean, bidCount: Long, comment: String?,
        climbedAt: String, synced: Boolean,
        gymUuid: String?, wallUuid: String?, productLayoutUuid: String?,
        climbName: String, difficultyAverage: Double?
    ) {
        database.auroraBidQueries.insertBid(
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
            difficulty_average = difficultyAverage
        )
    }

    override fun deleteBid(uuid: String) {
        database.auroraBidQueries.deleteBid(uuid)
    }

    override fun getUserBidDifficulties(since: String): List<Double> {
        return database.auroraBidQueries.getUserBidDifficulties(since).executeAsList()
            .mapNotNull { it.difficulty_average }
    }

    override fun getUnsyncedBids(): List<RawBid> {
        return database.auroraBidQueries.getUnsyncedBids().executeAsList().map { row ->
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
            database.auroraBidQueries.markBidSyncedIfUnchanged(uuid, expectedRowVersion)
            database.auroraBidQueries.lastBidChangeCount().executeAsOne() > 0L
        }
    }

    override fun getRawBidsForUser(): List<RawBid> {
        return database.auroraBidQueries.getRawBidsForUser().executeAsList().map { row ->
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

    // ── Board Session ───────────────────────────────────────────

    override fun insertBoardSession(
        startedAt: String, endedAt: String?,
        totalDurationSeconds: Long, pauseDurationSeconds: Long,
        ascentCount: Long, bidCount: Long
    ): Long {
        // INSERT + last_insert_rowid() must run in the same transaction so
        // concurrent inserts can't steal the id.
        return database.transactionWithResult {
            database.boardSessionQueries.insertBoardSession(
                started_at = startedAt,
                ended_at = endedAt,
                total_duration_seconds = totalDurationSeconds,
                pause_duration_seconds = pauseDurationSeconds,
                ascent_count = ascentCount,
                bid_count = bidCount
            )
            database.boardSessionQueries.getLastInsertedSessionId().executeAsOne()
        }
    }

    override fun getRecentBoardSessions(limit: Int): List<BoardSession> {
        return database.boardSessionQueries.getRecentBoardSessions(limit.toLong()).executeAsList().map { row ->
            BoardSession(
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

    override fun getActiveSession(): BoardSession? {
        return database.boardSessionQueries.getActiveSession().executeAsOneOrNull()?.let { row ->
            BoardSession(
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
        database.boardSessionQueries.updateActiveSession(
            ascent_count = ascentCount,
            bid_count = bidCount,
            pause_duration_seconds = pauseDurationSeconds,
            total_duration_seconds = totalDurationSeconds,
            id = id
        )
    }

    override fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long) {
        database.boardSessionQueries.endBoardSession(
            ended_at = endedAt,
            total_duration_seconds = totalDurationSeconds,
            pause_duration_seconds = pauseDurationSeconds,
            ascent_count = ascentCount,
            bid_count = bidCount,
            id = id
        )
    }

    override fun getAllBoardSessions(): List<BoardSession> {
        return database.boardSessionQueries.getAllBoardSessions().executeAsList().map { row ->
            BoardSession(
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
            val existing = database.climbListQueries.getBuiltinFavoritesList().executeAsOneOrNull()
            if (existing != null) {
                existing.id
            } else {
                database.climbListQueries.insertClimbList("Favoriten", 1L, DateTimeUtil.nowIso())
                database.climbListQueries.getLastInsertedListId().executeAsOne()
            }
        }
        cachedFavoritesListId = id
        return id
    }

    override fun getAllClimbLists(): List<ClimbList> {
        return database.climbListQueries.getAllClimbLists().executeAsList().map { row ->
            ClimbList(
                id = row.id,
                name = row.name,
                isBuiltin = row.is_builtin != 0L,
                createdAt = row.created_at,
                climbCount = row.climb_count
            )
        }
    }

    override fun getClimbListById(id: Long): ClimbList? {
        return database.climbListQueries.getClimbListById(id).executeAsOneOrNull()?.let { row ->
            ClimbList(
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
            database.climbListQueries.insertClimbList(name, 0L, now)
            database.climbListQueries.getLastInsertedListId().executeAsOne()
        }
    }

    override fun renameClimbList(id: Long, name: String) {
        database.climbListQueries.updateClimbListName(name, id)
    }

    override fun deleteClimbList(id: Long) {
        database.climbListQueries.deleteClimbListEntries(id)
        database.climbListQueries.deleteClimbList(id)
    }

    override fun addClimbToList(listId: Long, climbUuid: String) {
        val now = DateTimeUtil.nowIso()
        database.climbListQueries.insertClimbListEntry(listId, climbUuid, now)
    }

    override fun removeClimbFromList(listId: Long, climbUuid: String) {
        database.climbListQueries.removeClimbListEntry(listId, climbUuid)
    }

    override fun getClimbListEntryUuids(listId: Long, limit: Int, offset: Int): List<Pair<String, String>> {
        return database.climbListQueries.getClimbListEntryUuids(
            list_id = listId,
            limit = limit.toLong(),
            offset = offset.toLong()
        ).executeAsList().map { it.climb_uuid to it.added_at }
    }

    override fun countClimbListEntries(listId: Long): Long {
        return database.climbListQueries.countClimbListEntries(listId).executeAsOne()
    }

    override fun getListIdsForClimb(climbUuid: String): Set<Long> {
        return database.climbListQueries.getListIdsForClimb(climbUuid).executeAsList().toSet()
    }

    override fun isClimbFavorited(climbUuid: String): Boolean {
        val favId = ensureFavoritesListExists()
        return database.climbListQueries.isClimbInList(favId, climbUuid).executeAsOne() > 0
    }

    override fun toggleFavorite(climbUuid: String): Boolean {
        val favId = ensureFavoritesListExists()
        // Read-modify-write must be atomic: two rapid taps otherwise both
        // observe the same state and either double-insert or double-delete.
        return database.transactionWithResult {
            val isFav = database.climbListQueries.isClimbInList(favId, climbUuid).executeAsOne() > 0
            if (isFav) {
                database.climbListQueries.removeClimbListEntry(favId, climbUuid)
            } else {
                database.climbListQueries.insertClimbListEntry(favId, climbUuid, DateTimeUtil.nowIso())
            }
            !isFav
        }
    }

    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> {
        return database.climbListQueries.getClimbListEntriesRaw().executeAsList().map { row ->
            RawClimbListEntry(listId = row.list_id, climbUuid = row.climb_uuid, addedAt = row.added_at)
        }
    }

    // ── Denormalization refresh ─────────────────────────────────

    override fun getAllClimbKeys(): List<Pair<String, Long>> {
        val ascentKeys = database.auroraAscentQueries.getAllAscentClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        val bidKeys = database.auroraBidQueries.getAllBidClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        return (ascentKeys + bidKeys).distinct()
    }

    override fun updateAscentDenormalized(
        climbUuid: String, angle: Long,
        climbName: String, difficultyAverage: Double?,
        climbFrames: String, framesCount: Long
    ) {
        database.auroraAscentQueries.updateAscentDenormalized(
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            climb_frames = climbFrames,
            frames_count = framesCount,
            climb_uuid = climbUuid,
            angle = angle
        )
    }

    override fun updateBidDenormalized(
        climbUuid: String, angle: Long,
        climbName: String, difficultyAverage: Double?
    ) {
        database.auroraBidQueries.updateBidDenormalized(
            climb_name = climbName,
            difficulty_average = difficultyAverage,
            climb_uuid = climbUuid,
            angle = angle
        )
    }

    // ── Bulk operations ─────────────────────────────────────────

    override fun deleteAllUserBoardData() {
        database.transaction {
            database.auroraAscentQueries.deleteAllAscents()
            database.auroraBidQueries.deleteAllBids()
            database.boardSessionQueries.deleteAllBoardSessions()
            database.climbListQueries.deleteAllClimbListEntries()
            database.climbListQueries.deleteAllClimbLists()
        }
        cachedFavoritesListId = null
    }

    override fun runInTransaction(block: () -> Unit) {
        database.transaction { block() }
    }
}
