package com.cruxcoach.android.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.cruxcoach.android.ui.settings.BoardPickerDialog
import com.cruxcoach.android.ui.settings.GymBoardSearchSheet
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.util.GradeConverter
import kotlin.math.roundToInt

/** A board's angle set renders as discrete chips up to this many angles
 *  (MoonBoard variants, near-fixed boards like Touchstone 35/40); above it a
 *  board-specific slider is used instead (Tension/Grasshopper/… ~14 angles). */
private const val MAX_ANGLE_CHIPS = 4

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoardFilterScreen(
    viewModel: BoardBrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeBrand = BoardBrand.fromWire(state.filter.boardBrand)
    var showBoardPicker by remember { mutableStateOf(false) }
    var showGymSearch by remember { mutableStateOf(false) }
    var showTermInfo by remember { mutableStateOf(false) }

    if (showTermInfo) {
        FilterTermInfoDialog(onDismiss = { showTermInfo = false })
    }

    if (showBoardPicker) {
        // Unified picker — Kilter Original / Kilter Homewall / MoonBoard.
        // Confirming sets the global board selection that drives the
        // always-on "fits my board" filter and the brand-aware browse.
        // FEAT-031: the one shared board picker (same as Settings / Onboarding /
        // sync card) — identical state + the full board list incl. the Aurora
        // family. Selection persists via the shared VM; the browse list reloads
        // reactively when the board prefs change.
        BoardPickerDialog(
            onDismiss = { showBoardPicker = false },
            onSelected = { showBoardPicker = false },
            onFindViaGym = {
                showBoardPicker = false
                showGymSearch = true
            },
        )
    }

    if (showGymSearch) {
        // Same "don't know? find your gym" path as settings; the sheet
        // persists the pick via the shared board-picker VM (all brands),
        // and the browse list reloads reactively from the board prefs.
        GymBoardSearchSheet(
            onClose = { showGymSearch = false },
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
                actions = {
                    // One-tap reset of every browse filter + sort back to
                    // defaults; keeps the board selection (board / layout /
                    // size / angle are identity, not filters).
                    TextButton(
                        onClick = { viewModel.clearAllBrowseFilters() },
                        modifier = Modifier.testTag("board_filter_reset")
                    ) {
                        Text(stringResource(R.string.action_reset), color = OrangeAccent)
                    }
                    IconButton(
                        onClick = { showTermInfo = true },
                        modifier = Modifier.testTag("board_filter_info")
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.board_filter_info_action)
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
                // FEAT-027/031: a MoonBoard has no Aurora product_size row, so
                // boardSize is null — label it by its variant name instead (the
                // same brand-aware logic as the Settings board section). Kilter
                // and the Aurora family keep the product-size label.
                val brand = BoardBrand.fromWire(state.filter.boardBrand)
                val boardLabel = when {
                    brand == BoardBrand.MOONBOARD ->
                        MoonBoardVariant.fromLayoutId(state.filter.layoutId.toLong())?.displayName
                            ?: stringResource(R.string.settings_board_model_not_configured)
                    // Aurora family: name WHICH board (Tension / Grasshopper / …),
                    // with the variant where one exists (Tension TB2 Mirror/Spray),
                    // then the product size — same as the Settings board section.
                    // Kilter is the default brand, so it stays size-only.
                    brand != BoardBrand.KILTER -> {
                        val type = BoardConstants.auroraVariant(brand, state.filter.layoutId)?.displayName
                            ?: brand.displayName
                        val size = state.boardSize
                            ?.let { BoardConstants.sizeLabel(it.id, it.name, it.boardBrand) }
                        if (size != null) "$type · $size" else type
                    }
                    else ->
                        state.boardSize
                            ?.let { BoardConstants.sizeLabel(it.id, it.name, it.boardBrand) }
                            ?: stringResource(R.string.settings_board_model_not_configured)
                }
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
                // FEAT-027/033: the angle control is board-specific.
                //  • A board with FEW discrete angles (a MoonBoard variant's
                //    fixed configs, or a near-fixed board like Touchstone 35/40)
                //    → chips.
                //  • A board with MANY angles spanning a range (Tension,
                //    Grasshopper, Decoy, So iLL — ~14 each) → a slider bounded by
                //    that board's real min..max (incl. negatives like Grasshopper
                //    -5°), so the user doesn't face a dozen chips.
                //  • Kilter (empty angleChips) → the historical 0-70° slider.
                val angleChips = state.filter.angleChips
                when {
                    angleChips.isNotEmpty() && angleChips.size <= MAX_ANGLE_CHIPS -> {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.testTag("board_angle_chips"),
                        ) {
                            angleChips.forEach { angle ->
                                FilterChip(
                                    selected = state.filter.angle == angle,
                                    onClick = {
                                        // Exact (no 5° slider snap) — values come
                                        // straight from the board's angle set and
                                        // may be negative.
                                        viewModel.setAngleExact(angle)
                                        viewModel.commitFilterChange()
                                    },
                                    label = { Text("$angle°") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                        selectedLabelColor = OrangeAccent,
                                    ),
                                )
                            }
                        }
                    }
                    angleChips.size > MAX_ANGLE_CHIPS -> {
                        // Slide over catalogue INDICES, not degree values. Real
                        // boards can have gaps (Quantum has 15→25); evenly spaced
                        // degree stops would make valid values such as 40°
                        // unreachable. Every stop now maps one-to-one to a real
                        // catalogue angle, including negative values.
                        val selectedIndex = BoardAnglePicker.sliderIndex(angleChips, state.filter.angle)
                        Slider(
                            value = selectedIndex.toFloat(),
                            onValueChange = {
                                viewModel.setAngleExact(
                                    BoardAnglePicker.angleAtSliderIndex(angleChips, it.roundToInt())
                                )
                            },
                            onValueChangeFinished = { viewModel.commitFilterChange() },
                            valueRange = 0f..angleChips.lastIndex.toFloat(),
                            steps = (angleChips.size - 2).coerceAtLeast(0),
                            modifier = Modifier.testTag("board_angle_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = OrangeAccent,
                                activeTrackColor = OrangeAccent
                            )
                        )
                    }
                    else -> {
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
                    }
                }

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
                    enabled = !state.filter.ungradedOnly,
                    modifier = Modifier.testTag("board_grade_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent
                    )
                )

                // Ungraded-only ("Projekte") mode: shows exactly the climbs
                // without a community grade. Sits in the grade section because
                // it takes over the grade predicate from the slider above.
                FilterChip(
                    selected = state.filter.ungradedOnly,
                    onClick = { viewModel.updateUngradedOnlyFilter(!state.filter.ungradedOnly) },
                    label = {
                        Text(
                            stringResource(R.string.board_filter_ungraded_only),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                        selectedLabelColor = OrangeAccent
                    ),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("board_filter_ungraded_only")
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

                if (activeBrand.supportsClimbTypeFilter) {
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
                    // "Alle" = clear the (multi-select) status filter. Highlighted
                    // when no status bucket is active; tapping it resets to "all".
                    FilterChip(
                        selected = state.filter.statusFilter.isEmpty(),
                        onClick = { viewModel.clearStatusFilter() },
                        label = {
                            Text(
                                stringResource(R.string.board_filter_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        ),
                        modifier = Modifier.height(32.dp)
                    )
                    // The three disjoint status buckets are individually
                    // toggleable and combine as an OR-union. "Offen" (= Neu +
                    // Versucht) drops out as a redundant preset.
                    val statusOptions = listOf(
                        ClimbStatusFilter.NEW to stringResource(R.string.board_filter_status_new),
                        ClimbStatusFilter.ATTEMPTED to stringResource(R.string.board_filter_status_attempted),
                        ClimbStatusFilter.SENT to stringResource(R.string.board_filter_status_sent),
                    )
                    statusOptions.forEach { (status, label) ->
                        FilterChip(
                            selected = status in state.filter.statusFilter,
                            onClick = { viewModel.toggleStatusFilter(status) },
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
                    if (activeBrand.supportsBenchmarkFilter) {
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
                    }

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

                if (activeBrand == BoardBrand.QUANTUM) {
                    Text(
                        stringResource(R.string.board_filter_quantum_rules),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
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
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }

                    // "What still fits on the wall", measured against the
                    // layers the controller confirms right now. Inert on every
                    // other board, and inert here while nothing is lit — a
                    // filter that narrows nothing should not claim to.
                    Text(
                        stringResource(R.string.board_filter_quantum_overlap_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                                    .height(32.dp)
                                    .testTag("board_filter_quantum_overlap_${option.name}"),
                            )
                        }
                    }
                    Text(
                        when {
                            !state.quantumLayers.available ->
                                stringResource(R.string.board_filter_quantum_overlap_inert)
                            !state.quantumLayers.complete ->
                                stringResource(R.string.board_filter_quantum_overlap_incomplete)
                            state.filter.quantumOverlapFilter.active &&
                                state.quantumLayers.matchCount >= 0 -> stringResource(
                                R.string.board_filter_quantum_overlap_count,
                                state.quantumLayers.matchCount.toInt(),
                            )
                            else -> stringResource(R.string.board_filter_quantum_overlap_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!state.quantumLayers.complete && state.quantumLayers.available) {
                            WarningYellow
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("board_filter_quantum_overlap_hint"),
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
                    val officialLabel = if (activeBrand == BoardBrand.QUANTUM) {
                        stringResource(R.string.board_filter_origin_quantum)
                    } else {
                        stringResource(R.string.board_filter_origin_kilter)
                    }
                    val originOptions = buildList {
                        add(
                        OriginFilter.ALL to stringResource(R.string.board_filter_all),
                        )
                        add(
                        OriginFilter.CRUXCOACH to stringResource(R.string.board_filter_origin_cruxcoach),
                        )
                        add(OriginFilter.KILTER to officialLabel)
                        if (activeBrand.supportsBoardSeshOrigin) {
                            add(OriginFilter.BOARDSESH to stringResource(R.string.board_filter_origin_boardsesh))
                        }
                    }
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
                        ClimbSortField.QUALITY to stringResource(R.string.board_quality),
                        ClimbSortField.QUALITY_SENDS to stringResource(R.string.board_sort_quality_sends),
                        ClimbSortField.HOLDS to stringResource(R.string.board_moves),
                        ClimbSortField.NEWEST to stringResource(R.string.board_sort_newest),
                        ClimbSortField.RANDOM to stringResource(R.string.board_sort_random),
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
    Column {
        Text(
            stringResource(termRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
