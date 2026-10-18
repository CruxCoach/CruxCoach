package com.cruxcoach.app.browse

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BrowseLoadState { LOADING, LOADING_MORE, READY, FAILED }

/** Swift maps these to localized text. */
enum class BrowseError { NONE, QUERY_FAILED }

data class BrowseBoardConfig(
    val boardBrand: String = "kilter",
    val layoutId: Int = 1,
    val productSizeId: Int = 0,
    val boardSize: BoardSize? = null,
    val boardImages: List<BoardImage> = emptyList(),
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    /** False = show the "load this board's catalogue" empty state. */
    val hasCatalogue: Boolean = true,
    val hasAnyBoardData: Boolean = false,
)

data class RandomClimbEvent(val uuid: String, val id: Int)

data class BoardBrowserUiState(
    val board: BrowseBoardConfig = BrowseBoardConfig(),
    val core: BoardBrowserState = BoardBrowserState(),
    val climbs: List<ClimbWithStats> = emptyList(),
    /** Exact number of matches, or -1 while it has not been resolved yet. */
    val totalCount: Long = -1,
    val canLoadMore: Boolean = false,
    val loadState: BrowseLoadState = BrowseLoadState.LOADING,
    val error: BrowseError = BrowseError.NONE,
    val excludesSent: Boolean = false,
    val randomClimb: RandomClimbEvent? = null,
    /** The hold search filters the list (Android `holdFilterActive`): the browser shows its banner. */
    val holdFilterActive: Boolean = false,
    /** Climbs the applied hold search matches on its own (Android `holdFilterUuids.size`). */
    val holdFilterMatches: Int = 0,
    /** Android `ungradedAvailable`: climbs the "without a grade" switch would show; -1 until counted. */
    val ungradedAvailable: Long = -1,
) {
    val filter: BrowserFilterState get() = core.filter
}

/** Filter fields the filter sheet edits in one go; identity fields (board/angle/search/sort) are separate calls. */
data class BrowseFilterEdit(
    val minGradeIndex: Int,
    val maxGradeIndex: Int,
    val minAscensionists: Int,
    val climbTypeFilter: com.cruxcoach.data.repository.ClimbTypeFilter,
    val benchmarkOnly: Boolean,
    val originFilter: OriginFilter,
    val myClimbsOnly: Boolean,
    val ungradedOnly: Boolean,
    val quantumRuleMask: Long,
    val quantumOverlapFilter: com.cruxcoach.domain.board.QuantumOverlapFilter,
)

/**
 * Swift-facing board browser. Methods are plain calls from the main thread; all
 * repository work runs on [io] and results are published on [main]. Never throws.
 */
class BoardBrowserPresenter(
    private val boardRepository: BoardRepository,
    personalBoardRepo: PersonalBoardRepository,
    keyValues: KeyValueStore,
    ownPubkeyHex: () -> String?,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val prefs = BrowsePreferences(keyValues)
    private val pipeline = BoardBrowsePipeline(
        boardRepository, personalBoardRepo,
        ownPubkeyHex = { try { ownPubkeyHex() } catch (e: Exception) { null } },
    )
    private val scope = CoroutineScope(SupervisorJob() + main)
    // The pipeline caches are not thread-safe and SQLite has one connection anyway.
    private val dbLock = Mutex()
    private val gate = BrowseRequestGate()
    private var searchJob: Job? = null
    private var countJob: Job? = null
    private var boardJob: Job? = null
    private var dbOffset = 0
    private var randomCounter = 0
    private var filtersLoaded = false
    // Board-specific: old placement ids must not survive a board switch.
    private var holdFilterActive = false
    private var holdFilterUuids: Set<String> = emptySet()
    private var moonMaskKey: String? = null
    private var moonMask = 0L

    private val _state = MutableStateFlow(BoardBrowserUiState())
    val state: StateFlow<BoardBrowserUiState> = _state.asStateFlow()

    /** Swift observation hook; returns a handle whose close() stops it. */
    fun watch(onState: (BoardBrowserUiState) -> Unit): BrowseWatch {
        val job = scope.launch { _state.collect { onState(it) } }
        return BrowseWatch(job)
    }

    fun start() = reloadBoard(force = true)

    fun close() = scope.cancel()

    // --- board ---

    /** Persist a new active board and reload. Null size/angle keep the stored values. */
    fun switchBoard(boardBrand: String, layoutId: Int, productSizeId: Int?, angle: Int?) {
        guarded { prefs.setBoardSelection(boardBrand, layoutId, productSizeId, angle) }
        reloadBoard(force = true)
    }

    /** Re-read prefs + user data (e.g. on return from the detail screen) keeping the scroll depth. */
    fun refresh() {
        pipeline.invalidateUserData()
        reloadBoard(force = false)
    }

    /**
     * The stored board is not the one shown: another screen (the detail's guided fix) changed it.
     * False before the first load, which reads the stored board anyway.
     */
    internal fun storedBoardDiffers(): Boolean {
        if (!filtersLoaded) return false
        val sel = try { prefs.boardSelection() } catch (e: Exception) { return false }
        val s = _state.value
        return s.filter.boardBrand != sel.boardBrand || s.filter.layoutId != sel.layoutId ||
            s.board.productSizeId != sel.productSizeId
    }

    private fun reloadBoard(force: Boolean) {
        boardJob?.cancel()
        boardJob = scope.launch {
            try {
                val sel = prefs.boardSelection()
                val current = _state.value
                val initial = !filtersLoaded
                val switched = initial || current.filter.boardBrand != sel.boardBrand ||
                    current.filter.layoutId != sel.layoutId || current.board.productSizeId != sel.productSizeId
                if (switched) {
                    val brand = BoardBrand.fromWire(sel.boardBrand)
                    // Seed filters from prefs on first load; afterwards only the board identity changes.
                    val base = if (initial) current.core.copy(filter = prefs.loadFilter()) else current.core
                    filtersLoaded = true
                    holdFilterActive = false
                    holdFilterUuids = emptySet()
                    // Atomic: brand/layout/angle land together with a ZEROED mask; the real mask follows below.
                    val chips0 = BoardAnglePicker.chipsFor(brand, sel.layoutId, emptyList())
                    _state.update {
                        it.copy(
                            core = base.onBoardSwitch(BoardAnglePicker.clampAngle(sel.angle, chips0), sel.layoutId, sel.boardBrand, chips0),
                            holdFilterActive = false,
                            holdFilterMatches = 0,
                        )
                    }
                    // Android refreshes the layer state on a switch: the wall did not change while another board was shown.
                    val wall = latestQuantumLayers
                    if (brand == BoardBrand.QUANTUM && wall != null) {
                        _state.update { it.copy(core = it.core.copy(quantumLayers = wall.copy(matchCount = -1))) }
                    }
                    val loaded = withContext(io) { dbLock.withLock { loadBoard(sel, brand) } }
                    _state.update { s ->
                        if (s.filter.boardBrand != sel.boardBrand || s.filter.layoutId != sel.layoutId) s
                        else s.copy(
                            board = loaded.config,
                            core = s.core.copy(
                                hsmExcludedMask = loaded.hsmMask,
                                filter = s.filter.copy(
                                    angleChips = loaded.chips,
                                    angle = BoardAnglePicker.clampAngle(sel.angle, loaded.chips),
                                ),
                            ),
                        )
                    }
                    pipeline.invalidateRandomCache()
                } else if (current.filter.angle != sel.angle && force) {
                    _state.update { it.copy(core = it.core.copy(filter = it.filter.copy(angle = sel.angle))) }
                }
                search(preserveDepth = !switched && !force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadState = BrowseLoadState.FAILED, error = BrowseError.QUERY_FAILED) }
            }
        }
    }

    private class LoadedBoard(val config: BrowseBoardConfig, val hsmMask: Long, val chips: List<Int>)

    private fun loadBoard(sel: BoardSelection, brand: BoardBrand): LoadedBoard {
        val hasData = boardRepository.hasAnyClimbs()
        val hasCatalogue = !hasData || boardRepository.hasClimbsForBrand(sel.boardBrand)
        val supported = if (brand.usesCatalogueAngles) {
            boardRepository.getSupportedAnglesForLayout(sel.layoutId, sel.boardBrand)
        } else emptyList()
        val chips = BoardAnglePicker.chipsFor(brand, sel.layoutId, supported)
        val placements = boardRepository.getPlacementsForLayout(sel.productSizeId, sel.layoutId, sel.boardBrand)
            .associateBy { it.placementId.toInt() }
        var size: BoardSize? = null
        var images: List<BoardImage> = emptyList()
        var mask = 0L
        if (brand.usesAuroraPlacements) {
            size = boardRepository.getProductSize(sel.productSizeId, sel.boardBrand)
            images = boardRepository.getBoardImages(sel.productSizeId, sel.layoutId, sel.boardBrand)
            mask = if (size == null) 0L else HoldSetMask.excludedMask(
                layoutSetIds = boardRepository.getHoldSetIdsForLayout(sel.layoutId, sel.boardBrand),
                sizeSetIds = boardRepository.getHoldSetIdsForLayoutSize(sel.layoutId, sel.productSizeId, sel.boardBrand),
            )
        } else {
            mask = moonBoardMask(MoonBoardVariant.fromBoardSelection(sel.layoutId.toLong(), brand))
        }
        return LoadedBoard(
            BrowseBoardConfig(sel.boardBrand, sel.layoutId, sel.productSizeId, size, images, placements, hasCatalogue, hasData),
            mask, chips,
        )
    }

    // Port of MoonBoardMaskCache without the catalogue-revision key: a positive gate answer
    // is memoised, a negative one is re-asked on the next board load so a later sync is seen.
    private fun moonBoardMask(variant: MoonBoardVariant?): Long {
        if (variant == null) { moonMaskKey = null; moonMask = 0L; return 0L }
        val owned = prefs.moonBoardHoldSets(variant)
        val key = "${variant.name}|${owned.joinToString(",")}"
        if (key == moonMaskKey) return moonMask
        val computed = HoldSetMask.excludedMask(MoonBoardHoldSets.setIdsFor(variant), owned)
        if (computed == 0L) { moonMaskKey = key; moonMask = 0L; return 0L }
        if (!boardRepository.hasMoonBoardHoldSetMask()) return 0L
        moonMaskKey = key; moonMask = computed
        return computed
    }

    // --- filters ---

    /** Kilter slider value (0-70, snapped to 5) or an exact chip value (may be negative). */
    fun setAngle(angle: Int) {
        val chips = _state.value.filter.angleChips
        val next = if (chips.isEmpty()) {
            BoardAnglePicker.snapSliderAngle(angle.coerceIn(BoardAnglePicker.KILTER_MIN_ANGLE, BoardAnglePicker.KILTER_MAX_ANGLE))
        } else BoardAnglePicker.clampAngle(angle, chips)
        edit(persist = true) { it.copy(angle = next) }
    }

    fun setSearch(query: String) = edit(persist = false) { it.copy(searchQuery = query) }

    fun setSort(field: ClimbSortField, direction: SortDirection) {
        // Unconditional: re-picking RANDOM must re-roll, leaving it frees the shuffle.
        pipeline.invalidateRandomCache()
        edit(persist = true, force = true) { it.copy(sortField = field, sortDirection = direction) }
    }

    fun setFilters(e: BrowseFilterEdit) {
        val max = com.cruxcoach.util.GradeConverter.MAX_INDEX
        val lo = e.minGradeIndex.coerceIn(0, max)
        val hi = e.maxGradeIndex.coerceIn(0, max)
        edit(persist = true) { f ->
            val brand = BoardBrand.fromWire(f.boardBrand)
            val quantum = brand == BoardBrand.QUANTUM
            if (f.quantumRuleMask != e.quantumRuleMask) pipeline.invalidateRandomCache()
            f.copy(
                minGradeIndex = lo.coerceAtMost(hi),
                maxGradeIndex = hi.coerceAtLeast(lo),
                minAscensionists = e.minAscensionists.coerceAtLeast(0),
                climbTypeFilter = BoardBrowsePolicy.climbType(brand, e.climbTypeFilter),
                benchmarkOnly = BoardBrowsePolicy.benchmarkOnly(brand, e.benchmarkOnly),
                originFilter = BoardBrowsePolicy.origin(brand, e.originFilter),
                myClimbsOnly = e.myClimbsOnly,
                ungradedOnly = e.ungradedOnly,
                quantumRuleMask = if (quantum) e.quantumRuleMask and 31L else 0L,
                quantumOverlapFilter = if (quantum) e.quantumOverlapFilter else com.cruxcoach.domain.board.QuantumOverlapFilter.OFF,
            )
        }
    }

    fun toggleStatus(status: ClimbStatusFilter) = edit(persist = true) { f ->
        f.copy(statusFilter = if (status in f.statusFilter) f.statusFilter - status else f.statusFilter + status)
    }

    fun clearStatus() = edit(persist = true) { it.copy(statusFilter = emptySet()) }

    fun setExcludeSent(exclude: Boolean) = edit(persist = true) { f ->
        f.copy(statusFilter = statusFilterWithSentExcluded(f.statusFilter, exclude))
    }

    /** Reset every result-hiding filter; the board identity and angle stay. */
    fun clearAllFilters() {
        pipeline.invalidateRandomCache()
        edit(persist = true, force = true) { f ->
            BrowserFilterState(angle = f.angle, layoutId = f.layoutId, boardBrand = f.boardBrand, angleChips = f.angleChips)
        }
    }

    /** The newest wall roster from [followQuantumLayers], re-applied when the browser switches to Quantum. */
    private var latestQuantumLayers: BrowserQuantumLayerState? = null

    /** Whether a layer rack feeds this browser; without one "Fits the wall" cannot know the wall. */
    internal var followsQuantumLayers = false
        private set

    /** Android's browser follows the process-wide layer rack ("Fits the wall"). */
    internal fun followQuantumLayers(layers: kotlinx.coroutines.flow.Flow<BrowserQuantumLayerState>) {
        followsQuantumLayers = true
        scope.launch {
            layers.collect { next ->
                latestQuantumLayers = next
                setQuantumLayers(next.litPlacements, next.layerCount, next.complete)
            }
        }
    }

    /** Lit Quantum placements from the BLE layer manager (controller-confirmed). */
    fun setQuantumLayers(litPlacements: Set<Int>, layerCount: Int, complete: Boolean) {
        val s = _state.value
        if (BoardBrand.fromWire(s.filter.boardBrand) != BoardBrand.QUANTUM) return
        val next = BrowserQuantumLayerState(litPlacements, layerCount, complete)
        if (s.core.quantumLayers.copy(matchCount = -1) == next) return
        _state.update { it.copy(core = it.core.copy(quantumLayers = next)) }
        if (s.filter.quantumOverlapFilter.active) search(preserveDepth = false)
    }

    /**
     * Android `countHoldMatches`: how many climbs the hold search would keep under the current
     * filter, without applying it (the sheet's "N climbs found" before "Filter"). 0 when both are empty.
     */
    internal suspend fun holdMatchCount(selectedHolds: Set<Int>, zone: com.cruxcoach.domain.board.BoardZone?): Int {
        if (selectedHolds.isEmpty() && zone == null) return 0
        val s = _state.value
        val xy = s.board.placements.mapValues { it.value.x to it.value.y }
        return withContext(io) {
            dbLock.withLock { pipeline.findUuidsMatchingHoldFilter(s.filter, context(s), selectedHolds, zone, xy) }
        }.size
    }

    /** Hold/zone search: climbs using ALL [selectedHolds] and lying inside [zone]. Both empty clears the filter. */
    fun applyHoldFilter(selectedHolds: Set<Int>, zone: com.cruxcoach.domain.board.BoardZone?) {
        if (selectedHolds.isEmpty() && zone == null) {
            holdFilterActive = false
            holdFilterUuids = emptySet()
            _state.update { it.copy(holdFilterActive = false, holdFilterMatches = 0) }
            search(preserveDepth = false)
            return
        }
        val s = _state.value
        scope.launch {
            try {
                val xy = s.board.placements.mapValues { it.value.x to it.value.y }
                val uuids = withContext(io) {
                    dbLock.withLock { pipeline.findUuidsMatchingHoldFilter(s.filter, context(s), selectedHolds, zone, xy) }
                }
                holdFilterActive = true
                holdFilterUuids = uuids
                _state.update { it.copy(holdFilterActive = true, holdFilterMatches = uuids.size) }
                search(preserveDepth = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadState = BrowseLoadState.FAILED, error = BrowseError.QUERY_FAILED) }
            }
        }
    }

    private fun edit(persist: Boolean, force: Boolean = false, change: (BrowserFilterState) -> BrowserFilterState) {
        val before = _state.value.filter
        val after = try { change(before) } catch (e: Exception) { before }
        if (after == before && !force) return
        _state.update { it.copy(core = it.core.copy(filter = after), excludesSent = statusFilterExcludesSent(after.statusFilter)) }
        if (persist) guarded { prefs.saveFilter(after) }
        search(preserveDepth = false)
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { /* preference write failures must not reach Swift */ }
    }

    // --- query ---

    private fun context(s: BoardBrowserUiState) = BrowseContext(
        frenchGrades = try { prefs.frenchGrades() } catch (e: Exception) { true },
        boardSizeId = s.board.boardSize?.id?.toInt() ?: 0,
        hsmExcludedMask = s.core.hsmExcludedMask,
        holdFilterActive = holdFilterActive,
        holdFilterUuids = holdFilterUuids,
        quantumLayers = s.core.quantumLayers,
    )

    private fun search(preserveDepth: Boolean) {
        countJob?.cancel()
        val generation = gate.invalidate()
        val targetSize = if (preserveDepth) _state.value.climbs.size else 0
        searchJob?.cancel()
        searchJob = scope.launch {
            val snapshot = _state.value
            _state.update {
                it.copy(
                    loadState = if (it.climbs.isEmpty()) BrowseLoadState.LOADING else BrowseLoadState.LOADING_MORE,
                    totalCount = -1, error = BrowseError.NONE,
                    excludesSent = statusFilterExcludesSent(it.filter.statusFilter),
                )
            }
            try {
                val (rows, offset, exhausted) = withContext(io) {
                    dbLock.withLock { pipeline.search(snapshot.filter, context(snapshot), targetSize) }
                }
                if (!gate.accepts(generation)) return@launch
                dbOffset = offset
                _state.update {
                    it.copy(
                        climbs = rows, canLoadMore = !exhausted, loadState = BrowseLoadState.READY,
                        // An exhausted list IS the exact total; otherwise the debounced count follows.
                        totalCount = if (exhausted) rows.size.toLong() else -1,
                    )
                }
                if (!exhausted) requestCount(generation, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gate.accepts(generation)) {
                    _state.update { it.copy(loadState = BrowseLoadState.FAILED, error = BrowseError.QUERY_FAILED) }
                }
            }
        }
    }

    /**
     * Android `refreshUngradedAvailable`: the filter sheet offers "without a grade" with its count,
     * and disables it where the catalogue grades every climb. A count for another board/angle/type
     * than the one now shown is dropped.
     */
    fun refreshUngradedAvailable() {
        val snapshot = _state.value
        val f = snapshot.filter
        scope.launch {
            try {
                val count = withContext(io) { dbLock.withLock { pipeline.countUngraded(f, context(snapshot)) } }
                val now = _state.value.filter
                if (now.angle == f.angle && now.layoutId == f.layoutId && now.boardBrand == f.boardBrand &&
                    now.climbTypeFilter == f.climbTypeFilter
                ) {
                    _state.update { it.copy(ungradedAvailable = count) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unknown stays -1: the switch then stays usable rather than wrongly disabled.
            }
        }
    }

    private fun requestCount(generation: Long, snapshot: BoardBrowserUiState) {
        countJob?.cancel()
        countJob = scope.launch {
            try {
                delay(BoardBrowsePipeline.COUNT_DEBOUNCE_MS)
                val count = withContext(io) { dbLock.withLock { pipeline.resolveCount(snapshot.filter, context(snapshot)) } }
                if (gate.accepts(generation) && _state.value.filter == snapshot.filter) {
                    _state.update {
                        it.copy(
                            totalCount = count,
                            core = if (snapshot.filter.quantumOverlapFilter.active) {
                                it.core.copy(quantumLayers = it.core.quantumLayers.copy(matchCount = count))
                            } else it.core,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the count unknown (-1) rather than publish a wrong total.
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadState != BrowseLoadState.READY || !s.canLoadMore) return
        val generation = gate.current()
        val offset = dbOffset
        _state.update { it.copy(loadState = BrowseLoadState.LOADING_MORE) }
        scope.launch {
            try {
                val (rows, next, exhausted) = withContext(io) {
                    dbLock.withLock { pipeline.fetchFiltered(s.filter, context(s), offset) }
                }
                // Cancellation cannot stop a running SQLite query: reject stale pages explicitly.
                if (!gate.accepts(generation)) return@launch
                dbOffset = next
                _state.update {
                    val merged = mergeBrowseClimbs(it.climbs, rows)
                    it.copy(
                        climbs = merged, canLoadMore = !exhausted, loadState = BrowseLoadState.READY,
                        totalCount = if (exhausted) merged.size.toLong() else it.totalCount,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gate.accepts(generation)) {
                    _state.update { it.copy(loadState = BrowseLoadState.FAILED, error = BrowseError.QUERY_FAILED) }
                }
            }
        }
    }

    /** Publishes [BoardBrowserUiState.randomClimb]; the id makes repeated picks of one uuid distinct events. */
    fun pickRandom() {
        val s = _state.value
        scope.launch {
            try {
                val uuid = withContext(io) { dbLock.withLock { pipeline.pickRandom(s.filter, context(s), s.climbs) } }
                if (uuid != null) _state.update { it.copy(randomClimb = RandomClimbEvent(uuid, ++randomCounter)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No pick; nothing to report.
            }
        }
    }

    fun consumeRandomClimb() = _state.update { it.copy(randomClimb = null) }

    /**
     * Android `addRandomClimbToQueue`: the same random pick as [pickRandom], handed to [onPicked]
     * with the browsed angle instead of being opened.
     */
    internal fun pickRandomThen(onPicked: (String, Int) -> Unit) {
        val s = _state.value
        scope.launch {
            try {
                val uuid = withContext(io) { dbLock.withLock { pipeline.pickRandom(s.filter, context(s), s.climbs) } }
                if (uuid != null) onPicked(uuid, s.filter.angle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No pick; nothing to report.
            }
        }
    }
}

class BrowseWatch internal constructor(private val job: Job) {
    fun close() = job.cancel()
}
