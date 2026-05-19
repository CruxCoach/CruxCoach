package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.settings.BoardModelSelectionDialog
import com.cruxcoach.android.ui.settings.GymBoardSearchSheet
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.util.GradeConverter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardFilterScreen(
    viewModel: BoardBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showBoardPicker by remember { mutableStateOf(false) }
    var showGymSearch by remember { mutableStateOf(false) }

    if (showBoardPicker) {
        // Combined all-16 board picker (Original/Homewall segment +
        // frequency sort), reused from settings. Confirming sets the
        // global board selection that drives the always-on
        // "fits my board" list filter.
        BoardModelSelectionDialog(
            productSizes = BoardConstants.KILTER_KNOWN_SIZES,
            frequency = BoardConstants.DEFAULT_SIZE_FREQUENCY,
            selectedId = state.boardSize?.id?.toInt() ?: 0,
            onConfirm = { id ->
                viewModel.selectBoard(id)
                showBoardPicker = false
            },
            onDismiss = { showBoardPicker = false },
            onFindViaGym = {
                showBoardPicker = false
                showGymSearch = true
            },
        )
    }

    if (showGymSearch) {
        // Same "don't know? find your gym" path as settings; on pick
        // it sets the global board selection (drives the fits filter).
        GymBoardSearchSheet(
            onPicked = { _, productSizeId, _ ->
                viewModel.selectBoard(productSizeId)
                showGymSearch = false
            },
            onFallbackToDirect = {
                showGymSearch = false
                showBoardPicker = true
            },
            onDismiss = { showGymSearch = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.board_filter_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Climbs count banner (what used to live on the browser screen).
            if (state.filteredCount >= 0) {
                val countText = if (state.filteredCount > state.climbs.size) {
                    stringResource(
                        R.string.board_browser_climbs_loaded,
                        state.filteredCount,
                        state.climbs.size
                    )
                } else {
                    stringResource(R.string.board_browser_climbs_count, state.filteredCount)
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = countText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Combined layout+size board selector, at the very top.
                // Short caption + the selected board name as an orange
                // (no-underline) link → the all-16 picker (which itself
                // hosts the "don't know? find your gym" entry). Either
                // path sets the global board selection that drives the
                // always-on "fits my board" filter.
                val boardLabel = state.boardSize
                    ?.let { BoardConstants.sizeLabel(it.id, it.name) }
                    ?: stringResource(R.string.settings_board_model_not_configured)
                Column {
                    Text(
                        stringResource(R.string.board_filter_selected_board),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = boardLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = OrangeAccent,
                        modifier = Modifier
                            .testTag("board_filter_board_link")
                            .clickable { showBoardPicker = true }
                            .padding(vertical = 2.dp),
                    )
                }

                Text(
                    stringResource(R.string.board_filter_angle, state.filter.angle),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = state.filter.angle.toFloat(),
                    onValueChange = { viewModel.setAngle(it.toInt()) },
                    onValueChangeFinished = { viewModel.commitFilterChange() },
                    valueRange = 0f..70f,
                    steps = 13,
                    modifier = Modifier.testTag("board_angle_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent
                    )
                )

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
                    modifier = Modifier.testTag("board_grade_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent
                    )
                )

                Text(
                    stringResource(R.string.board_filter_min_ascents, state.filter.minAscensionists),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = state.filter.minAscensionists.toFloat(),
                    onValueChange = { viewModel.setMinAscensionists(it.toInt()) },
                    onValueChangeFinished = { viewModel.commitFilterChange() },
                    valueRange = 0f..50f,
                    steps = 49,
                    colors = SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent
                    )
                )

                Text(
                    stringResource(R.string.board_filter_type),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    val typeOptions = listOf(
                        ClimbTypeFilter.BOULDER to stringResource(R.string.board_filter_type_boulder),
                        ClimbTypeFilter.ROUTE to stringResource(R.string.board_filter_type_routes),
                        ClimbTypeFilter.ALL to stringResource(R.string.board_filter_all)
                    )
                    typeOptions.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter.climbTypeFilter == filter,
                            onClick = { viewModel.updateClimbTypeFilter(filter) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            ),
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }

                Text(
                    stringResource(R.string.board_filter_status),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    val statusOptions = listOf(
                        ClimbStatusFilter.NEW to stringResource(R.string.board_filter_status_new),
                        ClimbStatusFilter.UNSENT to stringResource(R.string.board_filter_status_unsent),
                        ClimbStatusFilter.SENT to stringResource(R.string.board_filter_status_sent),
                        ClimbStatusFilter.ATTEMPTED to stringResource(R.string.board_filter_status_attempted),
                        ClimbStatusFilter.ALL to stringResource(R.string.board_filter_all)
                    )
                    statusOptions.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter.statusFilter == filter,
                            onClick = { viewModel.updateStatusFilter(filter) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            ),
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = state.filter.benchmarkOnly,
                        onClick = { viewModel.updateBenchmarkFilter(!state.filter.benchmarkOnly) },
                        label = {
                            Text(
                                stringResource(R.string.board_filter_benchmarks_only),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = if (state.filter.benchmarkOnly) {
                            {
                                Icon(
                                    Icons.Default.Verified,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )

                    // My-climbs toggle: drafts + published, all angles, all
                    // grades. Bypasses the regular paginated browse path so
                    // a draft saved at any angle stays discoverable.
                    FilterChip(
                        selected = state.filter.myClimbsOnly,
                        onClick = { viewModel.updateMyClimbsFilter(!state.filter.myClimbsOnly) },
                        label = {
                            Text(
                                stringResource(R.string.board_filter_my_climbs),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = if (state.filter.myClimbsOnly) {
                            {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                }

                // Provenance filter — schema column `origin`. CruxCoach
                // climbs are the ones authored via this app's editor;
                // Kilter climbs come from the official Kilter app and
                // were pulled by our daily mirror.
                Text(
                    stringResource(R.string.board_filter_origin),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    val originOptions = listOf(
                        OriginFilter.ALL to stringResource(R.string.board_filter_all),
                        OriginFilter.CRUXCOACH to stringResource(R.string.board_filter_origin_cruxcoach),
                        OriginFilter.KILTER to stringResource(R.string.board_filter_origin_kilter),
                    )
                    originOptions.forEach { (filter, label) ->
                        FilterChip(
                            selected = state.filter.originFilter == filter,
                            onClick = { viewModel.updateOriginFilter(filter) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            ),
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.board_filter_sort),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.toggleSortDirection() },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("board_sort_direction")
                    ) {
                        Icon(
                            if (state.filter.sortDirection == SortDirection.DESC)
                                Icons.Default.ArrowDownward
                            else Icons.Default.ArrowUpward,
                            contentDescription = stringResource(
                                if (state.filter.sortDirection == SortDirection.DESC)
                                    R.string.board_filter_sort_desc
                                else R.string.board_filter_sort_asc
                            ),
                            tint = OrangeAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .testTag("board_sort_row")
                ) {
                    val sortOptions = listOf(
                        ClimbSortField.ASCENSIONISTS to stringResource(R.string.board_sends),
                        ClimbSortField.REPEATS to stringResource(R.string.board_sort_repeats),
                        ClimbSortField.QUALITY to stringResource(R.string.board_quality),
                        ClimbSortField.HOLDS to stringResource(R.string.board_moves)
                    )
                    sortOptions.forEach { (field, label) ->
                        FilterChip(
                            selected = state.filter.sortField == field,
                            onClick = { viewModel.updateSortField(field) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            ),
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
