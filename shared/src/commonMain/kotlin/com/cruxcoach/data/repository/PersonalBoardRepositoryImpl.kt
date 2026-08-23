package com.cruxcoach.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Stable identifier for the built-in "Ignored" list — promoted to
 *  [PersonalBoardRepository.IGNORED_LIST_EXTERNAL_ID] so the backup layer
 *  can discriminate the two built-ins; aliased here to keep call sites
 *  short. */
private const val IGNORED_LIST_EXTERNAL_ID = PersonalBoardRepository.IGNORED_LIST_EXTERNAL_ID
private const val MAX_PLAYBACK_ANGLE = 90L

class PersonalBoardRepositoryImpl(
    private val database: SecureDatabase
) : PersonalBoardRepository {

    @Volatile
    private var cachedFavoritesListId: Long? = null

    @Volatile
    private var cachedIgnoredListId: Long? = null

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
        externalId: String?,
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
            external_id = externalId,
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

    override fun getAscentsForBackup(): List<AscentBackupRow> {
        return database.ascentsQueries.getUserAscentsAll().executeAsList().map { row ->
            AscentBackupRow(
                uuid = row.uuid,
                climbUuid = row.climb_uuid,
                angle = row.angle,
                isMirror = row.is_mirror != 0L,
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
                climbName = row.climb_name,
                difficultyAverage = row.difficulty_average,
                climbFrames = row.climb_frames,
                framesCount = row.frames_count,
                externalId = row.external_id,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
            )
        }
    }

    override fun getBidsForBackup(): List<BidBackupRow> {
        return database.bidsQueries.getRawBidsForUser().executeAsList().map { row ->
            BidBackupRow(
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
                climbName = row.climb_name,
                difficultyAverage = row.difficulty_average,
                externalId = row.external_id,
                boardBrand = row.board_brand,
                layoutId = row.layout_id,
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
        externalId: String?,
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
            external_id = externalId,
        )
    }

    override fun updateBid(uuid: String, bidCount: Long, comment: String?) {
        database.bidsQueries.updateBid(
            bid_count = bidCount,
            comment = comment,
            uuid = uuid,
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
                climbCount = row.climb_count,
                isIgnored = row.external_id == IGNORED_LIST_EXTERNAL_ID,
                generatorParams = row.generator_params,
                hasPlaybackPlan = row.playback_step_count > 0L,
                playbackOrder = ListPlaybackOrder.fromWire(row.playback_order),
                playbackAdvance = ListPlaybackAdvance.fromWire(row.playback_advance),
                playbackRestSeconds = row.playback_rest_seconds,
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
                climbCount = row.climb_count,
                isIgnored = row.external_id == IGNORED_LIST_EXTERNAL_ID,
                generatorParams = row.generator_params,
                hasPlaybackPlan = row.playback_step_count > 0L,
                playbackOrder = ListPlaybackOrder.fromWire(row.playback_order),
                playbackAdvance = ListPlaybackAdvance.fromWire(row.playback_advance),
                playbackRestSeconds = row.playback_rest_seconds,
            )
        }
    }

    override fun createClimbList(name: String, generatorParams: String?): Long {
        val now = DateTimeUtil.nowIso()
        return database.transactionWithResult {
            if (generatorParams == null) {
                database.climbListsQueries.insertClimbList(name, 0L, now)
            } else {
                database.climbListsQueries.insertGeneratedClimbList(name, now, generatorParams)
            }
            database.climbListsQueries.getLastInsertedListId().executeAsOne()
        }
    }

    override fun renameClimbList(id: Long, name: String) {
        database.climbListsQueries.updateClimbListName(name, id)
    }

    override fun deleteClimbList(id: Long) {
        database.transaction {
            database.climbListsQueries.deleteListPlaybackSteps(id)
            database.climbListsQueries.deleteClimbListEntries(id)
            database.climbListsQueries.deleteClimbList(id)
        }
    }

    override fun addClimbToList(listId: Long, climbUuid: String) {
        val now = DateTimeUtil.nowIso()
        database.climbListsQueries.insertClimbListEntry(listId, climbUuid, now)
    }

    override fun addClimbToListAndExtendPlayback(
        listId: Long,
        climbUuid: String,
        angle: Long?,
    ) {
        val now = DateTimeUtil.nowIso()
        database.transaction {
            val wasAlreadyMember = database.climbListsQueries
                .isClimbInList(listId, climbUuid)
                .executeAsOne() > 0L
            database.climbListsQueries.insertClimbListEntry(listId, climbUuid, now)
            if (!wasAlreadyMember) {
                appendNewMemberToPlaybackPlan(listId, climbUuid, angle)
            }
        }
    }

    override fun removeClimbFromList(listId: Long, climbUuid: String) {
        database.transaction {
            database.climbListsQueries.deletePlaybackStepsForClimb(listId, climbUuid)
            database.climbListsQueries.removeClimbListEntry(listId, climbUuid)
        }
    }

    override fun getClimbListEntryUuids(listId: Long, limit: Int, offset: Int): List<Pair<String, String>> {
        return database.climbListsQueries.getClimbListEntryUuids(
            list_id = listId,
            limit = limit.toLong(),
            offset = offset.toLong()
        ).executeAsList().map { row -> row.climb_uuid to row.added_at }
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
                database.climbListsQueries.deletePlaybackStepsForClimb(favId, climbUuid)
                database.climbListsQueries.removeClimbListEntry(favId, climbUuid)
            } else {
                database.climbListsQueries.insertClimbListEntry(favId, climbUuid, DateTimeUtil.nowIso())
                appendNewMemberToPlaybackPlan(favId, climbUuid, requestedAngle = null)
            }
            !isFav
        }
    }

    override fun getClimbNote(climbUuid: String): String? =
        database.climbNotesQueries.selectByClimbUuid(climbUuid).executeAsOneOrNull()

    override fun saveClimbNote(climbUuid: String, note: String) {
        val normalized = note.trim().take(1000)
        if (normalized.isEmpty()) {
            database.climbNotesQueries.deleteByClimbUuid(climbUuid)
        } else {
            database.climbNotesQueries.upsert(
                climbUuid = climbUuid,
                note = normalized,
                updatedAt = DateTimeUtil.nowIso(),
            )
        }
    }

    override fun getClimbListEntriesRaw(): List<RawClimbListEntry> {
        return database.climbListsQueries.getClimbListEntriesRaw().executeAsList().map { row ->
            RawClimbListEntry(
                listId = row.list_id,
                climbUuid = row.climb_uuid,
                addedAt = row.added_at,
            )
        }
    }

    override fun getListPlaybackStepsRaw(): List<RawListPlaybackStep> {
        return database.climbListsQueries.getAllPlaybackStepsRaw().executeAsList().map { row ->
            RawListPlaybackStep(
                listId = row.list_id,
                climbUuid = row.climb_uuid,
                position = row.position,
                stepType = row.step_type,
                restSeconds = row.rest_seconds,
                angle = row.angle,
            )
        }
    }

    override fun getClimbListsForBackup(): List<ClimbListBackupRow> {
        return database.climbListsQueries.getAllClimbLists().executeAsList().map { row ->
            ClimbListBackupRow(
                id = row.id,
                name = row.name,
                isBuiltin = row.is_builtin != 0L,
                createdAt = row.created_at,
                description = row.description,
                color = row.color,
                externalId = row.external_id,
                generatorParams = row.generator_params,
                playbackOrder = ListPlaybackOrder.fromWire(row.playback_order),
                playbackAdvance = ListPlaybackAdvance.fromWire(row.playback_advance),
                playbackRestSeconds = row.playback_rest_seconds,
            )
        }
    }

    override fun restoreClimbList(
        name: String, createdAt: String,
        description: String?, color: String?, externalId: String?,
    ): Long {
        // Find-or-create + last_insert_rowid() must be atomic — same
        // race rationale as ensureFavoritesListExists / createClimbList.
        return database.transactionWithResult {
            val existing = externalId?.let {
                database.climbListsQueries.findClimbListByExternalId(it).executeAsOneOrNull()
            }
            if (existing != null) {
                database.climbListsQueries.updateAuroraClimbListMeta(
                    name = name, description = description, color = color, id = existing,
                )
                existing
            } else {
                // The "Aurora" insert is just the full-metadata insert
                // (is_builtin=0 + description/color/external_id); it also
                // preserves the original created_at, which the plain
                // createClimbList path resets to now().
                database.climbListsQueries.insertAuroraClimbList(
                    name = name, created_at = createdAt,
                    description = description, color = color, external_id = externalId,
                )
                database.climbListsQueries.getLastInsertedListId().executeAsOne()
            }
        }
    }

    // ── Optional training plan + playback defaults ──────────────

    override fun updateGeneratorParams(listId: Long, generatorParams: String?) {
        database.climbListsQueries.updateGeneratorParams(generatorParams, listId)
    }

    override fun updatePlaybackSettings(
        listId: Long,
        order: ListPlaybackOrder,
        advance: ListPlaybackAdvance,
        restSeconds: Long,
    ) {
        database.climbListsQueries.updatePlaybackSettings(
            playback_order = order.wireValue,
            playback_advance = advance.wireValue,
            playback_rest_seconds = restSeconds.coerceIn(0L, 3600L),
            id = listId,
        )
    }

    override fun addPlaybackClimb(listId: Long, climbUuid: String, angle: Long?): Long {
        val now = DateTimeUtil.nowIso()
        return database.transactionWithResult {
            database.climbListsQueries.insertClimbListEntry(listId, climbUuid, now)
            val next = nextPlaybackPosition(listId)
            database.climbListsQueries.insertPlaybackClimbStep(
                listId,
                climbUuid,
                next,
                angle?.coerceIn(0L, MAX_PLAYBACK_ANGLE),
            )
            database.climbListsQueries.getLastInsertedPlaybackStepId().executeAsOne()
        }
    }

    override fun addPlaybackRest(listId: Long, restSeconds: Long): Long {
        return database.transactionWithResult {
            val next = nextPlaybackPosition(listId)
            database.climbListsQueries.insertPlaybackRestStep(listId, next, restSeconds.coerceIn(0L, 3600L))
            database.climbListsQueries.getLastInsertedPlaybackStepId().executeAsOne()
        }
    }

    override fun getPlaybackSteps(listId: Long): List<ListPlaybackStepRow> {
        return database.climbListsQueries.getPlaybackSteps(listId).executeAsList().map { row ->
            ListPlaybackStepRow(
                id = row.id,
                listId = listId,
                position = row.position,
                stepType = row.step_type,
                climbUuid = row.climb_uuid,
                restSeconds = row.rest_seconds,
                angle = row.angle,
            )
        }
    }

    override fun removePlaybackStep(stepId: Long) {
        database.climbListsQueries.deletePlaybackStepById(stepId)
    }

    override fun removePlaybackSteps(stepIds: Collection<Long>) {
        database.transaction {
            stepIds.distinct().forEach(database.climbListsQueries::deletePlaybackStepById)
        }
    }

    override fun updatePlaybackRestSeconds(stepId: Long, restSeconds: Long) {
        database.climbListsQueries.updatePlaybackStepRestSeconds(restSeconds.coerceIn(0L, 3600L), stepId)
    }

    override fun updatePlaybackRestSeconds(stepIds: Collection<Long>, restSeconds: Long) {
        val duration = restSeconds.coerceIn(0L, 3600L)
        database.transaction {
            stepIds.distinct().forEach { stepId ->
                database.climbListsQueries.updatePlaybackStepRestSeconds(duration, stepId)
            }
        }
    }

    override fun movePlaybackStep(listId: Long, fromIndex: Int, toIndex: Int) {
        database.transaction {
            val ids = database.climbListsQueries.getPlaybackSteps(listId)
                .executeAsList().map { it.id }.toMutableList()
            if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) {
                return@transaction
            }
            val moved = ids.removeAt(fromIndex)
            ids.add(toIndex, moved)
            // Dense re-write keeps positions gap-free so index-based moves
            // stay trivially correct.
            ids.forEachIndexed { index, id ->
                database.climbListsQueries.updatePlaybackStepPosition(index.toLong(), id)
            }
        }
    }

    override fun reorderPlaybackSteps(listId: Long, orderedStepIds: List<Long>): Boolean {
        return database.transactionWithResult {
            val storedIds = database.climbListsQueries.getPlaybackSteps(listId)
                .executeAsList()
                .map { it.id }
            val suppliedIds = orderedStepIds.toSet()
            if (
                suppliedIds.size != orderedStepIds.size ||
                suppliedIds.size != storedIds.size ||
                suppliedIds != storedIds.toSet()
            ) {
                return@transactionWithResult false
            }
            orderedStepIds.forEachIndexed { index, id ->
                database.climbListsQueries.updatePlaybackStepPosition(index.toLong(), id)
            }
            true
        }
    }

    override fun replacePlaybackSteps(listId: Long, steps: List<NewListPlaybackStep>) {
        val now = DateTimeUtil.nowIso()
        database.transaction {
            database.climbListsQueries.deleteListPlaybackSteps(listId)
            steps.forEachIndexed { index, step ->
                val climbUuid = step.climbUuid
                if (climbUuid != null) {
                    database.climbListsQueries.insertClimbListEntry(listId, climbUuid, now)
                    database.climbListsQueries.insertPlaybackClimbStep(
                        listId,
                        climbUuid,
                        index.toLong(),
                        step.angle?.coerceIn(0L, MAX_PLAYBACK_ANGLE),
                    )
                } else {
                    database.climbListsQueries.insertPlaybackRestStep(
                        listId, index.toLong(), (step.restSeconds ?: 0L).coerceIn(0L, 3600L),
                    )
                }
            }
        }
    }

    private fun nextPlaybackPosition(listId: Long): Long {
        val steps = database.climbListsQueries.getPlaybackSteps(listId).executeAsList()
        return (steps.maxOfOrNull { it.position } ?: -1L) + 1L
    }

    /**
     * Extend an existing custom plan without conflating membership and plan
     * removal. Called only for a newly inserted list member, inside the
     * caller's transaction.
     */
    private fun appendNewMemberToPlaybackPlan(
        listId: Long,
        climbUuid: String,
        requestedAngle: Long?,
    ) {
        val current = getPlaybackSteps(listId)
        if (current.none { !it.isRest }) return

        // Defensive spelling-agnostic check for legacy UUID forms. Normally a
        // new membership cannot already be present in the plan because every
        // climb step also creates membership.
        val uuidKey = climbUuid.replace("-", "").lowercase()
        if (current.any { row ->
                row.climbUuid?.replace("-", "")?.lowercase() == uuidKey
            }
        ) {
            return
        }

        var nextPosition = (current.maxOfOrNull { it.position } ?: -1L) + 1L
        if (current.lastOrNull()?.isRest != true) {
            val configuredFallback = getClimbListById(listId)?.playbackRestSeconds ?: 0L
            val inferredRest = inferAutoPlaybackRestSeconds(
                previousRestSeconds = current.map { it.restSeconds },
                configuredFallbackSeconds = configuredFallback,
            )
            database.climbListsQueries.insertPlaybackRestStep(
                listId,
                nextPosition++,
                inferredRest,
            )
        }
        val inheritedAngle = current.asReversed().firstNotNullOfOrNull { it.angle }
        database.climbListsQueries.insertPlaybackClimbStep(
            listId,
            climbUuid,
            nextPosition,
            (requestedAngle ?: inheritedAngle)?.coerceIn(0L, MAX_PLAYBACK_ANGLE),
        )
    }

    // ── Ignored climbs ──────────────────────────────────────────

    override fun ensureIgnoredListExists(): Long {
        cachedIgnoredListId?.let { return it }
        // Atomic find-or-create keyed on the external_id sentinel — the
        // unique index on external_id is the backstop against a duplicate
        // ignored row if two callers race.
        val id = database.transactionWithResult {
            database.climbListsQueries
                .findClimbListByExternalId(IGNORED_LIST_EXTERNAL_ID).executeAsOneOrNull()
                ?: run {
                    database.climbListsQueries.insertBuiltinIgnoredList(
                        "Ignoriert", DateTimeUtil.nowIso(), IGNORED_LIST_EXTERNAL_ID,
                    )
                    database.climbListsQueries.getLastInsertedListId().executeAsOne()
                }
        }
        cachedIgnoredListId = id
        return id
    }

    override fun isClimbIgnored(climbUuid: String): Boolean {
        val ignoredId = ensureIgnoredListExists()
        return database.climbListsQueries.isClimbInList(ignoredId, climbUuid).executeAsOne() > 0
    }

    override fun toggleIgnored(climbUuid: String): Boolean {
        val ignoredId = ensureIgnoredListExists()
        // Atomic read-modify-write — mirrors toggleFavorite.
        return database.transactionWithResult {
            val isIgnored = database.climbListsQueries.isClimbInList(ignoredId, climbUuid).executeAsOne() > 0
            if (isIgnored) {
                database.climbListsQueries.removeClimbListEntry(ignoredId, climbUuid)
            } else {
                database.climbListsQueries.insertClimbListEntry(ignoredId, climbUuid, DateTimeUtil.nowIso())
            }
            !isIgnored
        }
    }

    override fun getIgnoredClimbUuids(): Set<String> {
        val ignoredId = ensureIgnoredListExists()
        return database.climbListsQueries.getAllClimbUuidsInList(ignoredId)
            .executeAsList().filterNotNull().toSet()
    }

    // ── Denormalization refresh ─────────────────────────────────

    override fun getAllClimbKeys(): List<Pair<String, Long>> {
        val ascentKeys = database.ascentsQueries.getAllAscentClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        val bidKeys = database.bidsQueries.getAllBidClimbKeys().executeAsList()
            .map { it.climb_uuid to it.angle }
        return (ascentKeys + bidKeys).distinct()
    }

    override fun getExistingLogUuids(): Set<String> {
        // log_uuid is the PK of whichever table the log landed in (ascent if
        // topped, else bid), so the union is the full set of already-imported
        // Kilter logs — the dedup key for counting a re-import.
        val ascentUuids = database.ascentsQueries.getAllAscentUuids().executeAsList()
        val bidUuids = database.bidsQueries.getAllBidUuids().executeAsList()
        return (ascentUuids + bidUuids).toHashSet()
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
            database.climbListsQueries.deleteAllPlaybackSteps()
            database.climbListsQueries.deleteAllClimbListEntries()
            database.climbListsQueries.deleteAllClimbLists()
        }
        cachedFavoritesListId = null
        cachedIgnoredListId = null
    }

    override fun getAllListEntryClimbUuids(): Set<String> =
        database.climbListsQueries.getAllListEntryClimbUuids().executeAsList().toSet()

    override fun deleteUserBoardDataForBrands(
        brands: Set<String>,
        listEntryClimbUuids: Collection<String>,
    ) {
        database.transaction {
            for (brand in brands) {
                database.ascentsQueries.deleteAscentsForBrand(brand)
                database.bidsQueries.deleteBidsForBrand(brand)
            }
            // Sessions stay: brand-less aggregates, see BoardSessions.sq.
            // Each uuid binds one SQLite host parameter — chunk well below
            // the portable 999-variable limit.
            listEntryClimbUuids.chunked(500).forEach {
                database.climbListsQueries.deleteClimbListEntriesForClimbs(it)
            }
        }
        // The built-in list rows survive this path, so the cached ids stay
        // technically valid — drop them anyway, mirroring
        // deleteAllUserBoardData, so this path can never grow a stale-cache
        // bug if what it deletes ever changes.
        cachedFavoritesListId = null
        cachedIgnoredListId = null
    }

    override fun runInTransaction(block: () -> Unit) {
        database.transaction { block() }
    }
}
