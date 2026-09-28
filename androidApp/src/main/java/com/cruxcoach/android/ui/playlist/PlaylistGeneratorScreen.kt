package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.settings.SettingsChoices
import com.cruxcoach.android.ui.settings.SettingsGroupHeader
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.PlanSlot
import com.cruxcoach.domain.playlist.CandidateSelection
import com.cruxcoach.domain.playlist.PyramidShape
import com.cruxcoach.domain.playlist.SessionPosition
import com.cruxcoach.domain.playlist.TrainingRanges
import androidx.compose.ui.res.pluralStringResource
import com.cruxcoach.domain.playlist.structureRange
import com.cruxcoach.android.ui.board.ClimbStatusFilter
import com.cruxcoach.android.ui.board.OriginFilter
import com.cruxcoach.data.repository.ClimbTypeFilter
import kotlin.math.roundToInt
import androidx.compose.material3.RangeSlider

/**
 * Training-list generator, laid out by the 0.2.3 guidelines (design.md): one card per
 * decision — goal, session size, grades, position, which climbs — each showing its current
 * value, with explanations behind info buttons and the rare filters one tap away but named
 * while active. Numbers are set exactly with steppers; choices wrap instead of scrolling
 * sideways. The planned session and "Generate" stay pinned at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaylistGeneratorScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit,
    viewModel: PlaylistGeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    if (showFilterSheet) {
        GeneratorFilterSheet(
            state = state,
            onDismiss = { showFilterSheet = false },
            onBenchmarkOnlyChange = viewModel::setBenchmarkOnly,
            onStatusChange = viewModel::setStatusFilter,
            onOriginChange = viewModel::setOriginFilter,
            onClimbTypeChange = viewModel::setClimbType,
        )
    }

    // Successful generate -> jump into the fresh list, unless the filler had
    // to leave slots empty. That count was recorded and never shown: the list
    // simply came out shorter than the preview the user had just approved,
    // with nothing on screen accounting for the difference. Only a total
    // failure was reported. Say it before navigating — after the jump the
    // state is gone, both fields are written in the same frame.
    LaunchedEffect(state.createdListId, state.droppedClimbs) {
        val id = state.createdListId
        if (id != null && state.droppedClimbs == 0) {
            viewModel.consumeCreatedList()
            onNavigateToPlaylist(id)
        }
    }

    val pendingListId = state.createdListId
    if (pendingListId != null && state.droppedClimbs > 0) {
        AlertDialog(
            // No dismiss-by-tapping-away: this is the one moment the count
            // exists, and it is the reason the list disagrees with the preview.
            onDismissRequest = {},
            title = { Text(stringResource(R.string.playlist_generator_dropped_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.playlist_generator_dropped_body,
                        state.droppedClimbs,
                        state.droppedClimbs,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeCreatedList()
                    onNavigateToPlaylist(pendingListId)
                }) {
                    Text(stringResource(R.string.playlist_generator_dropped_confirm))
                }
            },
        )
    }

    if (showNameDialog) {
        val defaultName = defaultPlaylistName(state.type)
        NamePlaylistDialog(
            defaultName = defaultName,
            onConfirm = { name ->
                showNameDialog = false
                viewModel.generate(name)
            },
            onDismiss = { showNameDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // One line, always: at 320 dp with large type the old title broke inside a word.
                title = {
                    Text(
                        stringResource(R.string.playlist_generator_title),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        // The result and the one action that matters stay in reach however far the
        // settings above have been scrolled: what the session will be, and "Generate".
        bottomBar = {
            GenerateBar(
                state = state,
                onGenerate = { showNameDialog = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileHeader(state)

            // ── Goal ────────────────────────────────────────────
            SettingsSectionCard {
                InfoHeading(typeLabel(state.type), typeDescription(state.type))
                SettingsChoices(
                    options = GeneratorGoal.entries.map { it to generatorGoalLabel(it) },
                    selected = GeneratorGoal.forType(state.type),
                    onSelect = { viewModel.setType(it.defaultType) },
                    modifier = Modifier.testTag("playlist_gen_goals"),
                )
                val siblingTypes = GeneratorGoal.forType(state.type).types
                if (siblingTypes.size > 1) {
                    FieldLabel(stringResource(R.string.playlist_generator_variant))
                    SettingsChoices(
                        options = GeneratorType.entries.filter { it in siblingTypes }
                            .map { it to typeLabel(it) },
                        selected = state.type,
                        onSelect = viewModel::setType,
                        modifier = Modifier.testTag("playlist_gen_variants"),
                    )
                }
            }

            // ── Session size ────────────────────────────────────
            SettingsSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsGroupHeader(stringResource(R.string.playlist_generator_section_size))
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.playlist_generator_estimated, state.estimatedMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StepperRow(
                    label = stringResource(sizeLabelRes(state.type), state.structureSize),
                    value = state.structureSize,
                    range = state.type.structureRange(),
                    onChange = viewModel::setStructureSize,
                    tag = "playlist_gen_size",
                )
                when (state.type) {
                    GeneratorType.POWER_ENDURANCE -> StepperRow(
                        label = stringResource(
                            R.string.playlist_generator_problems_per_set, state.problemsPerSet,
                        ),
                        value = state.problemsPerSet,
                        range = TrainingRanges.PE_PROBLEMS_PER_SET_RANGE,
                        onChange = viewModel::setProblemsPerSet,
                        tag = "playlist_gen_pps",
                    )
                    GeneratorType.PYRAMID -> {
                        StepperRow(
                            label = stringResource(
                                R.string.playlist_generator_pyramid_per_tier,
                                state.pyramidClimbsPerTier,
                            ),
                            value = state.pyramidClimbsPerTier,
                            range = TrainingRanges.PYRAMID_CLIMBS_PER_TIER,
                            onChange = viewModel::setPyramidClimbsPerTier,
                            tag = "playlist_gen_pyramid_per_tier",
                        )
                        FieldLabel(stringResource(R.string.playlist_generator_pyramid_shape))
                        SettingsChoices(
                            options = PyramidShape.entries.map { it to pyramidShapeLabel(it) },
                            selected = state.pyramidShape,
                            onSelect = viewModel::setPyramidShape,
                        )
                    }
                    GeneratorType.LIMIT, GeneratorType.PROJECTING -> StepperRow(
                        label = stringResource(
                            R.string.playlist_manual_repeats, state.attemptsPerProblem,
                        ),
                        value = state.attemptsPerProblem,
                        range = TrainingRanges.ATTEMPTS_RANGE,
                        onChange = viewModel::setAttemptsPerProblem,
                        tag = "playlist_gen_attempts",
                    )
                    GeneratorType.MANUAL -> {
                        StepperRow(
                            label = stringResource(
                                R.string.playlist_manual_repeats, state.manualRepeats,
                            ),
                            value = state.manualRepeats,
                            range = TrainingRanges.MANUAL_REPEATS,
                            onChange = viewModel::setManualRepeats,
                            tag = "playlist_gen_manual_repeats",
                        )
                        FieldLabel(
                            stringResource(
                                R.string.playlist_manual_rest, restLabel(state.manualRestSeconds),
                            )
                        )
                        RestSlider(
                            seconds = state.manualRestSeconds,
                            onChange = viewModel::setManualRest,
                            tag = "playlist_gen_manual_rest",
                        )
                        // Only meaningful once a problem is climbed more than once.
                        if (state.manualRepeats > 1) {
                            FieldLabel(
                                stringResource(
                                    R.string.playlist_manual_repeat_rest,
                                    restLabel(state.manualRepeatRestSeconds),
                                )
                            )
                            RestSlider(
                                seconds = state.manualRepeatRestSeconds,
                                onChange = viewModel::setManualRepeatRest,
                                tag = "playlist_gen_manual_repeat_rest",
                            )
                        }
                    }
                    GeneratorType.VOLUME -> Unit
                }
            }

            // ── Grades ──────────────────────────────────────────
            // One card, one range: the manual band for a manual session, the
            // training range for everything else.
            val manual = state.type == GeneratorType.MANUAL
            val gradeLow = if (manual) state.manualMinDifficulty else state.targetMinDifficulty
            val gradeHigh = if (manual) state.manualMaxDifficulty else state.targetMaxDifficulty
            if (gradeLow != null && gradeHigh != null && gradeLow > 0.0) {
                SettingsSectionCard {
                    InfoHeading(
                        stringResource(
                            R.string.playlist_generator_grade_range,
                            GradeDisplayHelper.formatDifficulty(gradeLow, state.gradeScale),
                            GradeDisplayHelper.formatDifficulty(gradeHigh, state.gradeScale),
                        ),
                        stringResource(
                            when {
                                manual -> R.string.playlist_type_manual_desc
                                state.profilePersonalized -> R.string.playlist_generator_grade_range_hint
                                else -> R.string.playlist_generator_grade_range_hint_default
                            }
                        ),
                    )
                    RangeSlider(
                        value = gradeLow.toFloat()..gradeHigh.toFloat(),
                        onValueChange = {
                            val low = it.start.roundToInt().toDouble()
                            val high = it.endInclusive.roundToInt().toDouble()
                            if (manual) viewModel.setManualRange(low, high)
                            else viewModel.setTargetRange(low, high)
                        },
                        valueRange = TrainingRanges.MIN_DIFFICULTY.toFloat()..
                            TrainingRanges.MAX_DIFFICULTY.toFloat(),
                        steps = (TrainingRanges.MAX_DIFFICULTY -
                            TrainingRanges.MIN_DIFFICULTY).roundToInt() - 1,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = OrangeAccent,
                            activeTrackColor = OrangeAccent,
                        ),
                        modifier = Modifier.testTag(
                            if (manual) "playlist_gen_manual_range" else "playlist_gen_grade_range"
                        ),
                    )
                    if (!manual && state.gradeRangeCustomized) {
                        TextButton(
                            onClick = viewModel::useRecommendedRange,
                            modifier = Modifier.testTag("playlist_gen_recommended"),
                        ) {
                            Text(stringResource(R.string.playlist_generator_use_recommended))
                        }
                    }
                }
            }

            // ── Where in the session ────────────────────────────
            SettingsSectionCard {
                SettingsGroupHeader(stringResource(R.string.playlist_generator_position))
                SettingsChoices(
                    options = SessionPosition.entries.map { it to positionLabel(it) },
                    selected = state.position,
                    onSelect = viewModel::setPosition,
                    modifier = Modifier.testTag("playlist_gen_positions"),
                )
                if (state.plan?.downgradedFromType != null) {
                    WarningNote(stringResource(R.string.playlist_generator_downgraded))
                }
            }

            // ── Which climbs ────────────────────────────────────
            SettingsSectionCard {
                SettingsGroupHeader(stringResource(R.string.playlist_generator_selection))
                SettingsChoices(
                    options = CandidateSelection.entries.map { it to selectionLabel(it) },
                    selected = state.selection,
                    onSelect = viewModel::setSelection,
                    modifier = Modifier.testTag("playlist_gen_selection"),
                )
                InfoHeadingSmall(
                    stringResource(R.string.playlist_generator_min_ascents),
                    stringResource(R.string.playlist_generator_min_ascents_info),
                )
                val allLabel = stringResource(R.string.board_filter_all)
                SettingsChoices(
                    options = minAscentOptions(state.minAscensionists).map { count ->
                        count to if (count == 0) allLabel else "$count+"
                    },
                    selected = state.minAscensionists,
                    onSelect = viewModel::setMinAscensionists,
                    modifier = Modifier.testTag("playlist_gen_min_ascents"),
                )
                if (state.angleAdjustable) {
                    StepperRow(
                        label = stringResource(R.string.playlist_generator_angle, state.angle),
                        value = state.angle,
                        range = 0..70,
                        step = 5,
                        onChange = viewModel::setAngle,
                        tag = "playlist_gen_angle",
                        showSlider = false,
                    )
                }
                // Rare restrictions live one tap away — but never out of sight
                // while they are narrowing the result.
                val activeFilters = activeFilterLabelRes(state).map { stringResource(it) }
                TextButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("playlist_gen_filters"),
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (activeFilters.isEmpty()) {
                            stringResource(R.string.playlist_generator_more_filters)
                        } else {
                            stringResource(
                                R.string.playlist_generator_more_filters_active,
                                activeFilters.joinToString(" · "),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Preview ─────────────────────────────────────────
            state.plan?.let { plan ->
                SettingsGroupHeader(stringResource(R.string.playlist_generator_preview))
                PlanPreviewCard(
                    plan = plan,
                    estimatedMinutes = state.estimatedMinutes,
                    gradeScale = state.gradeScale,
                )
                if (plan.usedDefaultProfile) {
                    WarningNote(stringResource(R.string.playlist_generator_default_profile))
                }
            }
        }
    }
}

/** Summary of the session as planned, the error if the last attempt failed, and the action. */
@Composable
private fun GenerateBar(state: PlaylistGeneratorState, onGenerate: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.error) {
                WarningNote(
                    stringResource(
                        if (state.type == GeneratorType.MANUAL) {
                            R.string.playlist_generator_error_manual
                        } else R.string.playlist_generator_error
                    )
                )
            }
            state.plan?.let { plan ->
                val climbs = plan.slots.filterIsInstance<PlanSlot.ClimbSlot>()
                val (low, high) = workGradeRange(climbs)
                Text(
                    stringResource(
                        R.string.playlist_generator_total,
                        climbs.size,
                        state.estimatedMinutes,
                        GradeDisplayHelper.formatDifficulty(low, state.gradeScale),
                        GradeDisplayHelper.formatDifficulty(high, state.gradeScale),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("playlist_gen_total"),
                )
            }
            Button(
                onClick = onGenerate,
                enabled = !state.isGenerating && state.plan != null && state.profileLoaded,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("playlist_gen_generate"),
            ) {
                if (state.isGenerating || !state.profileLoaded) {
                    // Also while the logbook profile loads: a greyed-out button with its
                    // normal label gives no reason for being greyed out.
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DarkBackground,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DarkBackground)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.playlist_generator_generate),
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * A number the climber sets exactly: the current value in the label, a 48 dp button either
 * side, and — where the range is long enough to make tapping tedious — a slider underneath.
 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    tag: String,
    step: Int = 1,
    showSlider: Boolean = range.last - range.first > STEPPER_SLIDER_FROM,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onChange((value - step).coerceIn(range)) },
                enabled = value > range.first,
                modifier = Modifier.size(48.dp).testTag("${tag}_down"),
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.playlist_generator_decrease, label),
                )
            }
            IconButton(
                onClick = { onChange((value + step).coerceIn(range)) },
                enabled = value < range.last,
                modifier = Modifier.size(48.dp).testTag("${tag}_up"),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.playlist_generator_increase, label),
                )
            }
        }
        if (showSlider) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // One stop per valid value, so the thumb cannot land between two
                // sessions that do not exist.
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = OrangeAccent,
                    activeTrackColor = OrangeAccent,
                ),
                modifier = Modifier.testTag(tag),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A field label with its explanation one tap away, inside a card that already has a heading. */
@Composable
private fun InfoHeadingSmall(title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        InfoButton(title, description)
    }
}

/** The usual thresholds, plus whatever value came in from the board browser. */
internal fun minAscentOptions(current: Int): List<Int> =
    (listOf(0, 5, 20, 100, 500) + current).distinct().sorted()

/** The working grades of a plan in whole grades — the same range the slider names. */
private fun workGradeRange(climbSlots: List<PlanSlot.ClimbSlot>): Pair<Double, Double> {
    val work = climbSlots
        .filter { it.section != com.cruxcoach.domain.playlist.PlanSection.WARM_UP }
        .ifEmpty { climbSlots }
    if (work.isEmpty()) return TrainingRanges.MIN_DIFFICULTY to TrainingRanges.MIN_DIFFICULTY
    val low = kotlin.math.ceil(work.minOf { it.minDifficulty })
    return low to kotlin.math.floor(work.maxOf { it.maxDifficulty }).coerceAtLeast(low)
}

/** Labels of the rarely-used restrictions that are currently narrowing the candidates. */
private fun activeFilterLabelRes(state: PlaylistGeneratorState): List<Int> = buildList {
    if (state.benchmarkOnly) add(R.string.board_filter_benchmarks_only)
    statusOptions.firstOrNull { it.first == state.statusFilter && it.first.isNotEmpty() }
        ?.let { add(it.second) }
    originOptions.firstOrNull { it.first == state.originFilter && it.first != OriginFilter.ALL }
        ?.let { add(it.second) }
    climbTypeOptions
        .firstOrNull { it.first == state.climbType && it.first != ClimbTypeFilter.BOULDER }
        ?.let { add(it.second) }
}

private val statusOptions = listOf(
    emptySet<ClimbStatusFilter>() to R.string.board_filter_all,
    setOf(ClimbStatusFilter.NEW) to R.string.board_filter_status_new,
    setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED) to R.string.board_filter_status_unsent,
    setOf(ClimbStatusFilter.SENT) to R.string.board_filter_status_sent,
)

private val originOptions = listOf(
    OriginFilter.ALL to R.string.board_filter_all,
    OriginFilter.CRUXCOACH to R.string.board_filter_origin_cruxcoach,
    OriginFilter.KILTER to R.string.board_filter_origin_kilter,
    OriginFilter.BOARDSESH to R.string.board_filter_origin_boardsesh,
)

private val climbTypeOptions = listOf(
    ClimbTypeFilter.BOULDER to R.string.board_filter_type_boulder,
    ClimbTypeFilter.ROUTE to R.string.board_filter_type_routes,
    ClimbTypeFilter.ALL to R.string.board_filter_all,
)

/** From this many steps on, the stepper gets a slider as well. */
private const val STEPPER_SLIDER_FROM = 8

@Composable
private fun ProfileHeader(state: PlaylistGeneratorState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (state.profilePersonalized) {
                    stringResource(
                        R.string.playlist_generator_profile,
                        state.maxGradeLabel ?: "–",
                        state.flashGradeLabel ?: "–",
                    )
                } else {
                    stringResource(R.string.playlist_generator_profile_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bar-per-climb grade curve + slot summary. Rest blocks render as narrow
 *  dim bars so the session's structure (sets, breaks) stays visible. */
@Composable
private fun PlanPreviewCard(
    plan: com.cruxcoach.domain.playlist.PlaylistPlan,
    estimatedMinutes: Int,
    gradeScale: com.cruxcoach.android.data.GradeScale,
) {
    val climbSlots = plan.slots.filterIsInstance<PlanSlot.ClimbSlot>()
    if (climbSlots.isEmpty()) return
    val minDiff = climbSlots.minOf { it.minDifficulty }
    val maxDiff = climbSlots.maxOf { it.maxDifficulty }
    val span = (maxDiff - minDiff).coerceAtLeast(1.0)
    // The summary names the WORKING grades, the same ones the range slider shows. Spanning
    // the warm-up as well, it read "4a–6b" under a slider that said "5c–6b+".
    val workSlots = climbSlots.filter { it.section != com.cruxcoach.domain.playlist.PlanSection.WARM_UP }
        .ifEmpty { climbSlots }
    val workLow = kotlin.math.ceil(workSlots.minOf { it.minDifficulty })
    val workHigh = kotlin.math.floor(workSlots.maxOf { it.maxDifficulty }).coerceAtLeast(workLow)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.testTag("playlist_gen_preview"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                plan.slots.forEach { slot ->
                    when (slot) {
                        is PlanSlot.ClimbSlot -> {
                            val center = (slot.minDifficulty + slot.maxDifficulty) / 2
                            val frac = (((center - minDiff) / span) * 0.7 + 0.3).toFloat()
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .height((72 * frac).dp)
                                    .background(
                                        OrangeAccent.copy(
                                            alpha = if (slot.section == com.cruxcoach.domain.playlist.PlanSection.WARM_UP) 0.45f else 0.9f,
                                        ),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                    ),
                            )
                        }
                        is PlanSlot.RestSlot -> Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(14.dp)
                                .background(InfoBlue.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            val restCount = plan.slots.count { it is PlanSlot.RestSlot }
            Text(
                stringResource(
                    R.string.playlist_generator_summary,
                    climbSlots.size,
                    restCount,
                    estimatedMinutes,
                    GradeDisplayHelper.formatDifficulty(workLow, gradeScale),
                    GradeDisplayHelper.formatDifficulty(workHigh, gradeScale),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun WarningNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = WarningYellow,
    )
}

/** Four user-facing goals keep the first decision small. The persisted engine
 * types remain unchanged so old generated playlists can still be replayed. */
private enum class GeneratorGoal(
    val types: Set<GeneratorType>,
    val defaultType: GeneratorType,
) {
    PYRAMID(setOf(GeneratorType.PYRAMID), GeneratorType.PYRAMID),
    ENDURANCE(
        setOf(GeneratorType.VOLUME, GeneratorType.POWER_ENDURANCE),
        GeneratorType.VOLUME,
    ),
    HARD(
        setOf(GeneratorType.LIMIT, GeneratorType.PROJECTING),
        GeneratorType.LIMIT,
    ),
    CUSTOM(setOf(GeneratorType.MANUAL), GeneratorType.MANUAL),
    ;

    fun contains(type: GeneratorType): Boolean = type in types

    companion object {
        fun forType(type: GeneratorType): GeneratorGoal = entries.first { it.contains(type) }
    }
}

@Composable
private fun generatorGoalLabel(goal: GeneratorGoal): String = stringResource(
    when (goal) {
        GeneratorGoal.PYRAMID -> R.string.playlist_goal_pyramid
        GeneratorGoal.ENDURANCE -> R.string.playlist_goal_endurance
        GeneratorGoal.HARD -> R.string.playlist_goal_hard
        GeneratorGoal.CUSTOM -> R.string.playlist_goal_custom
    }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GeneratorFilterSheet(
    state: PlaylistGeneratorState,
    onDismiss: () -> Unit,
    onBenchmarkOnlyChange: (Boolean) -> Unit,
    onStatusChange: (Set<ClimbStatusFilter>) -> Unit,
    onOriginChange: (OriginFilter) -> Unit,
    onClimbTypeChange: (ClimbTypeFilter) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.playlist_generator_more_filters),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.playlist_generator_filters_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FilterChip(
                selected = state.benchmarkOnly,
                onClick = { onBenchmarkOnlyChange(!state.benchmarkOnly) },
                label = { Text(stringResource(R.string.board_filter_benchmarks_only)) },
            )

            SectionTitle(stringResource(R.string.board_filter_status))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                statusOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = state.statusFilter == value,
                        onClick = { onStatusChange(value) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.board_filter_origin))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                originOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = state.originFilter == value,
                        onClick = { onOriginChange(value) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            SectionTitle(stringResource(R.string.board_filter_type))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                climbTypeOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = state.climbType == value,
                        onClick = { onClimbTypeChange(value) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun NamePlaylistDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_generator_name_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_gen_name_field"),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("playlist_gen_name_confirm"),
            ) { Text(stringResource(R.string.playlist_generator_generate), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun typeLabel(type: GeneratorType): String = stringResource(
    when (type) {
        GeneratorType.PYRAMID -> R.string.playlist_type_pyramid
        GeneratorType.MANUAL -> R.string.playlist_type_manual
        GeneratorType.POWER_ENDURANCE -> R.string.playlist_type_power_endurance
        GeneratorType.VOLUME -> R.string.playlist_type_volume
        GeneratorType.LIMIT -> R.string.playlist_type_limit
        GeneratorType.PROJECTING -> R.string.playlist_type_projecting
    }
)

@Composable
private fun typeDescription(type: GeneratorType): String = stringResource(
    when (type) {
        GeneratorType.PYRAMID -> R.string.playlist_type_pyramid_desc
        GeneratorType.MANUAL -> R.string.playlist_type_manual_desc
        GeneratorType.POWER_ENDURANCE -> R.string.playlist_type_power_endurance_desc
        GeneratorType.VOLUME -> R.string.playlist_type_volume_desc
        GeneratorType.LIMIT -> R.string.playlist_type_limit_desc
        GeneratorType.PROJECTING -> R.string.playlist_type_projecting_desc
    }
)

@Composable
private fun RestSlider(seconds: Int, onChange: (Int) -> Unit, tag: String) {
    Slider(
        value = seconds.toFloat(),
        onValueChange = {
            // Quarter-minute steps: finer than that is not a rest anyone times.
            val step = TrainingRanges.MANUAL_REST_STEP
            onChange((it / step).roundToInt() * step)
        },
        valueRange = TrainingRanges.MANUAL_REST_SECONDS.first.toFloat()..
            TrainingRanges.MANUAL_REST_SECONDS.last.toFloat(),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = OrangeAccent,
            activeTrackColor = OrangeAccent,
        ),
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun restLabel(seconds: Int): String = when {
    seconds <= 0 -> stringResource(R.string.playlist_manual_rest_none)
    seconds < 60 -> stringResource(R.string.playlist_manual_rest_seconds, seconds)
    else -> stringResource(R.string.playlist_manual_rest_minutes, seconds / 60, seconds % 60)
}

private fun sizeLabelRes(type: GeneratorType): Int = when (type) {
    GeneratorType.VOLUME -> R.string.playlist_generator_size_volume
    GeneratorType.LIMIT -> R.string.playlist_generator_size_limit
    GeneratorType.PROJECTING -> R.string.playlist_generator_size_projects
    GeneratorType.POWER_ENDURANCE -> R.string.playlist_generator_size_sets
    GeneratorType.PYRAMID -> R.string.playlist_generator_size_tiers
    GeneratorType.MANUAL -> R.string.playlist_generator_size_manual
}

@Composable
private fun pyramidShapeLabel(shape: PyramidShape): String = stringResource(
    when (shape) {
        PyramidShape.ASCENDING -> R.string.playlist_pyramid_ascending
        PyramidShape.UP_AND_DOWN -> R.string.playlist_pyramid_up_and_down
    }
)

@Composable
private fun selectionLabel(selection: CandidateSelection): String = stringResource(
    when (selection) {
        CandidateSelection.NEW -> R.string.playlist_selection_new
        CandidateSelection.PROJECTS -> R.string.playlist_selection_projects
        CandidateSelection.ALL -> R.string.playlist_selection_all
    }
)

@Composable
private fun positionLabel(position: SessionPosition): String = stringResource(
    when (position) {
        SessionPosition.START_COLD -> R.string.playlist_position_cold
        SessionPosition.WARMED_UP -> R.string.playlist_position_warm
        SessionPosition.END_OF_SESSION -> R.string.playlist_position_end
    }
)

@Composable
private fun defaultPlaylistName(type: GeneratorType): String = stringResource(
    when (type) {
        GeneratorType.PYRAMID -> R.string.playlist_default_name_pyramid
        GeneratorType.MANUAL -> R.string.playlist_default_name_manual
        GeneratorType.POWER_ENDURANCE -> R.string.playlist_default_name_power_endurance
        GeneratorType.VOLUME -> R.string.playlist_default_name_volume
        GeneratorType.LIMIT -> R.string.playlist_default_name_limit
        GeneratorType.PROJECTING -> R.string.playlist_default_name_projecting
    }
)
