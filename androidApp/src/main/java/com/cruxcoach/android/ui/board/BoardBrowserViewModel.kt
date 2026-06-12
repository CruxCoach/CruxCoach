package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import android.util.Log
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.data.BleShareManager
import com.cruxcoach.android.data.BleShareUiState
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.SessionType
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.android.util.PerfLogger
import com.cruxcoach.util.GradeConverter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import javax.inject.Inject
import com.cruxcoach.android.util.safeLaunch

/** Status of a climb relative to the local user. The three buckets are
 *  DISJOINT — `getUserAttemptedClimbUuids` `EXCEPT`s sent climbs, so every
 *  climb is in exactly one — which is what makes the multi-select union both
 *  unambiguous and O(1)-countable.
 *
 *  The browser status filter is a multi-select [Set] of these: an **empty
 *  set means "no status constraint"** (the "Alle" chip). The legacy
 *  single-select preset "Offen" (= unsent) is just {NEW, ATTEMPTED} and
 *  "Alle" is the empty set, so both drop out as redundant chips. */
enum class ClimbStatusFilter { NEW, ATTEMPTED, SENT }

/** Parse the persisted status-filter preference into a [Set]. Accepts the
 *  current comma-joined form ("NEW,SENT"), an empty string ("" = Alle), and
 *  migrates the legacy single-select tokens written by builds ≤ 0.2.0
 *  ("ALL" → {}, "UNSENT" → {NEW, ATTEMPTED}). Unknown tokens are ignored. */
internal fun parseStatusFilter(raw: String): Set<ClimbStatusFilter> {
    if (raw.isBlank()) return emptySet()
    val out = LinkedHashSet<ClimbStatusFilter>()
    for (token in raw.split(',')) {
        when (val t = token.trim()) {
            "", "ALL" -> { /* no constraint */ }
            "UNSENT" -> { out += ClimbStatusFilter.NEW; out += ClimbStatusFilter.ATTEMPTED }
            else -> runCatching { ClimbStatusFilter.valueOf(t) }.getOrNull()?.let { out += it }
        }
    }
    return out
}

/** Serialize the multi-select status filter for persistence. Empty set →
 *  "" (round-trips back to "Alle" via [parseStatusFilter]). */
internal fun serializeStatusFilter(statuses: Set<ClimbStatusFilter>): String =
    statuses.joinToString(",") { it.name }

/** Provenance filter — corresponds to the `origin` column on `climbs`.
 *  BOARDSESH = climbs imported from BoardSesh's public GraphQL (user-
 *  created on BoardSesh, never pushed to Kilter/Aurora); they carry
 *  `origin='boardsesh'` and are a distinct provenance from both the
 *  Kilter catalogue and CruxCoach-community climbs. */
enum class OriginFilter { ALL, CRUXCOACH, KILTER, BOARDSESH }

/** Pure-logic origin-bucketing extracted from [BoardBrowserViewModel] so it
 *  can be unit-tested without spinning up the full Hilt-injected ViewModel.
 *
 *  Local drafts authored via the editor are by definition cruxcoach-side,
 *  even on legacy rows whose `origin` column still reads 'kilter' (the
 *  schema default — newer `insertLocalDraft` writes 'cruxcoach' explicitly,
 *  but rows from earlier builds don't get retroactively rewritten).
 *  Group `source='local'` with the cruxcoach bucket so the user's own
 *  drafts always surface under "Quelle: CruxCoach" and never misclassify
 *  into the Kilter bucket below.
 */
internal object BrowserOriginFilter {
    fun apply(climbs: List<ClimbWithStats>, filter: OriginFilter): List<ClimbWithStats> {
        return when (filter) {
            OriginFilter.ALL -> climbs
            OriginFilter.CRUXCOACH -> climbs.filter { it.origin == "cruxcoach" || it.source == "local" }
            OriginFilter.KILTER -> climbs.filter { it.origin == "kilter" && it.source != "local" }
            // BoardSesh-imported climbs are their own provenance — never folded
            // into the cruxcoach or kilter buckets (both filters above exclude
            // origin=='boardsesh' already), so they surface only here and under ALL.
            OriginFilter.BOARDSESH -> climbs.filter { it.origin == "boardsesh" }
        }
    }
}

@Deprecated("Use EnhancedSessionSummary", replaceWith = ReplaceWith("EnhancedSessionSummary"))
data class SessionZoneSummary(
    val warmupCount: Int = 0,
    val optimalCount: Int = 0,
    val limitCount: Int = 0,
    val sessionType: SessionType = SessionType.PYRAMID_SESSION
) {
    val total: Int get() = warmupCount + optimalCount + limitCount
}

data class BrowserFilterState(
    val angle: Int = 40,
    val layoutId: Int = com.cruxcoach.android.data.BoardConstants.KILTER_ORIGINAL_LAYOUT,
    /** Active board brand — "kilter" | "moonboard" (FEAT-027). When
     *  "moonboard" the angle picker offers the variant's discrete
     *  [moonBoardAngles] instead of the Kilter 0-70° slider. */
    val boardBrand: String = "kilter",
    /** Discrete angle options for the active MoonBoard variant; empty for
     *  Kilter (which uses the continuous slider). */
    val moonBoardAngles: List<Int> = emptyList(),
    val minGradeIndex: Int = DEFAULT_MIN_GRADE_INDEX,
    val maxGradeIndex: Int = DEFAULT_MAX_GRADE_INDEX,
    val minAscensionists: Int = 0,
    val searchQuery: String = "",
    val sortField: ClimbSortField = ClimbSortField.ASCENSIONISTS,
    val sortDirection: SortDirection = SortDirection.DESC,
    /** Multi-select status filter; empty = no constraint ("Alle"). */
    val statusFilter: Set<ClimbStatusFilter> = emptySet(),
    val climbTypeFilter: ClimbTypeFilter = ClimbTypeFilter.BOULDER,
    val benchmarkOnly: Boolean = false,
    val originFilter: OriginFilter = OriginFilter.ALL,
    /** When true, restrict the browser list to climbs authored by the local
     *  user's Nostr pubkey (drafts + published). Bypasses angle/grade/asc
     *  filters at fetch time so drafts saved at any angle remain visible. */
    val myClimbsOnly: Boolean = false,
    /** "Nur unbewertete (Projekte)" mode (product decision 2026-06-11,
     *  replacing the old untouched-default-range heuristic): when true the
     *  browse list shows ONLY ungraded climbs (difficulty_average NULL) and
     *  the grade slider is inert. When false, ungraded climbs are NEVER part
     *  of a regular browse result — the BoardSesh provenance pull is the one
     *  exception (its imports are inherently ungraded; the origin chip is
     *  the explicit opt-in). */
    val ungradedOnly: Boolean = false,
) {
    companion object {
        /** Default (untouched) grade-slider range — single source of truth
         *  for the property defaults and the filter reset. */
        const val DEFAULT_MIN_GRADE_INDEX = 0
        const val DEFAULT_MAX_GRADE_INDEX = 16
    }
}

data class BrowserBleState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedBoardName: String? = null
)

data class HoldSearchState(
    val selectedHolds: Set<Int> = emptySet(),
    val heatmapMode: HeatmapMode = HeatmapMode.OFF,
    val heatmapData: Map<Int, Float> = emptyMap(),
    val matchCount: Int = 0,
    val isSearching: Boolean = false,
    val holdFilterActive: Boolean = false,
    val holdFilterUuids: Set<String> = emptySet(),
    val showSheet: Boolean = false
)

data class BoardBrowserState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val climbs: List<ClimbWithStats> = emptyList(),
    val climbCount: Long = 0,
    val filteredCount: Long = -1,
    val hasBoardData: Boolean = false,
    /** Whether the ACTIVE board's catalogue has any imported climbs.
     *  [hasBoardData] is brand-agnostic (any catalogue at all), so after a
     *  board switch it stays true on the Kilter catalogue while the new
     *  board has zero rows — this flag drives the "load catalogue" empty
     *  state for exactly that case. */
    val activeBrandHasCatalogue: Boolean = true,
    /** True while a board-data sync is running and the ACTIVE brand's
     *  catalogue import hasn't completed yet. Drives the third empty-state
     *  case ("catalogue loading") — without it the browser flashes the
     *  no-catalogue and then the misleading no-results state while the
     *  brand's climbs are still streaming in. */
    val activeBrandImporting: Boolean = false,
    val canLoadMore: Boolean = false,
    val dbOffset: Int = 0,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val zones: IntensityZones? = null,
    val error: String? = null,
    val easterAnimationsUnlocked: Boolean = false,
    val placements: Map<Int, com.cruxcoach.data.repository.BoardPlacement> = emptyMap(),
    val boardSize: com.cruxcoach.data.repository.BoardSize? = null,
    val boardImages: List<com.cruxcoach.data.repository.BoardImage> = emptyList(),
    /** Hold-set leg of the always-on "fits my board" filter: bits of the
     *  active layout's hold sets NOT mounted on [boardSize] (see
     *  HoldSetMask.excludedMask). 0 = filter off (full board, MoonBoard,
     *  or no size configured). Recomputed alongside [boardSize]. */
    val hsmExcludedMask: Long = 0,
    val filter: BrowserFilterState = BrowserFilterState(),
    val ble: BrowserBleState = BrowserBleState(),
    val holdSearch: HoldSearchState = HoldSearchState()
)

@HiltViewModel
class BoardBrowserViewModel @Inject constructor(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val sessionManager: BoardSessionManager,
    private val zoneManager: IntensityZoneManager,
    private val syncManager: BoardSyncManager,
    private val gattBridge: SessionGattBridge,
    private val sessionQueueManager: SessionQueueManager,
    private val bleShareManager: BleShareManager,
    private val nostrSigner: NostrSigner,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState
) : ViewModel() {

    private val _state = MutableStateFlow(BoardBrowserState())
    val state: StateFlow<BoardBrowserState> = _state.asStateFlow()

    val bleShareUiState: StateFlow<BleShareUiState> = bleShareManager.uiState

    private val _currentQueueClimbName = MutableStateFlow<String?>(null)
    val currentQueueClimbName: StateFlow<String?> = _currentQueueClimbName.asStateFlow()

    val sessionState: StateFlow<BoardSessionState> = sessionManager.state
    val restTimerState: StateFlow<RestTimerState> = sessionManager.restTimer

    /** Derived boolean — only emits on session start/stop, not every 500ms tick. */
    val isSessionActive: StateFlow<Boolean> = sessionManager.state
        .map { it.isActive }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionManager.state.value.isActive)

    // Cached user climb status sets (loaded once, reused for client-side filtering)
    private var sentUuids: Set<String> = emptySet()
    private var attemptedUuids: Set<String> = emptySet()
    private var statusLoaded = false
    // Ignored climbs ("Quatsch" the user never wants suggested). Loaded once
    // like the status sets and applied as an always-on client-side filter to
    // every fetched page (the ignored UUIDs live in the encrypted SecureDB,
    // the browse rows in the board DB — no cross-DB JOIN, so we filter here).
    private var hiddenUuids: Set<String> = emptySet()
    private var hiddenLoaded = false
    private var filtersLoaded = false
    private var searchJob: Job? = null

    // Cached count: only re-fetch from DB when count-affecting filters change
    private var cachedDbCount: Long = -1
    private var cachedCountKey: String = ""

    // RANDOM sort, browse mode. SQL ORDER BY RANDOM() re-shuffles per page →
    // duplicates and gaps when scrolling. Instead: page 1 is a fast SQL
    // random sample served immediately; the full shuffle of every *other*
    // matching UUID builds in the background and backs pages 2+. Excluding
    // page 1's UUIDs from that shuffle keeps the whole scroll duplicate- and
    // gap-free. Key signature excludes status/benchmark/origin filters —
    // those are applied client-side after pagination, so they don't change
    // the underlying match set and must not force a re-roll. The cache
    // lives only between pages of ONE selection: every explicit sort pick
    // (updateSortField) discards it, so re-tapping RANDOM re-rolls instead
    // of serving the same permutation for the ViewModel's lifetime.
    private var randomKey: String? = null
    private var randomPage1: List<ClimbWithStats>? = null
    private var randomCacheJob: Deferred<List<String>>? = null

    private fun invalidateRandomCache() {
        randomKey = null
        randomPage1 = null
        randomCacheJob?.cancel()
        randomCacheJob = null
    }

    companion object {
        private const val TAG = "BoardBrowserVM"
        private const val PAGE_SIZE = 50
        private const val MAX_STATUS_SCAN_PAGES = 10
        // Dice re-rolls to skip an ignored climb before giving up (see fetchRandomClimb).
        private const val RANDOM_PICK_MAX_ROLLS = 8

        // Ungraded-only mode rides on the existing SQL grade predicate
        //   ((difficulty_average >= :minDiff AND <= :maxDiff)
        //    OR (:showUngraded = 1 AND difficulty_average IS NULL))
        // by passing an IMPOSSIBLE range (min > max) together with
        // showUngraded=true: the range leg can never match, so only the
        // IS NULL leg does — every browse / count / uuid-enumeration query
        // shares that predicate shape, no SQL change needed.
        private const val UNGRADED_ONLY_MIN_DIFF = 9999.0
        private const val UNGRADED_ONLY_MAX_DIFF = -9999.0
    }

    /** Effective SQL grade-bound parameters derived from the filter state. */
    private data class GradeBounds(val minDiff: Double, val maxDiff: Double, val showUngraded: Boolean)

    /** Normal mode: the slider's real bounds with showUngraded=false —
     *  ungraded (NULL-difficulty) climbs are never shown in browse, whatever
     *  the slider position. Ungraded-only mode: the impossible range +
     *  showUngraded=true, so exactly the NULL-grade rows match (see the
     *  companion constants). */
    private fun gradeBounds(f: BrowserFilterState): GradeBounds {
        if (f.ungradedOnly) return GradeBounds(UNGRADED_ONLY_MIN_DIFF, UNGRADED_ONLY_MAX_DIFF, true)
        val french = _state.value.gradeScale == GradeScale.FRENCH
        return GradeBounds(
            KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french),
            KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french),
            false,
        )
    }

    init {
        PerfLogger.milestone("BoardBrowserVM.init START")
        viewModelScope.safeLaunch(TAG) {
            // Without try/catch a DataStore read failure would leave
            // isLoading=true forever (the spinner never resolves and
            // refreshBoardData is never called). Catch + log + flip
            // isLoading=false so the BoardBrowser at least shows an
            // empty list instead of an indefinite spinner; the user
            // can pull-to-refresh to retry.
            try {
                // Single DataStore read instead of 10 sequential .first() calls
                val snap = PerfLogger.traceSuspend("BoardBrowserVM.prefs (batch)") {
                    userPreferences.getBoardFilterSnapshot()
                }
                val sortField = try { ClimbSortField.valueOf(snap.sortField) } catch (_: Exception) { ClimbSortField.ASCENSIONISTS }
                val sortDir = try { SortDirection.valueOf(snap.sortDirection) } catch (_: Exception) { SortDirection.DESC }
                val statusFilter = parseStatusFilter(snap.statusFilter)
                val climbType = try { ClimbTypeFilter.valueOf(snap.climbType) } catch (_: Exception) { ClimbTypeFilter.BOULDER }
                val originFilter = try { OriginFilter.valueOf(snap.originFilter) } catch (_: Exception) { OriginFilter.ALL }
                PerfLogger.milestone("BoardBrowserVM prefs loaded (batch)")

                // FEAT-027: when the active board is a MoonBoard, the angle
                // picker offers the variant's discrete angles instead of the
                // Kilter slider.
                val moonBoardAngles = MoonBoardVariant
                    .fromLayoutId(snap.layoutId.toLong())?.angles.orEmpty()
                _state.update { it.copy(
                    gradeScale = snap.gradeScale,
                    filter = it.filter.copy(
                        angle = snap.angle, layoutId = snap.layoutId,
                        boardBrand = snap.boardBrand,
                        moonBoardAngles = moonBoardAngles,
                        minGradeIndex = snap.minGrade, maxGradeIndex = snap.maxGrade,
                        minAscensionists = snap.minAscensionists, sortField = sortField, sortDirection = sortDir,
                        statusFilter = statusFilter, climbTypeFilter = climbType,
                        benchmarkOnly = snap.benchmarkOnly,
                        originFilter = originFilter,
                        myClimbsOnly = snap.myClimbsOnly,
                        ungradedOnly = snap.ungradedOnly,
                    )
                ) }
                filtersLoaded = true
                PerfLogger.milestone("BoardBrowserVM filters applied, calling refreshBoardData")
                refreshBoardData()
                // FEAT-031: reload the browse list when the active board
                // changes. The shared board picker persists the new selection
                // from any screen; observe the board prefs directly so the
                // browser reflects it (race-free, unlike a post-confirm callback).
                launch {
                    combine(
                        userPreferences.boardBrand,
                        userPreferences.boardLayoutId,
                        userPreferences.boardProductSizeId,
                        userPreferences.boardAngle,
                    ) { brand, layout, size, angle -> Pair(Triple(brand, layout, size), angle) }
                        .drop(1)
                        .distinctUntilChanged()
                        .collect { (_, prefAngle) ->
                            // Re-seed the in-memory angle from prefs on an external
                            // write: setMoonBoardSelection pins 40° and a fixed-angle
                            // gym pick seeds the wall's angle. Without this the
                            // browser keeps querying the previous board's angle
                            // (climb_browse matches angle exactly → empty list) and
                            // the next persistFilters writes the stale angle back
                            // over the seed. persistFilters' own write is a no-op
                            // here (the pref already equals the in-memory angle).
                            if (_state.value.filter.angle != prefAngle) {
                                _state.update { it.copy(filter = it.filter.copy(angle = prefAngle)) }
                            }
                            refreshBoardData(force = true)
                        }
                }
                // Eagerly load status UUIDs in background (non-blocking)
                launch(Dispatchers.IO) {
                    PerfLogger.traceSuspend("ensureStatusLoaded") { ensureStatusLoaded() }
                }
                // Live updates for grade scale changes
                launch {
                    userPreferences.gradeScale.collect { s ->
                        _state.update { it.copy(gradeScale = s) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BoardBrowserVM", "init failed; rendering empty list", e)
                _state.update { it.copy(isLoading = false) }
            }
        }
        // Combine peripheral state into a single atomic update.
        // Prevents 4 separate state emissions on init → fewer recompositions.
        viewModelScope.safeLaunch(TAG) {
            combine(
                bleConnection.connectionState,
                bleConnection.connectedBoardName,
                zoneManager.zones,
                userPreferences.easterAnimationsUnlocked
            ) { connState, boardName, zones, easterUnlocked ->
                _state.update { it.copy(
                    ble = BrowserBleState(connectionState = connState, connectedBoardName = boardName),
                    zones = zones,
                    easterAnimationsUnlocked = easterUnlocked
                ) }
            }.collect {}
        }
        // Resolve current queue climb name
        viewModelScope.safeLaunch(TAG) {
            sessionQueueManager.state.collect { queueState ->
                val item = queueState.currentClimb
                if (item != null) {
                    val name = withContext(Dispatchers.IO) {
                        val uuid = item.climbUuid
                        val angle = item.angle
                        (boardRepository.getClimbByUuid(uuid, angle)
                            ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
                            ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle))?.name
                    }
                    _currentQueueClimbName.value = name
                } else {
                    _currentQueueClimbName.value = null
                }
            }
        }
        // Auto-refresh board data when a sync completes. Also tracks whether
        // the ACTIVE brand's catalogue is mid-import, so the empty state can
        // show "catalogue loading" instead of the misleading no-results /
        // no-catalogue cases while its climbs are still streaming in. Brand
        // comes from prefs (not filter state) — after a deletion the filter
        // brand is stale until the first successful refresh.
        viewModelScope.safeLaunch(TAG) {
            var lastGen = syncManager.state.value.syncGeneration
            var wasImporting = false
            combine(syncManager.state, userPreferences.boardBrand) { syncState, brandWire ->
                syncState to BoardBrand.fromWire(brandWire)
            }.collect { (syncState, brand) ->
                val step = syncState.boardSteps[brand]
                // Mid-import: the brand's step is non-terminal, or a FULL
                // sync is running (Kilter's importStep is non-null for its
                // whole duration; board-scoped loads clear it) and the
                // still-catalogue-less brand hasn't reached its lane yet —
                // the per-board lanes run sequentially. The importStep guard
                // keeps a board-scoped load of a DIFFERENT board from
                // masking the real no-catalogue recovery state.
                val importing = syncState.isSyncing && (
                    (step != null && step !is ImportStep.Done) ||
                    (step == null && syncState.importStep != null &&
                        !_state.value.activeBrandHasCatalogue)
                )
                if (importing != _state.value.activeBrandImporting) {
                    _state.update { it.copy(activeBrandImporting = importing) }
                }
                // The active brand finished while the rest of the multi-board
                // sync continues — refresh now so its climbs show immediately
                // instead of waiting for the whole sync to end.
                if (wasImporting && !importing && syncState.isSyncing) {
                    refreshBoardData(force = true)
                }
                wasImporting = importing
                if (syncState.syncGeneration > lastGen && !syncState.isSyncing) {
                    lastGen = syncState.syncGeneration
                    refreshBoardData(force = true)
                }
            }
        }
    }

    /**
     * Apply a board chosen from the browser filter's combined all-16
     * picker. Persists the global selection (same store the settings
     * picker and the always-on "fits my board" filter read), then
     * reloads so the list re-filters to that board immediately.
     */
    fun selectBoard(productSizeId: Int) {
        viewModelScope.safeLaunch(TAG) {
            val ps = BoardConstants.KILTER_KNOWN_SIZES
                .firstOrNull { it.id.toInt() == productSizeId }
            val layoutId = BoardConstants.layoutIdForProduct(
                ps?.productId?.toInt() ?: BoardConstants.KILTER_PRODUCT_ID
            )
            userPreferences.setBoardLayoutId(layoutId)
            userPreferences.setBoardProductSizeId(productSizeId)
            // Reset brand: the user may be switching from MoonBoard back
            // to Kilter via the filter-screen picker.
            userPreferences.setBoardBrand(BoardBrand.KILTER.wireValue)
            refreshBoardData(force = true)
        }
    }

    /** FEAT-027: switch the active board to a MoonBoard variant via the
     *  filter-screen picker — mirrors [selectBoard] for Kilter. The
     *  MoonBoard catalogue is part of the board-data sync, so no extra
     *  download is needed; the next browse fetch flips brand atomically. */
    fun selectMoonBoardVariant(variant: MoonBoardVariant) {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.setMoonBoardSelection(variant.layoutId.toInt())
            refreshBoardData(force = true)
        }
    }

    fun refreshBoardData(force: Boolean = false) {
        if (!filtersLoaded) return
        val dataChanged = climbNavState.statusDataChanged
        val changedUuids = if (dataChanged) {
            climbNavState.changedClimbUuids.toSet().also {
                climbNavState.statusDataChanged = false
                climbNavState.changedClimbUuids.clear()
            }
        } else emptySet()
        // Pick up creator-side mutations (save / update / publish / delete
        // from the editor + community-delete from the detail screen).
        // Without this, an in-place edit (e.g. rename) leaves the browser
        // showing stale data because the count didn't change. See
        // ClimbNavigationState.creatorDataChanged.
        val creatorDirty = climbNavState.creatorDataChanged.also {
            if (it) climbNavState.creatorDataChanged = false
        }

        // Consume pending setter filter from detail screen
        val setterFilterApplied = climbNavState.pendingSetterFilter != null
        climbNavState.pendingSetterFilter?.let { setter ->
            climbNavState.pendingSetterFilter = null
            _state.update { it.copy(filter = it.filter.copy(searchQuery = setter)) }
        }

        // Immediate UI update: when the active filter excludes sent climbs
        // (a non-empty selection without SENT — e.g. Neu, Versucht, or
        // Neu+Versucht), a just-logged send/bid should drop out of view at
        // once. The subsequent full re-search reconciles the exact set.
        if (changedUuids.isNotEmpty()) {
            val statuses = _state.value.filter.statusFilter
            if (statuses.isNotEmpty() && ClimbStatusFilter.SENT !in statuses) {
                _state.update { it.copy(
                    climbs = it.climbs.filter { climb -> climb.uuid !in changedUuids }
                ) }
            }
        }

        // Invalidate caches so new ascents/bids + ignore-toggles are picked up
        statusLoaded = false
        hiddenLoaded = false
        cachedCountKey = ""
        viewModelScope.safeLaunch(TAG) {
            val changed = withContext(Dispatchers.IO) {
                // hasAnyClimbs() = O(1) EXISTS probe; getClimbCount() is a
                // full table-scan that blocks tens of seconds on the bulk
                // importer's writer-lock during sync. We only need a
                // boolean here ("can we render the browse list?"), so the
                // EXISTS path is fine. countChanged is then just a
                // hasBoardData transition (false → true after the first
                // chunk lands), not a delta-by-row-count.
                val hasData = PerfLogger.traceQuery("hasAnyClimbs") { boardRepository.hasAnyClimbs() }
                val count = if (hasData) _state.value.climbCount.coerceAtLeast(1) else 0L
                val countChanged = hasData != _state.value.hasBoardData
                val prefSizeId = userPreferences.boardProductSizeId.first()
                val prefLayoutId = userPreferences.boardLayoutId.first()
                val prefBoardBrand = userPreferences.boardBrand.first()
                val prefAngle = userPreferences.boardAngle.first()
                // Same O(1) EXISTS probe, scoped to the active board — feeds
                // the "catalogue not downloaded" empty state after a board
                // switch (hasData above stays true on any other catalogue).
                val brandHasCatalogue = !hasData ||
                    PerfLogger.traceQuery("hasClimbsForBrand") {
                        boardRepository.hasClimbsForBrand(prefBoardBrand)
                    }
                if (brandHasCatalogue != _state.value.activeBrandHasCatalogue) {
                    _state.update { it.copy(activeBrandHasCatalogue = brandHasCatalogue) }
                }
                // FEAT-027: a MoonBoard layout has no Aurora product_size /
                // board_images rows — the Kilter-only lookups below would just
                // return empty. Skip them entirely for a MoonBoard board.
                val isMoonBoard = !BoardBrand.fromWire(prefBoardBrand).usesAuroraPlacements
                val needsBoardReload = _state.value.boardSize == null || _state.value.boardSize!!.id.toInt() != prefSizeId
                    || _state.value.filter.layoutId != prefLayoutId
                    || _state.value.filter.boardBrand != prefBoardBrand
                // Load/reload board data (placements once, boardSize + layoutId on change)
                if (count > 0) {
                    // Keep layout filter + brand in sync with preferences.
                    if (_state.value.filter.layoutId != prefLayoutId
                        || _state.value.filter.boardBrand != prefBoardBrand
                    ) {
                        val moonBoardAngles = MoonBoardVariant
                            .fromLayoutId(prefLayoutId.toLong())?.angles.orEmpty()
                        // A board switch must also re-seed the angle from prefs
                        // (MoonBoard pin to 40°, fixed-angle gym wall) — keeping
                        // the previous board's in-memory angle would query at an
                        // angle the new board may not have at all.
                        _state.update { it.copy(filter = it.filter.copy(
                            angle = prefAngle,
                            layoutId = prefLayoutId,
                            boardBrand = prefBoardBrand,
                            moonBoardAngles = moonBoardAngles,
                        )) }
                    }
                    // FEAT-031: placements are namespaced by board_brand; reload
                    // them (with the active brand) on a board change, not just
                    // once — otherwise an Aurora board reuses Kilter placements.
                    if (_state.value.placements.isEmpty() || needsBoardReload) {
                        // Scope to the active layout's sets (FEAT-031) — the
                        // unfiltered set mixes in other layouts/products (e.g.
                        // Tension TB2 holds bleeding onto the TB1 Full Wall).
                        val placements = PerfLogger.traceQuery("getPlacementsForLayout") {
                            boardRepository.getPlacementsForLayout(prefSizeId, prefLayoutId, prefBoardBrand)
                        }.associate { it.placementId.toInt() to it }
                        _state.update { it.copy(placements = placements) }
                        PerfLogger.milestone("BoardBrowserVM placements loaded (${placements.size})")
                    }
                    if (needsBoardReload && !isMoonBoard) {
                        val boardSize = PerfLogger.traceQuery("getProductSize") {
                            boardRepository.getProductSize(prefSizeId, prefBoardBrand)
                        }
                        val boardImages = boardRepository.getBoardImages(prefSizeId, prefLayoutId, prefBoardBrand)
                        // Hold-set leg of the always-on fit filter: which of the
                        // layout's hold sets the configured size does NOT carry
                        // (e.g. Homewall Mainline lacks the Auxiliary set).
                        // Computed once per board-config change; 0 = filter off
                        // (no size configured / no set data — stay lenient).
                        val hsmMask = if (boardSize == null) 0L else HoldSetMask.excludedMask(
                            layoutSetIds = boardRepository.getHoldSetIdsForLayout(prefLayoutId, prefBoardBrand),
                            sizeSetIds = boardRepository.getHoldSetIdsForLayoutSize(prefLayoutId, prefSizeId, prefBoardBrand),
                        )
                        _state.update { it.copy(boardSize = boardSize, boardImages = boardImages, hsmExcludedMask = hsmMask) }
                    } else if (needsBoardReload) {
                        // MoonBoard: clear any stale Kilter board image/size so
                        // the browse list doesn't carry over Kilter geometry.
                        // (No Aurora set data either → hsm filter off.)
                        _state.update { it.copy(boardSize = null, boardImages = emptyList(), hsmExcludedMask = 0L) }
                    }
                }
                _state.update { it.copy(climbCount = count, hasBoardData = count > 0) }
                if ((countChanged || force || dataChanged || creatorDirty || setterFilterApplied || needsBoardReload) && count > 0) {
                    searchClimbs()
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
                countChanged
            }
            if (changed) zoneManager.recompute()
        }
    }

    private fun persistFilters() {
        val f = _state.value.filter
        viewModelScope.safeLaunch(TAG) {
            userPreferences.setBoardFilters(
                angle = f.angle, minGrade = f.minGradeIndex, maxGrade = f.maxGradeIndex,
                minAscensionists = f.minAscensionists, sortField = f.sortField.name,
                sortDirection = f.sortDirection.name, statusFilter = serializeStatusFilter(f.statusFilter),
                climbType = f.climbTypeFilter.name, benchmarkOnly = f.benchmarkOnly,
                originFilter = f.originFilter.name,
                myClimbsOnly = f.myClimbsOnly,
                ungradedOnly = f.ungradedOnly,
            )
        }
    }

    // --- Slider state updates (visual feedback only, no DB query) ---

    fun setAngle(angle: Int) {
        val rounded = ((angle + 2) / 5) * 5
        _state.update { it.copy(filter = it.filter.copy(angle = rounded)) }
    }

    fun setGradeRange(minIndex: Int, maxIndex: Int) {
        val min = minIndex.coerceIn(0, GradeConverter.MAX_INDEX)
        val max = maxIndex.coerceIn(0, GradeConverter.MAX_INDEX)
        _state.update { it.copy(filter = it.filter.copy(
            minGradeIndex = min.coerceAtMost(max),
            maxGradeIndex = max.coerceAtLeast(min)
        )) }
    }

    fun setMinAscensionists(count: Int) {
        _state.update { it.copy(filter = it.filter.copy(minAscensionists = count.coerceAtLeast(0))) }
    }

    /** Called when a slider is released — persist + search. */
    fun commitFilterChange() {
        persistFilters()
        searchClimbs()
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(filter = it.filter.copy(searchQuery = query)) }
        searchClimbs()
    }

    fun updateSortField(field: ClimbSortField) {
        // Unconditional: a fresh RANDOM pick must re-roll even when RANDOM
        // is already selected, and leaving RANDOM frees the dead shuffle.
        invalidateRandomCache()
        _state.update { it.copy(filter = it.filter.copy(sortField = field)) }
        persistFilters()
        searchClimbs()
    }

    fun toggleSortDirection() {
        _state.update { s ->
            val next = if (s.filter.sortDirection == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
            s.copy(filter = s.filter.copy(sortDirection = next))
        }
        persistFilters()
        searchClimbs()
    }

    /** Toggle one status bucket in/out of the multi-select status filter. */
    fun toggleStatusFilter(status: ClimbStatusFilter) {
        _state.update { s ->
            val cur = s.filter.statusFilter
            val next = if (status in cur) cur - status else cur + status
            s.copy(filter = s.filter.copy(statusFilter = next))
        }
        persistFilters()
        searchClimbs()
    }

    /** Zero-results empty state: reset every result-hiding browse filter to
     *  its default in one tap, keeping the board identity (brand / layout /
     *  angle / size — those define WHAT board is browsed, not a filter). The
     *  hold filter is cleared via [clearHoldSelection], which also re-runs
     *  the search. */
    fun clearAllBrowseFilters() {
        _state.update { s ->
            s.copy(filter = s.filter.copy(
                minGradeIndex = BrowserFilterState.DEFAULT_MIN_GRADE_INDEX,
                maxGradeIndex = BrowserFilterState.DEFAULT_MAX_GRADE_INDEX,
                minAscensionists = 0,
                searchQuery = "",
                statusFilter = emptySet(),
                climbTypeFilter = ClimbTypeFilter.BOULDER,
                benchmarkOnly = false,
                originFilter = OriginFilter.ALL,
                myClimbsOnly = false,
                ungradedOnly = false,
            ))
        }
        persistFilters()
        clearHoldSelection()
    }

    /** Manual catalogue load for the active board from the browser's
     *  "catalogue missing" empty state. Explicit user intent — the sync
     *  manager's manual path deliberately bypasses the WiFi auto-load gate. */
    fun loadActiveBoardCatalogue() {
        syncManager.loadBoardCatalogue(BoardBrand.fromWire(_state.value.filter.boardBrand))
    }

    /** Clear the status filter (the "Alle" chip) — empty set = no constraint. */
    fun clearStatusFilter() {
        if (_state.value.filter.statusFilter.isEmpty()) return
        _state.update { it.copy(filter = it.filter.copy(statusFilter = emptySet())) }
        persistFilters()
        searchClimbs()
    }

    fun updateClimbTypeFilter(filter: ClimbTypeFilter) {
        _state.update { it.copy(filter = it.filter.copy(climbTypeFilter = filter)) }
        persistFilters()
        searchClimbs()
    }

    fun updateBenchmarkFilter(enabled: Boolean) {
        _state.update { it.copy(filter = it.filter.copy(benchmarkOnly = enabled)) }
        persistFilters()
        searchClimbs()
    }

    fun updateOriginFilter(filter: OriginFilter) {
        _state.update { it.copy(filter = it.filter.copy(originFilter = filter)) }
        persistFilters()
        searchClimbs()
    }

    /** "Eigene Climbs" toggle. Persisted alongside the other filter prefs so
     *  the user lands back on their filtered view across app restarts —
     *  fresh installs default to OFF (full catalog) via the BoardFilterSnapshot
     *  fallback, so a brand-new account never opens to an empty list. */
    fun updateMyClimbsFilter(enabled: Boolean) {
        _state.update { it.copy(filter = it.filter.copy(myClimbsOnly = enabled)) }
        persistFilters()
        searchClimbs()
    }

    /** "Nur unbewertete (Projekte)" toggle — when on, the browse list shows
     *  ONLY ungraded climbs and the grade slider is inert (see
     *  [BrowserFilterState.ungradedOnly]). */
    fun updateUngradedOnlyFilter(enabled: Boolean) {
        _state.update { it.copy(filter = it.filter.copy(ungradedOnly = enabled)) }
        persistFilters()
        searchClimbs()
    }

    private suspend fun ensureStatusLoaded() {
        if (!statusLoaded) {
            sentUuids = PerfLogger.traceQuery("getUserSentClimbUuids") {
                personalBoardRepo.getUserSentClimbUuids()
            }
            attemptedUuids = PerfLogger.traceQuery("getUserAttemptedClimbUuids") {
                personalBoardRepo.getUserAttemptedClimbUuids()
            }
            statusLoaded = true
            PerfLogger.milestone("Status UUIDs loaded (sent=${sentUuids.size}, attempted=${attemptedUuids.size})")
        }
    }

    private suspend fun ensureHiddenLoaded() {
        if (!hiddenLoaded) {
            hiddenUuids = PerfLogger.traceQuery("getIgnoredClimbUuids") {
                personalBoardRepo.getIgnoredClimbUuids()
            }
            hiddenLoaded = true
            PerfLogger.milestone("Ignored UUIDs loaded (hidden=${hiddenUuids.size})")
        }
    }

    /** Always-on ignore filter — drops climbs the user marked "ignored" so
     *  they never get suggested. Applied last in every fetchFiltered branch
     *  and to the random picker. No-op (returns the input) until the ignored
     *  set has loaded or when nothing is ignored. */
    private fun applyHiddenFilter(climbs: List<ClimbWithStats>): List<ClimbWithStats> =
        if (hiddenUuids.isEmpty()) climbs else climbs.filterNot { it.uuid in hiddenUuids }

    /** Client-side multi-select status filter (OR-union over the selected
     *  disjoint buckets). Empty selection = no constraint. */
    private fun applyStatusFilter(climbs: List<ClimbWithStats>, statuses: Set<ClimbStatusFilter>): List<ClimbWithStats> {
        if (statuses.isEmpty()) return climbs
        return climbs.filter { statusOf(it.uuid) in statuses }
    }

    /** The single disjoint bucket a climb falls into. `attemptedUuids` already
     *  excludes sends (the `EXCEPT` in `getUserAttemptedClimbUuids`), so the
     *  three checks are mutually exclusive. */
    private fun statusOf(uuid: String): ClimbStatusFilter = when {
        uuid in sentUuids -> ClimbStatusFilter.SENT
        uuid in attemptedUuids -> ClimbStatusFilter.ATTEMPTED
        else -> ClimbStatusFilter.NEW
    }

    /** True when the selection can be served by a direct UUID query — a
     *  non-empty subset of {SENT, ATTEMPTED}. NEW is the unbounded complement
     *  of the logged sets, so any selection containing it must page-scan. */
    private fun isDirectUuidStatus(statuses: Set<ClimbStatusFilter>): Boolean =
        statuses.isNotEmpty() && ClimbStatusFilter.NEW !in statuses

    /** Union of the logged-UUID sets for a direct-UUID selection. The two sets
     *  are disjoint, so this is just their concatenation. */
    private fun directStatusUuids(statuses: Set<ClimbStatusFilter>): Set<String> {
        val out = HashSet<String>(sentUuids.size + attemptedUuids.size)
        if (ClimbStatusFilter.SENT in statuses) out += sentUuids
        if (ClimbStatusFilter.ATTEMPTED in statuses) out += attemptedUuids
        return out
    }

    private fun applyBenchmarkFilter(climbs: List<ClimbWithStats>, benchmarkOnly: Boolean): List<ClimbWithStats> {
        return if (benchmarkOnly) climbs.filter { it.benchmarkDifficulty > 0.0 } else climbs
    }

    private fun applyOriginFilter(climbs: List<ClimbWithStats>, filter: OriginFilter): List<ClimbWithStats> =
        BrowserOriginFilter.apply(climbs, filter)

    private var firstContentReported = false

    fun searchClimbs() {
        if (!_state.value.hasBoardData) return

        searchJob?.cancel()
        searchJob = viewModelScope.safeLaunch(TAG) {
            val hasExisting = _state.value.climbs.isNotEmpty()
            _state.update { it.copy(
                isLoading = !hasExisting,
                isLoadingMore = hasExisting,
                error = null
            ) }
            try {
                val filter = _state.value.filter
                val (results, newDbOffset, dbExhausted) = withContext(Dispatchers.IO) {
                    if (filter.statusFilter.isNotEmpty()) ensureStatusLoaded()
                    ensureHiddenLoaded()
                    PerfLogger.traceQuery("searchClimbs.fetchFiltered") {
                        fetchFiltered(filter, dbOffset = 0)
                    }
                }
                // Show results IMMEDIATELY — don't block on count query
                _state.update { it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    climbs = results,
                    dbOffset = newDbOffset,
                    canLoadMore = !dbExhausted
                ) }
                // Signal first meaningful content
                if (!firstContentReported && results.isNotEmpty()) {
                    firstContentReported = true
                    PerfLogger.milestone("FIRST CONTENT: ${results.size} climbs loaded")
                    PerfLogger.logMemory("first-content")
                    PerfLogger.reportStartupTimeline()
                }
                // Fire-and-forget: resolve count in separate coroutine (non-blocking)
                viewModelScope.safeLaunch(TAG) {
                    val count = resolveCount(filter, newDbOffset)
                    _state.update { it.copy(filteredCount = count) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message) }
            }
        }
    }

    /**
     * Returns the filtered count, using cache when possible.
     * - Sort field/direction don't affect count → cache hit
     * - SENT/ATTEMPTED: [directCount] = total matching climbs from direct UUID query
     * - NEW/UNSENT: estimate from DB count minus in-memory sets
     * - Only angle/grade/ascensionists/searchQuery/climbType trigger a DB COUNT
     */
    private suspend fun resolveCount(filter: BrowserFilterState, directCount: Int = 0): Long {
        // MY-CLIMBS FILTER: directCount is the total after client-side
        // filtering in the my-climbs branch of fetchFiltered.
        if (filter.myClimbsOnly) {
            return directCount.toLong()
        }

        // HOLD FILTER: directCount is accurate from getClimbsByUuids
        val hs = _state.value.holdSearch
        if (hs.holdFilterActive && hs.holdFilterUuids.isNotEmpty()) {
            return directCount.toLong()
        }

        // ORIGIN SHORT-CIRCUITS (CRUXCOACH / BOARDSESH): fetchFiltered pulls
        // the whole origin-scoped set and returns its exact size as
        // directCount. Without this the count falls through to the
        // unconstrained DB count below (~190K) instead of the handful of
        // origin-scoped rows actually shown.
        if (filter.originFilter == OriginFilter.CRUXCOACH ||
            filter.originFilter == OriginFilter.BOARDSESH) {
            return directCount.toLong()
        }

        // DIRECT-UUID statuses (subset of {SENT, ATTEMPTED}): directCount is the
        // exact total from getClimbsByUuids over the unioned UUID set.
        if (isDirectUuidStatus(filter.statusFilter)) {
            return directCount.toLong()
        }

        // Build a key from count-affecting fields only (not sort).
        // ungradedOnly swaps the whole grade predicate (impossible range +
        // IS NULL leg), so it changes the count even at identical indices.
        val countKey = "${filter.angle}|${filter.minGradeIndex}|${filter.maxGradeIndex}|${filter.ungradedOnly}|" +
            "${filter.minAscensionists}|${filter.searchQuery}|${filter.climbTypeFilter}|${filter.benchmarkOnly}"

        // Fetch DB count only if count-affecting filters changed
        if (countKey != cachedCountKey) {
            cachedDbCount = withContext(Dispatchers.IO) { fetchDbCount(filter) }
            cachedCountKey = countKey
        }

        // Reaching here means the selection is empty (Alle) or includes NEW
        // (the direct-UUID early-return above handled the rest). The three
        // buckets are disjoint, so the count is the sum of the selected ones.
        // (NEW is approximated as DB-count minus the global logged sets — the
        // same estimate the single-select NEW/UNSENT presets always used.)
        val statuses = filter.statusFilter
        if (statuses.isEmpty()) return cachedDbCount
        var count = 0L
        if (ClimbStatusFilter.SENT in statuses) count += sentUuids.size
        if (ClimbStatusFilter.ATTEMPTED in statuses) count += attemptedUuids.size
        if (ClimbStatusFilter.NEW in statuses) count += (cachedDbCount - sentUuids.size - attemptedUuids.size)
        return count
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasBoardData || s.isLoadingMore || !s.canLoadMore) return

        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val (nextFiltered, newDbOffset, dbExhausted) = withContext(Dispatchers.IO) {
                    fetchFiltered(s.filter, dbOffset = s.dbOffset)
                }
                _state.update { it.copy(
                    isLoadingMore = false,
                    climbs = it.climbs + nextFiltered,
                    dbOffset = newDbOffset,
                    canLoadMore = !dbExhausted
                ) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isLoadingMore = false, error = e.message) }
            }
        }
    }

    /**
     * Fetches DB pages until PAGE_SIZE filtered results are collected, scan limit
     * reached, or DB exhausted. Returns (filteredResults, newDbOffset, dbExhausted).
     *
     * For SENT/ATTEMPTED: queries climb_browse directly by UUID set (small, fast).
     * For ALL/NEW/UNSENT: page-scans (high hit rate, most climbs match).
     */
    private suspend fun fetchFiltered(f: BrowserFilterState, dbOffset: Int): Triple<List<ClimbWithStats>, Int, Boolean> {
        // MY-CLIMBS FILTER: short-circuit the paginated browse path. We pull
        // every climb authored by the local pubkey on this layout in one
        // call and apply remaining filters client-side. Drafts (source=
        // 'local') saved at any angle stay visible regardless of the
        // current angle slider. dbExhausted=true on first page so the
        // infinite-scroll trigger doesn't keep firing.
        if (f.myClimbsOnly) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
            if (pubkey.isNullOrBlank()) return Triple(emptyList(), 0, true)
            val all = boardRepository.getOwnClimbsForBrowse(pubkey, f.layoutId, f.angle, f.boardBrand)
            val nameFiltered = if (f.searchQuery.isBlank()) all
                else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val originFiltered = applyOriginFilter(benchFiltered, f.originFilter)
            val sorted = sortInKotlin(applyHiddenFilter(originFiltered), f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // CRUXCOACH ORIGIN FILTER: short-circuit pagination. The default
        // browse path fetches PAGE_SIZE rows from a 190K-row Kilter
        // catalogue and post-filters down to cruxcoach-side climbs;
        // a community climb without sends (quality_average = NULL)
        // sorts to the end of the unconstrained query and effectively
        // disappears from the visible top pages. Pull the entire
        // cruxcoach-side set in one query (small total — community
        // climbs scale into the low thousands at most), then sort
        // client-side just like the my-climbs branch above.
        if (f.originFilter == OriginFilter.CRUXCOACH) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f)
            val all = boardRepository.getCruxCoachClimbs(
                f.layoutId, f.boardBrand, f.angle, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
                selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = gb.showUngraded
            )
            val nameFiltered = if (f.searchQuery.isBlank()) all
                else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val sorted = sortInKotlin(applyHiddenFilter(benchFiltered), f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // BOARDSESH ORIGIN FILTER: same short-circuit as CRUXCOACH above.
        // BoardSesh-imported climbs have quality_average=NULL and 0 sends,
        // so the paginated browse path sorts them past the visible pages of
        // the 190K-row catalogue and they vanish. Pull the whole boardsesh
        // set (a couple hundred rows) in one query and sort client-side.
        if (f.originFilter == OriginFilter.BOARDSESH) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f)
            // BoardSesh imports are inherently ungraded — selecting the
            // provenance chip IS the explicit opt-in, so the IS NULL escape
            // stays on unconditionally here (in normal mode: graded imports
            // within the slider range + all ungraded ones; in ungraded-only
            // mode the impossible range leaves exactly the ungraded set).
            val all = boardRepository.getBoardSeshClimbs(
                f.layoutId, f.boardBrand, f.angle, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
                selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = true
            )
            val nameFiltered = if (f.searchQuery.isBlank()) all
                else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val sorted = sortInKotlin(applyHiddenFilter(benchFiltered), f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // HOLD FILTER: direct UUID query with hold-matched UUIDs
        val hs = _state.value.holdSearch
        if (hs.holdFilterActive && hs.holdFilterUuids.isNotEmpty()) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            // getClimbsByUuids carries the plain range predicate (no
            // :showUngraded escape): in ungraded-only mode the impossible
            // range yields no rows — logically right, a range-only query
            // can never represent "unknown grade".
            val gb = gradeBounds(f)
            val all = boardRepository.getClimbsByUuids(
                hs.holdFilterUuids, f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter
            )
            val filtered = applyOriginFilter(applyBenchmarkFilter(applyStatusFilter(all, f.statusFilter), f.benchmarkOnly), f.originFilter)
            val sorted = sortInKotlin(applyHiddenFilter(filtered), f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // DIRECT-UUID statuses (non-empty subset of {SENT, ATTEMPTED}): query
        // the union of the relevant logged-UUID sets directly — no page scan.
        // The sets are disjoint, so the union needs no dedup and the count is
        // exact (sorted.size).
        if (isDirectUuidStatus(f.statusFilter)) {
            val uuids = directStatusUuids(f.statusFilter)
            if (uuids.isEmpty()) return Triple(emptyList(), 0, true)
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f)
            val all = boardRepository.getClimbsByUuids(
                uuids, f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter
            )
            val filtered = applyOriginFilter(applyBenchmarkFilter(all, f.benchmarkOnly), f.originFilter)
            val sorted = sortInKotlin(applyHiddenFilter(filtered), f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // ALLE (empty selection): no client-side status filtering (benchmark/origin only)
        if (f.statusFilter.isEmpty()) {
            val rawPage = fetchPage(f, dbOffset)
            val filtered = applyHiddenFilter(applyOriginFilter(applyBenchmarkFilter(rawPage, f.benchmarkOnly), f.originFilter))
            return Triple(filtered, dbOffset + rawPage.size, rawPage.size < PAGE_SIZE)
        }

        // Selection includes NEW (the unbounded complement of the logged sets):
        // page-scan with client-side filtering (high hit rate — most climbs are new)
        val collected = mutableListOf<ClimbWithStats>()
        var currentOffset = dbOffset
        repeat(MAX_STATUS_SCAN_PAGES) {
            val page = fetchPage(f, currentOffset)
            if (page.isEmpty()) return Triple(collected, currentOffset, true)
            currentOffset += page.size
            collected.addAll(applyHiddenFilter(applyOriginFilter(applyBenchmarkFilter(applyStatusFilter(page, f.statusFilter), f.benchmarkOnly), f.originFilter)))
            // Return EVERYTHING collected, not collected.take(PAGE_SIZE):
            // currentOffset has already advanced past the source rows of any
            // overflow, so truncating here would drop those climbs forever
            // (loadMore resumes from currentOffset). Callers tolerate
            // >PAGE_SIZE results (searchClimbs assigns, loadMore appends).
            if (collected.size >= PAGE_SIZE) return Triple(collected, currentOffset, false)
            if (page.size < PAGE_SIZE) return Triple(collected, currentOffset, true)
        }
        // Scan cap hit mid-DB: a continuation, not exhaustion — deeper pages
        // may still match. dbExhausted=false keeps infinite scroll alive (the
        // near-bottom trigger re-fires loadMore, which resumes the scan at
        // currentOffset); the true end is reported by the in-loop checks.
        return Triple(collected, currentOffset, false)
    }

    private fun sortInKotlin(
        climbs: List<ClimbWithStats>, field: ClimbSortField, dir: SortDirection
    ): List<ClimbWithStats> = boardBrowserSortInKotlin(climbs, field, dir)

    // Selected board's product_size_id (0 = none configured → the
    // "fits my board" SQL predicate is inert). Same edge-box rule the
    // map / canRenderClimbOnSize use, so browser ⇄ map stay consistent.
    private fun selSizeId(): Int = _state.value.boardSize?.id?.toInt() ?: 0

    // Hold-set leg of the same always-on filter (hsm bitmask, see
    // HoldSetMask). 0 = inert, exactly like selSizeId()'s 0 sentinel.
    private fun hsmMask(): Long = _state.value.hsmExcludedMask

    private suspend fun fetchPage(f: BrowserFilterState, offset: Int): List<ClimbWithStats> {
        if (f.sortField == ClimbSortField.RANDOM && f.searchQuery.isBlank()) {
            return fetchRandomPage(f, offset)
        }
        return if (f.searchQuery.isNotBlank()) {
            PerfLogger.traceQuery("searchClimbsByName(offset=$offset)") {
                boardRepository.searchClimbsByName(f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.sortField, f.sortDirection, PAGE_SIZE, offset, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask())
            }
        } else {
            val gb = gradeBounds(f)
            PerfLogger.traceQuery("searchClimbsSorted(offset=$offset)") {
                boardRepository.searchClimbsSorted(f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.sortField, f.sortDirection, PAGE_SIZE, offset, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = gb.showUngraded)
            }
        }
    }

    // RANDOM browse mode. Page 1: a fast SQL random sample (browseRandom),
    // served immediately so the list never blocks on the full enumeration.
    // Pages 2+: a single Kotlin shuffle of every *other* matching UUID,
    // built in the background (randomCacheJob) and awaited only when the
    // user actually scrolls past page 1. Excluding page 1's UUIDs from the
    // shuffle is what makes the combined scroll duplicate- and gap-free.
    // getClimbsByUuids does not preserve input order, so we re-key by uuid.
    private suspend fun fetchRandomPage(f: BrowserFilterState, offset: Int): List<ClimbWithStats> {
        val gb = gradeBounds(f)
        val minDiff = gb.minDiff
        val maxDiff = gb.maxDiff
        val sel = selSizeId()
        val hm = hsmMask()
        val su = gb.showUngraded
        // boardBrand must be part of the key: layout ids collide across brands
        // (every board's Original layout is id 1) and sel can be 0 on both
        // sides of a board switch (MoonBoard / not-yet-imported catalogue), so
        // an angle|layout|sel-identical switch would otherwise serve the
        // previous brand's cached page-1 + shuffle. hm (hold-set mask) changes
        // the match set the same way sel does, so it is keyed too; ditto su +
        // the bounds, which together also encode the ungraded-only mode (it
        // swaps the result set to exactly the NULL-grade rows, so toggling it
        // must re-roll the shuffle).
        val key = "${f.boardBrand}|${f.angle}|${f.layoutId}|$minDiff|$maxDiff|${f.minAscensionists}|${f.climbTypeFilter}|$sel|$hm|$su"

        if (key != randomKey) {
            randomKey = key
            randomPage1 = null
            randomCacheJob?.cancel()
            randomCacheJob = null
        }

        if (offset == 0) {
            randomPage1?.let { return it }
            val page1 = PerfLogger.traceQuery("randomPage1(sql)") {
                boardRepository.searchClimbsSorted(
                    f.angle, f.layoutId, f.boardBrand, minDiff, maxDiff, f.minAscensionists,
                    ClimbSortField.RANDOM, SortDirection.DESC, PAGE_SIZE, 0,
                    f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = su
                )
            }
            randomPage1 = page1
            val page1Uuids = page1.mapTo(HashSet()) { it.uuid }
            randomCacheJob = viewModelScope.async(Dispatchers.IO) {
                val all = PerfLogger.traceQuery("randomUuids(bg load)") {
                    boardRepository.getAllBrowseMatchingUuids(
                        f.angle, f.layoutId, f.boardBrand, minDiff, maxDiff, f.minAscensionists,
                        f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = su
                    )
                }
                val rest = all.filterNot { it in page1Uuids }.shuffled(Random.Default)
                Log.i("BoardBrowserVM", "random sort: bg shuffle ready, ${rest.size} climbs after page 1 (key=$key)")
                rest
            }
            return page1
        }

        // Pages 2+ index into the background shuffle, which sits "after"
        // page 1 — so subtract page 1's size to translate scroll offset to
        // shuffle index. await() blocks only until the bg job finishes.
        val cache = randomCacheJob?.await() ?: return emptyList()
        val cacheIdx = offset - (randomPage1?.size ?: 0)
        if (cacheIdx < 0 || cacheIdx >= cache.size) return emptyList()
        val slice = cache.subList(cacheIdx, minOf(cacheIdx + PAGE_SIZE, cache.size))
        val climbs = PerfLogger.traceQuery("randomPage(uuid×${slice.size})") {
            boardRepository.getClimbsByUuids(slice, f.angle)
        }
        val byUuid = climbs.associateBy { it.uuid }
        return slice.mapNotNull { byUuid[it] }
    }

    private fun fetchDbCount(f: BrowserFilterState): Long {
        return PerfLogger.traceQuery("fetchDbCount") {
            if (f.searchQuery.isNotBlank()) {
                if (f.benchmarkOnly) boardRepository.countBenchmarkSearchClimbs(f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask())
                else boardRepository.countSearchClimbs(f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask())
            } else {
                val gb = gradeBounds(f)
                if (f.benchmarkOnly) boardRepository.countBenchmarkFilteredClimbs(f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = gb.showUngraded)
                else boardRepository.countFilteredClimbs(f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = gb.showUngraded)
            }
        }
    }

    // --- Random climb ---

    data class RandomClimbEvent(val uuid: String, val id: Int)

    private val _randomClimbEvent = MutableStateFlow<RandomClimbEvent?>(null)
    val randomClimbEvent: StateFlow<RandomClimbEvent?> = _randomClimbEvent.asStateFlow()
    private var randomEventCounter = 0

    fun clearRandomClimb() { _randomClimbEvent.value = null }

    fun pickRandomClimb() {
        fetchRandomClimb { uuid -> _randomClimbEvent.value = RandomClimbEvent(uuid, ++randomEventCounter) }
    }

    fun addRandomClimbToQueue() {
        fetchRandomClimb { uuid -> sessionQueueManager.addClimb(uuid, _state.value.filter.angle) }
    }

    private fun fetchRandomClimb(onResult: (String) -> Unit) {
        val f = _state.value.filter
        val count = _state.value.filteredCount
        if (count <= 0) return
        viewModelScope.safeLaunch(TAG) {
            val uuid = withContext(Dispatchers.IO) {
                // filteredCount/list already exclude ignored climbs, but the
                // random-offset query hits the raw DB match set — so re-roll a
                // few times to avoid landing the dice on an ignored climb.
                // Bounded: a filter whose matches are nearly all ignored still
                // returns promptly via the last non-null candidate.
                ensureHiddenLoaded()
                var result: String? = null
                var fallback: String? = null
                var rolls = 0
                while (rolls < RANDOM_PICK_MAX_ROLLS && result == null) {
                    rolls++
                    val candidate = pickOneAtOffset(f, Random.nextInt(count.toInt()))
                    if (candidate != null) {
                        fallback = candidate
                        if (candidate !in hiddenUuids) result = candidate
                    }
                }
                result ?: fallback
            }
            if (uuid != null) {
                onResult(uuid)
            }
        }
    }

    /** Fetch the single climb at [offset] in the current filter's ordering
     *  (one row, no client-side filtering). Caller handles ignore re-rolls. */
    private fun pickOneAtOffset(f: BrowserFilterState, offset: Int): String? {
        val climb = if (f.searchQuery.isNotBlank()) {
            boardRepository.searchClimbsByName(
                f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.sortField, f.sortDirection,
                limit = 1, offset = offset, climbType = f.climbTypeFilter,
                selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask()
            )
        } else {
            val gb = gradeBounds(f)
            boardRepository.searchClimbsSorted(
                f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists,
                f.sortField, f.sortDirection, limit = 1, offset = offset,
                climbType = f.climbTypeFilter, selProductSizeId = selSizeId(), hsmExcludedMask = hsmMask(), showUngraded = gb.showUngraded
            )
        }
        return climb.firstOrNull()?.uuid
    }

    // --- Hold search & heatmap ---

    private var holdSearchJob: Job? = null
    private var heatmapJob: Job? = null

    fun toggleHoldSearchSheet() {
        _state.update { it.copy(holdSearch = it.holdSearch.copy(showSheet = !it.holdSearch.showSheet)) }
    }

    fun toggleHoldSelection(placementId: Int) {
        _state.update { s ->
            val current = s.holdSearch.selectedHolds
            val next = if (placementId in current) current - placementId else current + placementId
            s.copy(holdSearch = s.holdSearch.copy(selectedHolds = next))
        }
        holdSearchJob?.cancel()
        holdSearchJob = viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(holdSearch = it.holdSearch.copy(isSearching = true)) }
            val count = withContext(Dispatchers.IO) { countHoldMatches() }
            _state.update { it.copy(holdSearch = it.holdSearch.copy(matchCount = count, isSearching = false)) }
        }
    }

    fun clearHoldSelection() {
        _state.update { s ->
            s.copy(holdSearch = s.holdSearch.copy(
                selectedHolds = emptySet(), matchCount = 0,
                holdFilterActive = false, holdFilterUuids = emptySet()
            ))
        }
        searchClimbs()
    }

    fun applyHoldFilter() {
        val selected = _state.value.holdSearch.selectedHolds
        if (selected.isEmpty()) return
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(holdSearch = it.holdSearch.copy(isSearching = true)) }
            val uuids = withContext(Dispatchers.IO) { findUuidsMatchingAllHolds(selected) }
            _state.update { it.copy(holdSearch = it.holdSearch.copy(
                holdFilterActive = true,
                holdFilterUuids = uuids,
                isSearching = false,
                showSheet = false
            )) }
            searchClimbs()
        }
    }

    fun setHeatmapMode(mode: HeatmapMode) {
        heatmapJob?.cancel()
        _state.update { it.copy(holdSearch = it.holdSearch.copy(heatmapMode = mode, heatmapData = emptyMap())) }
        if (mode == HeatmapMode.OFF) return
        heatmapJob = viewModelScope.safeLaunch(TAG) {
            val heatmap = withContext(Dispatchers.Default) { computeHeatmap(mode) }
            if (_state.value.holdSearch.heatmapMode == mode) {
                _state.update { it.copy(holdSearch = it.holdSearch.copy(heatmapData = heatmap)) }
            }
        }
    }

    private fun countHoldMatches(): Int {
        val selected = _state.value.holdSearch.selectedHolds
        if (selected.isEmpty()) return 0
        return findUuidsMatchingAllHolds(selected).size
    }

    private fun findUuidsMatchingAllHolds(selectedHolds: Set<Int>): Set<String> {
        if (selectedHolds.isEmpty()) return emptySet()
        val start = System.currentTimeMillis()
        val f = _state.value.filter
        // Range-only predicate (no :showUngraded escape): in ungraded-only
        // mode the impossible bounds match nothing — consistent with the
        // browse list, whose ungraded rows a range query can never reach.
        val gb = gradeBounds(f)
        val patterns = selectedHolds.map { HoldHeatmapComputer.holdLikePattern(it) }
        // Single DB pass: load frames once, check all hold patterns per row
        val result = boardRepository.searchClimbUuidsByAllHolds(
            patterns, f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter
        )
        val elapsed = System.currentTimeMillis() - start
        PerfLogger.log("🔍 holdSearch: ${patterns.size} patterns, ${result.size} matches in ${elapsed}ms")
        return result
    }

    private fun computeHeatmap(mode: HeatmapMode): Map<Int, Float> {
        val f = _state.value.filter
        // Same range-only situation as the hold search above: ungraded-only
        // mode yields an empty heatmap rather than one of climbs the list
        // doesn't show.
        val gb = gradeBounds(f)
        val minDiff = gb.minDiff
        val maxDiff = gb.maxDiff
        val frameRows = when (mode) {
            HeatmapMode.PERSONAL -> {
                // Scope "my sends" to the active board (brand + layout) like the
                // other modes: an ascent logged on a different board must not
                // tint this board's grid.
                personalBoardRepo.getUserAscentsAll()
                    .filter { it.boardBrand == f.boardBrand && it.layoutId == f.layoutId.toLong() }
                    .map { it.climbFrames }
            }
            else -> {
                boardRepository.getAllFramesForHeatmap(
                    f.angle, f.layoutId, f.boardBrand, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter
                ).map { it.frames }
            }
        }
        val rawHeatmap = when (mode) {
            HeatmapMode.START -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.START)
            HeatmapMode.HAND -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.HAND)
            HeatmapMode.FOOT -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FOOT)
            HeatmapMode.FINISH -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FINISH)
            else -> HoldHeatmapComputer.computeGlobalHeatmap(frameRows)
        }
        return HoldHeatmapComputer.normalizeHeatmap(rawHeatmap)
    }

    // --- Easter animations ---

    private var animationJob: Job? = null
    private val _isAnimating = MutableStateFlow(false)
    val isAnimating: StateFlow<Boolean> = _isAnimating.asStateFlow()

    private val _animationDebug = MutableStateFlow("")
    val animationDebug: StateFlow<String> = _animationDebug.asStateFlow()

    fun playEasterAnimation(type: EasterAnimation) {
        animationJob?.cancel()
        animationJob = viewModelScope.safeLaunch(TAG) {
            // The LED-grid easter animation is Kilter-only: the frames are
            // built from Kilter's LED grid (getLedGrid defaults to the kilter
            // partition), and the other Aurora boards reuse Kilter-numbered
            // product_size ids — the same grid would light wrong/garbled LEDs
            // on a Tension etc., and a MoonBoard can't parse Aurora packets
            // at all. Gate on the CONNECTED board's brand (not the pref): a
            // stale active-board pref must not push Kilter frames — or the
            // trailing clearBoard() — to a different board still on the link.
            if (bleConnection.connectedBoardBrand.value != BoardBrand.KILTER) return@safeLaunch
            _isAnimating.value = true
            try {
                val animSizeId = userPreferences.boardProductSizeId.first()
                val grid = withContext(Dispatchers.IO) {
                    boardRepository.getLedGrid(animSizeId)
                }
                _animationDebug.value = "grid=${grid.size}"
                if (grid.isEmpty()) {
                    return@safeLaunch
                }
                val frames = when (type) {
                    EasterAnimation.EGG -> BoardEasterAnimations.easterEgg(grid)
                }
                _animationDebug.value = "grid=${grid.size} frames=${frames.size} leds/f=${frames.firstOrNull()?.leds?.size ?: 0}"
                if (frames.isEmpty() || frames.all { it.leds.isEmpty() }) {
                    return@safeLaunch
                }
                repeat(3) {
                    for (frame in frames) {
                        // sendRawLeds encodes with the CONNECTED board's
                        // encoder (correct apiLevel), not a hardcoded @3 one.
                        bleConnection.sendRawLeds(frame.leds)
                        delay(250)
                    }
                }
                bleConnection.clearBoard()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Pre-fix any throw from the BLE-emit loop / SQL read /
                // packet encoder skipped the catch (only had try/finally)
                // and propagated to the parent scope. The finally still
                // ran the animating flag down, but the parent scope was
                // poisoned with the uncaught exception.
                android.util.Log.w("BoardBrowserVM", "easter animation failed", e)
            } finally {
                _isAnimating.value = false
            }
        }
    }

    fun stopAnimation() {
        animationJob?.cancel()
        animationJob = null
        _isAnimating.value = false
        viewModelScope.safeLaunch(TAG) {
            runCatching { bleConnection.clearBoard() }
                .onFailure { android.util.Log.w("BoardBrowserVM", "stopAnimation clearBoard failed", it) }
        }
    }

    // --- Queue sharing ---

    /** Start BLE sharing for the current queue (only if climb sharing is enabled). */
    fun startQueueSharing() {
        if (bleShareManager.uiState.value.sharingEnabled) {
            gattBridge.startSharing()
        }
    }

    fun stopQueueSharing() {
        gattBridge.stopSharing()
    }

    fun sendPrev() = gattBridge.sendPrev()
    fun sendNext() = gattBridge.sendNext()

    fun joinNearbySession(session: NearbySession) {
        val device = session.device
        if (device != null) {
            gattBridge.joinSession(device)
        } else {
            Log.w("BoardBrowserVM", "Cannot join session: no BluetoothDevice available")
        }
    }

    // --- Session timer ---

    fun startSession() = sessionManager.startSession()

    fun endSession(): com.cruxcoach.data.repository.Board_sessions? {
        val session = sessionManager.endSession()
        if (session != null) {
            viewModelScope.safeLaunch(TAG) {
                val gradeScale = userPreferences.gradeScale.first()
                val summary = withContext(Dispatchers.IO) {
                    val ascents = personalBoardRepo.getUserAscentsBetween(
                        session.startedAt, session.endedAt ?: session.startedAt
                    )
                    val sends = ascents.filter { it.isSend }
                    val diffs = sends.mapNotNull { it.difficultyAverage }
                    val zones = zoneManager.zones.value
                    val counts = diffs.groupBy { zones.classify(it) }

                    val hardestSend = sends.maxByOrNull { it.difficultyAverage ?: 0.0 }
                    val flashCount = sends.count { it.bidCount <= 1L }
                    val uniqueClimbs = ascents.map { it.climbUuid }.distinct().size

                    val gradePyramid = sends
                        .filter { it.difficultyAverage != null }
                        .groupBy { KilterGradeMapper.difficultyToVScale(it.difficultyAverage!!) }
                        .map { (vGrade, list) ->
                            BoardGradePyramidEntry(
                                grade = GradeDisplayHelper.formatGrade(vGrade, gradeScale),
                                count = list.size,
                                difficultyInt = list.first().difficultyAverage!!.toInt()
                            )
                        }
                        .sortedBy { it.difficultyInt }

                    EnhancedSessionSummary(
                        warmupCount = counts[IntensityZone.WARMUP]?.size ?: 0,
                        optimalCount = counts[IntensityZone.OPTIMAL]?.size ?: 0,
                        limitCount = counts[IntensityZone.LIMIT]?.size ?: 0,
                        sessionType = IntensityZoneEngine.classifySession(diffs, zones),
                        hardestSendGrade = hardestSend?.difficultyAverage?.let {
                            GradeDisplayHelper.formatDifficulty(it, gradeScale)
                        },
                        hardestSendName = hardestSend?.climbName,
                        flashCount = flashCount,
                        totalSends = sends.size,
                        totalAttempts = ascents.count { !it.isSend },
                        uniqueClimbs = uniqueClimbs,
                        gradeDistribution = gradePyramid
                    )
                }
                _lastSessionSummary.value = summary
            }
        }
        return session
    }

    private val _lastSessionSummary = MutableStateFlow<EnhancedSessionSummary?>(null)
    val lastSessionSummary: StateFlow<EnhancedSessionSummary?> = _lastSessionSummary.asStateFlow()

    fun clearSessionSummary() {
        _lastSessionSummary.value = null
    }

    fun toggleSessionPause() {
        val s = sessionManager.state.value
        if (s.isPaused) sessionManager.resumeSession()
        else sessionManager.pauseSession()
    }

    // --- Rest timer ---

    fun cancelRestTimer() = sessionManager.cancelRestTimer()
    fun dismissRestTimerFinished() = sessionManager.dismissRestTimerFinished()
}
