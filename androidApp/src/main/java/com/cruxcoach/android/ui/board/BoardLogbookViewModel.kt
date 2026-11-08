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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
import com.cruxcoach.android.data.holdtypes.HoldTypeAssets
import com.cruxcoach.domain.holdtypes.HoldMix
import com.cruxcoach.domain.holdtypes.HoldTypeStats
import java.util.concurrent.ConcurrentHashMap

enum class StatsTimeInterval(@param:androidx.annotation.StringRes val labelResId: Int, val days: Int?) {
    ALL(com.cruxcoach.android.R.string.stats_interval_all, null),
    DAYS_30(com.cruxcoach.android.R.string.stats_interval_30_days, 30),
    DAYS_90(com.cruxcoach.android.R.string.stats_interval_90_days, 90),
    YEAR_1(com.cruxcoach.android.R.string.stats_interval_1_year, 365)
}

enum class LogbookOutcomeFilter { ALL, SENDS, ATTEMPTS }

data class BoardGradePyramidEntry(
    val grade: String,
    val count: Int,
    val difficultyInt: Int,
    /** Distinct problems among the sends, so count − problems are the repeats. */
    val problems: Int = count,
)

data class AngleDistEntry(
    val angle: Int,
    val count: Int
)

data class BoardLogbookStats(
    val hardestGrade: String? = null,
    val hardestDifficultyInt: Int = 0,
    /** The hardest send's difficulty as logged, for comparing grades. */
    val hardestDifficulty: Double? = null,
    val totalSends: Int = 0,
    val totalAttempts: Int = 0,
    val boulderSends: Int = 0,
    val routeSends: Int = 0,
    val flashRate: Float = 0f,
    val uniqueClimbs: Int = 0,
    val sessionCount: Int = 0,
    val angleDistribution: List<AngleDistEntry> = emptyList(),
    val activityMap: Map<LocalDate, Int> = emptyMap(),
    // Extended stats
    val gradeOutcomes: List<GradeOutcomeEntry> = emptyList(),
    /** The days the numbers cover; null without entries. */
    val period: StatsPeriod? = null,
    /** Climbing days per week over the period, weeks without a session included. */
    val daysPerWeek: List<DaysPerWeek> = emptyList(),
    /** All log entries and only the sends, per week or per month. */
    val entriesOverTime: List<VolumeBucket> = emptyList(),
    val sendsOverTime: List<VolumeBucket> = emptyList(),
    /** Sends per grade, repeats included. */
    val gradePyramid: List<BoardGradePyramidEntry> = emptyList(),
    /** The same numbers for the period before, when the period has a length. */
    val previous: PeriodTotals? = null,
    val gradeProgression: List<GradeProgressionPoint> = emptyList(),
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
    val editingIsSend: Boolean = true,
    val editBidCount: Int = 1,
    val editQuality: Int = 0,
    val editComment: String = "",
    // Delete confirm
    val showDeleteConfirm: String? = null,
    /** The delete confirmation was opened from the edit dialog; cancelling returns there. */
    val reopenEditAfterDeleteCancel: Boolean = false,
    // Multi-select
    val selectedUuids: Set<String> = emptySet(),
    val showBatchDeleteConfirm: Boolean = false,
    // Filters for the visible logbook list, all in its filter sheet. The
    // list has its own period: the overview above it does not depend on one,
    // and the statistics keep theirs.
    val logbookInterval: StatsTimeInterval = StatsTimeInterval.ALL,
    val logbookOutcomeFilter: LogbookOutcomeFilter = LogbookOutcomeFilter.ALL,
    val logbookBoardFilter: String? = null,
    val logbookAngleFilter: Int? = null,
    val availableLogbookAngles: List<Int> = emptyList(),
    // Stats
    val statsInterval: StatsTimeInterval = StatsTimeInterval.ALL,
    val stats: BoardLogbookStats = BoardLogbookStats(),
    /** The statistics have been computed once; until then the sheet shows that it is loading. */
    val statsReady: Boolean = false,
    val showStatsSheet: Boolean = false,
    val zones: IntensityZones? = null,
    // Chart view selectors
    val activityView: ActivityView = ActivityView.CALENDAR,
    val gradesView: GradesView = GradesView.PROBLEMS,
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
    // Tries until the send, per grade and per month, boards side by side (#44).
    // Like [boardComparison] it spans all boards; with one board it shows just that.
    val crossBoard: CrossBoardStats = CrossBoardStats(),
    val crossBoardView: CrossBoardView = CrossBoardView.TRIES_UNTIL_SEND,
    // Hold types (#63): the mix and the strong and weak types of one board in
    // the period; null while a new period or board computes, without labels
    // or without typed holds.
    val holdTypes: HoldTypeCardStats? = null,
    // The overview at the top of the logbook: records, the last 30 days and
    // the badges, over all boards and the whole logbook.
    val atAGlance: AtAGlance = AtAGlance(),
    val milestones: List<Milestone> = emptyList(),
    // The rolling level per board, for the progress chart when the statistics
    // show all of several boards; empty otherwise.
    val progressionByBoard: Map<String, List<GradeProgressionPoint>> = emptyMap(),
    /** The day whose recap is open, over all boards. */
    val sessionRecap: SessionRecap? = null,
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
    private val holdTypeAssets: HoldTypeAssets,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BoardLogbookState())
    val state: StateFlow<BoardLogbookState> = _state.asStateFlow()

    private var allAscents: List<AscentWithClimb> = emptyList()
    private var overviewJob: Job? = null
    private var statsJob: Job? = null
    /** The current read of the whole logbook; opening the sheet starts no second one, a reload replaces it. */
    private var preloadJob: Job? = null
    /** The whole logbook has been read once; the statistics wait for it. */
    private var logbookLoaded = false
    /** The last read of the whole logbook failed; a period or board tap in the sheet reads it again. */
    private var logbookLoadFailed = false

    // Hold types. allAscents has no frames: the sends' frames come from the
    // logbook (dropped when it reloads), an attempted problem's from the
    // catalogue ("" when it has none). The board's mix per layout and grade
    // range only changes with the catalogue. The card is kept while its
    // period, board and logbook stay the same.
    @Volatile private var sendFrames: Map<String, String>? = null
    private val catalogueFrames = ConcurrentHashMap<String, String>()
    @Volatile private var logbookVersion = 0
    private var holdTypesKey: HoldTypesKey? = null
    private var holdTypesJob: Job? = null
    private val boardMixCache = ConcurrentHashMap<BoardMixKey, HoldMix>()

    companion object {
        private const val PAGE_SIZE = 50
        private const val TAG = "BoardLogbookVM"
    }

    init {
        viewModelScope.safeLaunch(TAG) {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
                if (allAscents.isNotEmpty()) {
                    recomputeOverview()
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

    /** Reload after the screen becomes visible again. The ViewModel survives on the back
     *  stack, so entries logged, edited or deleted in the climb detail were missing (and the
     *  totals stale) until the logbook was left and reopened. */
    private var resumedOnce = false

    /** init already loads once; every later resume (return from a detail, app switch) reloads. */
    fun onScreenResumed() {
        if (resumedOnce) refreshAfterReturn() else resumedOnce = true
    }

    fun refreshAfterReturn() {
        // Silent and at least as many rows as shown: a spinner would drop the scroll position.
        loadAscents(silent = true)
        refreshOwnPublishable()
    }

    private fun loadAscents(silent: Boolean = false, afterRepair: Boolean = false) {
        viewModelScope.safeLaunch(TAG) {
            val limit = if (silent) maxOf(PAGE_SIZE, _state.value.ascents.size) else PAGE_SIZE
            if (!silent) _state.update { it.copy(isLoading = true, error = null) }
            val page = try {
                val (ascents, count) = withContext(Dispatchers.IO) {
                    personalBoardRepo.getUserLogbookPage(limit, 0) to personalBoardRepo.countUserLogbook()
                }
                _state.update { it.copy(
                    isLoading = false,
                    ascents = ascents,
                    totalCount = count,
                    canLoadMore = ascents.size >= limit,
                    hasData = ascents.isNotEmpty()
                ) }
                // Preload stats data in background so sheet opens instantly
                preloadStats()
                ascents
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
                return@safeLaunch
            }
            // Missing climb names and grades are filled in from the catalogue after
            // the list shows: a climb the catalogue lacks costs full scans of it,
            // seconds each on a phone, and the list and the overview do not wait for
            // that. When an entry really changed, the logbook loads once more to show it.
            try {
                val repaired = withContext(Dispatchers.IO) { repairMissingDenormalized(page.toMutableList()) }
                if (repaired && !afterRepair) loadAscents(silent = true, afterRepair = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "repairMissingDenormalized failed", e)
            }
        }
    }

    private fun preloadStats() {
        // A read after a failed one: the sheet shows its loading sign again, and a
        // period or board tap meanwhile waits for this read instead of starting one.
        if (logbookLoadFailed) {
            logbookLoadFailed = false
            if (!logbookLoaded) _state.update { it.copy(statsReady = false) }
        }
        // Only the newest read may land: an older one that finishes later would
        // put the logbook from before an edit or a reload back.
        preloadJob?.cancel()
        preloadJob = viewModelScope.safeLaunch(TAG) {
            try {
                val all = withContext(Dispatchers.IO) {
                    personalBoardRepo.getUserLogbookAllLight()
                }
                allAscents = all
                logbookLoaded = true
                sendFrames = null
                logbookVersion++
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
                        availableLogbookAngles = all.map { ascent -> ascent.angle.toInt() }.distinct().sorted(),
                        logbookBoardFilter = it.logbookBoardFilter?.takeIf { bf -> bf in brands },
                        boardFilter = clampedFilter,
                        heatmapBoardOptions = options,
                        heatmapBoardSelection = heatmapSel,
                    )
                }
                // The overview and the flash markers before the rest: they need
                // only a small part of the statistics and are the first thing on
                // screen. After the boards, which give the overview its colours.
                recomputeOverview()
                applyLogbookFilters()
                // Load the heatmap canvas for the seeded/selected board (init no
                // longer does this — the selection didn't exist yet). No-op for
                // the "Alle" case (null selection blanks the canvas).
                loadBoardData()
                recomputeStats()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "preloadStats failed; stats sheet may show stale", e)
                logbookLoadFailed = true
                // The statistics wait for this load: end their loading sign.
                _state.update { it.copy(statsReady = true) }
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
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val nextPage = withContext(Dispatchers.IO) {
                    val page = personalBoardRepo.getUserLogbookPage(PAGE_SIZE, s.ascents.size).toMutableList()
                    repairMissingDenormalized(page)
                    page
                }
                _state.update { it.copy(
                    isLoadingMore = false,
                    ascents = it.ascents + nextPage,
                    canLoadMore = nextPage.size >= PAGE_SIZE
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingMore = false, error = e.message) }
            }
        }
    }

    /**
     * Self-healing: when logbook entries have empty denormalized fields
     * (e.g. Kilter sync ran before board sync), fill them from BoardDB
     * and persist the fix so it doesn't recur. True when a name, grade, board or
     * layout changed; a climb without a grade in the catalogue stays without one.
     */
    private fun repairMissingDenormalized(entries: MutableList<AscentWithClimb>): Boolean {
        var changed = false
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.climbName.isNotBlank() && entry.difficultyAverage != null) continue
            // Use the case-fanout resolver: BLE protocol decodes uuids as
            // uppercase-no-hyphens, our climbs table stores lowercase
            // canonical form. Raw getClimbByUuid would silently miss those.
            val climb = climbNameResolver.resolveClimb(entry.climbUuid, entry.angle.toInt()) ?: continue
            if ((entry.climbName.isBlank() && climb.name.isNotBlank()) ||
                (entry.difficultyAverage == null && climb.difficultyAverage != null) ||
                entry.boardBrand != climb.boardBrand || entry.layoutId != climb.layoutId
            ) {
                changed = true
            }
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
        return changed
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

    fun setLogbookInterval(interval: StatsTimeInterval) {
        _state.update { it.copy(logbookInterval = interval) }
        applyLogbookFilters()
    }

    fun setLogbookOutcomeFilter(filter: LogbookOutcomeFilter) {
        _state.update { it.copy(logbookOutcomeFilter = filter) }
        applyLogbookFilters()
    }

    fun setLogbookBoardFilter(brand: String?) {
        _state.update { it.copy(logbookBoardFilter = brand) }
        applyLogbookFilters()
    }

    fun setLogbookAngleFilter(angle: Int?) {
        _state.update { it.copy(logbookAngleFilter = angle) }
        applyLogbookFilters()
    }

    private fun applyLogbookFilters() {
        if (allAscents.isEmpty()) return
        val s = _state.value
        val visible = BoardStatsComputer.filterByInterval(allAscents, s.logbookInterval).filter { ascent ->
            when (s.logbookOutcomeFilter) {
                LogbookOutcomeFilter.ALL -> true
                LogbookOutcomeFilter.SENDS -> ascent.isSend
                LogbookOutcomeFilter.ATTEMPTS -> !ascent.isSend
            }
        }.filter { ascent ->
            s.logbookBoardFilter == null || ascent.brand == BoardBrand.fromWire(s.logbookBoardFilter)
        }.filter { ascent ->
            s.logbookAngleFilter == null || ascent.angle.toInt() == s.logbookAngleFilter
        }
        val visibleUuids = visible.mapTo(mutableSetOf()) { it.uuid }
        _state.update {
            it.copy(
                ascents = visible,
                totalCount = visible.size.toLong(),
                canLoadMore = false,
                selectedUuids = it.selectedUuids.intersect(visibleUuids),
            )
        }
    }

    fun setActivityView(view: ActivityView) {
        _state.update { it.copy(activityView = view) }
    }

    fun setGradesView(view: GradesView) {
        _state.update { it.copy(gradesView = view) }
    }

    fun setCrossBoardView(view: CrossBoardView) {
        _state.update { it.copy(crossBoardView = view) }
    }

    /** Open the recap of the logbook day [dateKey] (yyyy-MM-dd). */
    fun openSessionRecap(dateKey: String) {
        val day = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return
        viewModelScope.safeLaunch(TAG) {
            if (allAscents.isEmpty()) return@safeLaunch
            val scale = _state.value.gradeScale
            val recap = withContext(Dispatchers.Default) {
                StatsHighlightsComputer.sessionRecap(allAscents, day, scale)
            }
            _state.update { it.copy(sessionRecap = recap) }
        }
    }

    fun closeSessionRecap() {
        _state.update { it.copy(sessionRecap = null) }
    }

    fun toggleStatsSheet() {
        val opening = !_state.value.showStatsSheet
        _state.update { it.copy(showStatsSheet = opening) }
        if (opening && allAscents.isEmpty() && preloadJob?.isActive != true) preloadStats()
        else if (opening) recomputeHoldTypes()
        else holdTypesJob?.cancel()
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

    /**
     * The overview at the top of the logbook and the true flashes (prior-session
     * attempts disqualify, so they need the full history). Neither depends on the
     * period or board of the statistics, so a filter change does not redo them.
     */
    private fun recomputeOverview() {
        overviewJob?.cancel()
        overviewJob = viewModelScope.safeLaunch(TAG) {
            try {
                val all = allAscents
                val scale = _state.value.gradeScale
                val result = withContext(Dispatchers.Default) {
                    val flashes = BoardStatsComputer.trueFlashUuids(all)
                    ComputedOverview(
                        flashUuids = flashes,
                        atAGlance = StatsHighlightsComputer.atAGlance(all, scale, flashes),
                        milestones = StatsHighlightsComputer.milestones(
                            all, scale, BoardStatsComputer.weekStreakOf(all),
                        ),
                    )
                }
                _state.update {
                    it.copy(
                        flashUuids = result.flashUuids,
                        atAGlance = result.atAGlance,
                        milestones = result.milestones,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recomputeOverview failed", e)
            }
        }
    }

    private fun recomputeStats() {
        // Not before the whole logbook is read: a period or board tapped while it
        // loads would compute an empty logbook and end the sheet's loading sign.
        // The load computes the newest choice once it is done.
        if (!logbookLoaded) {
            // After a failed read, the tap reads the logbook again, with the loading sign.
            if (logbookLoadFailed) preloadStats()
            return
        }
        // Only the newest choice may land: a slow "All" started before a tap on
        // "30 days" must not overwrite it when it finishes later.
        statsJob?.cancel()
        statsJob = viewModelScope.safeLaunch(TAG) {
            try {
                val s = _state.value
                // Per-board split: restrict to the selected family when set.
                val scoped = s.boardFilter
                    ?.let { bf -> allAscents.filter { it.brand == BoardBrand.fromWire(bf) } }
                    ?: allAscents
                val result = withContext(Dispatchers.Default) {
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
                    val cross = CrossBoardStatsComputer.compute(
                        allAscents, s.statsInterval, s.gradeScale,
                        s.customDateFrom, s.customDateTo, context
                    )
                    val byBoard = if (s.boardFilter == null && s.availableBoardBrands.size > 1) {
                        BoardStatsComputer.gradeProgressionByBoard(
                            allAscents, s.statsInterval, s.customDateFrom, s.customDateTo, context,
                        )
                    } else {
                        emptyMap()
                    }
                    ComputedStats(st, cmp, cross, byBoard)
                }
                _state.update {
                    it.copy(
                        stats = result.stats,
                        statsReady = true,
                        boardComparison = result.comparison,
                        crossBoard = result.crossBoard,
                        progressionByBoard = result.progressionByBoard,
                    )
                }
                recomputeHeatmap()
                recomputeHoldTypes()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recomputeStats failed", e)
                // Show what there is rather than loading forever.
                _state.update { it.copy(statsReady = true) }
            }
        }
    }

    /**
     * The hold-type card for the period and board of the statistics. Only while
     * the sheet is open: the board's side reads the catalogue's problems.
     */
    private fun recomputeHoldTypes() {
        holdTypesJob?.cancel()
        val s = _state.value
        if (!s.showStatsSheet) return
        val key = HoldTypesKey(s.statsInterval, s.customDateFrom, s.customDateTo, s.boardFilter, logbookVersion)
        if (key == holdTypesKey && s.holdTypes != null) return
        if (key != holdTypesKey) {
            // Never show the numbers of another period or board while the new ones compute.
            holdTypesKey = key
            _state.update { it.copy(holdTypes = null) }
        }
        val entries = allAscents
        holdTypesJob = viewModelScope.safeLaunch(TAG) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val period = BoardStatsComputer.filterByInterval(entries, s.statsInterval, s.customDateFrom, s.customDateTo)
                    val board = HoldTypeStatsComputer.cardBoard(period, s.boardFilter) { candidate ->
                        holdTypeAssets.boards().contains(candidate) &&
                            holdTypeAssets.dataset(candidate)?.holds?.any { it.type != null } == true
                    } ?: return@withContext null
                    val dataset = holdTypeAssets.dataset(board) ?: return@withContext null
                    val frames = holdTypeFrames(period.filter { it.boardBrand == board }, key.logbook)
                    ensureActive()
                    val yours = HoldTypeStatsComputer.problems(period, board, frames)
                    val boardMixes = HoldTypeStatsComputer.gradeRanges(yours).mapValues { (layoutId, grades) ->
                        ensureActive()
                        boardMixCache.getOrPut(BoardMixKey(board, layoutId, grades)) {
                            // Problems somebody has climbed; every listed one when the catalogue has no ascents.
                            val climbed = boardMix(board, layoutId, grades, minAscensionists = 1, dataset)
                            if (climbed.problems > 0) climbed else boardMix(board, layoutId, grades, minAscensionists = 0, dataset)
                        }
                    }
                    HoldTypeStatsComputer.compute(board, dataset, yours, boardMixes)
                }
                _state.update { it.copy(holdTypes = result) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recomputeHoldTypes failed", e)
                _state.update { it.copy(holdTypes = null) }
            }
        }
    }

    /**
     * The frames of the climbs in [entries], by normalized climb uuid: from the
     * logbook's sends, else from the catalogue. The sends' frames are kept only
     * while the logbook is still at [version].
     */
    private fun holdTypeFrames(entries: List<AscentWithClimb>, version: Int): Map<String, String> {
        val sends = sendFrames ?: personalBoardRepo.getUserAscentsAll()
            .filter { it.climbFrames.isNotBlank() }
            .associate { normalizeClimbUuid(it.climbUuid) to it.climbFrames }
            .also { if (version == logbookVersion) sendFrames = it }
        val missing = entries.map { it.climbUuid }.distinct()
            .filter { normalizeClimbUuid(it) !in sends && !catalogueFrames.containsKey(normalizeClimbUuid(it)) }
        if (missing.isNotEmpty()) {
            // Logged uuids can differ in case from the catalogue's spelling.
            val found = boardRepository.getClimbFramesByUuids(missing.flatMap { listOf(it, it.lowercase(), it.uppercase()) })
                .entries.associate { (uuid, frames) -> normalizeClimbUuid(uuid) to frames }
            missing.forEach { uuid ->
                val normalized = normalizeClimbUuid(uuid)
                catalogueFrames[normalized] = found[normalized].orEmpty()
            }
        }
        return catalogueFrames + sends
    }

    private fun boardMix(
        board: String,
        layoutId: Int,
        grades: ClosedFloatingPointRange<Double>,
        minAscensionists: Int,
        dataset: com.cruxcoach.domain.holdtypes.HoldTypeDataset,
    ): HoldMix {
        val climbs = boardRepository.getAllFramesForHeatmapAllAngles(
            layoutId = layoutId,
            boardBrand = board,
            minDifficulty = grades.start,
            maxDifficulty = grades.endInclusive,
            minAscensionists = minAscensionists,
            climbType = ClimbTypeFilter.ALL,
        ).asSequence().map { HoldTypeStatsComputer.typedClimb(board, layoutId, it.frames) }
        return HoldTypeStats.holdMix(climbs.asIterable(), mapOf(board to dataset))
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
            editingIsSend = ascent.isSend,
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
                    if (!s.editingIsSend) personalBoardRepo.updateBid(uuid, s.editBidCount.toLong(), s.editComment.ifBlank { null })
                    else personalBoardRepo.updateAscent(
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

    /** Delete tapped inside the edit dialog: hide it, but bring it back if the user cancels. */
    fun requestDeleteFromEdit() {
        val uuid = _state.value.editingAscentUuid ?: return
        _state.update {
            it.copy(showEditDialog = false, showDeleteConfirm = uuid, reopenEditAfterDeleteCancel = true)
        }
    }

    fun dismissDeleteConfirm() {
        _state.update {
            it.copy(
                showDeleteConfirm = null,
                showEditDialog = it.reopenEditAfterDeleteCancel,
                reopenEditAfterDeleteCancel = false,
            )
        }
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
                _state.update {
                    it.copy(
                        showDeleteConfirm = null,
                        reopenEditAfterDeleteCancel = false,
                        editingAscentUuid = null,
                        selectedUuids = it.selectedUuids - uuid,
                    )
                }
                reloadAscents()
                zoneManager.recompute()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "confirmDeleteAscent failed uuid=$uuid", e)
                _state.update { it.copy(showDeleteConfirm = null, reopenEditAfterDeleteCancel = false) }
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

/** The period, board filter and logbook state a hold-type card was computed for. */
private data class HoldTypesKey(
    val interval: StatsTimeInterval,
    val from: LocalDate?,
    val to: LocalDate?,
    val boardFilter: String?,
    val logbook: Int,
)

/** A board's problems on one layout within a grade range, whose hold mix [BoardLogbookViewModel] keeps. */
private data class BoardMixKey(val board: String, val layoutId: Int, val grades: ClosedFloatingPointRange<Double>)

/** Everything [BoardLogbookViewModel] computes for the statistics in one pass. */
private class ComputedStats(
    val stats: BoardLogbookStats,
    val comparison: List<BoardComparisonEntry>,
    val crossBoard: CrossBoardStats,
    val progressionByBoard: Map<String, List<GradeProgressionPoint>>,
)

/** The logbook overview and the flash markers of its entries: the whole logbook, every board. */
private class ComputedOverview(
    val flashUuids: Set<String>,
    val atAGlance: AtAGlance,
    val milestones: List<Milestone>,
)
