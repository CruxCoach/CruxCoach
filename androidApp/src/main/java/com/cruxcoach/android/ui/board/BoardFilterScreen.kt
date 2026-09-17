package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoText
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.util.GradeConverter
import kotlin.math.roundToInt

import androidx.compose.material3.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardFilterScreen(viewModel: BoardBrowserViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.filter, state.filteredCount, state.isLoading, state.isLoadingMore) {
        if (state.filteredCount < 0 && !state.isLoading && !state.isLoadingMore) viewModel.requestFilteredCount()
    }
    val activeBrand = BoardBrand.fromWire(state.filter.boardBrand)
    var showTermInfo by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (showTermInfo) FilterTermInfoDialog { showTermInfo = false }
    val advancedLabels = buildList {
        if (state.filter.minAscensionists > 0) add(stringResource(R.string.board_filter_min_ascents, state.filter.minAscensionists))
        if (state.filter.benchmarkOnly) add(stringResource(R.string.board_filter_benchmarks_only))
        if (state.filter.myClimbsOnly) add(stringResource(R.string.board_filter_my_climbs))
        if (state.filter.ungradedOnly) add(stringResource(R.string.board_filter_ungraded_only))
        if (state.filter.originFilter != OriginFilter.ALL) add(stringResource(R.string.board_filter_origin))
        if (state.filter.climbTypeFilter != ClimbTypeFilter.BOULDER) add(stringResource(R.string.board_filter_type))
        if (state.filter.quantumRuleMask != 0L) add(stringResource(R.string.board_filter_quantum_rules))
        if (state.filter.quantumOverlapFilter.active) add(stringResource(R.string.board_filter_quantum_overlap_title))
    }
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.board_filter_title)) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            actions = {
                TextButton(onClick = { viewModel.clearAllBrowseFilters() }, modifier = Modifier.testTag("board_filter_reset")) { Text(stringResource(R.string.action_reset)) }
                IconButton(onClick = { showTermInfo = true }) { Icon(Icons.Outlined.Info, stringResource(R.string.board_filter_info_action)) }
            }, windowInsets = WindowInsets(0.dp),
        ) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).testTag("board_filter_show_results")) {
                    Text(if (state.filteredCount >= 0 && !state.isLoading)
                        stringResource(R.string.board_filter_show_count, state.filteredCount)
                    else stringResource(R.string.board_filter_show_results))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val frenchMode = state.gradeScale == GradeScale.FRENCH
                val vScaleIndices = remember { GradeConverter.V_SCALE_INDICES }
                val gradeStops =
                    if (frenchMode) GradeConverter.MAX_INDEX + 1 else vScaleIndices.size
                val gradeSliderMax = (gradeStops - 1).toFloat()

                fun toSliderPos(unifiedIndex: Int): Float {
                    if (frenchMode) return unifiedIndex.toFloat()
                    val pos = vScaleIndices.indexOfFirst { it >= unifiedIndex }
                    return (if (pos < 0) vScaleIndices.size - 1 else pos).toFloat()
                }

                fun toUnifiedIndex(sliderPos: Float): Int {
                    if (frenchMode) return sliderPos.toInt()
                    return vScaleIndices.getOrElse(sliderPos.toInt()) { vScaleIndices.last() }
                }

                val minLabel = GradeDisplayHelper.formatByIndex(
                    state.filter.minGradeIndex,
                    state.gradeScale
                )
                val maxLabel = GradeDisplayHelper.formatByIndex(
                    state.filter.maxGradeIndex,
                    state.gradeScale
                )
                Text(
                    stringResource(R.string.board_filter_grade_range, minLabel, maxLabel),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                RangeSlider(
                    value = toSliderPos(state.filter.minGradeIndex)..toSliderPos(state.filter.maxGradeIndex),
                    onValueChange = {
                        viewModel.setGradeRange(
                            toUnifiedIndex(it.start),
                            toUnifiedIndex(it.endInclusive)
                        )
                    },
                    onValueChangeFinished = { viewModel.commitFilterChange() },
                    valueRange = 0f..gradeSliderMax,
                    steps = (gradeStops - 2).coerceAtLeast(0),
                    // Inert while ungraded-only mode is active — the grade
                    // range is replaced by the "only NULL grades" predicate.
                    enabled = !state.filter.ungradedOnly && !state.filter.myClimbsOnly,
                    modifier = Modifier.testTag("board_grade_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent
                    )
                )


            if (state.filter.myClimbsOnly) Text(stringResource(R.string.board_filter_own_scope), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            BoardStatusFilter(statuses = state.filter.statusFilter, onChange = viewModel::updateStatusFilter, compact = true)
            HorizontalDivider()
            val sortOptions = listOf(
                ClimbSortField.ASCENSIONISTS to stringResource(R.string.board_sends),
                ClimbSortField.QUALITY to stringResource(R.string.board_quality),
                ClimbSortField.QUALITY_SENDS to stringResource(R.string.board_sort_quality_sends),
                ClimbSortField.HOLDS to stringResource(R.string.board_moves),
                ClimbSortField.NEWEST to stringResource(R.string.board_sort_newest),
                ClimbSortField.RANDOM to stringResource(R.string.board_sort_random),
            )
            FilterChoiceRow(stringResource(R.string.board_filter_sort), state.filter.sortField, sortOptions, "board_sort_row", viewModel::updateSortField)
            if (state.filter.sortField != ClimbSortField.RANDOM) {
                FilterChoiceRow(stringResource(R.string.board_filter_order), state.filter.sortDirection,
                    listOf(SortDirection.DESC to stringResource(R.string.board_filter_sort_desc), SortDirection.ASC to stringResource(R.string.board_filter_sort_asc)),
                    "board_sort_direction", { if (it != state.filter.sortDirection) viewModel.toggleSortDirection() })
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.board_filter_more)) },
                supportingContent = { Text(if (advancedLabels.isEmpty()) stringResource(R.string.board_filter_more_hint) else advancedLabels.joinToString(" · ")) },
                trailingContent = { Text(if (advanced) "−" else "+") },
                modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }.testTag("board_filter_more"),
            )
            if (advanced) {
                FilterSwitchRow(stringResource(R.string.board_filter_ungraded_only), state.filter.ungradedOnly, viewModel::updateUngradedOnlyFilter, "board_filter_ungraded_only")
                FilterSwitchRow(stringResource(R.string.board_filter_my_climbs), state.filter.myClimbsOnly, viewModel::updateMyClimbsFilter, "board_filter_my_climbs")
                if (activeBrand.supportsBenchmarkFilter) FilterSwitchRow(stringResource(R.string.board_filter_benchmarks_only), state.filter.benchmarkOnly, viewModel::updateBenchmarkFilter, "board_filter_benchmark")
                Text(stringResource(R.string.board_filter_min_ascents, state.filter.minAscensionists), style = MaterialTheme.typography.titleSmall)
                Slider(value = state.filter.minAscensionists.toFloat(), onValueChange = { viewModel.setMinAscensionists(it.toInt()) },
                    onValueChangeFinished = { viewModel.commitFilterChange() }, valueRange = 0f..50f, steps = 49,
                    enabled = !state.filter.myClimbsOnly)
                if (activeBrand.supportsClimbTypeFilter) FilterChoiceRow(stringResource(R.string.board_filter_type), state.filter.climbTypeFilter,
                    listOf(ClimbTypeFilter.BOULDER to stringResource(R.string.board_filter_type_boulder), ClimbTypeFilter.ROUTE to stringResource(R.string.board_filter_type_routes), ClimbTypeFilter.ALL to stringResource(R.string.board_filter_all)),
                    "board_filter_type", viewModel::updateClimbTypeFilter)
                val originOptions = buildList {
                    add(OriginFilter.ALL to stringResource(R.string.board_filter_all))
                    add(OriginFilter.CRUXCOACH to stringResource(R.string.board_filter_origin_cruxcoach))
                    add(OriginFilter.KILTER to stringResource(if (activeBrand == BoardBrand.QUANTUM) R.string.board_filter_origin_quantum else R.string.board_filter_origin_kilter))
                    if (activeBrand.supportsBoardSeshOrigin) add(OriginFilter.BOARDSESH to stringResource(R.string.board_filter_origin_boardsesh))
                }
                FilterChoiceRow(stringResource(R.string.board_filter_origin), state.filter.originFilter, originOptions, "board_filter_origin", viewModel::updateOriginFilter)
                if (activeBrand == BoardBrand.QUANTUM) {
                    Text(
                        stringResource(R.string.board_filter_quantum_rules),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val ruleOptions = listOf(
                            QuantumRuleFilter.STANDARD to stringResource(R.string.board_filter_quantum_standard),
                            QuantumRuleFilter.CAMPUSING to stringResource(R.string.board_filter_quantum_campusing),
                            QuantumRuleFilter.EDGE to stringResource(R.string.board_filter_quantum_edge),
                            QuantumRuleFilter.KICKPLATE to stringResource(R.string.board_filter_quantum_kickplate),
                            QuantumRuleFilter.MATCHING to stringResource(R.string.board_filter_quantum_matching),
                        )
                        ruleOptions.forEach { (rule, label) ->
                            FilterChip(
                                selected = (state.filter.quantumRuleMask and rule.bit) != 0L,
                                onClick = { viewModel.toggleQuantumRuleFilter(rule) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = OrangeAccent
                                ),
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        stringResource(R.string.board_filter_quantum_overlap_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val overlapOptions = listOf(
                            QuantumOverlapFilter.OFF to
                                stringResource(R.string.board_filter_quantum_overlap_off),
                            QuantumOverlapFilter.NONE to
                                stringResource(R.string.board_filter_quantum_overlap_none),
                            QuantumOverlapFilter.AT_MOST_ONE to
                                stringResource(R.string.board_filter_quantum_overlap_one),
                        )
                        overlapOptions.forEach { (option, label) ->
                            FilterChip(
                                selected = state.filter.quantumOverlapFilter == option,
                                onClick = { viewModel.setQuantumOverlapFilter(option) },
                                enabled = option == QuantumOverlapFilter.OFF ||
                                    state.quantumLayers.available,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = OrangeAccent,
                                ),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("board_filter_quantum_overlap_${option.name}"),
                            )
                        }
                    }
                    Text(
                        when {
                            state.quantumLayers.occupied && !state.quantumLayers.complete ->
                                stringResource(R.string.board_filter_quantum_overlap_incomplete)
                            !state.quantumLayers.occupied ->
                                stringResource(R.string.board_filter_quantum_overlap_inert)
                            state.filter.quantumOverlapFilter.active &&
                                state.quantumLayers.matchCount >= 0 -> stringResource(
                                R.string.board_filter_quantum_overlap_count,
                                state.quantumLayers.matchCount.toInt(),
                            )
                            else -> stringResource(R.string.board_filter_quantum_overlap_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!state.quantumLayers.complete &&
                            state.quantumLayers.occupied
                        ) WarningYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("board_filter_quantum_overlap_hint"),
                    )
                }


            }
        }
    }
}

@Composable
internal fun FilterSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun <T> FilterChoiceRow(title: String, selected: T, choices: List<Pair<T, String>>, tag: String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(choices.firstOrNull { it.first == selected }?.second.orEmpty()) },
        trailingContent = { Text("›") }, modifier = Modifier.clickable { open = true }.testTag(tag))
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value); open = false }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == selected, onClick = null); Text(label, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } })
}

internal fun BrowserFilterState.activeBrowseFilterCount(): Int = listOf(
    minGradeIndex != BrowserFilterState.DEFAULT_MIN_GRADE_INDEX || maxGradeIndex != BrowserFilterState.DEFAULT_MAX_GRADE_INDEX,
    minAscensionists > 0, searchQuery.isNotBlank(),
    statusFilter.isNotEmpty() && statusFilter.size != ClimbStatusFilter.entries.size,
    climbTypeFilter != ClimbTypeFilter.BOULDER, benchmarkOnly, originFilter != OriginFilter.ALL,
    quantumRuleMask != 0L, quantumOverlapFilter.active, myClimbsOnly, ungradedOnly,
).count { it }

/** Plain-language glossary for the browse filter / sort terms, opened from the
 *  ℹ action. Adapted to 0.2.0's multi-select status model (Neu / Versucht /
 *  Gesendet — no "Unsent" chip) and its added modes (ungraded-only, the
 *  quality×sends and random sorts). */
@Composable
private fun FilterTermInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_filter_info_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterTermEntry(R.string.board_filter_status_new, R.string.board_filter_info_status_new)
                FilterTermEntry(R.string.board_filter_status_attempted, R.string.board_filter_info_status_attempted)
                FilterTermEntry(R.string.board_filter_status_sent, R.string.board_filter_info_status_sent)
                FilterTermEntry(R.string.board_filter_benchmarks_only, R.string.board_filter_info_benchmarks)
                FilterTermEntry(R.string.board_filter_ungraded_only, R.string.board_filter_info_ungraded)
                FilterTermEntry(R.string.board_sends, R.string.board_filter_info_sort_sends)
                FilterTermEntry(R.string.board_quality, R.string.board_filter_info_sort_quality)
                FilterTermEntry(R.string.board_sort_quality_sends, R.string.board_filter_info_sort_quality_sends)
                FilterTermEntry(R.string.board_moves, R.string.board_filter_info_sort_moves)
                FilterTermEntry(R.string.board_sort_newest, R.string.board_filter_info_sort_newest)
                FilterTermEntry(R.string.board_sort_random, R.string.board_filter_info_sort_random)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun FilterTermEntry(termRes: Int, descriptionRes: Int) {
    InfoText(stringResource(termRes) + "\n" + stringResource(descriptionRes))
}
