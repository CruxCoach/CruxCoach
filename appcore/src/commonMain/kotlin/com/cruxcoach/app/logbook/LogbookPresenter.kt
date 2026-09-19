package com.cruxcoach.app.logbook

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.app.settings.readGradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** Entries of one calendar day (`climbedAt.take(10)`), newest day first as delivered by the repository. */
data class LogbookDay(val date: String, val entries: List<AscentWithClimb>) {
    val sendCount: Int get() = entries.count { it.isSend }
    val attemptCount: Int get() = entries.count { !it.isSend }
}

enum class LogbookError { NONE, LOAD_FAILED, EDIT_FAILED, DELETE_FAILED, BATCH_DELETE_PARTIAL, INVALID_DATE_RANGE }

data class LogbookEditState(
    val uuid: String,
    val isSend: Boolean,
    val bidCount: Int,
    val quality: Int,
    val comment: String,
)

data class LogbookState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val ascents: List<AscentWithClimb> = emptyList(),
    val days: List<LogbookDay> = emptyList(),
    /** True-flash send uuids over the FULL history. */
    val flashUuids: Set<String> = emptySet(),
    val totalCount: Long = 0,
    val canLoadMore: Boolean = false,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val hasData: Boolean = false,
    val error: LogbookError = LogbookError.NONE,
    val editing: LogbookEditState? = null,
    val deleteConfirmUuid: String? = null,
    val selectedUuids: Set<String> = emptySet(),
    val showBatchDeleteConfirm: Boolean = false,
    // The date interval is shared by list and statistics so both describe the same period.
    val outcomeFilter: LogbookOutcomeFilter = LogbookOutcomeFilter.ALL,
    val listBoardFilter: String? = null,
    val angleFilter: Int? = null,
    val availableAngles: List<Int> = emptyList(),
    val statsInterval: StatsTimeInterval = StatsTimeInterval.ALL,
    val customDateFrom: LocalDate? = null,
    val customDateTo: LocalDate? = null,
    val stats: BoardLogbookStats = BoardLogbookStats(),
    /** Board family the STATISTICS are scoped to; null = all boards. */
    val statsBoardFilter: String? = null,
    /** Kilter first, for a stable chip order. */
    val availableBoardBrands: List<String> = emptyList(),
    /** Always across all boards, so rows stay stable while [statsBoardFilter] changes. */
    val boardComparison: List<BoardComparisonEntry> = emptyList(),
)

internal fun groupByDay(entries: List<AscentWithClimb>): List<LogbookDay> {
    val grouped = LinkedHashMap<String, MutableList<AscentWithClimb>>()
    for (entry in entries) grouped.getOrPut(entry.climbedAt.take(10)) { mutableListOf() }.add(entry)
    return grouped.map { (date, list) -> LogbookDay(date, list) }
}

/**
 * Port of Android `BoardLogbookViewModel` without the hold heat-map and the
 * own-climb publish gate. The first page paints immediately; once the full
 * light history is loaded the list becomes the filtered full set, as on Android.
 */
class LogbookPresenter(
    private val personalBoardRepo: PersonalBoardRepository,
    private val keyValues: KeyValueStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { BoardStatsComputer.systemToday() },
) {
    private val _state = MutableStateFlow(LogbookState(gradeScale = keyValues.readGradeScale()))
    val state: StateFlow<LogbookState> = _state.asStateFlow()

    private var allAscents: List<AscentWithClimb> = emptyList()
    private var loadJob: Job? = null

    init {
        reload(showSpinner = true)
    }

    fun watch(onState: (LogbookState) -> Unit): StateWatch = scope.watchState(state, onState)

    /** Call when the screen reappears: entries may have been logged elsewhere. */
    fun refresh() {
        _state.update { it.copy(gradeScale = keyValues.readGradeScale()) }
        reload(showSpinner = false)
    }

    private fun reload(showSpinner: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (showSpinner) _state.update { it.copy(isLoading = true, error = LogbookError.NONE) }
            try {
                val (page, count) = withContext(ioDispatcher) {
                    personalBoardRepo.getUserLogbookPage(PAGE_SIZE, 0) to personalBoardRepo.countUserLogbook()
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        ascents = page,
                        days = groupByDay(page),
                        totalCount = count,
                        canLoadMore = page.size >= PAGE_SIZE,
                        hasData = page.isNotEmpty(),
                    )
                }
                preloadStats()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = LogbookError.LOAD_FAILED) }
            }
        }
    }

    private suspend fun preloadStats() {
        val all = withContext(ioDispatcher) { personalBoardRepo.getUserLogbookAllLight() }
        allAscents = all
        val brands = all.map { it.boardBrand }.distinct()
            .sortedBy { if (BoardBrand.fromWire(it) == BoardBrand.KILTER) 0 else 1 }
        _state.update {
            it.copy(
                flashUuids = BoardStatsComputer.trueFlashUuids(all),
                availableBoardBrands = brands,
                availableAngles = all.map { a -> a.angle.toInt() }.distinct().sorted(),
                // A filter whose board lost all its entries would strand an empty screen.
                listBoardFilter = it.listBoardFilter?.takeIf { bf -> bf in brands },
                statsBoardFilter = it.statsBoardFilter?.takeIf { bf -> bf in brands },
            )
        }
        applyLogbookFilters()
        recomputeStats()
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore || s.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true) }
        scope.launch {
            try {
                val offset = _state.value.ascents.size
                val page = withContext(ioDispatcher) { personalBoardRepo.getUserLogbookPage(PAGE_SIZE, offset) }
                _state.update {
                    // The full list may have replaced the paged one meanwhile.
                    if (!it.canLoadMore || it.ascents.size != offset) return@update it.copy(isLoadingMore = false)
                    val merged = it.ascents + page
                    it.copy(
                        ascents = merged,
                        days = groupByDay(merged),
                        isLoadingMore = false,
                        canLoadMore = page.size >= PAGE_SIZE,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoadingMore = false, error = LogbookError.LOAD_FAILED) }
            }
        }
    }

    // --- Period and filters ---

    fun setStatsInterval(interval: StatsTimeInterval) {
        _state.update { it.copy(statsInterval = interval, customDateFrom = null, customDateTo = null) }
        applyLogbookFilters()
        launchStats()
    }

    /** ISO dates (`yyyy-MM-dd`), inclusive; overrides the interval. */
    fun setCustomDateRange(fromIso: String, toIso: String) {
        val from = BoardStatsComputer.parseDate(fromIso)
        val to = BoardStatsComputer.parseDate(toIso)
        if (from == null || to == null || fromIso.length != 10 || toIso.length != 10 || from > to) {
            _state.update { it.copy(error = LogbookError.INVALID_DATE_RANGE) }
            return
        }
        _state.update { it.copy(customDateFrom = from, customDateTo = to) }
        applyLogbookFilters()
        launchStats()
    }

    fun setOutcomeFilter(filter: LogbookOutcomeFilter) {
        _state.update { it.copy(outcomeFilter = filter) }
        applyLogbookFilters()
    }

    fun setListBoardFilter(brand: String?) {
        _state.update { it.copy(listBoardFilter = brand) }
        applyLogbookFilters()
    }

    fun setAngleFilter(angle: Int?) {
        _state.update { it.copy(angleFilter = angle) }
        applyLogbookFilters()
    }

    fun setStatsBoardFilter(brand: String?) {
        if (_state.value.statsBoardFilter == brand) return
        _state.update { it.copy(statsBoardFilter = brand) }
        launchStats()
    }

    private fun applyLogbookFilters() {
        if (allAscents.isEmpty()) return
        val s = _state.value
        val boardFilter = s.listBoardFilter?.let { BoardBrand.fromWire(it) }
        val visible = BoardStatsComputer
            .filterByInterval(allAscents, s.statsInterval, s.customDateFrom, s.customDateTo, today())
            .filter { ascent ->
                when (s.outcomeFilter) {
                    LogbookOutcomeFilter.ALL -> true
                    LogbookOutcomeFilter.SENDS -> ascent.isSend
                    LogbookOutcomeFilter.ATTEMPTS -> !ascent.isSend
                }
            }
            .filter { boardFilter == null || it.brand == boardFilter }
            .filter { s.angleFilter == null || it.angle.toInt() == s.angleFilter }
        val visibleUuids = visible.mapTo(mutableSetOf()) { it.uuid }
        _state.update {
            it.copy(
                ascents = visible,
                days = groupByDay(visible),
                totalCount = visible.size.toLong(),
                canLoadMore = false,
                selectedUuids = it.selectedUuids.intersect(visibleUuids),
            )
        }
    }

    private fun launchStats() {
        scope.launch {
            try {
                recomputeStats()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Stale statistics are preferable to a failed screen.
            }
        }
    }

    private suspend fun recomputeStats() {
        val s = _state.value
        val all = allAscents
        val day = today()
        val scopedBrand = s.statsBoardFilter?.let { BoardBrand.fromWire(it) }
        val scoped = if (scopedBrand == null) all else all.filter { it.brand == scopedBrand }
        val (stats, comparison) = withContext(ioDispatcher) {
            BoardStatsComputer.computeStats(
                scoped, s.statsInterval, s.gradeScale, s.customDateFrom, s.customDateTo, day,
            ) to BoardStatsComputer.computeBoardComparison(
                all, s.statsInterval, s.gradeScale, s.customDateFrom, s.customDateTo, day,
            )
        }
        _state.update { it.copy(stats = stats, boardComparison = comparison) }
    }

    // --- Edit ---

    fun edit(uuid: String) {
        val entry = _state.value.ascents.find { it.uuid == uuid } ?: return
        _state.update {
            it.copy(
                editing = LogbookEditState(
                    uuid = entry.uuid,
                    isSend = entry.isSend,
                    bidCount = entry.bidCount.toInt().coerceAtLeast(1),
                    quality = (entry.quality?.toInt() ?: 0).coerceIn(0, 5),
                    comment = entry.comment ?: "",
                )
            )
        }
    }

    fun dismissEdit() = _state.update { it.copy(editing = null) }

    fun updateEditBidCount(count: Int) =
        _state.update { it.copy(editing = it.editing?.copy(bidCount = count.coerceAtLeast(1))) }

    fun updateEditQuality(quality: Int) =
        _state.update { it.copy(editing = it.editing?.copy(quality = quality.coerceIn(0, 5))) }

    fun updateEditComment(comment: String) = _state.update { it.copy(editing = it.editing?.copy(comment = comment)) }

    fun saveEdit() {
        val edit = _state.value.editing ?: return
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    // Bids live in their own table and carry no quality.
                    if (!edit.isSend) {
                        personalBoardRepo.updateBid(edit.uuid, edit.bidCount.toLong(), edit.comment.ifBlank { null })
                    } else {
                        personalBoardRepo.updateAscent(
                            uuid = edit.uuid,
                            bidCount = edit.bidCount.toLong(),
                            quality = if (edit.quality > 0) edit.quality.toLong() else null,
                            comment = edit.comment.ifBlank { null },
                        )
                    }
                }
                _state.update { it.copy(editing = null) }
                reload(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(editing = null, error = LogbookError.EDIT_FAILED) }
            }
        }
    }

    // --- Delete ---

    fun requestDelete(uuid: String) = _state.update { it.copy(deleteConfirmUuid = uuid) }

    fun dismissDeleteConfirm() = _state.update { it.copy(deleteConfirmUuid = null) }

    fun confirmDelete() {
        val uuid = _state.value.deleteConfirmUuid ?: return
        val entry = _state.value.ascents.find { it.uuid == uuid }
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    if (entry?.isSend == false) personalBoardRepo.deleteBid(uuid) else personalBoardRepo.deleteAscent(uuid)
                }
                _state.update { it.copy(deleteConfirmUuid = null, selectedUuids = it.selectedUuids - uuid) }
                reload(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(deleteConfirmUuid = null, error = LogbookError.DELETE_FAILED) }
            }
        }
    }

    fun toggleSelection(uuid: String) = _state.update { s ->
        s.copy(selectedUuids = if (uuid in s.selectedUuids) s.selectedUuids - uuid else s.selectedUuids + uuid)
    }

    fun clearSelection() = _state.update { it.copy(selectedUuids = emptySet()) }

    fun selectAll() = _state.update { s -> s.copy(selectedUuids = s.ascents.mapTo(mutableSetOf()) { it.uuid }) }

    fun requestBatchDelete() {
        if (_state.value.selectedUuids.isEmpty()) return
        _state.update { it.copy(showBatchDeleteConfirm = true) }
    }

    fun dismissBatchDeleteConfirm() = _state.update { it.copy(showBatchDeleteConfirm = false) }

    fun confirmBatchDelete() {
        val selected = _state.value.selectedUuids
        if (selected.isEmpty()) return
        val bidUuids = _state.value.ascents.filter { !it.isSend && it.uuid in selected }.mapTo(mutableSetOf()) { it.uuid }
        scope.launch {
            var errors = 0
            withContext(ioDispatcher) {
                // Per row, so one failure does not strand the rest of the batch.
                for (uuid in selected) {
                    try {
                        if (uuid in bidUuids) personalBoardRepo.deleteBid(uuid) else personalBoardRepo.deleteAscent(uuid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        errors++
                    }
                }
            }
            _state.update {
                it.copy(
                    showBatchDeleteConfirm = false,
                    selectedUuids = emptySet(),
                    error = if (errors > 0) LogbookError.BATCH_DELETE_PARTIAL else it.error,
                )
            }
            reload(showSpinner = false)
        }
    }

    fun consumeError() = _state.update { it.copy(error = LogbookError.NONE) }

    fun close() = scope.cancel()

    companion object {
        const val PAGE_SIZE = 50
    }
}
