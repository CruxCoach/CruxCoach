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
import com.cruxcoach.domain.playlist.SessionPosition
import com.cruxcoach.domain.playlist.TrainingRanges

/**
 * Generator wizard: session type, duration, session position, angle — with
 * a live grade-curve preview of the planned session. Generate persists the
 * snapshot playlist and navigates to its detail screen.
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

    // Successful generate → jump into the fresh playlist.
    LaunchedEffect(state.createdListId) {
        state.createdListId?.let { id ->
            viewModel.consumeCreatedList()
            onNavigateToPlaylist(id)
        }
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

            // ── Duration ────────────────────────────────────────
            SectionTitle(
                stringResource(R.string.playlist_generator_duration, state.durationMinutes),
            )
            Slider(
                value = state.durationMinutes.toFloat(),
                onValueChange = { viewModel.setDuration((it / 5).toInt() * 5) },
                valueRange = TrainingRanges.MIN_DURATION_MINUTES.toFloat()..TrainingRanges.MAX_DURATION_MINUTES.toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = OrangeAccent,
                    activeTrackColor = OrangeAccent,
                ),
                modifier = Modifier.testTag("playlist_gen_duration"),
            )

            // ── Session position ────────────────────────────────
            SectionTitle(stringResource(R.string.playlist_generator_position))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionPosition.entries.forEach { pos ->
                    FilterChip(
                        selected = state.position == pos,
                        onClick = { viewModel.setPosition(pos) },
                        label = { Text(positionLabel(pos)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.testTag("playlist_gen_pos_${pos.name.lowercase()}"),
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
            if (state.error) {
                WarningNote(stringResource(R.string.playlist_generator_error))
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        GeneratorType.POWER_ENDURANCE -> R.string.playlist_type_power_endurance_desc
        GeneratorType.VOLUME -> R.string.playlist_type_volume_desc
        GeneratorType.LIMIT -> R.string.playlist_type_limit_desc
        GeneratorType.PROJECTING -> R.string.playlist_type_projecting_desc
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
        GeneratorType.POWER_ENDURANCE -> R.string.playlist_default_name_power_endurance
        GeneratorType.VOLUME -> R.string.playlist_default_name_volume
        GeneratorType.LIMIT -> R.string.playlist_default_name_limit
        GeneratorType.PROJECTING -> R.string.playlist_default_name_projecting
    }
)
