package com.cruxcoach.android.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.data.repository.AscentWithClimb
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
    val heatmapData: Map<Int, Float> = emptyMap()
)

@HiltViewModel
class BoardLogbookViewModel @Inject constructor(
    private val personalBoardRepo: PersonalBoardRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val zoneManager: IntensityZoneManager,
    val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BoardLogbookState())
    val state: StateFlow<BoardLogbookState> = _state.asStateFlow()

    private var allAscents: List<AscentWithClimb> = emptyList()

    companion object {
        private const val PAGE_SIZE = 50
    }

    init {
        viewModelScope.launch {
            userPreferences.gradeScale.collect { scale ->
                _state.update { it.copy(gradeScale = scale) }
                if (allAscents.isNotEmpty()) {
                    recomputeStats()
                }
            }
        }
        viewModelScope.launch {
            zoneManager.zones.collect { zones ->
                _state.update { it.copy(zones = zones) }
            }
        }
        loadBoardData()
        loadAscents()
    }

    private fun loadBoardData() {
        viewModelScope.launch {
            val sizeId = userPreferences.boardProductSizeId.first()
            val layoutId = userPreferences.boardLayoutId.first()
            val (placements, boardSize, boardImages) = withContext(Dispatchers.IO) {
                Triple(
                    boardRepository.getAllPlacements().associate { it.placementId.toInt() to it },
                    boardRepository.getProductSize(sizeId),
                    boardRepository.getBoardImages(sizeId, layoutId)
                )
            }
            _state.update {
                it.copy(
                    placements = placements,
                    boardSize = boardSize,
                    boardImages = boardImages
                )
            }
        }
    }

    private fun loadAscents() {
        viewModelScope.launch {
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

    private fun preloadStats() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) {
                personalBoardRepo.getUserLogbookAllLight()
            }
            allAscents = all
            recomputeStats()
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoadingMore || !s.canLoadMore) return

        viewModelScope.launch {
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
     * and persist the fix so it doesn't recur.
     */
    private fun repairMissingDenormalized(entries: MutableList<AscentWithClimb>) {
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.climbName.isNotBlank() && entry.difficultyAverage != null) continue
            val climb = boardRepository.getClimbByUuid(entry.climbUuid, entry.angle.toInt()) ?: continue
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
                    climb.frames, climb.framesCount
                )
            } else {
                personalBoardRepo.updateBidDenormalized(
                    entry.climbUuid, entry.angle, climb.name, climb.difficultyAverage
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

    private fun recomputeStats() {
        viewModelScope.launch {
            val s = _state.value
            val stats = withContext(Dispatchers.Default) {
                BoardStatsComputer.computeStats(
                    allAscents, s.statsInterval, s.gradeScale,
                    s.customDateFrom, s.customDateTo, context
                )
            }
            _state.update { it.copy(stats = stats) }
            recomputeHeatmap()
        }
    }

    fun setHeatmapMode(mode: HeatmapMode) {
        if (_state.value.heatmapMode == mode) return
        _state.update { it.copy(heatmapMode = mode, heatmapData = emptyMap()) }
        recomputeHeatmap()
    }

    private fun recomputeHeatmap() {
        viewModelScope.launch {
            val mode = _state.value.heatmapMode
            if (mode == HeatmapMode.OFF) {
                _state.update { it.copy(heatmapData = emptyMap()) }
                return@launch
            }
            val layoutId = userPreferences.boardLayoutId.first()
            val data = withContext(Dispatchers.IO) {
                val frameRows: List<String> = when (mode) {
                    // allAscents comes from getUserLogbookAllLight() which
                    // strips climb_frames to save memory for the list UI —
                    // the heavy SELECT * variant is the only one that carries
                    // the frames we need to render the personal heatmap.
                    HeatmapMode.PERSONAL -> personalBoardRepo.getUserAscentsAll()
                        .map { it.climbFrames }
                        .filter { it.isNotBlank() }
                    else -> boardRepository.getAllFramesForHeatmap(
                        angle = 40,
                        layoutId = layoutId,
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

        viewModelScope.launch {
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (entry?.isSend == false) personalBoardRepo.deleteBid(uuid)
                else personalBoardRepo.deleteAscent(uuid)
            }
            _state.update { it.copy(showDeleteConfirm = null, selectedUuids = it.selectedUuids - uuid) }
            reloadAscents()
            zoneManager.recompute()
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

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uuids.forEach { uuid ->
                    if (uuid in bidUuids) personalBoardRepo.deleteBid(uuid)
                    else personalBoardRepo.deleteAscent(uuid)
                }
            }
            _state.update { it.copy(showBatchDeleteConfirm = false, selectedUuids = emptySet()) }
            reloadAscents()
            zoneManager.recompute()
        }
    }

    private fun reloadAscents() {
        viewModelScope.launch {
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
