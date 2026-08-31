package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
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
}

@Composable
private fun distributionChartViewLabel(view: DistributionChartView): String = when (view) {
    DistributionChartView.ANGLE -> stringResource(R.string.board_stats_angle_distribution)
    DistributionChartView.PERIOD_COMPARISON -> stringResource(R.string.board_stats_period_comparison)
}

@Composable
private fun boardBrandLabel(brand: String): String = when (val b = BoardBrand.fromWire(brand)) {
    BoardBrand.KILTER -> stringResource(R.string.board_selection_brand_kilter)
    BoardBrand.MOONBOARD -> stringResource(R.string.board_selection_brand_moonboard)
    // Aurora-family + any future brand: the proper-noun display name (e.g.
    // "So iLL", "Grasshopper") rather than a naive capitalize of the wire value.
    else -> b.displayName
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

/** Compact per-board comparison: one row per board the user has logged on,
 *  showing sends and hardest sent grade over the current interval. Rows are
 *  tappable to scope the stats to that board (mirrors the filter chips), and
 *  the active board's row is highlighted. Kept as a summary list (not a chart)
 *  so it stays light at the top of the sheet. */
@Composable
private fun BoardComparisonSection(
    entries: List<BoardComparisonEntry>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    ChartSection(stringResource(R.string.board_stats_board_comparison)) {
        // Column header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                stringResource(R.string.board_stats_board_comparison_board),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.board_sends),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            Text(
                stringResource(R.string.board_logbook_best_grade),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        entries.forEach { entry ->
            val isActive = selected == entry.boardBrand
            Surface(
                color = if (isActive) OrangeAccent.copy(alpha = 0.15f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Toggle: tapping the active board returns to "all".
                        onSelect(if (isActive) null else entry.boardBrand)
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        boardBrandLabel(entry.boardBrand),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) OrangeAccent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${entry.sendCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        entry.hardestGrade ?: "-",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = GradeHard,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.width(72.dp),
                    )
                }
            }
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
    boardComparison: List<BoardComparisonEntry> = emptyList(),
    heatmapBoardOptions: List<com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption> = emptyList(),
    heatmapBoardSelection: com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption? = null,
    onHeatmapBoardSelect: (com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption?) -> Unit = {},
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
                // Compact board comparison: sends / top grade per board, so a
                // multi-board user can size them up side by side. Tapping a row
                // scopes the stats to that board (same as its filter chip).
                if (boardComparison.size > 1) {
                    BoardComparisonSection(
                        entries = boardComparison,
                        selected = boardFilter,
                        onSelect = onBoardFilterSelect,
                    )
                }
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

            // Central performance signal: a rolling four-week level based on
            // the best distinct sends, deliberately separate from volume.
            if (stats.gradeProgression.size >= 2) {
                ChartSection(stringResource(R.string.board_stats_grade_progression)) {
                    BoardGradeProgressionChart(
                        entries = stats.gradeProgression,
                        gradeScale = gradeScale,
                    )
                }
            }

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
            // Start / Hand / Foot / Finish) plus the FEAT-039 board selector.
            // Sits at the very bottom of the sheet because the board rendering
            // is the heaviest visual and pushes the charts above into the
            // initial view. Shown whenever at least one board is renderable
            // (heatmapBoardOptions); the per-grid canvas itself is hidden under
            // "Alle" (null selection) because disjoint placement-id spaces can't
            // be overlaid into one aggregate grid — only a hint is shown there.
            if (heatmapBoardOptions.isNotEmpty()) {
                val sectionTitle = stringResource(
                    R.string.board_stats_heatmap_section,
                    heatmapModeLabel(heatmapMode)
                )
                ChartSection(sectionTitle) {
                    // Board selector: "Alle" + one entry per renderable board
                    // (brand/layout/size/variant). Picking a board re-renders the
                    // heatmap on exactly that grid; "Alle" hides it.
                    HeatmapBoardSelector(
                        options = heatmapBoardOptions,
                        selected = heatmapBoardSelection,
                        onSelect = onHeatmapBoardSelect,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (heatmapBoardSelection == null) {
                        // "Alle"/GLOBAL: no specific board — the per-grid heatmap
                        // cannot represent disjoint grids honestly, so hide it and
                        // tell the user how to see one. The aggregate stats above
                        // stay all-boards.
                        Text(
                            stringResource(R.string.board_stats_heatmap_pick_board),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Per-board heatmap. The selected board is already named in
                        // the dropdown anchor above, so no separate label here.
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
                        // Capability gate keyed on the SELECTED brand (not the
                        // not-yet-loaded boardSize): the placement-id heatmap only
                        // applies to Aurora-protocol boards (Kilter + the Aurora
                        // family). MoonBoard has no Aurora placements — show the
                        // not-supported hint rather than an empty canvas. For a
                        // supported brand we render once placements have loaded;
                        // the brief pre-load window simply shows nothing (no
                        // misleading "unsupported" flash).
                        val brandHasHeatmap =
                            BoardBrand.fromWire(heatmapBoardSelection.brandWire).hasHeatmap
                        if (!brandHasHeatmap) {
                            Text(
                                stringResource(R.string.board_stats_heatmap_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (placements.isNotEmpty() && boardSize != null) {
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
    }
}

/**
 * Board selector for the stats hold-heatmap (FEAT-039). An
 * ExposedDropdownMenuBox (matching the chart-view selectors + the climb
 * editor's angle picker) listing "Alle" plus one entry per renderable board
 * (brand / layout / size / MoonBoard variant). "Alle" (null) hides the
 * per-grid heatmap; a concrete board renders it on that grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeatmapBoardSelector(
    options: List<com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption>,
    selected: com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption?,
    onSelect: (com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.map_filter_show_all)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.displayName ?: allLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.board_stats_heatmap_board_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // "Alle" — the aggregate view (no per-grid heatmap).
            DropdownMenuItem(
                text = {
                    Text(
                        allLabel,
                        fontWeight = if (selected == null) FontWeight.Bold else FontWeight.Normal
                    )
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.displayName,
                            fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
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
