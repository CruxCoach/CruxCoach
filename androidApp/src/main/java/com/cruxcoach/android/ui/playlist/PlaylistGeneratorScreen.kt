package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.roundToInt
import androidx.compose.material3.RangeSlider

/**
 * Generator wizard: session type, duration, session position, angle — with
 * a live grade-curve preview of the planned session. Generate persists the
 * generated list plus its training plan and opens the normal list detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistGeneratorScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit,
    viewModel: PlaylistGeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNameDialog by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.playlist_generator_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Profile header ──────────────────────────────────
            ProfileHeader(state)

            // ── Session type ────────────────────────────────────
            SectionTitle(stringResource(R.string.playlist_generator_type))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GeneratorType.entries.forEach { type ->
                    FilterChip(
                        selected = state.type == type,
                        onClick = { viewModel.setType(type) },
                        label = { Text(typeLabel(type)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.testTag("playlist_gen_type_${type.name.lowercase()}"),
                    )
                }
            }
            Text(
                typeDescription(state.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Size ────────────────────────────────────────────
            // The slider sets the structure, not the clock. A duration slider
            // could only be divided down into one of these anyway, so it was
            // finer than its own effect — every setting from 40 to 150 minutes
            // produced the same four 4x4 sets — and it named a time the
            // session then did not take. Now the climber picks what the type
            // actually counts and the minutes follow, shown beside it.
            val range = state.type.structureRange()
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(
                    stringResource(sizeLabelRes(state.type), state.structureSize),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.playlist_generator_estimated, state.estimatedMinutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = state.structureSize.toFloat(),
                onValueChange = { viewModel.setStructureSize(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // One stop per valid value, so the thumb cannot land between
                // two sessions that do not exist.
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = OrangeAccent,
                    activeTrackColor = OrangeAccent,
                ),
                modifier = Modifier.testTag("playlist_gen_size"),
            )

            // ── Session position ────────────────────────────────
            SectionTitle(stringResource(R.string.playlist_generator_position))
            // Scrollable single-line row — a fixed Row wraps the longest
            // chip ("Trainingsende") onto two lines on narrow screens.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SessionPosition.entries.forEach { pos ->
                    FilterChip(
                        selected = state.position == pos,
                        onClick = { viewModel.setPosition(pos) },
                        label = { Text(positionLabel(pos), maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.testTag("playlist_gen_pos_${pos.name.lowercase()}"),
                    )
                }
            }

            // ── Manual controls ─────────────────────────────────
            // Only in manual mode, and only the things it actually changes:
            // the band, how many tries each problem gets, and the two rests.
            // Everything else on this screen (board, angle, warm-up) applies
            // as it does elsewhere.
            if (state.type == GeneratorType.MANUAL) {
                SectionTitle(
                    stringResource(
                        R.string.playlist_manual_grade_range,
                        // The scale the climber reads everywhere else. The
                        // mapper's own formatGrade appends the raw difficulty
                        // ("V6 (22,0)") — a developer's view, and V-scale at
                        // that, in an app that shows Font grades by default.
                        GradeDisplayHelper.formatDifficulty(
                            state.manualMinDifficulty, state.gradeScale,
                        ),
                        GradeDisplayHelper.formatDifficulty(
                            state.manualMaxDifficulty, state.gradeScale,
                        ),
                    )
                )
                RangeSlider(
                    value = state.manualMinDifficulty.toFloat()..state.manualMaxDifficulty.toFloat(),
                    onValueChange = {
                        viewModel.setManualRange(
                            it.start.roundToInt().toDouble(),
                            it.endInclusive.roundToInt().toDouble(),
                        )
                    },
                    valueRange = TrainingRanges.MIN_DIFFICULTY.toFloat()..
                        TrainingRanges.MAX_DIFFICULTY.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent,
                    ),
                    modifier = Modifier.testTag("playlist_gen_manual_range"),
                )

                SectionTitle(
                    stringResource(R.string.playlist_manual_repeats, state.manualRepeats)
                )
                Slider(
                    value = state.manualRepeats.toFloat(),
                    onValueChange = { viewModel.setManualRepeats(it.roundToInt()) },
                    valueRange = TrainingRanges.MANUAL_REPEATS.first.toFloat()..
                        TrainingRanges.MANUAL_REPEATS.last.toFloat(),
                    steps = TrainingRanges.MANUAL_REPEATS.last -
                        TrainingRanges.MANUAL_REPEATS.first - 1,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = OrangeAccent,
                        activeTrackColor = OrangeAccent,
                    ),
                    modifier = Modifier.testTag("playlist_gen_manual_repeats"),
                )

                SectionTitle(
                    stringResource(R.string.playlist_manual_rest, restLabel(state.manualRestSeconds))
                )
                RestSlider(
                    seconds = state.manualRestSeconds,
                    onChange = viewModel::setManualRest,
                    tag = "playlist_gen_manual_rest",
                )

                // Only meaningful once a problem is climbed more than once.
                if (state.manualRepeats > 1) {
                    SectionTitle(
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

            // ── Pyramid shape ───────────────────────────────────
            // Whether the pyramid comes back down was decided by the duration
            // alone, so every session under 90 minutes was half a pyramid
            // under a name that promises a whole one.
            if (state.type == GeneratorType.PYRAMID) {
                SectionTitle(stringResource(R.string.playlist_generator_pyramid_shape))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PyramidShape.entries.forEach { shape ->
                        FilterChip(
                            selected = state.pyramidShape == shape,
                            onClick = { viewModel.setPyramidShape(shape) },
                            label = { Text(pyramidShapeLabel(shape), maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier
                                .testTag("playlist_gen_pyramid_${shape.name.lowercase()}"),
                        )
                    }
                }
            }

            // ── Candidate selection (soft preference) ───────────
            SectionTitle(stringResource(R.string.playlist_generator_selection))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CandidateSelection.entries.forEach { sel ->
                    FilterChip(
                        selected = state.selection == sel,
                        onClick = { viewModel.setSelection(sel) },
                        label = { Text(selectionLabel(sel), maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.testTag("playlist_gen_sel_${sel.name.lowercase()}"),
                    )
                }
            }

            // ── Angle ───────────────────────────────────────────
            if (state.angleAdjustable) {
                SectionTitle(stringResource(R.string.playlist_generator_angle, state.angle))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = { viewModel.setAngle(state.angle - 5) },
                        modifier = Modifier.testTag("playlist_gen_angle_down"),
                    ) { Text("−5°") }
                    Text(
                        "${state.angle}°",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = { viewModel.setAngle(state.angle + 5) },
                        modifier = Modifier.testTag("playlist_gen_angle_up"),
                    ) { Text("+5°") }
                }
            }

            if (state.error) {
                WarningNote(
                    stringResource(
                        if (state.type == GeneratorType.MANUAL) {
                            R.string.playlist_generator_error_manual
                        } else R.string.playlist_generator_error
                    )
                )
            }

            // ── Preview ─────────────────────────────────────────
            state.plan?.let { plan ->
                SectionTitle(stringResource(R.string.playlist_generator_preview))
                PlanPreviewCard(
                    plan = plan,
                    estimatedMinutes = state.estimatedMinutes,
                    gradeScale = state.gradeScale,
                )
                if (plan.downgradedFromType != null) {
                    WarningNote(stringResource(R.string.playlist_generator_downgraded))
                }
                if (plan.usedDefaultProfile) {
                    WarningNote(stringResource(R.string.playlist_generator_default_profile))
                }
            }

            // ── Generate ────────────────────────────────────────
            Button(
                onClick = { showNameDialog = true },
                enabled = !state.isGenerating && state.plan != null,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_gen_generate"),
            ) {
                if (state.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(20.dp).height(20.dp),
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
                    GradeDisplayHelper.formatDifficulty(minDiff, gradeScale),
                    GradeDisplayHelper.formatDifficulty(maxDiff, gradeScale),
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
