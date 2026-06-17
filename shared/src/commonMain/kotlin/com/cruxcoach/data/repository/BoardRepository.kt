package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser

/**
 * Typed board family for rows that carry the persisted `board_brand` String.
 * These extensions are the single bridge from the storage representation
 * (String, at the SQLite/DTO boundary) to the typed [BoardBrand] capability
 * model, so call sites use `row.brand.usesAuroraPlacements` etc. instead of
 * re-deriving board behaviour from raw string comparisons.
 */
val ClimbWithStats.brand: BoardBrand get() = BoardBrand.fromWire(boardBrand)
val AscentWithClimb.brand: BoardBrand get() = BoardBrand.fromWire(boardBrand)
val RawBid.brand: BoardBrand get() = BoardBrand.fromWire(boardBrand)

enum class ClimbSortField {
    QUALITY, DIFFICULTY, ASCENSIONISTS, NAME, HOLDS, BENCHMARK_DIFFICULTY,
    /** Quality multiplied by sends: surfaces climbs that are both popular AND well-rated. */
    QUALITY_SENDS,
    /** SQLite RANDOM() over the filtered set. Direction is ignored. Pagination
     *  is independently random per page — scrolling yields more random results
     *  rather than a stable shuffled list. Adequate for browsing-for-discovery. */
    RANDOM,
}
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
    /** Schema column `climbs.source`: 'kilter' | 'nostr' | 'local'. Drives the
     *  draft badge in browse rows ('local' = local-only draft, not yet
     *  published). Default 'kilter' keeps older code paths neutral. */
    val source: String = "kilter",
    /** Publish-lifecycle column `climbs.sync_status`: NULL | 'draft' | 'synced'
     *  | 'published_nostr' | 'failed'. Combined with [source] to disambiguate
     *  failed-publish rows from synced ones. */
    val syncStatus: String? = null,
    /** The Nostr event ID of the climb's most recent successful publish.
     *  Non-null iff at least one publish reached at least one relay
     *  (set by [markClimbPublishedNostr] / received via live-sub on
     *  upsertCommunityClimb). Drives the detail-screen's provenance
     *  badge + delete-action routing: "does this climb have a live
     *  publication?" is the deterministic signal, where sync_status
     *  alone can drift ('failed' after a successful prior publish,
     *  etc.). */
    val nostrEventId: String? = null,
    /** Board brand: 'kilter' | 'moonboard' (FEAT-027). Projected by the
     *  `climb_browse` VIEW from `climbs.board_brand`; default 'kilter'
     *  keeps pre-0.2.0 / Kilter-only code paths neutral. */
    val boardBrand: String = "kilter",
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
    val isSend: Boolean = true,
    // Kilter-sync flag, carried for the backup round-trip so a restore
    // doesn't re-arm /logs/bulk uploads for already-synced rows. Defaults
    // suit the logbook projection queries that don't select it;
    // getUserAscentsAll (SELECT *) populates the real value.
    val synced: Boolean = false,
    // Board family + layout this ascent was logged on (denormalized onto
    // ascents/bids in 7.sqm). Defaults suit the light/projection queries that
    // don't select them; getUserAscentsAll (SELECT *) populates the real
    // values so the personal heatmap can scope to a single board's grid.
    val boardBrand: String = "kilter",
    val layoutId: Long? = null,
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

/**
 * One row from the community-climb dead-letter queue (M16). [rawEventJson]
 * is the original signed Kind-30078 payload — re-validating its Schnorr
 * signature on retry is cheap and keeps the retry path safe against a
 * corrupted DB row.
 */
data class CommunityClimbDeadLetter(
    val uuid: String,
    val eventId: String,
    val eventCreatedAt: Long,
    val rawEventJson: String,
    val firstFailedAtMs: Long,
    val lastFailedAtMs: Long,
    val retryCount: Long,
    val lastErrorExcerpt: String?,
)

/** Two-counter snapshot for the dead-letter queue diagnostics UI. */
data class DeadLetterCounts(
    val total: Long,
    val abandoned: Long,
)

data class AngleOption(
    val angle: Int,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long?,
    val benchmarkDifficulty: Double,
    /** True for the angle the setter created this (community) climb at —
     *  surfaced as info in the picker while the climb stays climbable at
     *  every angle the board supports. Always false for angle-agnostic
     *  imported climbs (no single setter angle). */
    val isSetterAngle: Boolean = false
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
    val imageFilename: String?,
    /** Owning board family. Drives the brand-namespaced background asset
     *  path in the renderer; defaults to [BoardBrand.KILTER] for the
     *  historical single-board callers (and the pre-0.2.0 data model). */
    val boardBrand: BoardBrand = BoardBrand.KILTER,
)

/**
 * Collapse same-dimension duplicate product sizes to the most-complete one.
 *
 * Some boards list the same physical size more than once with different hold-set
 * builds — e.g. Grasshopper has a 3-set and a 5-set "Master" (8×12), both listed.
 * For board selection we want ONE entry per physical size, showing the most
 * complete board image, so we group by (product + edge bbox) and keep the size
 * with the highest set count. A no-op for boards whose sizes all differ in
 * dimensions (Tension, Decoy, So iLL). Result is sorted by id for stable order.
 *
 * Pure (no DB) so the de-dup rule is unit-tested directly.
 */
fun dedupeProductSizesByDimension(sizesWithSetCount: List<Pair<BoardSize, Int>>): List<BoardSize> =
    sizesWithSetCount
        .groupBy { (size, _) ->
            listOf(size.productId, size.edgeLeft, size.edgeRight, size.edgeBottom, size.edgeTop)
        }
        .mapNotNull { (_, group) -> group.maxByOrNull { it.second }?.first }
        .sortedBy { it.id }

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
    val climbCount: Long,
    /** True only for the built-in "Ignored" list — lets the lists UI pick a
     *  distinct icon and the add-to-list dialog filter it out. */
    val isIgnored: Boolean = false,
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
    // `hsmExcludedMask` (browse/search/count below): hold-set leg of the
    // always-on "fits my board" filter — bits of the layout's hold sets NOT
    // mounted on the user's selected size (HoldSetMask.excludedMask). A climb
    // passes iff (hsm & mask) = 0; hsm=0 (unknown) always passes. 0 = off.
    //
    // `showUngraded` (every method that carries the minDifficulty/maxDifficulty
    // grade predicate): zero-ascent catalogue stubs ship with
    // difficulty_average NULL. true = NULL-grade rows pass the grade
    // predicate; false = they are filtered out. The browse VM passes false in
    // normal mode (ungraded climbs never show) and true only in the dedicated
    // "ungraded only" mode — paired there with an impossible range
    // (minDifficulty > maxDifficulty) so EXACTLY the NULL-grade rows match —
    // and unconditionally on the BoardSesh provenance pull (inherently
    // ungraded imports; the origin chip is the opt-in).
    fun searchClimbsByName(query: String, angle: Int, layoutId: Int, boardBrand: String, sortField: ClimbSortField = ClimbSortField.QUALITY, sortDirection: SortDirection = SortDirection.DESC, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0): List<ClimbWithStats>
    fun searchClimbsSorted(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int = 50, offset: Int = 0, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0, showUngraded: Boolean = false): List<ClimbWithStats>
    fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats?
    /** Defensive fallback for [getClimbByUuid]: resolves a stored row whose
     *  uuid matches [uuid] only after normalization (strip hyphens +
     *  lowercase on both sides). The board DB mixes uuid formats (legacy
     *  nodash-UPPERCASE vs new-world dashed-lowercase), so a logbook-imported
     *  uuid can fail the exact/case lookups yet still have a stored row under
     *  a different format. This is a full scan — callers MUST try the indexed
     *  [getClimbByUuid] first and only fall back here on a miss.
     *
     *  Default returns null (no normalized match); [BoardRepositoryImpl]
     *  overrides with the SQL scan. The default keeps in-memory test fakes
     *  that only model the exact-uuid path compiling unchanged. */
    fun getClimbByUuidNormalized(uuid: String, angle: Int): ClimbWithStats? = null
    fun countFilteredClimbsFast(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0, showUngraded: Boolean = false): Long
    fun countFilteredClimbs(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0, showUngraded: Boolean = false): Long
    fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0, showUngraded: Boolean = false): Long
    fun countSearchClimbs(query: String, angle: Int, layoutId: Int, boardBrand: String, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0): Long
    fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, boardBrand: String, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0): Long
    fun getClimbCount(): Long
    /** Per-brand catalogue sizes (FEAT-031), keyed by `board_brand` wire value.
     *  Brands with no imported climbs are absent from the map. */
    fun getClimbCountsByBrand(): Map<String, Long>
    /** O(1) existence check. Far cheaper than [getClimbCount] — that one
     *  full-table-scans on a 190k-row catalog, and worse, blocks on the
     *  bulk importer's writer-lock during sync (~28s on slower-eMMC).
     *  Use this anywhere the caller only needs a boolean (empty-state
     *  decision in BoardBrowser, fresh-install probe). */
    fun hasAnyClimbs(): Boolean
    /** Brand-scoped [hasAnyClimbs]: whether the given board's catalogue has
     *  any imported climbs. Same O(1) EXISTS probe, scoped by board_brand. */
    fun hasClimbsForBrand(boardBrand: String): Boolean
    fun getStatCount(): Long
    /** Stats with no matching climbs row (cron desync indicator). */
    fun countOrphanStats(): Long
    /** Listed climbs the browse VIEW excludes because no stats exist. */
    fun countListedClimbsWithoutStats(): Long
    /** True if 7.sqm flagged the install for a forced clean re-sync. */
    fun hasPostV8ResyncMarker(): Boolean
    /** Cleared after the post-v8 resync completes successfully. */
    fun clearPostV8ResyncMarker()
    /** True if 10.sqm flagged the install for a one-shot Homewall
     *  resync (0.1.3 → 0.1.4 OTA upgrade — the old per-chunk hash
     *  cache would otherwise short-circuit Homewall data sync). */
    fun hasHomewallResyncMarker(): Boolean
    /** Cleared after the Homewall resync completes successfully. */
    fun clearHomewallResyncMarker()
    /** Wipe just the cron-derived catalog rows (source='kilter') so
     *  the next sync runs through the fresh-install fast path. Used
     *  by the post-migration force-resync flow; cruxcoach-authored
     *  climbs are preserved. */
    fun deleteKilterCatalogData()
    fun climbExistsByUuid(uuid: String): Boolean
    /** Format-blind existence/identity resolution: returns the CANONICAL
     *  stored uuid of the climb matching [uuid] across the DB's mixed uuid
     *  spellings (legacy nodash-UPPERCASE curated rows vs new-world
     *  dashed-lowercase API uuids), or null when the climb truly is missing.
     *  Use instead of [climbExistsByUuid] wherever an exact-match miss would
     *  insert a logical duplicate of an already-present climb (the Kilter
     *  own-climb backfills), and use the returned uuid to address the
     *  existing row (e.g. [setClimbKilterAuthorUuid]).
     *
     *  Default returns null (no match); [BoardRepositoryImpl] overrides with
     *  indexed exact-spelling probes plus a normalized-scan fallback. The
     *  default keeps in-memory test fakes that only model the exact-uuid
     *  path compiling unchanged. */
    fun findClimbCanonicalUuid(uuid: String): String? = null
    fun statExistsByUuid(uuid: String): Boolean
    fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER): List<AngleClimbCount>
    fun getAnglesForClimb(climbUuid: String): List<AngleOption>
    /** Distinct angles the given board layout is used at — the data-driven
     *  angle range for the variable-angle picker on adjustable boards.
     *  Brand-scoped: layout ids collide across brands. */
    fun getSupportedAnglesForLayout(layoutId: Int, boardBrand: String): List<Int>
    fun countNomatchClimbs(): Long
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbWithStats>
    /** Fetch climbs by UUID list at a given angle, no additional filters. */
    fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats>
    /** Fetch climbs by UUID list regardless of angle — one representative row
     *  per climb. Fallback for list display so MoonBoard problems set only at
     *  a non-default angle (e.g. Masters 25°) aren't dropped. */
    fun getClimbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats>
    /** Active-board-scoped uuid resolution at [angle] (user lists). Returns only
     *  climbs on the active board ([boardBrand] + [layoutId]); when
     *  [boardBrand] == "kilter" ALSO includes Kilter climbs from other layouts
     *  that physically FIT [selProductSizeId] (edge-containment, NULL edges =
     *  fits all). Non-Kilter boards get no cross-size exception. */
    fun getClimbsByUuidsForBoard(uuids: Collection<String>, angle: Int, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<ClimbWithStats>
    /** Angle-agnostic board-scoped fallback (one row per uuid). Same scoping
     *  rules as [getClimbsByUuidsForBoard] — stays board-scoped so the
     *  GROUP BY collapse never re-leaks other-board climbs. */
    fun getClimbsByUuidsForBoardAnyAngle(uuids: Collection<String>, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<ClimbWithStats>
    /** UUID-only projection of the entire browse-filter match set. Backs the
     *  VM's UUID-shuffle cache for RANDOM sort — load once per filter
     *  signature, shuffle in Kotlin, paginate over the cached list. */
    fun getAllBrowseMatchingUuids(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int = 0, hsmExcludedMask: Long = 0, showUngraded: Boolean = false): List<String>
    /** Find climb UUIDs whose frames contain the given placement ID. Brand-scoped
     *  so a board only matches its own climbs (layout_id collides across boards). */
    fun searchClimbUuidsByHold(holdPattern: String, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<String>
    /** Find climb UUIDs whose frames contain ALL given hold patterns (single DB pass). */
    fun searchClimbUuidsByAllHolds(holdPatterns: List<String>, angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Set<String>
    /** Get all frames for heatmap computation within current browse filters (brand-scoped). */
    fun getAllFramesForHeatmap(angle: Int, layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow>
    /** Angle-agnostic frames for the logbook stats heatmap: one row per climb
     *  with stats at ANY angle of (layout, brand). Hold usage doesn't depend
     *  on the angle, so the stats sheet must not pin one. */
    fun getAllFramesForHeatmapAllAngles(layoutId: Int, boardBrand: String, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): List<ClimbFrameRow>
}

/** Board layout, placement, LED, and product-size queries. */
interface BoardLayoutQueries {
    fun getAllPlacements(boardBrand: String = "kilter"): List<BoardPlacement>
    /** Placements restricted to the set_ids that the active layout actually
     *  paints onto the board photo (one row per layered board_image). The
     *  unfiltered [getAllPlacements] mixes in placements from every set
     *  the cron ever shipped — including ones whose holds aren't part of
     *  the current Original/Homewall layout — so the editor's nearest-
     *  hold tap detection would snap to invisible Off-Set placements
     *  between the rendered holds, leaving a circle on what looks to the
     *  user like empty board space. Falls back to all placements when no
     *  board_images row exists for the (productSize, layout) combination
     *  yet (very early sync state, mostly tests). */
    fun getPlacementsForLayout(productSizeId: Int, layoutId: Int, boardBrand: String = "kilter"): List<BoardPlacement>
    fun getProductSize(id: Int, boardBrand: String = "kilter"): BoardSize?
    /** Product-sizes for a single Aurora `product_id`. The post-sync model
     *  picker and the Settings board-size dropdown both filter by the
     *  user's currently-active layout — Original (Kilter id=1) and
     *  Homewall (Kilter id=7) ship with disjoint size sets. Pre-fix this
     *  hardcoded id=1, silently dropping Homewall sizes from every UI
     *  reachable through this method. */
    fun getAllProductSizes(productId: Long, boardBrand: String = "kilter"): List<BoardSize>
    /** Picker-ready product sizes for a brand: every size the user might own,
     *  with same-dimension duplicates collapsed to the most-complete one (the
     *  size whose board image composites the most hold-sets). Sorted by id.
     *  Drives the size tier for every interactive board (variant or single-
     *  layout), so e.g. a Grasshopper Ninja owner can pick Ninja instead of
     *  being pinned to the largest size. */
    fun getSelectableProductSizesForBrand(boardBrand: String): List<BoardSize>
    fun getBoardImages(productSizeId: Int, layoutId: Int, boardBrand: String = "kilter"): List<BoardImage>
    /** A layout's full hold-set universe (ascending set ids — the rank order
     *  that defines the climbs.hsm bit indices). Empty when the brand ships
     *  no board_images set data (MoonBoard). */
    fun getHoldSetIdsForLayout(layoutId: Int, boardBrand: String): List<Long>
    /** Hold sets actually mounted on one product size of the layout (e.g.
     *  Kilter Homewall 8x12 Mainline = {26,28,29} of {26,27,28,29}). Diffed
     *  against [getHoldSetIdsForLayout] via HoldSetMask.excludedMask to get
     *  the hsm exclusion mask the browse queries filter on. */
    fun getHoldSetIdsForLayoutSize(layoutId: Int, productSizeId: Int, boardBrand: String): List<Long>
    /** Sizes that have board-image tiles for the given layout. Used by
     *  the climb-detail screen to render an Aurora-imported or cross-
     *  board community climb on the right physical board even when the
     *  user's preferred layout differs from the climb's. */
    fun getProductSizesForLayout(layoutId: Int, boardBrand: String = "kilter"): List<Int>
    /** FEAT-031: a sensible default active-board config for an Aurora board,
     *  derived from its just-synced catalogue — the most-climbed layout and
     *  the largest product_size. Lets the picker configure the active board
     *  in one step (Aurora sizes are only known post-sync). Null when the
     *  board has no catalogue rows yet. */
    fun getDefaultLayoutForBrand(boardBrand: String): Int?
    fun getDefaultProductSizeForBrand(boardBrand: String): Pair<Int, String>?
    /** True when the user's currently-configured [productSizeId] can
     *  validly host this climb's render — same layout, has images,
     *  big enough to contain the climb's bbox. The detail screen
     *  uses this to default to the user's settings board (matching
     *  the BoardBrowser → Detail mental model) and only fall back to
     *  the climb's source size when that's not possible (Aurora-
     *  imported cross-board climb, smaller user-board than the
     *  climb's bbox, etc.). */
    fun canRenderClimbOnSize(uuid: String, productSizeId: Int, boardBrand: String = "kilter"): Boolean
    /** Full set of CruxCoach-side climbs (community + local-drafts)
     *  that satisfy the user's browse filters. Returns the entire
     *  matching set in one call — the client-side OriginFilter that
     *  used to post-filter pagination silently buried community
     *  climbs without sends at the bottom of the 190K-row Kilter
     *  catalogue. Cruxcoach-side row count stays tiny, so a single
     *  un-paginated SELECT is the right shape. Caller sorts in
     *  Kotlin via the existing sortInKotlin path. */
    fun getCruxCoachClimbs(
        layoutId: Int, boardBrand: String, angle: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int = 0,
        hsmExcludedMask: Long = 0, showUngraded: Boolean = false,
    ): List<ClimbWithStats>
    /** Full set of BoardSesh-imported climbs (origin='boardsesh') that
     *  satisfy the browse filters. Returns the whole matching set in one
     *  call for the same reason as [getCruxCoachClimbs]: BoardSesh climbs
     *  have no sends/quality and would otherwise be buried at the bottom
     *  of the paginated catalogue and never shown. Caller sorts in Kotlin. */
    fun getBoardSeshClimbs(
        layoutId: Int, boardBrand: String, angle: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int = 0,
        hsmExcludedMask: Long = 0, showUngraded: Boolean = false,
    ): List<ClimbWithStats>
    /** Find the smallest product_size whose four edges *contain* the
     *  climb's bounding box AND has board_images for the climb's
     *  layout. This pins each climb to the physical board variant
     *  it was set on (= the user's actual board at climb-set time),
     *  so cropped sub-routes stay rendered on the right Kilter SKU
     *  (e.g. a Homewall-10×12-with-kickboard climb always renders
     *  on size 25 even when the user has Homewall-7×10 configured).
     *  Two climbs from the same physical board therefore always
     *  render at the same size — consistent per source-size, not
     *  per-current-pref. Returns null when the climb has no edges
     *  or no containing size exists; caller falls back to layout-
     *  only heuristics. */
    fun getProductSizeForClimbRender(uuid: String, boardBrand: String = "kilter"): Int?
    fun getPlacementLedMap(productSizeId: Int, boardBrand: String = "kilter"): Map<Int, Int>
    /** FEAT-031: per-board role-id → board colour byte, derived from the
     *  synced placement_roles.led_color. Empty when the board's catalogue
     *  hasn't shipped placement_roles yet — callers then fall back to the
     *  conventional [LedHoldColors] defaults. */
    fun getRoleColorMapForBrand(boardBrand: String): Map<Int, Int>
    fun getMirrorPlacementMap(productSizeId: Int, boardBrand: String = "kilter"): Map<Int, Int>
    fun countLeds(): Long
    fun getLedGrid(productSizeId: Int, boardBrand: String = "kilter"): List<LedGridPoint>
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
    /** Record the climb author's Kilter userUuid on an existing row (fill-only:
     *  a non-NULL value is never overwritten). Backs the own-Kilter-climb
     *  publish gate, which compares it against the connected account's
     *  userUuid — never a display-name match. Default no-op so existing test
     *  fakes that never exercise the Kilter backfill keep compiling. */
    fun setClimbKilterAuthorUuid(uuid: String, authorUuid: String) {}
    fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long,
                           ledPosition: Long, placementId: Long, boardBrand: String = "kilter")
    fun upsertLed(holeId: Long, productSizeId: Long, position: Long, boardBrand: String = "kilter")
    fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?, boardBrand: String = "kilter")
    fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long, boardBrand: String = "kilter")
    fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long,
                          edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?, boardBrand: String = "kilter")
    fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String, boardBrand: String = "kilter")
    fun upsertPlacementRole(boardBrand: String, id: Long, name: String?, ledColor: String?, screenColor: String?)
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
    val rowVersion: Long = 0L,
    /** Board family + layout (7.sqm) — carried for backup round-trip. */
    val boardBrand: String = "kilter",
    val layoutId: Long? = null,
)

data class RawClimbListEntry(
    val listId: Long,
    val climbUuid: String,
    val addedAt: String
)

/**
 * One row of the local "Verlauf" (history) log — a SENT climb the user
 * recorded. Denormalized at record time (climb metadata + board family) so
 * the history screen renders without a cross-DB join. Local-only: never
 * synced to Kilter, never backed up/exported.
 */
data class ClimbHistoryEntry(
    val id: Long,
    val climbUuid: String,
    val climbName: String,
    val angle: Int,
    val difficultyAverage: Double?,
    val boardBrand: String,
    val layoutId: Long?,
    val climbedAt: String,
    val recordedAt: String,
)

// ── Community-climb support (FEAT-003) ─────────────────────

/** Snapshot of the fields needed to publish a NIP-09 deletion + a
 *  tombstone-replacement Kind-30078, plus the Kilter status for UI
 *  warnings (no API delete on Kilter — manual cleanup required). */
data class CommunityClimbDeleteContext(
    val nostrEventId: String?,
    val nostrDTag: String?,
    val createdByPubkey: String?,
    val kilterStatus: String?,
    val origin: String,
    /** Board family (`climbs.board_brand`) of the climb being deleted.
     *  Drives the deletion event's back-compat `L` namespace: 'kilter'
     *  → NS_CLIMB (legacy), any non-Kilter board → NS_CLIMB_V2, so that
     *  ≥0.2.0 subscribers (which subscribe on both namespaces) catch
     *  new-board deletions while old apps never see them. Defaults to
     *  'kilter' for pre-0.2.0 rows with no brand. */
    val boardBrand: String = "kilter",
)

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

/** Op recorded with each `kilter_publish_attempts` row. Mirrors the
 *  CREATE/UPDATE branch the publisher / retry worker actually took. */
enum class KilterPublishOp { CREATE, UPDATE }

/** Terminal outcome of a single Kilter-publish attempt. Stored as a
 *  string in `kilter_publish_attempts.outcome`; the enum keeps the
 *  caller side type-safe. */
enum class KilterPublishOutcomeKind {
    SUCCESS,
    TRANSIENT,
    PERMANENT,
    RATE_LIMITED,
    AUTH,
    SKIPPED;

    fun storageValue(): String = when (this) {
        SUCCESS -> "success"
        TRANSIENT -> "transient"
        PERMANENT -> "permanent"
        RATE_LIMITED -> "rate_limited"
        AUTH -> "auth"
        SKIPPED -> "skipped"
    }
}

/** Per-row mapping of `kilter_publish_attempts`. */
data class KilterPublishAttempt(
    val id: Long,
    val climbUuid: String,
    val attemptedAtMs: Long,
    val op: String,
    val via: String,
    val outcome: String,
    val httpCode: Int?,
    val errorExcerpt: String?,
)

/** Aggregate counters for the Kilter-account UI's queue health card. */
data class KilterPublishQueueStats(
    val pendingCount: Long,
    val failedCount: Long,
    /** Wall-clock millis of the most recent attempt across all climbs.
     *  Null when no attempt was ever recorded. */
    val lastAttemptAtMs: Long?,
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

/**
 * Full-fidelity snapshot of an own-authored climb for the v3 backup
 * envelope (FEAT-008 Phase B). Carries every persisted column that
 * matters for restore: the editor-domain fields, the Nostr publish
 * provenance, and the Kilter-side lifecycle. Derived columns
 * (frames_count, is_listed, is_nomatch, frames_pace, hsm) and the
 * tombstone flag are intentionally omitted — `restoreOwnClimb` writes
 * sane defaults for those.
 */
data class OwnClimbBackupRow(
    val uuid: String,
    val layoutId: Long,
    val setterUsername: String?,
    val name: String,
    val frames: String,
    val edgeLeft: Long?,
    val edgeRight: Long?,
    val edgeBottom: Long?,
    val edgeTop: Long?,
    val createdAt: String?,
    val description: String,
    val moveCount: Long,
    val source: String,             // 'local' | 'nostr'
    val syncStatus: String,         // 'draft' | 'failed' | 'published_nostr' | …
    val createdByPubkey: String?,
    val framesHash: String?,
    val nostrEventId: String?,
    val nostrDTag: String?,
    val nostrPublishVia: String?,
    val kilterStatus: String?,
    val kilterSyncedAt: Long?,
    val kilterPublishVia: String?,
    val kilterError: String?,
    // FEAT-031: board family the draft was authored on, round-tripped so a
    // MoonBoard/Aurora own-climb doesn't restore as Kilter. Defaults to
    // "kilter" for legacy backups that predate the field (set on the export
    // side from OwnClimbExport.boardBrand).
    val boardBrand: String = "kilter",
)

/**
 * Per-angle stats for an own climb in the backup envelope. Mirrors the
 * subset of `climb_stats` columns that an own-climb row ever carries
 * (Kilter-only `fa_*` and `official_kilter_difficulty` are skipped).
 */
data class OwnClimbStatBackupRow(
    val climbUuid: String,
    val angle: Long,
    val displayDifficulty: Double?,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val ascensionistCount: Long,
    val benchmarkDifficulty: Double?,
)

/**
 * The CLIMB'S OWN publish coordinates, read per-row by
 * [CommunityPublishRetryWorker] so a queued draft for a non-active board
 * is re-published with its own brand + layout instead of the currently-
 * active board's (FEAT-031). [boardBrand] (from `climbs.board_brand`) and
 * [layoutId] (from `climb_stats.layout_id`, falling back to
 * `climbs.layout_id`) are always the climb's. [sizeLabel] is best-effort:
 * the largest product_size for that brand+layout, or null when none
 * resolves — the worker then substitutes the active board's size name.
 */
data class ClimbPublishContext(
    val boardBrand: String,
    val layoutId: Long,
    val sizeLabel: String?,
)

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
    /** Layout ID at row-creation time. The Kilter API rejects publishes
     *  whose `product_name` doesn't match the placement IDs' product, so
     *  the retry worker has to derive the product name from this. */
    val layoutId: Long,
    /** Board family the climb belongs to (`climbs.board_brand` wire value,
     *  e.g. "kilter" / "tension"). Carried for completeness so callers can
     *  tell which board a row came from without a second lookup. */
    val boardBrand: String,
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
        /** Active board's wire brand. Aurora-family boards (Tension,
         *  Grasshopper, Decoy, So iLL, Touchstone) reuse Kilter's low layout-ids,
         *  so the brand can't be inferred from [layoutId] — pass the editor's
         *  real brand. Null = derive from layoutId (Kilter / MoonBoard only). */
        boardBrand: String? = null,
    )
    /** Delete a local draft (drafts user explicitly discards). */
    fun deleteLocalClimb(uuid: String)
    /**
     * Tombstone a CruxCoach-authored, already-published climb. Owner-locked
     * at the SQL layer: only flips rows whose `created_by_pubkey` matches
     * `pubkey` AND `origin = 'cruxcoach'`. A Kilter-origin row can never
     * be removed via this path — Kilter is read-only here.
     *
     * Sets `is_deleted = 1`, `is_listed = 0`, `sync_status = 'deleted'`,
     * and bumps `created_at` to [tombstoneIso] so the subscriber's stale-
     * event guard rejects any incoming Original-Event whose `created_at`
     * is older than the tombstone moment. Drops the climb_stats rows so
     * orphan-stats diagnostics stay clean.
     */
    fun markCommunityClimbDeleted(uuid: String, pubkey: String, tombstoneIso: String)
    /** True iff the row exists locally and is_deleted=1 (subscriber's L3
     *  absorption: refuse re-importing a tombstoned climb that arrives via
     *  a Live-Sub event from a non-deleting relay). */
    fun isClimbTombstoned(uuid: String): Boolean
    /**
     * Insert a minimal `is_deleted=1` row so a future Original-Event for
     * the same uuid is absorbed by L3. Used on devices that receive a
     * Kind-5 deletion intent (or Kind-30078 tombstone-replacement) for
     * a uuid they never had locally — without the shell the next
     * Original-Event from a non-deleting relay would import the climb
     * fresh. INSERT OR IGNORE: real existing rows are not trampled.
     */
    fun insertTombstoneShell(uuid: String, pubkey: String, dTag: String, tombstoneIso: String)
    /**
     * Bundle of fields the CommunityClimbDeleter reads in one go: the
     * d-tag + last published event id (for NIP-09 `a`+`e` tags), the
     * row's author (owner check), Kilter publish status (UI hint), and
     * origin ('cruxcoach' is the only deletable provenance).
     */
    fun getCommunityClimbDeleteContext(uuid: String): CommunityClimbDeleteContext?
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
     * True iff a row already exists for this uuid that is NOT a genuine
     * community climb (catalogue content, or any NULL-author row). A real
     * community row has both origin='cruxcoach' AND a non-NULL author, so the
     * NULL returned by [getClimbAuthorPubkey] is ambiguous ("no row" vs
     * "catalogue row with no author"); the live subscriber uses this to reject
     * a community Kind-30078 that would re-key/overwrite catalogue content.
     */
    fun isNonCommunityClimb(uuid: String): Boolean
    /**
     * True iff a row exists locally with `source='local'` — i.e. authored
     * via the editor's `insertLocalDraft`. Used as a backstop self-filter
     * in the live subscriber when the primary pubkey-based check can't
     * fire (degraded signer, key rotation: the event still carries the
     * old pubkey, the local user now presents a new one). Without this,
     * an own-event echo would clobber `sync_status` / `kilter_status` /
     * `nostr_event_id` via upsertCommunityClimb's INSERT OR REPLACE.
     */
    fun isLocallyAuthored(uuid: String): Boolean
    /**
     * The recorded Kilter author identity (`kilter_author_uuid`) for a row.
     * NULL = no row, or author unknown (curated content) → never
     * publishable-as-mine. The publish gate compares this against the
     * CONNECTED Kilter account's userUuid — identity, never a display-name
     * match. Default null so existing fakes keep compiling.
     */
    fun getClimbKilterAuthorUuid(uuid: String): String? = null
    /**
     * Every climb authored by [authorUuid] (the connected Kilter account),
     * tombstones excluded. Backs the "Meine Climbs" list and the logbook
     * publish gate. Default empty so existing fakes keep compiling.
     */
    fun getOwnAuthoredKilterClimbs(authorUuid: String): List<CommunityClimbRow> = emptyList()
    /**
     * Single-row authorship-gated lookup for the own-climb publish path:
     * returns the row only when its `kilter_author_uuid` equals
     * [authorUuid]. Null = no row, unknown author, or foreign author.
     */
    fun getOwnAuthoredClimbRow(uuid: String, authorUuid: String): CommunityClimbRow? = null
    /**
     * Convert an own-authored Kilter row IN PLACE into a CruxCoach
     * community climb — the climb KEEPS its Kilter uuid (identity
     * decision: no new uuid). Flips origin/source/sync_status/
     * created_by_pubkey to the same values a fresh editor draft gets, and
     * stamps kilter_status='synced' so the publisher's best-effort Kilter
     * leg skips (the climb already lives on Kilter natively).
     *
     * SQL-guarded: refuses when the row's `kilter_author_uuid` differs
     * from [kilterAuthorUuid], when it is already owned by a different
     * `created_by_pubkey`, or when it already carries a published
     * `nostr_event_id`. Returns true iff the row was converted (or
     * re-converted idempotently for a retry).
     */
    fun adoptKilterClimbAsCommunity(
        uuid: String,
        kilterAuthorUuid: String,
        pubkey: String,
        adoptedAtEpochSeconds: Long,
    ): Boolean = false
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
        /** Real board family from the event's `board_brand` tag (FEAT-031).
         *  Persisted verbatim to `climbs.board_brand` so ingestion keys the
         *  climb on its brand, NOT on its layout_id — the new boards reuse
         *  layout-ids that collide with Kilter's. Defaults to 'kilter' for
         *  legacy events that carry no `board_brand` tag. */
        boardBrand: String = "kilter",
    )

    // ─── Community-climb dead-letter queue (M16) ──────────────────────
    // Failed Kind-30078 upserts (SQLite layer threw — disk full, lock,
    // unexpected constraint) get persisted here so the subscriber can
    // retry on next start. Without this, the cursor advances over the
    // failed event on the very next successful one and NIP-01 Live-REQ
    // never re-delivers it.

    /**
     * Atomic insert-or-increment for a failed community-climb upsert.
     * On first failure for [uuid] inserts a new row with retry_count=1;
     * on subsequent failures bumps retry_count and overwrites
     * `last_failed_at_ms` + `last_error_excerpt`. Wraps both branches
     * in a SQLDelight transaction for safety against parallel writers.
     */
    fun recordCommunityClimbDeadLetter(
        uuid: String,
        eventId: String,
        eventCreatedAt: Long,
        rawEventJson: String,
        nowMs: Long,
        errorExcerpt: String?,
    )

    /**
     * Drained by the subscriber's retry pass. [maxRetries] is the
     * cap-application boundary: rows with retry_count < it come back
     * for another try, rows >= it are abandoned (still readable for
     * diagnostics). [limit] bounds per-call cost — pass a small
     * number (~25) so a giant backlog doesn't lock the DB.
     */
    fun getRetriableCommunityClimbDeadLetters(
        maxRetries: Long,
        limit: Long,
    ): List<CommunityClimbDeadLetter>

    /** Drop a DLQ row after a successful retry-upsert. */
    fun deleteCommunityClimbDeadLetter(uuid: String)

    /** Aggregate counts for a future "Failed climb imports" UI card. */
    fun getCommunityClimbDeadLetterCounts(maxRetries: Long): DeadLetterCounts
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
        /** The emitted Nostr event's created_at, ISO-8601. Persisted so the
         *  next edit/delete clamps monotonically (FEAT-039 audit BUG-1). */
        createdAtIso: String,
    )
    fun markClimbPublishFailed(uuid: String)
    /**
     * Pre-send crash-safety marker. Promote a draft into the retry queue
     * BEFORE the relay round-trip starts — semantically `sync_status='failed'`
     * = "needs retry". Called immediately before the publisher sends the
     * signed event so a process death between relay-accept and the
     * post-send `markClimbPublishedNostr` flip doesn't leave the row
     * stuck at 'draft' forever (drafts aren't drained by the retry
     * worker). Restricted to source='local' + origin='cruxcoach' rows.
     */
    fun markClimbPublishInFlight(uuid: String)

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
    /** Active-board-scoped variant of [getClimbsByPubkey] (SetterDetailScreen).
     *  Restricts the setter's climbs to the active board ([boardBrand] +
     *  [layoutId]); when [boardBrand] == "kilter" ALSO includes the setter's
     *  Kilter climbs from other layouts that FIT [selProductSizeId]
     *  (edge-containment, NULL edges = fits all). Non-Kilter: no cross-size. */
    fun getClimbsByPubkeyForBoard(pubkey: String, angle: Int, boardBrand: String, layoutId: Int, selProductSizeId: Int): List<SetterClimbEntry>
    /** My-climbs filter (board browser). Returns one [ClimbWithStats] per
     *  uuid authored by [pubkey] on [layoutId], ignoring angle/grade/asc
     *  filters so drafts saved at any angle remain discoverable. The row
     *  is picked at [preferredAngle] when available, else any angle. */
    fun getOwnClimbsForBrowse(pubkey: String, layoutId: Int, preferredAngle: Int, boardBrand: String): List<ClimbWithStats>
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
    /**
     * Sweep the residual "stuck pending" pool: any climb whose
     * `kilter_status='pending'` has either no attempt history at all OR
     * whose latest attempt is older than [olderThanMs] (wall-clock
     * cutoff). Sets it back to 'failed' so the retry worker's queue
     * picks it up. Returns the number of rows touched.
     *
     * Covers the residual edge from `claimKilterPublishSlot`'s try/catch
     * downgrade — when even the catch path throws (process kill mid-
     * statement, OOM in the SQLite driver), the row stayed 'pending'
     * forever. The retry worker invokes this once per tick before
     * draining the queue.
     */
    fun sweepStuckKilterPending(olderThanMs: Long): Long
    /**
     * Append an immutable per-attempt audit row to `kilter_publish_attempts`.
     * Called from every terminal branch of the publisher + retry worker so
     * the timeline reconstruction survives subsequent overwrites of the
     * single-value `climbs.kilter_*` columns.
     */
    fun recordKilterPublishAttempt(
        climbUuid: String,
        attemptedAtMs: Long,
        op: KilterPublishOp,
        via: String,
        outcome: KilterPublishOutcomeKind,
        httpCode: Int? = null,
        errorExcerpt: String? = null,
    )
    /** Latest-first attempt history for a climb. */
    fun getKilterPublishAttempts(climbUuid: String, limit: Int = 50): List<KilterPublishAttempt>
    /** Aggregate counters for the Kilter-account UI's queue health card. */
    fun getKilterPublishQueueStats(): KilterPublishQueueStats
    /**
     * Climbs with `origin='cruxcoach'`, Nostr-published, awaiting Kilter
     * sync, scoped to [pubkey]. Drained by [KilterPublishRetryWorker].
     * Pubkey-scope mirrors [getClimbsAwaitingNostrRetry] so a backup
     * restored from a different nsec (DataExchange override path) or an
     * identity-switch on the same device cannot push climbs authored
     * under another identity to the active Kilter account.
     */
    fun getClimbsAwaitingKilterRetry(pubkey: String): List<CommunityClimbRow>
    /**
     * Local climbs the editor sent to relays where no relay accepted
     * (`sync_status='failed'`) — the Nostr-side retry queue, scoped to
     * [pubkey]. Drained by the periodic CommunityPublishRetryWorker.
     */
    fun getClimbsAwaitingNostrRetry(pubkey: String): List<CommunityClimbRow>
    /**
     * Drafts (`source='local'`, sync_status `draft`/`failed`) for the active
     * board [boardBrand] (wire value, e.g. "kilter"/"tension"), authored
     * by [pubkey] or with a NULL `created_by_pubkey` (legacy / pre-key-init).
     * Pass null for [pubkey] to fetch only legacy NULL-pubkey drafts.
     * Restricts visibility across identity switches: drafts authored under
     * identity A are invisible to identity B opened in the editor on the same
     * device. Scoping to [boardBrand] also stops one board's drafts (e.g.
     * Tension) from leaking into another board's drawer (e.g. Kilter).
     */
    fun getDraftClimbs(pubkey: String?, boardBrand: String): List<CommunityClimbRow>
    fun getMyClimbs(pubkey: String): List<CommunityClimbRow>
    fun getCommunityClimbs(): List<CommunityClimbRow>
    /** Single-row stats lookup used by the editor when restoring a draft.
     *  Returns null when the climb has no stats row at all. The pair is
     *  (angle, setterGradeId) — for local drafts these match what
     *  upsertLocalClimbStat persisted; both null means the row predates
     *  having stats and the editor falls back to its default angle. */
    fun getClimbStatsForUuid(uuid: String): Pair<Int, Int?>?
    /** The climb's OWN brand + layout + (best-effort) size label, for the
     *  Nostr retry worker — see [ClimbPublishContext]. Null when the climb
     *  isn't in the local DB. */
    fun getClimbPublishContext(uuid: String): ClimbPublishContext?
    /** Look up an existing climb by frames_hash for duplicate detection, scoped
     *  to [boardBrand] (frames_hash folds in layout_id but not the brand, and
     *  layout_id=1 is shared across boards). */
    fun findClimbByFramesHash(framesHash: String, layoutId: Long, boardBrand: String): CommunityClimbRow?
    /** Cache the setter-grade entry for a community climb (MVP — no vote aggregation). */
    fun upsertSetterGrade(climbDTag: String, angle: Long, setterGradeId: Int, lastUpdatedEpochMs: Long)

    // ── Backup / restore for own climbs (FEAT-008 Phase B) ──────
    /**
     * Snapshot every own-authored climb belonging to [pubkey] (plus
     * legacy NULL-pubkey local drafts) for export into the v3 backup
     * envelope. Returns full-fidelity rows including Nostr + Kilter
     * provenance. Tombstoned rows are excluded.
     */
    fun getOwnClimbsForBackup(pubkey: String): List<OwnClimbBackupRow>
    /**
     * Per-angle stats for the same set of own climbs as
     * [getOwnClimbsForBackup]. JOIN-filtered so a stats row whose
     * climb has been tombstoned is excluded.
     */
    fun getOwnClimbStatsForBackup(pubkey: String): List<OwnClimbStatBackupRow>
    /** Lookup the chosen angle for a single own climb. Returns null when
     *  no climb_stats row exists yet (climb just published, stats row is
     *  written after); caller falls back to a sensible default (40°). */
    fun getOwnClimbAngle(uuid: String): Long?
    /**
     * Restore a single own climb from a backup envelope. Returns true
     * when a new row was inserted, false when an existing row with the
     * same uuid was preserved (idempotent re-import; the live-sub may
     * have already re-fetched the published climb, or the user is
     * restoring on top of their own current state). The caller decides
     * whether to surface the skip count to the user.
     *
     * Uses INSERT OR IGNORE under the hood — a uuid collision keeps the
     * existing row in place rather than clobbering it.
     */
    fun restoreOwnClimb(row: OwnClimbBackupRow): Boolean
    /**
     * Restore the per-angle stats for an own climb. Idempotent
     * (INSERT OR REPLACE on the (climb_uuid, angle) primary key).
     */
    fun restoreOwnClimbStat(row: OwnClimbStatBackupRow)
}

// ── Composite interface (backward-compatible) ───────────────

interface BoardRepository :
    BoardClimbQueries,
    BoardLayoutQueries,
    BoardWriteOperations,
    CommunityClimbQueries
