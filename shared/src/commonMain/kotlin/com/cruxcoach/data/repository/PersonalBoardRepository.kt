package com.cruxcoach.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Personal board data — ascents, bids, sessions, climb lists.
 * Backed by the per-key SecureDatabase. No cross-DB JOINs;
 * climb metadata is denormalized at insert time.
 */
interface PersonalBoardRepository {

    companion object {
        /** Stable identifier of the built-in "Ignored" list in
         *  climb_lists.external_id. Shared with the backup layer so a
         *  restore can tell the two `is_builtin=1` lists apart —
         *  Favorites is the builtin WITHOUT an external_id. */
        const val IGNORED_LIST_EXTERNAL_ID = "cruxcoach:builtin:ignored"
    }

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
        // FEAT-005 Aurora idempotency marker, round-tripped by the backup so
        // a post-restore Aurora re-import still dedups instead of doubling
        // the logbook. Null for every non-Aurora row.
        externalId: String? = null,
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

    /** Full-fidelity ascent rows for the backup envelope — carries the
     *  columns the UI-facing [AscentWithClimb] intentionally drops
     *  (is_benchmark, gym/wall/product-layout context, external_id). */
    fun getAscentsForBackup(): List<AscentBackupRow>

    /** Full-fidelity bid rows for the backup envelope; see [getAscentsForBackup]. */
    fun getBidsForBackup(): List<BidBackupRow>

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
        // See [insertAscent].externalId.
        externalId: String? = null,
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
    fun createClimbList(name: String, generatorParams: String? = null): Long
    fun renameClimbList(id: Long, name: String)
    fun deleteClimbList(id: Long)
    fun addClimbToList(listId: Long, climbUuid: String)
    /**
     * Add a genuinely new list member and, when the list already has an
     * explicit playback plan, append it there with an inferred rest. Existing
     * members are not re-added to the plan: they may have been removed from
     * the playback sequence intentionally.
     */
    fun addClimbToListAndExtendPlayback(listId: Long, climbUuid: String, angle: Long?)
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

    // ── Private climb notes ────────────────────────────────

    /** Returns the user's encrypted, device-local note for this climb. */
    fun getClimbNote(climbUuid: String): String?

    /** Stores a trimmed note, or removes it when [note] is blank. */
    fun saveClimbNote(climbUuid: String, note: String)

    fun getClimbListEntriesRaw(): List<RawClimbListEntry>
    fun getListPlaybackStepsRaw(): List<RawListPlaybackStep>

    /** Full-fidelity list rows for the backup envelope — includes
     *  external_id / description / color, which the UI-facing
     *  [Climb_lists] model doesn't carry (FEAT-005 circuit identity +
     *  the built-in Ignored sentinel). */
    fun getClimbListsForBackup(): List<ClimbListBackupRow>

    /**
     * Restore-side find-or-create for one backup list row. When
     * [externalId] is non-null the row is keyed on it (circuit identity):
     * an existing match gets its metadata refreshed and its id returned;
     * otherwise a new row is inserted with the full metadata — including
     * the ORIGINAL [createdAt], which the plain [createClimbList] path
     * used to reset to now(). Never used for the two built-in lists
     * (those route through [ensureFavoritesListExists] /
     * [ensureIgnoredListExists]).
     */
    fun restoreClimbList(
        name: String, createdAt: String,
        description: String?, color: String?, externalId: String?,
    ): Long

    // ── Optional training plan + playback defaults ──────────────
    // Every list is playable. An explicit plan adds ordering, repeated climbs,
    // pinned angles and rest blocks without changing unique list membership.

    fun updateGeneratorParams(listId: Long, generatorParams: String?)
    fun updatePlaybackSettings(
        listId: Long,
        order: ListPlaybackOrder,
        advance: ListPlaybackAdvance,
        restSeconds: Long,
    )
    /** Appends a climb to the plan and ensures normal list membership. */
    fun addPlaybackClimb(listId: Long, climbUuid: String, angle: Long?): Long
    fun addPlaybackRest(listId: Long, restSeconds: Long): Long
    fun getPlaybackSteps(listId: Long): List<ListPlaybackStepRow>
    fun removePlaybackStep(stepId: Long)
    /** Applies a multi-row removal atomically. */
    fun removePlaybackSteps(stepIds: Collection<Long>)
    fun updatePlaybackRestSeconds(stepId: Long, restSeconds: Long)
    /** Applies one duration to multiple rest rows atomically. */
    fun updatePlaybackRestSeconds(stepIds: Collection<Long>, restSeconds: Long)
    fun movePlaybackStep(listId: Long, fromIndex: Int, toIndex: Int)
    /**
     * Persist an exact drag-preview order while retaining row IDs. Returns
     * false without changing the plan when the supplied IDs are stale.
     */
    fun reorderPlaybackSteps(listId: Long, orderedStepIds: List<Long>): Boolean
    /** Replaces only the ordered plan. Referenced climbs are added to normal
     *  membership, while existing list members not used by the plan remain. */
    fun replacePlaybackSteps(listId: Long, steps: List<NewListPlaybackStep>)

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
    /** Every DISTINCT climb uuid referenced by any climb list — the input
     *  to the cross-DB brand resolution for the per-board logbook
     *  deletion (list entries carry no brand column; their climbs' brands
     *  live in the separate board DB). */
    fun getAllListEntryClimbUuids(): Set<String>
    /** Per-board logbook wipe (Settings → "Delete logbook data"
     *  multiselect): ascents + bids of the [brands] wire values, plus the
     *  list entries in [listEntryClimbUuids] (resolved by the caller
     *  against the board DB — no cross-DB JOIN exists). List rows
     *  themselves and board sessions survive: sessions are brand-less
     *  aggregates only the all-boards path ([deleteAllUserBoardData])
     *  removes. One transaction. */
    fun deleteUserBoardDataForBrands(brands: Set<String>, listEntryClimbUuids: Collection<String>)
    fun runInTransaction(block: () -> Unit)
}
