package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.BoardClimbParser

enum class ClimbSortField { QUALITY, DIFFICULTY, ASCENSIONISTS, REPEATS, NAME, HOLDS, BENCHMARK_DIFFICULTY }
enum class SortDirection { ASC, DESC }

enum class ClimbTypeFilter {
    BOULDER, ROUTE, ALL;
    fun minFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 2L; ALL -> 1L }
    fun maxFrames(): Long = when (this) { BOULDER -> 1L; ROUTE -> 999L; ALL -> 999L }
}

data class ClimbWithStats(
    val uuid: String,
    val layoutId: Long,
    val setterUsername: String?,
    val name: String,
    val frames: String,
    val framesCount: Long,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val description: String = "",
    val isNomatch: Boolean = false,
    val framesPace: Long = 0,
    val hsm: Long = 0,
    val benchmarkDifficulty: Double = 0.0,
    val faUsername: String? = null,
    val faAt: String? = null,
    /** Pre-computed move count from DB. 0 = not yet computed (fallback to live parse). */
    val storedMoveCount: Long = 0,
    /** Provenance: 'cruxcoach' (set via the CruxCoach editor) | 'kilter' (set via
     *  the official Kilter app, pulled by us via the API harvest). Defaults to
     *  'kilter' to keep older code paths neutral. */
    val origin: String = "kilter",
    /** Kilter publish lifecycle: NULL | 'pending' | 'synced' | 'failed'. Only
     *  meaningful for `origin == 'cruxcoach'` rows — for native Kilter climbs
     *  the field is irrelevant (they're inherently on Kilter). */
    val kilterStatus: String? = null,
    /** Setter pubkey for CruxCoach-authored climbs (Nostr hex pubkey). NULL for
     *  native Kilter rows. Used to gate the Edit-this-climb action: only the
     *  original setter sees it. */
    val createdByPubkey: String? = null,
) {
    /** True when this climb is a multi-frame route (not a boulder). */
    val isRoute: Boolean get() = framesCount > 1

    /** Move count: uses pre-computed DB value, falls back to live parse from frames. */
    val moveCount: Int by lazy {
        if (storedMoveCount > 0) storedMoveCount.toInt()
        else if (frames.isNotEmpty()) BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames))
        else 0
    }
}

data class AscentWithClimb(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val bidCount: Long,
    val quality: Long?,
    val difficulty: Long?,
    val comment: String?,
    val climbedAt: String,
    val climbName: String,
    val climbFrames: String,
    val difficultyAverage: Double?,
    val framesCount: Long = 1,
    val isSend: Boolean = true
)

data class HoldPosition(
    val holeId: Long,
    val productSizeId: Long,
    val x: Long,
    val y: Long,
    val ledPosition: Long,
    val placementId: Long
)

data class AngleClimbCount(
    val angle: Long,
    val climbCount: Long
)

data class AngleOption(
    val angle: Int,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val benchmarkDifficulty: Double
)

data class BoardPlacement(
    val placementId: Long,
    val holeId: Long,
    val setId: Long,
    val x: Long,
    val y: Long
)

data class BoardSize(
    val id: Long,
    val productId: Long,
    val name: String,
    val edgeLeft: Long,
    val edgeRight: Long,
    val edgeBottom: Long,
    val edgeTop: Long,
    val imageFilename: String?
)

data class BoardImage(
    val id: Long,
    val productSizeId: Long,
    val layoutId: Long,
    val setId: Long,
    val imageFilename: String
)

data class BoardHole(
    val id: Long,
    val productSizeId: Long,
    val x: Long,
    val y: Long,
    val mirroredHoleId: Long?
)

data class LedGridPoint(
    val placementId: Long,
    val x: Long,
    val y: Long,
    val ledPosition: Long
)


data class Climb_lists(
    val id: Long,
    val name: String,
    val isBuiltin: Boolean,
    val createdAt: String,
    val climbCount: Long
)

data class Climb_list_entries(
    val addedAt: String,
    val climb: ClimbWithStats
)

data class Board_sessions(
    val id: Long,
    val startedAt: String,
    val endedAt: String?,
    val totalDurationSeconds: Long,
    val pauseDurationSeconds: Long,
    val ascentCount: Long,
    val bidCount: Long
)

data class ClimbFrameRow(
    val uuid: String,
    val frames: String
)

// ── Focused sub-interfaces ──────────────────────────────────

/** Climb search, filter, and count queries. All browse/search/count methods require layoutId to scope results to a board type. */
interface BoardClimbQueries {
    fun searchClimbsByName(query: String, angle: Int, layoutId: Int, sortField: ClimbSortField = ClimbSortField.QUALITY, sortDirection: SortDirection = SortDirection.DESC, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<ClimbWithStats>
    fun searchClimbsSorted(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<ClimbWithStats>
    fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats?
    fun countFilteredClimbsFast(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int): Long
    fun countFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): Long
    fun getClimbCount(): Long
    fun getStatCount(): Long
    fun climbExistsByUuid(uuid: String): Boolean
    fun statExistsByUuid(uuid: String): Boolean
    fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<AngleClimbCount>
    fun getAnglesForClimb(climbUuid: String): List<AngleOption>
    fun countNomatchClimbs(): Long
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbWithStats>
    /** Fetch climbs by UUID list at a given angle, no additional filters. */
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats>
    /** Find climb UUIDs whose frames contain the given placement ID. */
    fun searchClimbUuidsByHold(holdPattern: String, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<String>
    /** Find climb UUIDs whose frames contain ALL given hold patterns (single DB pass). */
    fun searchClimbUuidsByAllHolds(holdPatterns: List<String>, angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Set<String>
    /** Get all frames for heatmap computation within current browse filters. */
    fun getAllFramesForHeatmap(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow>
}

/** Board layout, placement, LED, and product-size queries. */
interface BoardLayoutQueries {
    fun getAllPlacements(): List<BoardPlacement>
    fun getProductSize(id: Int): BoardSize?
    fun getAllProductSizes(): List<BoardSize>
    fun getBoardImages(productSizeId: Int, layoutId: Int): List<BoardImage>
    fun getPlacementLedMap(productSizeId: Int): Map<Int, Int>
    fun getMirrorPlacementMap(productSizeId: Int): Map<Int, Int>
    fun countLeds(): Long
    fun getLedGrid(productSizeId: Int): List<LedGridPoint>
}

/** Write operations: upserts, sync state, transactions (public board data only). */
interface BoardWriteOperations {
    fun upsertClimb(uuid: String, layoutId: Long, setter: String?, name: String, frames: String,
                    framesCount: Long, isListed: Long, edgeLeft: Long?, edgeRight: Long?,
                    edgeBottom: Long?, edgeTop: Long?, createdAt: String?,
                    description: String = "", isNomatch: Long = 0, framesPace: Long = 0, hsm: Long = 0,
                    moveCount: Long = 0)
    fun upsertClimbStat(climbUuid: String, angle: Long, displayDifficulty: Double?,
                        difficultyAverage: Double?, qualityAverage: Double?,
                        ascensionistCount: Long?, benchmarkDifficulty: Double?,
                        faUsername: String? = null, faAt: String? = null,
                        officialKilterDifficulty: Long? = null)
    fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long,
                           ledPosition: Long, placementId: Long)
    fun upsertLed(holeId: Long, productSizeId: Long, position: Long)
    fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?)
    fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long)
    fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long,
                          edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?)
    fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String)
    fun upsertSyncState(tableName: String, lastSynchronizedAt: String)
    fun getSyncState(tableName: String): String?
    fun getAllClimbUuids(): Set<String>
    fun getAllStatKeys(): Map<Pair<String, Long>, Long?>
    fun runInTransaction(block: () -> Unit)
    fun deleteAllBoardData()
}

data class RawAscent(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val attemptId: Long,
    val bidCount: Long,
    val quality: Long?,
    val difficulty: Long?,
    val isBenchmark: Boolean,
    val comment: String?,
    val climbedAt: String,
    val synced: Boolean,
    val gymUuid: String? = null,
    val wallUuid: String? = null,
    val productLayoutUuid: String? = null,
    /** Optimistic-locking token snapshot at read time. Pass to
     *  [PersonalBoardRepository.markAscentSyncedIfUnchanged]. */
    val rowVersion: Long = 0L
)

data class RawBid(
    val uuid: String,
    val userId: Long = 0L,
    val climbUuid: String,
    val angle: Long,
    val isMirror: Boolean,
    val bidCount: Long,
    val comment: String?,
    val climbedAt: String,
    val synced: Boolean,
    val gymUuid: String? = null,
    val wallUuid: String? = null,
    val productLayoutUuid: String? = null,
    /** Optimistic-locking token snapshot at read time. Pass to
     *  [PersonalBoardRepository.markBidSyncedIfUnchanged]. */
    val rowVersion: Long = 0L
)

data class RawClimbListEntry(
    val listId: Long,
    val climbUuid: String,
    val addedAt: String
)

// ── Community-climb support (FEAT-003) ─────────────────────

data class LocalClimbDraft(
    val uuid: String,
    val name: String,
    val description: String,
    val framesText: String,
    val framesHash: String,
    val createdAt: String,
    val createdByPubkey: String?,
    val moveCount: Long,
    /** Setter display string seeded from the local user's Kind 0 profile.
     *  Null when the user has no profile yet — Browse falls back to
     *  `npub:<short>` via the same path it uses for foreign climbs. */
    val setterUsername: String? = null,
)

/** Aggregate row for the SettersListScreen — one per unique pubkey. */
data class SetterStat(
    val pubkey: String,
    val displayName: String?,    // setter_username column — already resolved by Plan C
    val climbCount: Long,
)

/** A single climb in the SetterDetailScreen's list — only the fields the
 *  list row needs. Keeps it light + lets us include `angle` (which the
 *  full ClimbWithStats doesn't carry, since the regular browse filters
 *  by a global angle). */
data class SetterClimbEntry(
    val uuid: String,
    val name: String,
    val angle: Int,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long,
)

/** Snapshot of a climb's Kilter publish-state — drives the create-vs-update
 *  decision on edit publishes. */
data class KilterPublishState(
    val status: String?,        // NULL | 'pending' | 'synced' | 'failed' | 'diverged'
    val syncedAtEpochSeconds: Long?,
)

/** Outcome of [BoardRepository.claimKilterPublishSlot]. */
sealed class KilterClaim {
    /** Slot was empty (`kilter_status` was NULL or 'failed') and is now
     *  marked `'pending'` for the calling flow. `previouslySyncedAtEpochSeconds`
     *  carries the row's `kilter_synced_at` from BEFORE the claim — non-null
     *  means "we previously synced this climb on Kilter; use UPDATE-climb",
     *  null means "first publish; use CREATE-climb". */
    data class Won(val previouslySyncedAtEpochSeconds: Long?) : KilterClaim()
    /** Another flow already holds the slot, or the row is in a terminal
     *  state ('synced' / 'rejected' / 'diverged'). Caller should skip
     *  this row — the current owner will mark a result or the row is
     *  already finished. */
    data object Lost : KilterClaim()
}

data class CommunityClimbRow(
    val uuid: String,
    val name: String,
    val setterUsername: String?,
    val description: String,
    val framesText: String,
    val source: String,            // 'kilter' | 'nostr' | 'local'
    val syncStatus: String,         // 'draft' | 'synced' | 'published_nostr' | 'failed'
    val createdByPubkey: String?,
    val nostrEventId: String?,
    val nostrDTag: String?,
    val framesHash: String?,
    val createdAt: String?,
    val moveCount: Long,
    /** Set when Kilter accepted the climb at least once. Used by the
     *  retry worker to pick between create and update endpoints. */
    val kilterSyncedAt: Long?,
)

/** Climb-creation + community-climb queries (FEAT-003). */
interface CommunityClimbQueries {
    /** Insert or upsert a local climb draft (source='local'). Re-saving an
     *  already-loaded draft replaces the row in place (same uuid).
     *  `bounds` is the bounding box of the selected holds; pass null when
     *  it can't be derived (placements not loaded yet). When null the
     *  edge_* columns stay NULL — browse compatibility filters treat
     *  "fits all sizes" in that case. */
    fun insertLocalDraft(
        draft: LocalClimbDraft,
        layoutId: Long,
        angle: Long,
        setterGradeId: Int?,
        bounds: com.cruxcoach.domain.community.ClimbBounds?,
    )
    /** Delete a local draft (drafts user explicitly discards). */
    fun deleteLocalClimb(uuid: String)
    /**
     * Returns the local row's stored `created_at` ISO string (or null if
     * the climb isn't in the DB yet). Used by the Channel-B subscriber
     * to skip re-broadcasted old events that would overwrite a newer
     * state — replaceable Kind-30078 events on relays don't enforce
     * ordering on receive, so the client has to.
     */
    fun getClimbCreatedAt(uuid: String): String?
    /**
     * Returns the local row's stored `created_by_pubkey` (or null if no
     * row, or if the row has no Nostr provenance). Used by the Channel-B
     * subscriber to enforce one-author-per-uuid: an incoming event whose
     * signed pubkey differs from the existing owner is rejected before
     * upsert, blocking the cross-author overwrite path that the
     * uuid-only primary key on `climbs` would otherwise allow.
     */
    fun getClimbAuthorPubkey(uuid: String): String?
    /**
     * Returns (placement_id → normalized 0..1 frequency) for boulders at the
     * given layout+angle, optionally weighted by climbs that contain ALL
     * `seedHolds`. Used by the editor heatmap overlay.
     *
     * - When `seedHolds` is empty → general popularity heatmap.
     * - When `seedHolds` has entries → only counts climbs that include
     *   every seed hold; surfaces "what holds typically follow these".
     * - When `targetRole` is non-null → only placements with that role
     *   in the source climb are counted (role-aware suggestions for the
     *   user's currently active brush). When null → all roles aggregated.
     */
    fun computeEditorHeatmap(
        layoutId: Long,
        angle: Long,
        seedHolds: Set<Int>,
        targetRole: Int? = null,
    ): Map<Int, Float>
    /** Upsert a community climb received from Nostr. `bounds` comes from
     *  the optional `bounds` Nostr-tag — null when the event predates Plan
     *  2 or the publisher couldn't derive coordinates. */
    fun upsertCommunityClimb(
        uuid: String,
        layoutId: Long,
        setterUsername: String?,
        name: String,
        framesText: String,
        description: String,
        moveCount: Long,
        nostrEventId: String,
        nostrDTag: String,
        createdByPubkey: String,
        framesHash: String,
        createdAt: String,
        angle: Long,
        difficultyAverage: Double?,
        qualityAverage: Double?,
        bounds: com.cruxcoach.domain.community.ClimbBounds?,
    )
    /**
     * Flip a draft to "published_nostr" after the relay accepts the event.
     * `pubkey` is the user's own hex pubkey — persisted so "my climbs"
     * filters always have a stable creator handle.
     */
    fun markClimbPublishedNostr(
        uuid: String,
        nostrEventId: String,
        nostrDTag: String,
        pubkey: String,
    )
    fun markClimbPublishFailed(uuid: String)

    // ── Kilter-side publish lifecycle (independent of Nostr sync_status) ──
    /** Mark a climb as enqueued for Kilter publish. Sets `kilter_status='pending'`. */
    /** Read just the Kilter publish-state. Null when the climb isn't in
     *  the local DB. */
    fun getKilterPublishState(uuid: String): KilterPublishState?
    /** Bulk-rename setter_username on every CruxCoach community climb
     *  authored by [pubkey]. Used by both: profile-editor save (own
     *  pubkey) and the live subscriber (foreign pubkeys, after Kind 0
     *  resolves). The query semantics are identical for either case. */
    fun updateSetterUsernameForPubkey(pubkey: String, displayName: String)
    /** All climbs authored by [pubkey] (CruxCoach setter detail). Each
     *  entry carries its angle — same climb at multiple angles becomes
     *  multiple entries, matching the climb_stats row layout. */
    fun getClimbsByPubkey(pubkey: String): List<SetterClimbEntry>
    /** Distinct cruxcoach setters with their climb-count, ordered desc. */
    fun getCommunitySetterStats(): List<SetterStat>
    fun markKilterPublishPending(uuid: String)
    /**
     * Mark a climb as accepted by Kilter. `via` is 'self' (user account) or
     * 'cruxcoach' (bundled fallback). `syncedAtEpochSeconds` is the moment
     * Kilter accepted; useful for the "veröffentlicht am" UI badge.
     */
    fun markKilterPublishSynced(uuid: String, via: String, syncedAtEpochSeconds: Long)
    /** Mark a climb's Kilter publish as failed; `error` captures the last reason. */
    fun markKilterPublishFailed(uuid: String, error: String)
    /** Server explicitly rejected an update on an already-published climb.
     *  Distinct from `'failed'` — the retry worker stops poking it. */
    fun markKilterPublishDiverged(uuid: String, error: String)
    /** Server rejected the *create* payload with a 4xx (validation,
     *  content-policy, account-state). Terminal — the retry worker stops
     *  poking it. Without this, 4xx CREATEs would stay in the retry queue
     *  forever because the queue criterion matches `kilter_status='failed'`. */
    fun markKilterPublishRejected(uuid: String, error: String)

    /**
     * Atomic CAS claim of the Kilter publish slot. Transitions
     * `kilter_status` from `NULL`/`'failed'` to `'pending'` for an
     * origin='cruxcoach' row.
     *
     * Returns `KilterClaim.Won` (with the row's pre-claim
     * `kilter_synced_at` so the caller can pick CREATE vs UPDATE) when
     * the claim succeeded, `KilterClaim.Lost` when another flow already
     * holds the slot or the row isn't claimable.
     *
     * Replaces the read-then-decide pattern in CommunityClimbPublisher
     * + KilterPublishRetryWorker that could let two flows both attempt
     * to publish the same climb (TOCTOU between the
     * `getKilterPublishState` read and the API call).
     */
    fun claimKilterPublishSlot(uuid: String): KilterClaim
    /** Climbs with `origin='cruxcoach'`, Nostr-published, awaiting Kilter sync. */
    fun getClimbsAwaitingKilterRetry(): List<CommunityClimbRow>
    /**
     * Drafts (`source='local'`, sync_status `draft`/`failed`) authored
     * by [pubkey] or with a NULL `created_by_pubkey` (legacy / pre-key-init).
     * Pass null to fetch only legacy NULL-pubkey drafts. Restricts visibility
     * across identity switches: drafts authored under identity A are
     * invisible to identity B opened in the editor on the same device.
     */
    fun getDraftClimbs(pubkey: String?): List<CommunityClimbRow>
    fun getMyClimbs(pubkey: String): List<CommunityClimbRow>
    fun getCommunityClimbs(): List<CommunityClimbRow>
    /** Single-row stats lookup used by the editor when restoring a draft.
     *  Returns null when the climb has no stats row at all. The pair is
     *  (angle, setterGradeId) — for local drafts these match what
     *  upsertLocalClimbStat persisted; both null means the row predates
     *  having stats and the editor falls back to its default angle. */
    fun getClimbStatsForUuid(uuid: String): Pair<Int, Int?>?
    /** Look up an existing climb by frames_hash for duplicate detection. */
    fun findClimbByFramesHash(framesHash: String, layoutId: Long): CommunityClimbRow?
    /** Cache the setter-grade entry for a community climb (MVP — no vote aggregation). */
    fun upsertSetterGrade(climbDTag: String, angle: Long, setterGradeId: Int, lastUpdatedEpochMs: Long)
}

// ── Composite interface (backward-compatible) ───────────────

interface BoardRepository :
    BoardClimbQueries,
    BoardLayoutQueries,
    BoardWriteOperations,
    CommunityClimbQueries
