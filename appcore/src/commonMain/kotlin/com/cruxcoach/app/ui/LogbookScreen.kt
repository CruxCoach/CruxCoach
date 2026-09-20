package com.cruxcoach.app.ui

import com.cruxcoach.app.logbook.AngleDistEntry
import com.cruxcoach.app.logbook.BoardComparisonEntry
import com.cruxcoach.app.logbook.BoardGradePyramidEntry
import com.cruxcoach.app.logbook.BoardLogbookStats
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.logbook.GradeOutcomeEntry
import com.cruxcoach.app.logbook.GradeProgressionPoint
import com.cruxcoach.app.logbook.HistoryPresenter
import com.cruxcoach.app.logbook.HistoryState
import com.cruxcoach.app.logbook.LogbookDay
import com.cruxcoach.app.logbook.LogbookOutcomeFilter
import com.cruxcoach.app.logbook.LogbookPresenter
import com.cruxcoach.app.logbook.LogbookState
import com.cruxcoach.app.logbook.StatsTimeInterval
import com.cruxcoach.app.logbook.TimeBucketEntry
import com.cruxcoach.app.logbook.TimeBucketKind
import com.cruxcoach.app.logbook.UniqueClimbEntry
import com.cruxcoach.app.logbook.WeeklyVolumeEntry
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.app.settings.HistoryRetention
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.domain.board.BoardBrand

/** One logged send or attempt. [grade] is already in the user's scale. */
class LogbookEntryUi(
    val uuid: String,
    val climbUuid: String,
    val climbName: String,
    val grade: String,
    val angle: Int,
    val isMirror: Boolean,
    val isSend: Boolean,
    /** True flash over the FULL history, not just this period. */
    val isFlash: Boolean,
    val tries: Int,
    /** 0 = unrated. */
    val quality: Int,
    val comment: String,
    val boardWire: String,
    /** Full stored timestamp; [LogbookDayUi.date] is its day part. */
    val loggedAt: String,
    val isSelected: Boolean,
)

class LogbookDayUi(val date: String, val entries: List<LogbookEntryUi>, val sendCount: Int, val attemptCount: Int)

class GradeBarUi(val grade: String, val difficulty: Int, val count: Int)

class GradeOutcomeUi(
    val grade: String,
    val difficulty: Int,
    val flashes: Int,
    val redpoints: Int,
    val attempts: Int,
    val total: Int,
)

class UniqueClimbUi(val grade: String, val difficulty: Int, val unique: Int, val sends: Int)

class AngleBarUi(val angle: Int, val count: Int)

/** Sends per bucket. [kind] is day | isoWeek | month; unused fields are 0 so Swift formats the label. */
class TimeBucketUi(
    val kind: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val isoWeek: Int,
    val count: Int,
)

class WeeklyVolumeUi(
    val isoWeek: Int,
    val weekBasedYear: Int,
    val easy: Int,
    val medium: Int,
    val hard: Int,
    val elite: Int,
    val total: Int,
)

/** [xFraction] positions the point on a real time axis, so idle weeks stay gaps. */
class ProgressionPointUi(
    val isoWeek: Int,
    val weekBasedYear: Int,
    val showYear: Boolean,
    val weekStartDate: String,
    val level: Double,
    val levelGrade: String,
    val xFraction: Float,
)

class BoardComparisonUi(
    val boardWire: String,
    val boardTitle: String,
    val sends: Int,
    val attempts: Int,
    val hardestGrade: String,
    val hardestDifficulty: Int,
)

/** Deltas against the equally long period before the selected one. */
class PeriodComparisonUi(
    val hasData: Boolean,
    val intervalCode: String,
    val sendsDelta: Int,
    val flashRateDelta: Float,
    val hardestGradeDelta: Int,
    val uniqueClimbsDelta: Int,
)

class PersonalRecordsUi(
    val hardestFlashGrade: String,
    val hardestFlashDifficulty: Int,
    val mostSendsInDay: Int,
    val mostSendsDate: String,
    val avgSessionsPerWeek: Double,
    val weekStreak: Int,
)

class LogbookStatsUi(
    val hardestGrade: String,
    val hardestDifficulty: Int,
    val totalSends: Int,
    /** Sum of tries over sends AND attempts, not a row count. */
    val totalAttempts: Int,
    val boulderSends: Int,
    val routeSends: Int,
    val flashRate: Float,
    val uniqueClimbs: Int,
    val sessionCount: Int,
    val gradePyramid: List<GradeBarUi>,
    val angleDistribution: List<AngleBarUi>,
    val sendsOverTime: List<TimeBucketUi>,
    /** ISO date → entries logged that day, for the activity calendar. */
    val activityDates: List<String>,
    val activityCounts: List<Int>,
    val gradeOutcomes: List<GradeOutcomeUi>,
    /** One best outcome per board family and climb. */
    val outcomeFlashes: Int,
    val outcomeRedpoints: Int,
    val outcomeAttempts: Int,
    val weeklyVolume: List<WeeklyVolumeUi>,
    val gradeProgression: List<ProgressionPointUi>,
    val uniqueClimbsByGrade: List<UniqueClimbUi>,
    val periodComparison: PeriodComparisonUi,
    val records: PersonalRecordsUi,
)

class LogbookScreenState(
    val isLoading: Boolean,
    val isLoadingMore: Boolean,
    val hasData: Boolean,
    val days: List<LogbookDayUi>,
    val totalCount: Long,
    val canLoadMore: Boolean,
    /** none | loadFailed | editFailed | deleteFailed | batchDeletePartial | invalidDateRange */
    val errorCode: String,
    val isEditing: Boolean,
    val editUuid: String,
    val editIsSend: Boolean,
    val editTries: Int,
    val editQuality: Int,
    val editComment: String,
    val deleteConfirmUuid: String,
    val selectedCount: Int,
    val showBatchDeleteConfirm: Boolean,
    /** all | sends | attempts */
    val outcomeFilterCode: String,
    /** Board wire value, or "" for all boards. */
    val listBoardFilter: String,
    /** -1 = every angle. */
    val angleFilter: Int,
    val availableAngles: List<Int>,
    val availableBoardWires: List<String>,
    val availableBoardTitles: List<String>,
    /** all | days30 | days90 | year1 */
    val intervalCode: String,
    /** "" unless a custom range overrides [intervalCode]. */
    val customFrom: String,
    val customTo: String,
    val statsBoardFilter: String,
    val stats: LogbookStatsUi,
    val boardComparison: List<BoardComparisonUi>,
)

class HistoryEntryUi(
    val id: Long,
    val climbUuid: String,
    val climbName: String,
    val grade: String,
    val angle: Int,
    val boardWire: String,
    val loggedAt: String,
    val recordedAt: String,
    val isSelected: Boolean,
)

class HistoryScreenState(
    val entries: List<HistoryEntryUi>,
    /** off | days30 | days90 | days365 */
    val retentionCode: String,
    val selectedCount: Int,
    val allSelected: Boolean,
    /** none | loadFailed | deleteFailed | clearFailed | settingNotSaved */
    val errorCode: String,
)

/** Codes for the logbook enums. Kept here so `UiCodes` stays owned by its author. */
internal object LogbookCodes {
    fun outcome(value: LogbookOutcomeFilter): String = when (value) {
        LogbookOutcomeFilter.ALL -> "all"
        LogbookOutcomeFilter.SENDS -> "sends"
        LogbookOutcomeFilter.ATTEMPTS -> "attempts"
    }

    fun outcome(code: String): LogbookOutcomeFilter? = when (code) {
        "all" -> LogbookOutcomeFilter.ALL
        "sends" -> LogbookOutcomeFilter.SENDS
        "attempts" -> LogbookOutcomeFilter.ATTEMPTS
        else -> null
    }

    fun interval(value: StatsTimeInterval): String = when (value) {
        StatsTimeInterval.ALL -> "all"
        StatsTimeInterval.DAYS_30 -> "days30"
        StatsTimeInterval.DAYS_90 -> "days90"
        StatsTimeInterval.YEAR_1 -> "year1"
    }

    fun interval(code: String): StatsTimeInterval? = when (code) {
        "all" -> StatsTimeInterval.ALL
        "days30" -> StatsTimeInterval.DAYS_30
        "days90" -> StatsTimeInterval.DAYS_90
        "year1" -> StatsTimeInterval.YEAR_1
        else -> null
    }

    fun bucket(value: TimeBucketKind): String = when (value) {
        TimeBucketKind.DAY -> "day"
        TimeBucketKind.ISO_WEEK -> "isoWeek"
        TimeBucketKind.MONTH -> "month"
    }

    fun retention(value: HistoryRetention): String = when (value) {
        HistoryRetention.OFF -> "off"
        HistoryRetention.DAYS_30 -> "days30"
        HistoryRetention.DAYS_90 -> "days90"
        HistoryRetention.DAYS_365 -> "days365"
    }

    /**
     * Product names, not translatable text. Duplicated from `UiCodes.brandTitle`
     * only because this file was written against a base without it; the
     * integrator may delegate to that one instead.
     */
    fun boardTitle(wire: String): String = when (BoardBrand.fromWire(wire)) {
        BoardBrand.KILTER -> "Kilter Board"
        BoardBrand.MOONBOARD -> "MoonBoard"
        BoardBrand.TENSION -> "Tension Board"
        BoardBrand.GRASSHOPPER -> "Grasshopper"
        BoardBrand.DECOY -> "Decoy"
        BoardBrand.SOILL -> "So iLL"
        BoardBrand.TOUCHSTONE -> "Touchstone"
        BoardBrand.QUANTUM -> "Quantum"
        else -> wire
    }

    fun retention(code: String): HistoryRetention? = when (code) {
        "off" -> HistoryRetention.OFF
        "days30" -> HistoryRetention.DAYS_30
        "days90" -> HistoryRetention.DAYS_90
        "days365" -> HistoryRetention.DAYS_365
        else -> null
    }
}

/**
 * Logbook screen: paged days, the statistics of the selected period and the
 * list filters. Statistics always describe the whole period, never just the
 * rows the list filters leave visible — that is the Android behaviour.
 */
class LogbookScreenModel(private val presenter: LogbookPresenter) {

    val currentState: LogbookScreenState get() = map(presenter.state.value)

    fun watch(onState: (LogbookScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refresh() = presenter.refresh()
    fun loadMore() = presenter.loadMore()
    fun close() = presenter.close()
    fun consumeError() = presenter.consumeError()

    fun setOutcomeFilter(code: String) {
        LogbookCodes.outcome(code)?.let { presenter.setOutcomeFilter(it) }
    }

    /** "" = all boards. */
    fun setListBoardFilter(boardWire: String) = presenter.setListBoardFilter(boardWire.ifBlank { null })

    /** -1 = every angle. */
    fun setAngleFilter(angle: Int) = presenter.setAngleFilter(angle.takeIf { it >= 0 })

    fun setInterval(code: String) {
        LogbookCodes.interval(code)?.let { presenter.setStatsInterval(it) }
    }

    /** Inclusive ISO dates; an invalid range reports `invalidDateRange`. */
    fun setCustomRange(fromIso: String, toIso: String) = presenter.setCustomDateRange(fromIso, toIso)

    fun setStatsBoardFilter(boardWire: String) = presenter.setStatsBoardFilter(boardWire.ifBlank { null })

    fun edit(uuid: String) = presenter.edit(uuid)
    fun dismissEdit() = presenter.dismissEdit()
    fun setEditTries(count: Int) = presenter.updateEditBidCount(count)
    fun setEditQuality(quality: Int) = presenter.updateEditQuality(quality)
    fun setEditComment(comment: String) = presenter.updateEditComment(comment)
    fun saveEdit() = presenter.saveEdit()

    fun requestDelete(uuid: String) = presenter.requestDelete(uuid)
    fun dismissDeleteConfirm() = presenter.dismissDeleteConfirm()
    fun confirmDelete() = presenter.confirmDelete()
    fun toggleSelection(uuid: String) = presenter.toggleSelection(uuid)
    fun selectAll() = presenter.selectAll()
    fun clearSelection() = presenter.clearSelection()
    fun requestBatchDelete() = presenter.requestBatchDelete()
    fun dismissBatchDeleteConfirm() = presenter.dismissBatchDeleteConfirm()
    fun confirmBatchDelete() = presenter.confirmBatchDelete()

    private fun map(state: LogbookState): LogbookScreenState {
        val scale = state.gradeScale
        val edit = state.editing
        return LogbookScreenState(
            isLoading = state.isLoading,
            isLoadingMore = state.isLoadingMore,
            hasData = state.hasData,
            days = state.days.map { day(it, state.flashUuids, state.selectedUuids, scale) },
            totalCount = state.totalCount,
            canLoadMore = state.canLoadMore,
            errorCode = state.error.name.lowercase().split("_").mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar { c -> c.uppercaseChar() }
            }.joinToString(""),
            isEditing = edit != null,
            editUuid = edit?.uuid ?: "",
            editIsSend = edit?.isSend ?: true,
            editTries = edit?.bidCount ?: 1,
            editQuality = edit?.quality ?: 0,
            editComment = edit?.comment ?: "",
            deleteConfirmUuid = state.deleteConfirmUuid ?: "",
            selectedCount = state.selectedUuids.size,
            showBatchDeleteConfirm = state.showBatchDeleteConfirm,
            outcomeFilterCode = LogbookCodes.outcome(state.outcomeFilter),
            listBoardFilter = state.listBoardFilter ?: "",
            angleFilter = state.angleFilter ?: -1,
            availableAngles = state.availableAngles,
            availableBoardWires = state.availableBoardBrands,
            availableBoardTitles = state.availableBoardBrands.map { boardTitle(it) },
            intervalCode = LogbookCodes.interval(state.statsInterval),
            customFrom = state.customDateFrom?.toString() ?: "",
            customTo = state.customDateTo?.toString() ?: "",
            statsBoardFilter = state.statsBoardFilter ?: "",
            stats = stats(state.stats, scale),
            boardComparison = state.boardComparison.map { comparison(it) },
        )
    }

    private fun day(
        day: LogbookDay,
        flashUuids: Set<String>,
        selected: Set<String>,
        scale: GradeScale,
    ) = LogbookDayUi(
        date = day.date,
        entries = day.entries.map { entry(it, it.uuid in flashUuids, it.uuid in selected, scale) },
        sendCount = day.sendCount,
        attemptCount = day.attemptCount,
    )

    private fun entry(
        row: AscentWithClimb,
        isFlash: Boolean,
        isSelected: Boolean,
        scale: GradeScale,
    ) = LogbookEntryUi(
        uuid = row.uuid,
        climbUuid = row.climbUuid,
        climbName = row.climbName,
        grade = grade(row.difficultyAverage, scale),
        angle = row.angle.toInt(),
        isMirror = row.isMirror,
        isSend = row.isSend,
        isFlash = isFlash,
        tries = row.bidCount.coerceAtLeast(1L).toInt(),
        quality = row.quality?.toInt() ?: 0,
        comment = row.comment ?: "",
        boardWire = row.boardBrand,
        loggedAt = row.climbedAt,
        isSelected = isSelected,
    )

    private fun stats(stats: BoardLogbookStats, scale: GradeScale): LogbookStatsUi {
        val activity = stats.activityMap.entries.sortedBy { it.key }
        val progression = stats.gradeProgression
        return LogbookStatsUi(
            hardestGrade = stats.hardestGrade ?: "",
            hardestDifficulty = stats.hardestDifficultyInt,
            totalSends = stats.totalSends,
            totalAttempts = stats.totalAttempts,
            boulderSends = stats.boulderSends,
            routeSends = stats.routeSends,
            flashRate = stats.flashRate,
            uniqueClimbs = stats.uniqueClimbs,
            sessionCount = stats.sessionCount,
            gradePyramid = stats.gradePyramid.map { bar(it) },
            angleDistribution = stats.angleDistribution.map { angleBar(it) },
            sendsOverTime = stats.sendsOverTime.map { bucket(it) },
            activityDates = activity.map { it.key },
            activityCounts = activity.map { it.value },
            gradeOutcomes = stats.gradeOutcomes.map { outcome(it) },
            outcomeFlashes = stats.outcomeDistribution.flashes,
            outcomeRedpoints = stats.outcomeDistribution.redpoints,
            outcomeAttempts = stats.outcomeDistribution.attempts,
            weeklyVolume = stats.weeklyVolume.map { volume(it) },
            gradeProgression = progression.mapIndexed { index, point -> progression(point, index, progression, scale) },
            uniqueClimbsByGrade = stats.uniqueClimbsByGrade.map { unique(it) },
            periodComparison = stats.periodComparison.let { comparison ->
                PeriodComparisonUi(
                    hasData = comparison != null,
                    intervalCode = comparison?.let { LogbookCodes.interval(it.interval) } ?: "",
                    sendsDelta = comparison?.totalSendsDelta ?: 0,
                    flashRateDelta = comparison?.flashRateDelta ?: 0f,
                    hardestGradeDelta = comparison?.hardestGradeDelta ?: 0,
                    uniqueClimbsDelta = comparison?.uniqueClimbsDelta ?: 0,
                )
            },
            records = PersonalRecordsUi(
                hardestFlashGrade = stats.personalRecords.hardestFlashGrade ?: "",
                hardestFlashDifficulty = stats.personalRecords.hardestFlashDifficulty,
                mostSendsInDay = stats.personalRecords.mostSendsInDay,
                mostSendsDate = stats.personalRecords.mostSendsDate ?: "",
                avgSessionsPerWeek = stats.personalRecords.avgSessionsPerWeek,
                weekStreak = stats.personalRecords.weekStreak,
            ),
        )
    }

    private fun bar(entry: BoardGradePyramidEntry) = GradeBarUi(entry.grade, entry.difficultyInt, entry.count)

    private fun angleBar(entry: AngleDistEntry) = AngleBarUi(entry.angle, entry.count)

    private fun bucket(entry: TimeBucketEntry) = TimeBucketUi(
        kind = LogbookCodes.bucket(entry.kind),
        year = entry.year,
        month = entry.month,
        day = entry.day,
        isoWeek = entry.isoWeek,
        count = entry.count,
    )

    private fun outcome(entry: GradeOutcomeEntry) = GradeOutcomeUi(
        grade = entry.grade,
        difficulty = entry.difficultyInt,
        flashes = entry.flashCount,
        redpoints = entry.redpointCount,
        attempts = entry.attemptCount,
        total = entry.total,
    )

    private fun unique(entry: UniqueClimbEntry) =
        UniqueClimbUi(entry.grade, entry.difficultyInt, entry.uniqueCount, entry.totalSends)

    private fun volume(entry: WeeklyVolumeEntry) = WeeklyVolumeUi(
        isoWeek = entry.isoWeek,
        weekBasedYear = entry.weekBasedYear,
        easy = entry.easyCount,
        medium = entry.mediumCount,
        hard = entry.hardCount,
        elite = entry.eliteCount,
        total = entry.total,
    )

    private fun progression(
        point: GradeProgressionPoint,
        index: Int,
        all: List<GradeProgressionPoint>,
        scale: GradeScale,
    ) = ProgressionPointUi(
        isoWeek = point.isoWeek,
        weekBasedYear = point.weekBasedYear,
        showYear = point.showYear,
        weekStartDate = point.weekStartIso,
        level = point.performanceDifficulty,
        levelGrade = grade(point.performanceDifficulty, scale),
        xFraction = com.cruxcoach.app.logbook.progressionXFraction(all, index),
    )

    private fun comparison(entry: BoardComparisonEntry) = BoardComparisonUi(
        boardWire = entry.boardBrand,
        boardTitle = boardTitle(entry.boardBrand),
        sends = entry.sendCount,
        attempts = entry.attemptCount,
        hardestGrade = entry.hardestGrade ?: "",
        hardestDifficulty = entry.hardestDifficultyInt,
    )

    private fun grade(difficulty: Double?, scale: GradeScale): String =
        difficulty?.let { BoardStatsComputer.formatDifficulty(it, scale) } ?: ""

    private fun boardTitle(wire: String): String = LogbookCodes.boardTitle(wire)
}

/** Projection history ("Verlauf"): the sends logged on the board, newest first. */
class HistoryScreenModel(private val presenter: HistoryPresenter) {

    val currentState: HistoryScreenState get() = map(presenter.state.value)

    fun watch(onState: (HistoryScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refreshSettings() = presenter.refreshSettings()
    fun close() = presenter.close()
    fun consumeError() = presenter.consumeError()

    fun setRetention(code: String) {
        LogbookCodes.retention(code)?.let { presenter.setRetention(it) }
    }

    fun toggleSelection(id: Long) = presenter.toggleSelection(id)
    fun toggleSelectAll() = presenter.toggleSelectAll()
    fun clearSelection() = presenter.clearSelection()
    fun deleteSelected() = presenter.deleteSelected()
    fun clearHistory() = presenter.clearHistory()

    private fun map(state: HistoryState) = HistoryScreenState(
        entries = state.entries.map { entry(it, it.id in state.selectedIds, state.gradeScale) },
        retentionCode = LogbookCodes.retention(state.retention),
        selectedCount = state.selectedIds.size,
        allSelected = state.allSelected,
        errorCode = when (state.error) {
            com.cruxcoach.app.logbook.HistoryError.NONE -> "none"
            com.cruxcoach.app.logbook.HistoryError.LOAD_FAILED -> "loadFailed"
            com.cruxcoach.app.logbook.HistoryError.DELETE_FAILED -> "deleteFailed"
            com.cruxcoach.app.logbook.HistoryError.CLEAR_FAILED -> "clearFailed"
            com.cruxcoach.app.logbook.HistoryError.SETTING_NOT_SAVED -> "settingNotSaved"
        },
    )

    private fun entry(row: ClimbHistoryEntry, isSelected: Boolean, scale: GradeScale) = HistoryEntryUi(
        id = row.id,
        climbUuid = row.climbUuid,
        climbName = row.climbName,
        grade = row.difficultyAverage?.let { BoardStatsComputer.formatDifficulty(it, scale) } ?: "",
        angle = row.angle,
        boardWire = row.boardBrand,
        loggedAt = row.climbedAt,
        recordedAt = row.recordedAt,
        isSelected = isSelected,
    )
}
