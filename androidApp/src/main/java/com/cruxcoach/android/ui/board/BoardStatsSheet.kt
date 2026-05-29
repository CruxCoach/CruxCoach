package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.GradeScale
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import java.time.LocalDate

@Composable
private fun statsTimeIntervalLabel(interval: StatsTimeInterval): String = when (interval) {
    StatsTimeInterval.ALL -> stringResource(R.string.board_stats_interval_all)
    StatsTimeInterval.DAYS_30 -> stringResource(R.string.board_stats_interval_30d)
    StatsTimeInterval.DAYS_90 -> stringResource(R.string.board_stats_interval_90d)
    StatsTimeInterval.YEAR_1 -> stringResource(R.string.board_stats_interval_1y)
}

@Composable
private fun heatmapModeLabel(mode: HeatmapMode): String = when (mode) {
    HeatmapMode.OFF -> stringResource(R.string.board_heatmap_off)
    HeatmapMode.GLOBAL -> stringResource(R.string.board_heatmap_global)
    HeatmapMode.PERSONAL -> stringResource(R.string.board_heatmap_personal)
    HeatmapMode.START -> stringResource(R.string.board_heatmap_start)
    HeatmapMode.HAND -> stringResource(R.string.board_heatmap_hand)
    HeatmapMode.FOOT -> stringResource(R.string.board_heatmap_foot)
    HeatmapMode.FINISH -> stringResource(R.string.board_heatmap_finish)
}

@Composable
private fun gradeChartViewLabel(view: GradeChartView): String = when (view) {
    GradeChartView.PYRAMID -> stringResource(R.string.board_stats_grade_pyramid)
    GradeChartView.FLASH_SEND_ATTEMPT -> stringResource(R.string.board_stats_flash_send_attempt)
    GradeChartView.OUTCOME_DONUT -> stringResource(R.string.board_stats_outcome_distribution)
    GradeChartView.UNIQUE_CLIMBS -> stringResource(R.string.board_stats_unique)
}

@Composable
private fun timeChartViewLabel(view: TimeChartView): String = when (view) {
    TimeChartView.SENDS_OVER_TIME -> stringResource(R.string.board_stats_sends_over_time)
    TimeChartView.WEEKLY_VOLUME -> stringResource(R.string.board_stats_weekly_volume)
    TimeChartView.GRADE_PROGRESSION -> stringResource(R.string.board_stats_grade_progression)
}

@Composable
private fun distributionChartViewLabel(view: DistributionChartView): String = when (view) {
    DistributionChartView.ANGLE -> stringResource(R.string.board_stats_angle_distribution)
    DistributionChartView.PERIOD_COMPARISON -> stringResource(R.string.board_stats_period_comparison)
}

@Composable
private fun boardBrandLabel(brand: String): String = when (BoardBrand.fromWire(brand)) {
    BoardBrand.KILTER -> stringResource(R.string.board_selection_brand_kilter)
    BoardBrand.MOONBOARD -> stringResource(R.string.board_selection_brand_moonboard)
    else -> brand.replaceFirstChar { it.uppercase() }
}

/** Board-family selector for the per-board stats split. "Alle" (null) +
 *  one chip per board the user has logged on. Only shown for multi-board
 *  users (the caller gates on availableBoardBrands.size > 1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardFilterChips(
    brands: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.map_filter_show_all)) },
        )
        brands.forEach { brand ->
            FilterChip(
                selected = selected == brand,
                onClick = { onSelect(brand) },
                label = { Text(boardBrandLabel(brand)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BoardStatsSheet(
    stats: BoardLogbookStats,
    statsInterval: StatsTimeInterval,
    gradeChartView: GradeChartView,
    timeChartView: TimeChartView,
    distributionChartView: DistributionChartView,
    gradeScale: GradeScale,
    zones: IntensityZones? = null,
    customDateFrom: LocalDate? = null,
    customDateTo: LocalDate? = null,
    heatmapMode: HeatmapMode = HeatmapMode.PERSONAL,
    heatmapData: Map<Int, Float> = emptyMap(),
    placements: Map<Int, com.cruxcoach.data.repository.BoardPlacement> = emptyMap(),
    boardSize: com.cruxcoach.data.repository.BoardSize? = null,
    boardImages: List<com.cruxcoach.data.repository.BoardImage> = emptyList(),
    boardFilter: String? = null,
    availableBoardBrands: List<String> = emptyList(),
    onBoardFilterSelect: (String?) -> Unit = {},
    onIntervalSelect: (StatsTimeInterval) -> Unit,
    onGradeChartViewSelect: (GradeChartView) -> Unit,
    onTimeChartViewSelect: (TimeChartView) -> Unit,
    onDistributionChartViewSelect: (DistributionChartView) -> Unit,
    onCustomDateRange: (LocalDate, LocalDate) -> Unit,
    onHeatmapModeSelect: (HeatmapMode) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.board_stats_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Per-board split: only when the user has logged on >1 board.
            if (availableBoardBrands.size > 1) {
                BoardFilterChips(
                    brands = availableBoardBrands,
                    selected = boardFilter,
                    onSelect = onBoardFilterSelect,
                )
            }

            // Time interval chips + custom date
            StatsIntervalChips(
                selected = statsInterval,
                customDateFrom = customDateFrom,
                customDateTo = customDateTo,
                onSelect = onIntervalSelect,
                onCustomDateRange = onCustomDateRange
            )

            // Personal Records (always visible, compact)
            BoardPersonalRecordsRow(stats.personalRecords)

            // Activity heatmap (no dropdown — always the same)
            if (stats.activityMap.isNotEmpty()) {
                ChartSection(stringResource(R.string.board_stats_activity)) {
                    BoardActivityHeatmap(
                        activityMap = stats.activityMap,
                        interval = statsInterval
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HeatmapLegend()
                }
            }

            // --- Grad-Analyse (dropdown) ---
            if (stats.gradePyramid.isNotEmpty() || stats.gradeOutcomes.isNotEmpty()) {
                ChartSectionWithSelector(
                    options = GradeChartView.entries.toTypedArray(),
                    selected = gradeChartView,
                    onSelect = onGradeChartViewSelect,
                    labelOf = { gradeChartViewLabel(it) }
                ) {
                    when (gradeChartView) {
                        GradeChartView.PYRAMID -> {
                            BoardGradePyramidChart(
                                entries = stats.gradePyramid,
                                zones = zones,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((stats.gradePyramid.size * 36 + 20).dp)
                            )
                        }
                        GradeChartView.FLASH_SEND_ATTEMPT -> {
                            BoardGradeOutcomeChart(entries = stats.gradeOutcomes)
                        }
                        GradeChartView.OUTCOME_DONUT -> {
                            BoardOutcomeDonutChart(distribution = stats.outcomeDistribution)
                        }
                        GradeChartView.UNIQUE_CLIMBS -> {
                            BoardUniqueClimbsChart(entries = stats.uniqueClimbsByGrade)
                        }
                    }
                }
            }

            // --- Zeitverlauf (dropdown) ---
            if (stats.sendsOverTime.size >= 2 || stats.weeklyVolume.isNotEmpty()) {
                ChartSectionWithSelector(
                    options = TimeChartView.entries.toTypedArray(),
                    selected = timeChartView,
                    onSelect = onTimeChartViewSelect,
                    labelOf = { timeChartViewLabel(it) }
                ) {
                    when (timeChartView) {
                        TimeChartView.SENDS_OVER_TIME -> {
                            BoardSendsOverTimeChart(
                                entries = stats.sendsOverTime,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                            )
                        }
                        TimeChartView.WEEKLY_VOLUME -> {
                            BoardWeeklyVolumeChart(entries = stats.weeklyVolume)
                        }
                        TimeChartView.GRADE_PROGRESSION -> {
                            BoardGradeProgressionChart(
                                entries = stats.gradeProgression,
                                gradeScale = gradeScale
                            )
                        }
                    }
                }
            }

            // --- Verteilungen (dropdown) ---
            if (stats.angleDistribution.isNotEmpty() || stats.periodComparison != null) {
                ChartSectionWithSelector(
                    options = DistributionChartView.entries.toTypedArray(),
                    selected = distributionChartView,
                    onSelect = onDistributionChartViewSelect,
                    labelOf = { distributionChartViewLabel(it) }
                ) {
                    when (distributionChartView) {
                        DistributionChartView.ANGLE -> {
                            BoardAngleDistChart(entries = stats.angleDistribution)
                        }
                        DistributionChartView.PERIOD_COMPARISON -> {
                            val comparison = stats.periodComparison
                            if (comparison != null) {
                                BoardPeriodComparisonCard(comparison)
                            } else {
                                Text(
                                    stringResource(R.string.board_stats_select_period),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Heatmap section — full mode picker (Meine Sends / Global /
            // Start / Hand / Foot / Finish). Sits at the very bottom of
            // the sheet because the board rendering is the heaviest
            // visual and pushes the charts above into the initial view.
            if (placements.isNotEmpty()) {
                val sectionTitle = stringResource(
                    R.string.board_stats_heatmap_section,
                    heatmapModeLabel(heatmapMode)
                )
                ChartSection(sectionTitle) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HeatmapMode.entries
                            .filter { it != HeatmapMode.OFF }
                            .forEach { mode ->
                                FilterChip(
                                    selected = mode == heatmapMode,
                                    onClick = { onHeatmapModeSelect(mode) },
                                    label = {
                                        Text(
                                            heatmapModeLabel(mode),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OrangeAccent,
                                        selectedLabelColor = DarkBackground
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    KilterBoardVisualization(
                        holds = emptyList(),
                        placements = placements,
                        boardSize = boardSize,
                        boardImages = boardImages,
                        heatmapData = heatmapData.ifEmpty { null },
                        selectedHolds = emptySet(),
                        onHoldTapped = null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (heatmapData.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.board_stats_heatmap_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsIntervalChips(
    selected: StatsTimeInterval,
    customDateFrom: LocalDate? = null,
    customDateTo: LocalDate? = null,
    onSelect: (StatsTimeInterval) -> Unit,
    onCustomDateRange: ((LocalDate, LocalDate) -> Unit)? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val hasCustomRange = customDateFrom != null && customDateTo != null

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatsTimeInterval.entries.forEach { interval ->
            FilterChip(
                selected = interval == selected && !hasCustomRange,
                onClick = { onSelect(interval) },
                label = { Text(statsTimeIntervalLabel(interval), style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent,
                    selectedLabelColor = DarkBackground
                ),
                shape = RoundedCornerShape(20.dp)
            )
        }
        if (onCustomDateRange != null) {
            CustomDateChip(
                isActive = hasCustomRange,
                customFrom = customDateFrom,
                customTo = customDateTo,
                onClick = { showDatePicker = true }
            )
        }
    }

    if (showDatePicker && onCustomDateRange != null) {
        CustomDateRangeDialog(
            initialFrom = customDateFrom,
            initialTo = customDateTo,
            onConfirm = { from, to ->
                onCustomDateRange(from, to)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom?.let {
            it.toEpochDay() * 86400000L
        },
        initialSelectedEndDateMillis = initialTo?.let {
            it.toEpochDay() * 86400000L
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val startMillis = datePickerState.selectedStartDateMillis
                    val endMillis = datePickerState.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        val from = LocalDate.ofEpochDay(startMillis / 86400000L)
                        val to = LocalDate.ofEpochDay(endMillis / 86400000L)
                        onConfirm(from, to)
                    }
                }
            ) { Text(stringResource(R.string.action_ok), color = OrangeAccent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    ) {
        DateRangePicker(
            state = datePickerState,
            modifier = Modifier.height(460.dp),
            title = { Text(stringResource(R.string.board_stats_select_date_range), modifier = Modifier.padding(16.dp)) }
        )
    }
}
