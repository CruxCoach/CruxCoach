package com.cruxcoach.android.ui.board

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.brand
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.data.repository.ClimbTypeFilter
import java.time.LocalDate
import javax.inject.Inject
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.community.isCommunityPublished
import com.cruxcoach.android.community.normalizeClimbUuid
import com.cruxcoach.android.util.safeLaunch

enum class StatsTimeInterval(@param:androidx.annotation.StringRes val labelResId: Int, val days: Int?) {
    ALL(com.cruxcoach.android.R.string.stats_interval_all, null),
    DAYS_30(com.cruxcoach.android.R.string.stats_interval_30_days, 30),
    DAYS_90(com.cruxcoach.android.R.string.stats_interval_90_days, 90),
    YEAR_1(com.cruxcoach.android.R.string.stats_interval_1_year, 365)
}

data class BoardGradePyramidEntry(
    val grade: String,
    val count: Int,
    val difficultyInt: Int
)

data class AngleDistEntry(
    val angle: Int,
    val count: Int
)

data class TimeBucketEntry(
    val label: String,
    val count: Int
)

data class BoardLogbookStats(
    val hardestGrade: String? = null,
    val hardestDifficultyInt: Int = 0,
    val totalSends: Int = 0,
    val totalAttempts: Int = 0,
    val boulderSends: Int = 0,
    val routeSends: Int = 0,
    val flashRate: Float = 0f,
    val uniqueClimbs: Int = 0,
    val sessionCount: Int = 0,
    val gradePyramid: List<BoardGradePyramidEntry> = emptyList(),
    val angleDistribution: List<AngleDistEntry> = emptyList(),
    val sendsOverTime: List<TimeBucketEntry> = emptyList(),
    val activityMap: Map<LocalDate, Int> = emptyMap(),
    // Extended stats
    val gradeOutcomes: List<GradeOutcomeEntry> = emptyList(),
    val outcomeDistribution: OutcomeDistribution = OutcomeDistribution(0, 0, 0),
    val weeklyVolume: List<WeeklyVolumeEntry> = emptyList(),
    val gradeProgression: List<GradeProgressionPoint> = emptyList(),
    val uniqueClimbsByGrade: List<UniqueClimbEntry> = emptyList(),
    val periodComparison: PeriodComparison? = null,
    val personalRecords: PersonalRecords = PersonalRecords()
)

data class BoardLogbookState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val ascents: List<AscentWithClimb> = emptyList(),
    /** True-flash send uuids over the FULL history (see BoardStatsComputer.trueFlashUuids). */
    val flashUuids: Set<String> = emptySet(),
    val totalCount: Long = 0,
    val canLoadMore: Boolean = false,
    val gradeScale: GradeScale = GradeScale.V_SCALE,
    val hasData: Boolean = false,
    val error: String? = null,
    // Edit dialog
    val showEditDialog: Boolean = false,
    val editingAscentUuid: String? = null,
    val editBidCount: Int = 1,
    val editQuality: Int = 0,
    val editComment: String = "",
    // Delete confirm
    val showDeleteConfirm: String? = null,
    // Multi-select
    val selectedUuids: Set<String> = emptySet(),
    val showBatchDeleteConfirm: Boolean = false,
    // Stats
    val statsInterval: StatsTimeInterval = StatsTimeInterval.ALL,
    val stats: BoardLogbookStats = BoardLogbookStats(),
    val showStatsSheet: Boolean = false,
    val zones: IntensityZones? = null,
    // Chart view selectors
    val gradeChartView: GradeChartView = GradeChartView.PYRAMID,
    val timeChartView: TimeChartView = TimeChartView.SENDS_OVER_TIME,
    val distributionChartView: DistributionChartView = DistributionChartView.ANGLE,
    val customDateFrom: LocalDate? = null,
    val customDateTo: LocalDate? = null,
    // Heatmap (board visualization in stats sheet). Default = PERSONAL
    // ("Meine Sends"); other modes pull frames from the board DB so the
    // user can switch between own / global / role views without leaving
    // their stats screen.
    val placements: Map<Int, com.cruxcoach.data.repository.BoardPlacement> = emptyMap(),
    val boardSize: com.cruxcoach.data.repository.BoardSize? = null,
    val boardImages: List<com.cruxcoach.data.repository.BoardImage> = emptyList(),
    val heatmapMode: HeatmapMode = HeatmapMode.PERSONAL,
    val heatmapData: Map<Int, Float> = emptyMap(),
    // Heatmap board selector (FEAT-039): the EXPLICIT board the hold-heatmap
    // renders on. The hold-id spaces are disjoint across brands/layouts, so a
    // single grid can never aggregate boards — the user picks exactly which
    // board's grid to overlay their ascents on. [heatmapBoardSelection] = null
    // means "Alle" (no specific board): the per-grid heatmap is HIDDEN and only
    // the aggregate stats (counts / pyramid / per-board split) remain. Defaults
    // to the active board on first load so nothing regresses. [heatmapBoardOptions]
    // is enumerated from the picker model, gated to imported brands.
    val heatmapBoardOptions: List<com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption> = emptyList(),
    val heatmapBoardSelection: com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption? = null,
    // Per-board stats split: which board family the stats + heatmap are
    // scoped to. null = all boards combined. Only meaningful (and the
    // selector only shown) when the user has logged on more than one board.
    val boardFilter: String? = null,
    val availableBoardBrands: List<String> = emptyList(),
    // Per-board headline comparison (send count / top grade per board) over the
    // selected interval. Computed across ALL boards (not scoped to boardFilter)
    // so the user can compare them side by side; only surfaced when >1 board.
    val boardComparison: List<BoardComparisonEntry> = emptyList(),
    // Own-Kilter-climb publish gate for logbook entries: NORMALIZED
    // (lowercase, no dashes) climb uuids the connected Kilter account
    // authored that are not yet published to the CruxCoach community.
    // Normalized because ascents store BLE/API uuid spellings that can
    // differ from the canonical board-DB row.
    val ownPublishableClimbUuids: Set<String> = emptySet(),
    /** Climb uuid of an in-flight own-climb publish (disables its button). */
    val ownPublishInProgressUuid: String? = null,
    /** One-shot own-climb publish feedback (snackbar). */
    val ownPublishFeedback: OwnPublishFeedback? = null,
)

/**
 * Resolve a denormalized logbook row to a concrete heatmap board.
 *
 * Rows written before board context was introduced are known to be Kilter
 * rows, so only those may use the historical Original-layout fallback. A
 * missing layout on any other brand means that the concrete board is unknown
 * (for example an ambiguous MoonBoard screen import) and must not be rendered
 * on an arbitrary board generation.
 */
internal fun heatmapLoggedBoardKey(
    boardBrandWire: String,
    layoutId: Long?,
): Pair<String, Int>? {
    val brand = BoardBrand.fromWire(boardBrandWire)
    val concreteLayoutId = layoutId?.toInt()
        ?: if (brand == BoardBrand.KILTER) {
            com.cruxcoach.android.data.BoardConstants.KILTER_ORIGINAL_LAYOUT
        } else {
            return null
        }
    return brand.wireValue to concreteLayoutId
}

@HiltViewModel
class BoardLogbookViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val zoneManager: IntensityZoneManager,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    /** UUID-case fanout (raw → lowercase → uppercase → hyphenated …)
     *  needed because BLE-decoded ascents store uuids upper-case-no-hyphens
     *  but the climbs table writes lowercase canonical form (see 7.sqm).
     *  Without going through the resolver the repair-pass below silently
     *  fails and the user sees blank climb names with the cards still
     *  navigating to the correct detail screen — confusing UX. */
    private val climbNameResolver: com.cruxcoach.android.data.ClimbNameResolver,
    private val ownClimbPublisher: com.cruxcoach.android.community.OwnKilterClimbPublisher,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BoardLogbookState())
    val state: StateFlow<BoardLogbookState> = _state.asStateFlow()

    private var allAscents: List<AscentWithClimb> = emptyList()

    companion object {
        private const val PAGE_SIZE = 50
        private const val TAG = "BoardLogbookVM"
    }

    init {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
                if (allAscents.isNotEmpty()) {
                    recomputeStats()
                }
            }
        }
        viewModelScope.safeLaunch(TAG) {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        // FEAT-039: the heatmap canvas now follows the EXPLICIT board selection,
        // which is seeded in preloadStats (after the logbook loads). loadBoardData
        // is therefore driven from there once a selection exists — not eagerly in
        // init, where no selection is set yet.
        loadAscents()
        refreshOwnPublishable()
    }

    /**
     * Recompute the authorship-gated publish set for logbook entries. A
     * logbook entry gets the publish action ONLY when its climb was
     * authored by the connected Kilter account (identity match on
     * kilter_author_uuid) and hasn't been community-published yet —
     * logged-but-foreign climbs are never publishable.
     */
    private fun refreshOwnPublishable() {
        viewModelScope.safeLaunch(TAG) {
            val publishable = withContext(Dispatchers.IO) {
                ownClimbPublisher.getOwnAuthoredClimbs()
                    .filterNot { it.isCommunityPublished }
                    .map { normalizeClimbUuid(it.uuid) }
                    .toSet()
            }
            _state.update { it.copy(ownPublishableClimbUuids = publishable) }
        }
    }

    /** Publish an own-authored climb straight from its logbook entry. */
    fun publishOwnClimb(climbUuid: String) {
        if (_state.value.ownPublishInProgressUuid != null) return
        _state.update { it.copy(ownPublishInProgressUuid = climbUuid) }
        viewModelScope.safeLaunch(TAG) {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ownClimbPublisher.publish(climbUuid) }
                    .onFailure { Log.w(TAG, "logbook own-climb publish threw uuid=$climbUuid", it) }
                    .getOrNull()
            }
            val feedback = when (outcome) {
                is OwnKilterClimbPublisher.Outcome.Published -> OwnPublishFeedback.Published
                OwnKilterClimbPublisher.Outcome.NotAuthor -> OwnPublishFeedback.NotAuthor
                OwnKilterClimbPublisher.Outcome.NoNostrIdentity -> OwnPublishFeedback.NoNostrIdentity
                OwnKilterClimbPublisher.Outcome.AlreadyPublished -> OwnPublishFeedback.AlreadyPublished
                is OwnKilterClimbPublisher.Outcome.Failed, null -> OwnPublishFeedback.Failed
            }
            _state.update {
                it.copy(ownPublishInProgressUuid = null, ownPublishFeedback = feedback)
            }
            if (feedback == OwnPublishFeedback.Published) refreshOwnPublishable()
        }
    }

    fun consumeOwnPublishFeedback() {
        _state.update { it.copy(ownPublishFeedback = null) }
    }

    /**
     * (brand, layoutId, productSizeId) the stats heatmap renders with —
     * FEAT-039: driven by the user's EXPLICIT heatmap board selection
     * ([BoardLogbookState.heatmapBoardSelection]) rather than the active/default
     * board. Returns null when no specific board is selected ("Alle"): the
     * heatmap cannot aggregate disjoint grids, so callers blank the canvas (the
     * sheet shows a "pick a board" hint instead). The selection is seeded to the
     * active board on first load, so the default behaviour is unchanged.
     */
    private fun resolveHeatmapBoard(): Triple<String, Int, Int>? {
        val sel = _state.value.heatmapBoardSelection ?: return null
        return Triple(sel.brandWire, sel.layoutId, sel.sizeId)
    }

    private fun loadBoardData() {
        viewModelScope.safeLaunch(TAG) {
            try {
                // Brand + layout-scoped placements (FEAT-031/039), resolved from
                // the EXPLICIT heatmap board selection. Defaults to the active
                // board; null (= "Alle") blanks the canvas so a single grid never
                // masquerades as an all-boards aggregate.
                val resolved = resolveHeatmapBoard()
                if (resolved == null) {
                    _state.update {
                        it.copy(placements = emptyMap(), boardSize = null, boardImages = emptyList())
                    }
                    return@safeLaunch
                }
                val (brand, layoutId, sizeId) = resolved
                val (placements, boardSize, boardImages) = withContext(Dispatchers.IO) {
                    Triple(
                        boardRepository.getPlacementsForLayout(sizeId, layoutId, brand).associate { it.placementId.toInt() to it },
                        boardRepository.getProductSize(sizeId, brand),
                        boardRepository.getBoardImages(sizeId, layoutId, brand)
                    )
                }
                _state.update {
                    it.copy(
                        placements = placements,
                        boardSize = boardSize,
                        boardImages = boardImages
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadBoardData failed; placements/heatmap canvas will be empty", e)
            }
        }
    }

    private fun loadAscents() {
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val (ascents, count) = withContext(Dispatchers.IO) {
                    val list = personalBoardRepo.getUserLogbookPage(PAGE_SIZE, 0).toMutableList()
                    val total = personalBoardRepo.countUserLogbook()
                    repairMissingDenormalized(list)
                    list to total
                }
                _state.update { it.copy(
                    isLoading = false,
                    ascents = ascents,
                    totalCount = count,
                    canLoadMore = ascents.size >= PAGE_SIZE,
                    hasData = ascents.isNotEmpty()
                ) }
                // Preload stats data in background so sheet opens instantly
                preloadStats()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun retryInitialLoad() {
        if (_state.value.ascents.isEmpty() && !_state.value.isLoading) loadAscents()
    }

    private fun preloadStats() {
        viewModelScope.safeLaunch(TAG) {
            try {
                val all = withContext(Dispatchers.IO) {
                    personalBoardRepo.getUserLogbookAllLight()
                }
                allAscents = all
                // True-flash set needs the FULL history (prior-session
                // attempts disqualify), so it is derived here, not per page.
                _state.update { it.copy(flashUuids = BoardStatsComputer.trueFlashUuids(all)) }
                // Boards the user has actually logged on — drives whether the
                // per-board stats selector is shown. Kilter first, then the
                // rest, for a stable chip order.
                val brands = all.map { it.boardBrand }.distinct()
                    .sortedBy { if (BoardBrand.fromWire(it) == BoardBrand.KILTER) 0 else 1 }

                // FEAT-039 heatmap board selector. The heatmap plots the user's
                // OWN ascents, so only offer board TYPES they have real logs for
                // (not every imported catalogue, and not the active board if it
                // has no logs). Gate is the set of (brandWire, layoutId) the user
                // has logged on; legacy rows (pre-7.sqm) can carry a null
                // layout_id — those are Kilter-era (multi-board logging postdates
                // the denormalization), so default them to Kilter Original.
                val activeBrand = userPreferences.boardBrand.first()
                val activeLayoutId = userPreferences.boardLayoutId.first()
                val activeSizeId = userPreferences.boardProductSizeId.first()
                val loggedBoards: Set<Pair<String, Int>> = all
                    .mapNotNull { heatmapLoggedBoardKey(it.boardBrand, it.layoutId) }
                    .toSet()
                // The whole gate is pure in-memory now: no catalogue probe
                // (hasClimbsForBrand) and no layout lookup (getDefaultLayoutForBrand
                // was a GROUP BY over the unindexed climbs.board_brand — ~6s per
                // single-layout brand, ~12s total here). Single-layout boards take
                // their layout straight from the user's logged ascent, so opening
                // the stats sheet runs ZERO board-DB queries on the gate path.
                // Render the ACTIVE board on the user's CONFIGURED size, not the
                // enumerator's representative default: a Kilter 12x16 Super Wide
                // has different hold columns + image than a 12x12 with kickboard,
                // so the user's own board should match their settings. Other boards
                // keep their default size; MoonBoard is size-less (sizeId 0) so it
                // is never overridden.
                val activeBrandWire = BoardBrand.fromWire(activeBrand).wireValue
                val options = com.cruxcoach.android.data.BoardConstants
                    .heatmapBoardOptions(loggedBoards)
                    .map { opt ->
                        if (opt.brandWire == activeBrandWire && opt.layoutId == activeLayoutId &&
                            opt.brandWire != BoardBrand.MOONBOARD.wireValue && activeSizeId > 0
                        ) opt.copy(sizeId = activeSizeId) else opt
                    }
                val defaultSelection = resolveDefaultHeatmapSelection(
                    options, activeBrand, activeLayoutId
                )

                _state.update {
                    // Clamp a stale filter: if the selected board no longer has
                    // any ascents (e.g. all its logs were deleted) drop back to
                    // "all" so stats don't render empty with no way to recover.
                    val clampedFilter = it.boardFilter?.takeIf { bf -> bf in brands }
                    // Seed the heatmap selection to the active board only on the
                    // first load; preserve an existing user pick across reloads,
                    // dropping it only if its board is no longer offered.
                    val heatmapSel = it.heatmapBoardSelection
                        ?.takeIf { sel -> sel in options }
                        ?: defaultSelection
                    it.copy(
                        availableBoardBrands = brands,
                        boardFilter = clampedFilter,
                        heatmapBoardOptions = options,
                        heatmapBoardSelection = heatmapSel,
                    )
                }
                // Load the heatmap canvas for the seeded/selected board (init no
                // longer does this — the selection didn't exist yet). No-op for
                // the "Alle" case (null selection blanks the canvas).
                loadBoardData()
                recomputeStats()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "preloadStats failed; stats sheet may show stale", e)
            }
        }
    }

    /**
     * The heatmap option that represents the user's currently-active board, for
     * the default selection (FEAT-039 — no regression: the active board is
     * pre-selected). Prefers the enumerated option matching the active
     * (brand, layout); when the active brand has a different enumerated layout,
     * its first option; otherwise the first offered board (or null when nothing
     * is renderable — the sheet then opens on "Alle"). The enumerated options
     * are the single source of truth, so the default always resolves to a real
     * menu entry the user can re-select.
     */
    private fun resolveDefaultHeatmapSelection(
        options: List<com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption>,
        activeBrandWire: String,
        activeLayoutId: Int,
    ): com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption? {
        val brandWire = BoardBrand.fromWire(activeBrandWire).wireValue
        return options.firstOrNull { it.brandWire == brandWire && it.layoutId == activeLayoutId }
            ?: options.firstOrNull { it.brandWire == brandWire }
            ?: options.firstOrNull()
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.canLoadMore) return

        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(isLoadingMore = true, error = null) }
            try {
                val nextPage = withContext(Dispatchers.IO) {
                    val page = personalBoardRepo.getUserLogbookPage(PAGE_SIZE, s.ascents.size).toMutableList()
                    repairMissingDenormalized(page)
                    page
                }
                _state.update { it.copy(
                    isLoadingMore = false,
                    ascents = it.ascents + nextPage,
                    canLoadMore = nextPage.size >= PAGE_SIZE,
                    error = null,
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingMore = false, error = e.message) }
            }
        }
    }

    /**
     * Self-healing: when logbook entries have empty denormalized fields
     * (e.g. Kilter sync ran before board sync), fill them from BoardDB
     * and persist the fix so it doesn't recur.
     */
    private fun repairMissingDenormalized(entries: MutableList<AscentWithClimb>) {
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.climbName.isNotBlank() && entry.difficultyAverage != null) continue
            // Use the case-fanout resolver: BLE protocol decodes uuids as
            // uppercase-no-hyphens, our climbs table stores lowercase
            // canonical form. Raw getClimbByUuid would silently miss those.
            val climb = climbNameResolver.resolveClimb(entry.climbUuid, entry.angle.toInt()) ?: continue
            // Fix in-memory for immediate display
            entries[i] = entry.copy(
                climbName = climb.name,
                difficultyAverage = climb.difficultyAverage,
                climbFrames = climb.frames,
                framesCount = climb.framesCount
            )
            // Persist fix in SecureDB
            if (entry.isSend) {
                personalBoardRepo.updateAscentDenormalized(
                    entry.climbUuid, entry.angle, climb.name, climb.difficultyAverage,
                    climb.frames, climb.framesCount,
                    climb.boardBrand, climb.layoutId
                )
            } else {
                personalBoardRepo.updateBidDenormalized(
                    entry.climbUuid, entry.angle, climb.name, climb.difficultyAverage,
                    climb.boardBrand, climb.layoutId
                )
            }
        }
    }

    // --- Stats ---

    fun setStatsInterval(interval: StatsTimeInterval) {
        _state.update { it.copy(statsInterval = interval, customDateFrom = null, customDateTo = null) }
        recomputeStats()
    }

    fun setCustomDateRange(from: LocalDate, to: LocalDate) {
        _state.update { it.copy(customDateFrom = from, customDateTo = to) }
        recomputeStats()
    }

    fun setGradeChartView(view: GradeChartView) {
        _state.update { it.copy(gradeChartView = view) }
    }

    fun setTimeChartView(view: TimeChartView) {
        _state.update { it.copy(timeChartView = view) }
    }

    fun setDistributionChartView(view: DistributionChartView) {
        _state.update { it.copy(distributionChartView = view) }
    }

    fun toggleStatsSheet() {
        val opening = !_state.value.showStatsSheet
        _state.update { it.copy(showStatsSheet = opening) }
        if (opening && allAscents.isEmpty()) preloadStats()
    }

    /**
     * Scope the aggregate stats (counts / pyramid / outcomes) to one board
     * family, or null for all. FEAT-039: this no longer drives the hold-heatmap
     * canvas — that is the independent [setHeatmapBoardSelection]. The
     * comparison row and "Alle" stay all-boards regardless.
     */
    fun setBoardFilter(brand: String?) {
        if (_state.value.boardFilter == brand) return
        _state.update { it.copy(boardFilter = brand) }
        recomputeStats()
    }

    private fun recomputeStats() {
        viewModelScope.safeLaunch(TAG) {
            try {
                val s = _state.value
                // Per-board split: restrict to the selected family when set.
                val scoped = s.boardFilter
                    ?.let { bf -> allAscents.filter { it.brand == BoardBrand.fromWire(bf) } }
                    ?: allAscents
                val (stats, comparison) = withContext(Dispatchers.Default) {
                    val st = BoardStatsComputer.computeStats(
                        scoped, s.statsInterval, s.gradeScale,
                        s.customDateFrom, s.customDateTo, context
                    )
                    // Comparison spans ALL boards (unscoped) so the rows stay
                    // stable as the user toggles the per-board filter.
                    val cmp = BoardStatsComputer.computeBoardComparison(
                        allAscents, s.statsInterval, s.gradeScale,
                        s.customDateFrom, s.customDateTo
                    )
                    st to cmp
                }
                _state.update { it.copy(stats = stats, boardComparison = comparison) }
                recomputeHeatmap()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recomputeStats failed", e)
            }
        }
    }

    fun setHeatmapMode(mode: HeatmapMode) {
        if (_state.value.heatmapMode == mode) return
        _state.update { it.copy(heatmapMode = mode, heatmapData = emptyMap()) }
        recomputeHeatmap()
    }

    /**
     * Pick the board the hold-heatmap renders on (FEAT-039). null = "Alle":
     * the per-grid heatmap is hidden (disjoint grids can't aggregate) and only
     * the all-boards stats remain. A concrete option re-renders the canvas
     * (placements/size/images) and recomputes the heat values for that board.
     * The stats brand-filter ([setBoardFilter]) is independent and untouched.
     */
    fun setHeatmapBoardSelection(
        option: com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption?
    ) {
        if (_state.value.heatmapBoardSelection == option) return
        _state.update {
            // Blank the canvas + heat eagerly so the old board never flashes
            // under the new label while loadBoardData/recomputeHeatmap run.
            it.copy(
                heatmapBoardSelection = option,
                placements = emptyMap(),
                boardSize = null,
                boardImages = emptyList(),
                heatmapData = emptyMap(),
            )
        }
        loadBoardData()
        recomputeHeatmap()
    }

    private fun recomputeHeatmap() {
        viewModelScope.safeLaunch(TAG) {
            try {
                val mode = _state.value.heatmapMode
                if (mode == HeatmapMode.OFF) {
                    _state.update { it.copy(heatmapData = emptyMap()) }
                    return@safeLaunch
                }
                // Heatmap is always single-grid (FEAT-039): brand AND layout come
                // from the user's explicit board selection — the SAME resolution
                // as the canvas, so the heat values always match the rendered
                // grid. null (= "Alle") blanks the heat: disjoint placement-id
                // spaces can't be overlaid into one aggregate grid.
                val resolved = resolveHeatmapBoard()
                if (resolved == null) {
                    _state.update { it.copy(heatmapData = emptyMap()) }
                    return@safeLaunch
                }
                val (activeBrand, layoutId, _) = resolved
                val data = withContext(Dispatchers.IO) {
                    val frameRows: List<String> = when (mode) {
                        // allAscents comes from getUserLogbookAllLight() which
                        // strips climb_frames to save memory for the list UI —
                        // the heavy SELECT * variant is the only one that carries
                        // the frames we need to render the personal heatmap.
                        //
                        // Scope to the active board's family: a Kilter 12x12
                        // grid and a MoonBoard 11x18 grid use disjoint hold
                        // ids, so overlaying both boards' ascents was
                        // physically meaningless. Filtering by board_brand
                        // (reliably set; legacy rows default to 'kilter')
                        // fixes that without dropping legacy NULL-layout rows.
                        HeatmapMode.PERSONAL -> personalBoardRepo.getUserAscentsAll()
                            .filter { it.brand == BoardBrand.fromWire(activeBrand) }
                            .map { it.climbFrames }
                            .filter { it.isNotBlank() }
                        // Angle-agnostic: hold usage doesn't depend on the
                        // angle, and the previous hardcoded 40° rendered an
                        // empty map for boards/layouts logged at other angles.
                        else -> boardRepository.getAllFramesForHeatmapAllAngles(
                            layoutId = layoutId,
                            boardBrand = activeBrand,
                            minDifficulty = 0.0,
                            maxDifficulty = Double.MAX_VALUE,
                            minAscensionists = 0,
                            climbType = ClimbTypeFilter.ALL
                        ).map { it.frames }
                    }
                    val raw = when (mode) {
                        HeatmapMode.START -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.START)
                        HeatmapMode.HAND -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.HAND)
                        HeatmapMode.FOOT -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FOOT)
                        HeatmapMode.FINISH -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FINISH)
                        else -> HoldHeatmapComputer.computeGlobalHeatmap(frameRows)
                    }
                    HoldHeatmapComputer.normalizeHeatmap(raw)
                }
                if (_state.value.heatmapMode == mode) {
                    _state.update { it.copy(heatmapData = data) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recomputeHeatmap failed; keeping previous overlay", e)
            }
        }
    }

    // --- Single edit ---

    fun editAscent(ascent: AscentWithClimb) {
        _state.update { it.copy(
            showEditDialog = true,
            editingAscentUuid = ascent.uuid,
            editBidCount = ascent.bidCount.toInt().coerceAtLeast(1),
            editQuality = (ascent.quality?.toInt() ?: 0).coerceIn(0, 5),
            editComment = ascent.comment ?: ""
        ) }
    }

    fun dismissEditDialog() {
        _state.update { it.copy(showEditDialog = false, editingAscentUuid = null) }
    }

    fun updateEditBidCount(count: Int) {
        _state.update { it.copy(editBidCount = count.coerceAtLeast(1)) }
    }

    fun updateEditQuality(quality: Int) {
        _state.update { it.copy(editQuality = quality.coerceIn(0, 5)) }
    }

    fun updateEditComment(comment: String) {
        _state.update { it.copy(editComment = comment) }
    }

    fun saveEdit() {
        val s = _state.value
        val uuid = s.editingAscentUuid ?: return

        viewModelScope.safeLaunch(TAG) {
            try {
                withContext(Dispatchers.IO) {
                    personalBoardRepo.updateAscent(
                        uuid = uuid,
                        bidCount = s.editBidCount.toLong(),
                        quality = if (s.editQuality > 0) s.editQuality.toLong() else null,
                        comment = s.editComment.ifBlank { null }
                    )
                }
                _state.update { it.copy(showEditDialog = false, editingAscentUuid = null) }
                reloadAscents()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "saveEdit failed uuid=$uuid", e)
                _state.update { it.copy(showEditDialog = false, editingAscentUuid = null) }
            }
        }
    }

    // --- Single delete ---

    fun requestDeleteAscent(uuid: String) {
        _state.update { it.copy(showDeleteConfirm = uuid) }
    }

    fun dismissDeleteConfirm() {
        _state.update { it.copy(showDeleteConfirm = null) }
    }

    fun confirmDeleteAscent() {
        val uuid = _state.value.showDeleteConfirm ?: return
        val entry = _state.value.ascents.find { it.uuid == uuid }
        viewModelScope.safeLaunch(TAG) {
            try {
                withContext(Dispatchers.IO) {
                    if (entry?.isSend == false) personalBoardRepo.deleteBid(uuid)
                    else personalBoardRepo.deleteAscent(uuid)
                }
                _state.update { it.copy(showDeleteConfirm = null, selectedUuids = it.selectedUuids - uuid) }
                reloadAscents()
                zoneManager.recompute()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "confirmDeleteAscent failed uuid=$uuid", e)
                _state.update { it.copy(showDeleteConfirm = null) }
            }
        }
    }

    // --- Multi-select ---

    fun toggleSelection(uuid: String) {
        _state.update { s ->
            val next = if (uuid in s.selectedUuids) s.selectedUuids - uuid else s.selectedUuids + uuid
            s.copy(selectedUuids = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedUuids = emptySet()) }
    }

    fun selectAll() {
        _state.update { s ->
            val allUuids = s.ascents.map { it.uuid }.toSet()
            if (s.selectedUuids == allUuids) s.copy(selectedUuids = emptySet())
            else s.copy(selectedUuids = allUuids)
        }
    }

    fun requestBatchDelete() {
        if (_state.value.selectedUuids.isEmpty()) return
        _state.update { it.copy(showBatchDeleteConfirm = true) }
    }

    fun dismissBatchDeleteConfirm() {
        _state.update { it.copy(showBatchDeleteConfirm = false) }
    }

    fun confirmBatchDelete() {
        val uuids = _state.value.selectedUuids.toList()
        if (uuids.isEmpty()) return
        val bidUuids = _state.value.ascents.filter { !it.isSend && it.uuid in _state.value.selectedUuids }.map { it.uuid }.toSet()

        viewModelScope.safeLaunch(TAG) {
            // Per-row try/catch so a single delete failure doesn't strand
            // the rest of the batch in selected-but-not-deleted state.
            // The per-row counter informs a future "X of N deletes failed"
            // Snackbar (audit recommendation); for now logged.
            var deleted = 0
            var errors = 0
            withContext(Dispatchers.IO) {
                for (uuid in uuids) {
                    try {
                        if (uuid in bidUuids) personalBoardRepo.deleteBid(uuid)
                        else personalBoardRepo.deleteAscent(uuid)
                        deleted++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors++
                        Log.w(TAG, "batch delete failed uuid=$uuid", e)
                    }
                }
            }
            if (errors > 0) Log.w(TAG, "batch delete summary: ok=$deleted err=$errors of ${uuids.size}")
            _state.update { it.copy(showBatchDeleteConfirm = false, selectedUuids = emptySet()) }
            reloadAscents()
            zoneManager.recompute()
        }
    }

    private fun reloadAscents() {
        viewModelScope.safeLaunch(TAG) {
            try {
                val (ascents, count) = withContext(Dispatchers.IO) {
                    val list = personalBoardRepo.getUserLogbookPage(PAGE_SIZE, 0)
                    val total = personalBoardRepo.countUserLogbook()
                    list to total
                }
                _state.update { it.copy(
                    ascents = ascents,
                    totalCount = count,
                    canLoadMore = ascents.size >= PAGE_SIZE,
                    hasData = ascents.isNotEmpty()
                ) }
                preloadStats()
            } catch (e: Exception) {
                android.util.Log.e("BoardLogbookVM", "Reload failed", e)
            }
        }
    }
}
