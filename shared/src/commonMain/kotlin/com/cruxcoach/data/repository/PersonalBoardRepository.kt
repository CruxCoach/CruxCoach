package com.cruxcoach.data.repository

import kotlinx.coroutines.flow.Flow

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
        climbFrames: String, framesCount: Long,
        // Board family + layout this ascent was logged on. Defaults suit the
        // Kilter-only callers (Kilter sync, backup restore) which leave them
        // implicit; the manual-log path passes the climb's real values.
        boardBrand: String = "kilter", layoutId: Long? = null,
    )

    fun deleteAscent(uuid: String)
    fun updateAscent(uuid: String, bidCount: Long, quality: Long?, comment: String?)
    /** Edit an attempt (bid). Bids have no quality rating. */
    fun updateBid(uuid: String, bidCount: Long, comment: String?)

    fun getUserAscentsAll(): List<AscentWithClimb>
    fun getUserAscentsBetween(from: String, to: String): List<AscentWithClimb>
    fun getUserSentClimbUuids(): Set<String>
    fun getUserAttemptedClimbUuids(): Set<String>
    fun getUserSendDifficulties(since: String): List<Double>
    fun getUserLogbookPage(limit: Int = 50, offset: Int = 0): List<AscentWithClimb>
    fun getUserLogbookAllLight(): List<AscentWithClimb>
    fun getUserHistoryForClimb(climbUuid: String): List<AscentWithClimb>
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
        climbName: String, difficultyAverage: Double?,
        boardBrand: String = "kilter", layoutId: Long? = null,
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

    fun getRecentBoardSessions(limit: Int = 20): List<Board_sessions>
    fun getActiveSession(): Board_sessions?
    fun updateActiveSession(id: Long, ascentCount: Long, bidCount: Long, pauseDurationSeconds: Long, totalDurationSeconds: Long)
    fun endBoardSession(id: Long, endedAt: String, totalDurationSeconds: Long, pauseDurationSeconds: Long, ascentCount: Long, bidCount: Long)
    fun getAllBoardSessions(): List<Board_sessions>

    // ── Climb list queries ──────────────────────────────────────

    fun ensureFavoritesListExists(): Long
    fun getAllClimbLists(): List<Climb_lists>
    fun getClimbListById(id: Long): Climb_lists?
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

    // ── Ignored climbs (built-in "Ignored" list) ────────────────
    // The user marks "Quatsch" climbs (e.g. the Weihnachtsbaum) as ignored
    // so they never get suggested in browse. Backed by the same climb_lists
    // machinery as favorites, via a built-in list keyed on a stable
    // external_id sentinel.

    /** Lazily creates (once) and returns the built-in Ignored list id. */
    fun ensureIgnoredListExists(): Long
    fun isClimbIgnored(climbUuid: String): Boolean
    /** Toggles the climb's ignored membership; returns the NEW state. */
    fun toggleIgnored(climbUuid: String): Boolean
    /** All ignored climb UUIDs — loaded once for the browser's client-side
     *  always-on ignore filter. */
    fun getIgnoredClimbUuids(): Set<String>

    fun getClimbListEntriesRaw(): List<RawClimbListEntry>

    // ── Playlists (kind='playlist' climb_lists) ─────────────────
    // Ordered, playable lists: explicit position, duplicate climbs allowed
    // (4x4 sets), rest rows interleaved. Plain-list methods above stay
    // added_at-ordered and untouched.

    /** Creates an empty playlist; [generatorParams] is the JSON parameter
     *  snapshot for generated playlists (null for manual ones). */
    fun createPlaylist(name: String, generatorParams: String? = null): Long
    fun updateGeneratorParams(listId: Long, generatorParams: String?)
    /** Appends a climb at the end; returns the new entry id. */
    fun addPlaylistClimb(listId: Long, climbUuid: String, angle: Long?): Long
    /** Appends a rest block at the end; returns the new entry id. */
    fun addPlaylistRest(listId: Long, restSeconds: Long): Long
    /** All entries ordered by position (climbs + rests). */
    fun getPlaylistEntries(listId: Long): List<PlaylistEntryRow>
    fun removePlaylistEntry(entryId: Long)
    fun updatePlaylistRestSeconds(entryId: Long, restSeconds: Long)
    /** Moves the entry at [fromIndex] to [toIndex] (indices into the
     *  position-ordered entry list) and re-writes dense positions. */
    fun movePlaylistEntry(listId: Long, fromIndex: Int, toIndex: Int)
    /** Replaces ALL entries of the playlist with [entries] in order —
     *  the generator's snapshot write. */
    fun replacePlaylistEntries(listId: Long, entries: List<NewPlaylistEntry>)

    // ── Denormalization refresh ─────────────────────────────────

    /** Returns all distinct (climbUuid, angle) pairs across ascents and bids. */
    fun getAllClimbKeys(): List<Pair<String, Long>>

    /** Every already-imported Kilter log uuid (ascent + bid PKs). Used to
     *  count how many fetched logs are genuinely new vs re-imported. */
    fun getExistingLogUuids(): Set<String>

    /** Batch-update denormalized fields after a board sync. Also back-fills
     *  board_brand + layout_id from the matched climb, self-healing legacy /
     *  restored rows that defaulted to kilter/NULL. */
    fun updateAscentDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, climbFrames: String, framesCount: Long, boardBrand: String, layoutId: Long?)
    fun updateBidDenormalized(climbUuid: String, angle: Long, climbName: String, difficultyAverage: Double?, boardBrand: String, layoutId: Long?)

    // ── Climb history ("Verlauf") ───────────────────────────────
    // Local, append-only log of SENT climbs for the history screen. Never
    // synced to Kilter; never backed up/exported.

    /** Append one SENT climb to the local history log. */
    suspend fun recordClimbHistory(
        climbUuid: String, climbName: String, angle: Long, difficultyAverage: Double?,
        boardBrand: String, layoutId: Long?, climbedAt: String, recordedAt: String,
    )

    /** History entries, newest-recorded first; re-emits on every change. */
    fun observeClimbHistory(): Flow<List<ClimbHistoryEntry>>

    /** Wipe the entire history log. */
    suspend fun clearClimbHistory()

    /** Delete a user-selected set of history entries (single or multi-select). */
    suspend fun deleteClimbHistory(ids: List<Long>)

    /** Retention prune: drop history rows recorded before [cutoffIso] (ISO LocalDateTime). */
    suspend fun pruneClimbHistory(cutoffIso: String)

    suspend fun climbHistoryCount(): Long

    // ── Bulk operations ─────────────────────────────────────────

    fun deleteAllUserBoardData()
    fun runInTransaction(block: () -> Unit)
}
