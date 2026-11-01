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
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.domain.board.BoardBrand
import java.time.LocalDate

@Composable
internal fun statsTimeIntervalLabel(interval: StatsTimeInterval): String = when (interval) {
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
internal fun boardBrandLabel(brand: String): String = when (val b = BoardBrand.fromWire(brand)) {
    BoardBrand.KILTER -> stringResource(R.string.board_selection_brand_kilter)
    BoardBrand.MOONBOARD -> stringResource(R.string.board_selection_brand_moonboard)
    // Aurora-family + any future brand: the proper-noun display name (e.g.
    // "So iLL", "Grasshopper") rather than a naive capitalize of the wire value.
    else -> b.displayName
}

/** Board-family selector for the per-board stats split. "Alle" (null) +
 *  one chip per board the user has logged on, each with its sends in the
 *  period, so the chips double as the comparison of how much you climb on
 *  each board. Only shown for multi-board users (the caller gates on
 *  availableBoardBrands.size > 1). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardFilterChips(
    brands: List<String>,
    comparison: List<BoardComparisonEntry>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val byBoard = comparison.associateBy { it.boardBrand }
    val hardest = comparison.filter { it.hardestGrade != null }.maxByOrNull { it.hardestDifficultyInt }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = {
                BoardChipLabel(stringResource(R.string.map_filter_show_all), comparison.sumOf { it.sendCount }, hardest?.hardestGrade)
            },
        )
        brands.forEach { brand ->
            FilterChip(
                selected = selected == brand,
                onClick = { onSelect(brand) },
                label = { BoardChipLabel(boardBrandLabel(brand), byBoard[brand]?.sendCount ?: 0, byBoard[brand]?.hardestGrade) },
            )
        }
    }
}

/** "Kilter 11 · 7a": the board, its sends and its best grade in the period. */
@Composable
private fun BoardChipLabel(name: String, sends: Int, best: String?) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(name)
        Spacer(Modifier.width(6.dp))
        Text(
            listOfNotNull("$sends", best).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BoardStatsSheet(
    stats: BoardLogbookStats,
    statsInterval: StatsTimeInterval,
    gradeScale: GradeScale,
    zones: com.cruxcoach.domain.board.IntensityZones? = null,
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
    crossBoard: CrossBoardStats = CrossBoardStats(),
    crossBoardView: CrossBoardView = CrossBoardView.TRIES_UNTIL_SEND,
    onCrossBoardViewSelect: (CrossBoardView) -> Unit = {},
    progressionByBoard: Map<String, List<GradeProgressionPoint>> = emptyMap(),
    activityView: ActivityView = ActivityView.CALENDAR,
    onActivityViewSelect: (ActivityView) -> Unit = {},
    gradesView: GradesView = GradesView.PROBLEMS,
    onGradesViewSelect: (GradesView) -> Unit = {},
    heatmapBoardOptions: List<com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption> = emptyList(),
    heatmapBoardSelection: com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption? = null,
    onHeatmapBoardSelect: (com.cruxcoach.android.data.BoardConstants.HeatmapBoardOption?) -> Unit = {},
    onBoardFilterSelect: (String?) -> Unit = {},
    onIntervalSelect: (StatsTimeInterval) -> Unit,
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
            InfoHeading(stringResource(R.string.board_stats_title), stringResource(R.string.ux_stats_help))

            // Filters first: everything below follows the period and the board.
            StatsIntervalChips(
                selected = statsInterval,
                customDateFrom = customDateFrom,
                customDateTo = customDateTo,
                onSelect = onIntervalSelect,
                onCustomDateRange = onCustomDateRange
            )
            if (availableBoardBrands.size > 1) {
                BoardFilterChips(
                    brands = availableBoardBrands,
                    comparison = boardComparison,
                    selected = boardFilter,
                    onSelect = onBoardFilterSelect,
                )
            }

            // The period in four numbers; the cards below explain them.
            PeriodNumbers(stats)

            // How the boards feel against each other, when there is enough
            // data. Spans every board whatever the board filter is.
            crossBoard.feel?.let { feel ->
                BoardsCard(feel = feel, feelOrder = crossBoard.boards, allBoards = availableBoardBrands)
            }

            // How hard: the rolling level over time, one line per board when
            // several are shown together.
            if (stats.gradeProgression.size >= 2 || progressionByBoard.values.any { it.size >= 2 }) {
                ProgressCard(
                    single = stats.gradeProgression,
                    byBoard = progressionByBoard,
                    boardFilter = boardFilter,
                    allBoards = availableBoardBrands,
                    gradeScale = gradeScale,
                )
            }

            // Which grades: every problem once (flashed, sent later or open),
            // or every send with its repeats in the intensity-zone colours.
            GradesCard(
                outcomes = stats.gradeOutcomes,
                sends = stats.gradePyramid,
                zones = zones,
                view = gradesView,
                onSelect = onGradesViewSelect,
            )

            // How many tries: until the send, per grade, per month (#44).
            if (crossBoard.boards.isNotEmpty()) {
                CrossBoardSection(
                    stats = crossBoard,
                    view = crossBoardView,
                    onViewSelect = onCrossBoardViewSelect,
                    allBoards = availableBoardBrands,
                )
            }

            // When: the days as a calendar, or all entries or the sends over time.
            ActivityCard(
                view = activityView,
                onSelect = onActivityViewSelect,
                activityMap = stats.activityMap,
                daysPerWeek = stats.daysPerWeek,
                entries = stats.entriesOverTime,
                sends = stats.sendsOverTime,
                interval = statsInterval,
            )

            // At which angles.
            if (stats.angleDistribution.isNotEmpty()) {
                ChartSection(stringResource(R.string.board_stats_angle_distribution)) {
                    BoardAngleDistChart(entries = stats.angleDistribution)
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
            // The calendar needs a bounded height for its month list, but the text-input mode
            // must not keep it: with the keyboard open the fixed height pushed OK/Cancel off
            // screen (M-008). Input mode wraps its two fields instead.
            modifier = if (datePickerState.displayMode == DisplayMode.Input) {
                Modifier.heightIn(max = 460.dp)
            } else {
                Modifier.height(460.dp)
            },
            title = { Text(stringResource(R.string.board_stats_select_date_range), modifier = Modifier.padding(16.dp)) }
        )
    }
}
