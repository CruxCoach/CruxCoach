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
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.SessionGattBridge
import com.cruxcoach.android.data.SessionQueueManager
import com.cruxcoach.android.data.UserPreferences
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

enum class ClimbStatusFilter { ALL, SENT, ATTEMPTED, NEW, UNSENT }

/** Provenance filter — corresponds to the `origin` column on `climbs`. */
enum class OriginFilter { ALL, CRUXCOACH, KILTER }

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
    val minGradeIndex: Int = 0,
    val maxGradeIndex: Int = 14,
    val minAscensionists: Int = 0,
    val searchQuery: String = "",
    val sortField: ClimbSortField = ClimbSortField.ASCENSIONISTS,
    val sortDirection: SortDirection = SortDirection.DESC,
    val statusFilter: ClimbStatusFilter = ClimbStatusFilter.ALL,
    val climbTypeFilter: ClimbTypeFilter = ClimbTypeFilter.BOULDER,
    val benchmarkOnly: Boolean = false,
    val originFilter: OriginFilter = OriginFilter.ALL,
    /** When true, restrict the browser list to climbs authored by the local
     *  user's Nostr pubkey (drafts + published). Bypasses angle/grade/asc
     *  filters at fetch time so drafts saved at any angle remain visible. */
    val myClimbsOnly: Boolean = false,
)

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
    val canLoadMore: Boolean = false,
    val dbOffset: Int = 0,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val zones: IntensityZones? = null,
    val error: String? = null,
    val easterAnimationsUnlocked: Boolean = false,
    val placements: Map<Int, com.cruxcoach.data.repository.BoardPlacement> = emptyMap(),
    val boardSize: com.cruxcoach.data.repository.BoardSize? = null,
    val boardImages: List<com.cruxcoach.data.repository.BoardImage> = emptyList(),
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
    // the underlying match set and must not force a re-roll.
    private var randomKey: String? = null
    private var randomPage1: List<ClimbWithStats>? = null
    private var randomCacheJob: Deferred<List<String>>? = null

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_STATUS_SCAN_PAGES = 10
    }

    init {
        PerfLogger.milestone("BoardBrowserVM.init START")
        viewModelScope.launch {
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
                val statusFilter = try { ClimbStatusFilter.valueOf(snap.statusFilter) } catch (_: Exception) { ClimbStatusFilter.ALL }
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
                    )
                ) }
                filtersLoaded = true
                PerfLogger.milestone("BoardBrowserVM filters applied, calling refreshBoardData")
                refreshBoardData()
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        // Auto-refresh board data when a sync completes
        viewModelScope.launch {
            var lastGen = syncManager.state.value.syncGeneration
            syncManager.state.collect { syncState ->
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
        viewModelScope.launch {
            val ps = BoardConstants.KILTER_KNOWN_SIZES
                .firstOrNull { it.id.toInt() == productSizeId }
            val layoutId = BoardConstants.layoutIdForProduct(
                ps?.productId?.toInt() ?: BoardConstants.KILTER_PRODUCT_ID
            )
            userPreferences.setBoardLayoutId(layoutId)
            userPreferences.setBoardProductSizeId(productSizeId)
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

        // Immediate UI update: remove logged climbs from status-filtered list
        if (changedUuids.isNotEmpty()) {
            val filter = _state.value.filter.statusFilter
            if (filter == ClimbStatusFilter.NEW || filter == ClimbStatusFilter.UNSENT) {
                _state.update { it.copy(
                    climbs = it.climbs.filter { climb -> climb.uuid !in changedUuids }
                ) }
            }
        }

        // Invalidate caches so new ascents/bids are picked up
        statusLoaded = false
        cachedCountKey = ""
        viewModelScope.launch {
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
                // FEAT-027: a MoonBoard layout has no Aurora product_size /
                // board_images rows — the Kilter-only lookups below would just
                // return empty. Skip them entirely for a MoonBoard board.
                val isMoonBoard = prefBoardBrand == "moonboard"
                val needsBoardReload = _state.value.boardSize == null || _state.value.boardSize!!.id.toInt() != prefSizeId
                    || _state.value.filter.layoutId != prefLayoutId
                // Load/reload board data (placements once, boardSize + layoutId on change)
                if (count > 0) {
                    // Keep layout filter + brand in sync with preferences.
                    if (_state.value.filter.layoutId != prefLayoutId
                        || _state.value.filter.boardBrand != prefBoardBrand
                    ) {
                        val moonBoardAngles = MoonBoardVariant
                            .fromLayoutId(prefLayoutId.toLong())?.angles.orEmpty()
                        _state.update { it.copy(filter = it.filter.copy(
                            layoutId = prefLayoutId,
                            boardBrand = prefBoardBrand,
                            moonBoardAngles = moonBoardAngles,
                        )) }
                    }
                    if (_state.value.placements.isEmpty()) {
                        val placements = PerfLogger.traceQuery("getAllPlacements") {
                            boardRepository.getAllPlacements()
                        }.associate { it.placementId.toInt() to it }
                        _state.update { it.copy(placements = placements) }
                        PerfLogger.milestone("BoardBrowserVM placements loaded (${placements.size})")
                    }
                    if (needsBoardReload && !isMoonBoard) {
                        val boardSize = PerfLogger.traceQuery("getProductSize") {
                            boardRepository.getProductSize(prefSizeId)
                        }
                        val boardImages = boardRepository.getBoardImages(prefSizeId, prefLayoutId)
                        _state.update { it.copy(boardSize = boardSize, boardImages = boardImages) }
                    } else if (needsBoardReload) {
                        // MoonBoard: clear any stale Kilter board image/size so
                        // the browse list doesn't carry over Kilter geometry.
                        _state.update { it.copy(boardSize = null, boardImages = emptyList()) }
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
        viewModelScope.launch {
            userPreferences.setBoardFilters(
                angle = f.angle, minGrade = f.minGradeIndex, maxGrade = f.maxGradeIndex,
                minAscensionists = f.minAscensionists, sortField = f.sortField.name,
                sortDirection = f.sortDirection.name, statusFilter = f.statusFilter.name,
                climbType = f.climbTypeFilter.name, benchmarkOnly = f.benchmarkOnly,
                originFilter = f.originFilter.name,
                myClimbsOnly = f.myClimbsOnly,
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

    fun updateStatusFilter(filter: ClimbStatusFilter) {
        _state.update { it.copy(filter = it.filter.copy(statusFilter = filter)) }
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

    private fun applyStatusFilter(climbs: List<ClimbWithStats>, filter: ClimbStatusFilter): List<ClimbWithStats> {
        return when (filter) {
            ClimbStatusFilter.ALL -> climbs
            ClimbStatusFilter.SENT -> climbs.filter { it.uuid in sentUuids }
            ClimbStatusFilter.ATTEMPTED -> climbs.filter { it.uuid in attemptedUuids }
            ClimbStatusFilter.NEW -> climbs.filter { it.uuid !in sentUuids && it.uuid !in attemptedUuids }
            ClimbStatusFilter.UNSENT -> climbs.filter { it.uuid !in sentUuids }
        }
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
        searchJob = viewModelScope.launch {
            val hasExisting = _state.value.climbs.isNotEmpty()
            _state.update { it.copy(
                isLoading = !hasExisting,
                isLoadingMore = hasExisting,
                error = null
            ) }
            try {
                val filter = _state.value.filter
                val (results, newDbOffset, dbExhausted) = withContext(Dispatchers.IO) {
                    if (filter.statusFilter != ClimbStatusFilter.ALL) ensureStatusLoaded()
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
                viewModelScope.launch {
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

        // SENT/ATTEMPTED: directCount is the total from getClimbsByUuids (accurate)
        if (filter.statusFilter == ClimbStatusFilter.SENT ||
            filter.statusFilter == ClimbStatusFilter.ATTEMPTED) {
            return directCount.toLong()
        }

        // Build a key from count-affecting fields only (not sort)
        val countKey = "${filter.angle}|${filter.minGradeIndex}|${filter.maxGradeIndex}|" +
            "${filter.minAscensionists}|${filter.searchQuery}|${filter.climbTypeFilter}|${filter.benchmarkOnly}"

        // Fetch DB count only if count-affecting filters changed
        if (countKey != cachedCountKey) {
            cachedDbCount = withContext(Dispatchers.IO) { fetchDbCount(filter) }
            cachedCountKey = countKey
        }

        return when (filter.statusFilter) {
            ClimbStatusFilter.ALL -> cachedDbCount
            ClimbStatusFilter.NEW -> cachedDbCount - sentUuids.size - attemptedUuids.size
            ClimbStatusFilter.UNSENT -> cachedDbCount - sentUuids.size
            else -> cachedDbCount
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasBoardData || s.isLoadingMore || !s.canLoadMore) return

        viewModelScope.launch {
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
            val all = boardRepository.getOwnClimbsForBrowse(pubkey, f.layoutId, f.angle)
            val nameFiltered = if (f.searchQuery.isBlank()) all
                else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val originFiltered = applyOriginFilter(benchFiltered, f.originFilter)
            val sorted = sortInKotlin(originFiltered, f.sortField, f.sortDirection)
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
            val french = _state.value.gradeScale == GradeScale.FRENCH
            val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
            val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
            val all = boardRepository.getCruxCoachClimbs(
                f.layoutId, f.angle, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter,
                selProductSizeId = selSizeId()
            )
            val nameFiltered = if (f.searchQuery.isBlank()) all
                else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val sorted = sortInKotlin(benchFiltered, f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // HOLD FILTER: direct UUID query with hold-matched UUIDs
        val hs = _state.value.holdSearch
        if (hs.holdFilterActive && hs.holdFilterUuids.isNotEmpty()) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val french = _state.value.gradeScale == GradeScale.FRENCH
            val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
            val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
            val all = boardRepository.getClimbsByUuids(
                hs.holdFilterUuids, f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter
            )
            val filtered = applyOriginFilter(applyBenchmarkFilter(applyStatusFilter(all, f.statusFilter), f.benchmarkOnly), f.originFilter)
            val sorted = sortInKotlin(filtered, f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // SENT / ATTEMPTED: direct UUID query — no page scanning needed
        if (f.statusFilter == ClimbStatusFilter.SENT || f.statusFilter == ClimbStatusFilter.ATTEMPTED) {
            val uuids = if (f.statusFilter == ClimbStatusFilter.SENT) sentUuids else attemptedUuids
            if (uuids.isEmpty()) return Triple(emptyList(), 0, true)
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val french = _state.value.gradeScale == GradeScale.FRENCH
            val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
            val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
            val all = boardRepository.getClimbsByUuids(
                uuids, f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter
            )
            val filtered = applyOriginFilter(applyBenchmarkFilter(all, f.benchmarkOnly), f.originFilter)
            val sorted = sortInKotlin(filtered, f.sortField, f.sortDirection)
            return Triple(sorted.take(PAGE_SIZE), sorted.size, sorted.size <= PAGE_SIZE)
        }

        // ALL: no client-side filtering needed (except benchmark)
        if (f.statusFilter == ClimbStatusFilter.ALL) {
            val rawPage = fetchPage(f, dbOffset)
            val filtered = applyOriginFilter(applyBenchmarkFilter(rawPage, f.benchmarkOnly), f.originFilter)
            return Triple(filtered, dbOffset + rawPage.size, rawPage.size < PAGE_SIZE)
        }

        // NEW / UNSENT: page-scan with client-side filtering (high hit rate)
        val collected = mutableListOf<ClimbWithStats>()
        var currentOffset = dbOffset
        repeat(MAX_STATUS_SCAN_PAGES) {
            val page = fetchPage(f, currentOffset)
            if (page.isEmpty()) return Triple(collected, currentOffset, true)
            currentOffset += page.size
            collected.addAll(applyOriginFilter(applyBenchmarkFilter(applyStatusFilter(page, f.statusFilter), f.benchmarkOnly), f.originFilter))
            if (collected.size >= PAGE_SIZE) return Triple(collected.take(PAGE_SIZE), currentOffset, false)
            if (page.size < PAGE_SIZE) return Triple(collected, currentOffset, true)
        }
        return Triple(collected, currentOffset, true)
    }

    private fun sortInKotlin(
        climbs: List<ClimbWithStats>, field: ClimbSortField, dir: SortDirection
    ): List<ClimbWithStats> = boardBrowserSortInKotlin(climbs, field, dir)

    // Selected board's product_size_id (0 = none configured → the
    // "fits my board" SQL predicate is inert). Same edge-box rule the
    // map / canRenderClimbOnSize use, so browser ⇄ map stay consistent.
    private fun selSizeId(): Int = _state.value.boardSize?.id?.toInt() ?: 0

    private suspend fun fetchPage(f: BrowserFilterState, offset: Int): List<ClimbWithStats> {
        if (f.sortField == ClimbSortField.RANDOM && f.searchQuery.isBlank()) {
            return fetchRandomPage(f, offset)
        }
        return if (f.searchQuery.isNotBlank()) {
            PerfLogger.traceQuery("searchClimbsByName(offset=$offset)") {
                boardRepository.searchClimbsByName(f.searchQuery, f.angle, f.layoutId, f.sortField, f.sortDirection, PAGE_SIZE, offset, f.climbTypeFilter, selProductSizeId = selSizeId())
            }
        } else {
            val french = _state.value.gradeScale == GradeScale.FRENCH
            val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
            val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
            PerfLogger.traceQuery("searchClimbsSorted(offset=$offset)") {
                boardRepository.searchClimbsSorted(f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.sortField, f.sortDirection, PAGE_SIZE, offset, f.climbTypeFilter, selProductSizeId = selSizeId())
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
        val french = _state.value.gradeScale == GradeScale.FRENCH
        val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
        val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
        val sel = selSizeId()
        val key = "${f.angle}|${f.layoutId}|$minDiff|$maxDiff|${f.minAscensionists}|${f.climbTypeFilter}|$sel"

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
                    f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists,
                    ClimbSortField.RANDOM, SortDirection.DESC, PAGE_SIZE, 0,
                    f.climbTypeFilter, selProductSizeId = sel
                )
            }
            randomPage1 = page1
            val page1Uuids = page1.mapTo(HashSet()) { it.uuid }
            randomCacheJob = viewModelScope.async(Dispatchers.IO) {
                val all = PerfLogger.traceQuery("randomUuids(bg load)") {
                    boardRepository.getAllBrowseMatchingUuids(
                        f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists,
                        f.climbTypeFilter, selProductSizeId = sel
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
                if (f.benchmarkOnly) boardRepository.countBenchmarkSearchClimbs(f.searchQuery, f.angle, f.layoutId, f.climbTypeFilter, selProductSizeId = selSizeId())
                else boardRepository.countSearchClimbs(f.searchQuery, f.angle, f.layoutId, f.climbTypeFilter, selProductSizeId = selSizeId())
            } else {
                val french = _state.value.gradeScale == GradeScale.FRENCH
                val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
                val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
                if (f.benchmarkOnly) boardRepository.countBenchmarkFilteredClimbs(f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter, selProductSizeId = selSizeId())
                else boardRepository.countFilteredClimbs(f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter, selProductSizeId = selSizeId())
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
        val randomOffset = Random.nextInt(count.toInt())
        viewModelScope.launch {
            val uuid = withContext(Dispatchers.IO) {
                val climb = if (f.searchQuery.isNotBlank()) {
                    boardRepository.searchClimbsByName(
                        f.searchQuery, f.angle, f.layoutId, f.sortField, f.sortDirection,
                        limit = 1, offset = randomOffset, climbType = f.climbTypeFilter,
                        selProductSizeId = selSizeId()
                    )
                } else {
                    val french = _state.value.gradeScale == GradeScale.FRENCH
                    val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
                    val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
                    boardRepository.searchClimbsSorted(
                        f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists,
                        f.sortField, f.sortDirection, limit = 1, offset = randomOffset,
                        climbType = f.climbTypeFilter, selProductSizeId = selSizeId()
                    )
                }
                climb.firstOrNull()?.uuid
            }
            if (uuid != null) {
                onResult(uuid)
            }
        }
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
        holdSearchJob = viewModelScope.launch {
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
        viewModelScope.launch {
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
        heatmapJob = viewModelScope.launch {
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
        val french = _state.value.gradeScale == GradeScale.FRENCH
        val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
        val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
        val patterns = selectedHolds.map { HoldHeatmapComputer.holdLikePattern(it) }
        // Single DB pass: load frames once, check all hold patterns per row
        val result = boardRepository.searchClimbUuidsByAllHolds(
            patterns, f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter
        )
        val elapsed = System.currentTimeMillis() - start
        PerfLogger.log("🔍 holdSearch: ${patterns.size} patterns, ${result.size} matches in ${elapsed}ms")
        return result
    }

    private fun computeHeatmap(mode: HeatmapMode): Map<Int, Float> {
        val f = _state.value.filter
        val french = _state.value.gradeScale == GradeScale.FRENCH
        val minDiff = KilterGradeMapper.indexToFilterMin(f.minGradeIndex, french)
        val maxDiff = KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, french)
        val frameRows = when (mode) {
            HeatmapMode.PERSONAL -> {
                val ascents = personalBoardRepo.getUserAscentsAll()
                ascents.map { it.climbFrames }
            }
            else -> {
                boardRepository.getAllFramesForHeatmap(
                    f.angle, f.layoutId, minDiff, maxDiff, f.minAscensionists, f.climbTypeFilter
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
        animationJob = viewModelScope.launch {
            _isAnimating.value = true
            try {
                val animSizeId = userPreferences.boardProductSizeId.first()
                val grid = withContext(Dispatchers.IO) {
                    boardRepository.getLedGrid(animSizeId)
                }
                _animationDebug.value = "grid=${grid.size}"
                if (grid.isEmpty()) {
                    return@launch
                }
                val frames = when (type) {
                    EasterAnimation.EGG -> BoardEasterAnimations.easterEgg(grid)
                }
                _animationDebug.value = "grid=${grid.size} frames=${frames.size} leds/f=${frames.firstOrNull()?.leds?.size ?: 0}"
                if (frames.isEmpty() || frames.all { it.leds.isEmpty() }) {
                    return@launch
                }
                val encoder = com.cruxcoach.domain.board.BoardPacketEncoder(3)
                repeat(3) {
                    for (frame in frames) {
                        val chunks = encoder.encodeClimb(frame.leds)
                        bleConnection.sendRawChunks(chunks)
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
        viewModelScope.launch {
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
            viewModelScope.launch {
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
