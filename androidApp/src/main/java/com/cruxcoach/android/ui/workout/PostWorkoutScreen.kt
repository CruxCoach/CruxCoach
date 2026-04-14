package com.cruxcoach.android.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostWorkoutScreen(
    onComplete: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: PostWorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.isSaved) {
        onComplete()
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.post_workout_title)) })
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Duration summary
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = OrangeAccent.copy(alpha = 0.12f)
                )
            ) {
                if (state.durationMin > 0 || state.completedCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.durationMin}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent
                            )
                            Text(stringResource(R.string.workout_minutes), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.completedCount}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent
                            )
                            Text(stringResource(R.string.session_detail_exercises), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.workout_no_recording),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // RPE Slider
            SectionLabel(stringResource(R.string.post_workout_rpe))
            Column {
                Slider(
                    value = state.rpe,
                    onValueChange = { viewModel.updateRpe(it) },
                    valueRange = 1f..10f,
                    steps = 17, // 0.5 increments
                    modifier = Modifier.testTag("postworkout_rpe_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = rpeColor(state.rpe),
                        activeTrackColor = rpeColor(state.rpe)
                    )
                )
                Text(
                    text = stringResource(R.string.post_workout_rpe_display, "%.1f".format(state.rpe), rpeLabel(state.rpe)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // Energy Level
            SectionLabel(stringResource(R.string.post_workout_energy))
            EmojiSelector(
                options = listOf("1" to stringResource(R.string.workout_energy_exhausted), "2" to stringResource(R.string.workout_energy_tired), "3" to stringResource(R.string.workout_energy_normal), "4" to stringResource(R.string.workout_energy_good), "5" to stringResource(R.string.workout_energy_top)),
                emojis = listOf("\uD83D\uDE35", "\uD83D\uDE2A", "\uD83D\uDE10", "\uD83D\uDE04", "\uD83D\uDD25"),
                selected = state.energyLevel,
                onSelect = { viewModel.updateEnergyLevel(it) }
            )

            // Mood pre/post
            SectionLabel(stringResource(R.string.post_workout_mood_pre))
            EmojiSelector(
                options = listOf("1" to stringResource(R.string.workout_mood_bad), "2" to stringResource(R.string.workout_mood_fair), "3" to stringResource(R.string.workout_mood_ok), "4" to stringResource(R.string.workout_mood_good), "5" to stringResource(R.string.workout_mood_great)),
                emojis = listOf("\uD83D\uDE1E", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE42", "\uD83D\uDE01"),
                selected = state.moodPre,
                onSelect = { viewModel.updateMoodPre(it) }
            )

            SectionLabel(stringResource(R.string.post_workout_mood_post))
            EmojiSelector(
                options = listOf("1" to stringResource(R.string.workout_mood_bad), "2" to stringResource(R.string.workout_mood_fair), "3" to stringResource(R.string.workout_mood_ok), "4" to stringResource(R.string.workout_mood_good), "5" to stringResource(R.string.workout_mood_great)),
                emojis = listOf("\uD83D\uDE1E", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE42", "\uD83D\uDE01"),
                selected = state.moodPost,
                onSelect = { viewModel.updateMoodPost(it) }
            )

            // Skin status
            SectionLabel(stringResource(R.string.post_workout_skin))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkinChip("GOOD", stringResource(R.string.skin_good), SuccessGreen, state.skinStatus == "GOOD", testTag = "postworkout_skin_good") {
                    viewModel.updateSkinStatus("GOOD")
                }
                SkinChip("THIN", stringResource(R.string.skin_thin), WarningYellow, state.skinStatus == "THIN", testTag = "postworkout_skin_thin") {
                    viewModel.updateSkinStatus("THIN")
                }
                SkinChip("SPLIT", stringResource(R.string.skin_split), ErrorRed, state.skinStatus == "SPLIT", testTag = "postworkout_skin_split") {
                    viewModel.updateSkinStatus("SPLIT")
                }
            }

            // Pain areas
            SectionLabel(stringResource(R.string.post_workout_pain))
            val painOptions = listOf(
                "finger A2 pulley" to stringResource(R.string.pain_a2),
                "finger A4 pulley" to stringResource(R.string.pain_a4),
                "shoulder" to stringResource(R.string.pain_shoulder),
                "elbow" to stringResource(R.string.pain_elbow),
                "wrist" to stringResource(R.string.pain_wrist),
                "back" to stringResource(R.string.pain_back),
                "knee" to stringResource(R.string.pain_knee)
            )
            FlowChips(
                options = painOptions,
                selected = state.painAreas,
                onToggle = { viewModel.togglePainArea(it) }
            )

            // Sleep hours
            SectionLabel(stringResource(R.string.post_workout_sleep))
            Column {
                Slider(
                    value = state.sleepHours,
                    onValueChange = { viewModel.updateSleepHours(it) },
                    valueRange = 0f..14f,
                    steps = 27, // 0.5h steps
                    modifier = Modifier.testTag("postworkout_sleep_slider")
                )
                Text(
                    text = stringResource(R.string.workout_hours, "%.1f".format(state.sleepHours)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // Free notes
            SectionLabel(stringResource(R.string.post_workout_notes))
            OutlinedTextField(
                value = state.notes,
                onValueChange = { viewModel.updateNotes(it) },
                modifier = Modifier.fillMaxWidth().testTag("postworkout_notes_field"),
                placeholder = { Text(stringResource(R.string.workout_notes_placeholder)) },
                minLines = 2,
                maxLines = 4
            )

            // Save button
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("postworkout_save_button"),
                enabled = !state.isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.post_workout_save), fontWeight = FontWeight.Bold)
                }
            }

            state.error?.let { error ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.cruxcoach.android.ui.common.ErrorCard(
                    error = error,
                    onDismiss = { viewModel.clearError() },
                    onReportBug = {
                        onNavigateToBugReport(
                            context.getString(R.string.error_bug_report_workout_title),
                            error
                        )
                        viewModel.clearError()
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun EmojiSelector(
    options: List<Pair<String, String>>,
    emojis: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEachIndexed { index, (_, label) ->
            val value = index + 1
            val isSelected = value == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    label = { Text(emojis[index], style = MaterialTheme.typography.titleLarge) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrangeAccent.copy(alpha = 0.2f)
                    )
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RowScope.SkinChip(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    isSelected: Boolean,
    testTag: String = "",
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).then(
            if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) color else MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) color else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(
    options: List<Pair<String, String>>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (key, label) ->
            val isSelected = key in selected
            FilterChip(
                selected = isSelected,
                onClick = { onToggle(key) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ErrorRed.copy(alpha = 0.2f),
                    selectedLabelColor = ErrorRed
                )
            )
        }
    }
}

private fun rpeColor(rpe: Float): androidx.compose.ui.graphics.Color {
    return when {
        rpe <= 5f -> SuccessGreen
        rpe <= 7f -> WarningYellow
        rpe <= 8.5f -> OrangeAccent
        else -> ErrorRed
    }
}

@Composable
private fun rpeLabel(rpe: Float): String {
    return when {
        rpe <= 3f -> stringResource(R.string.rpe_very_light)
        rpe <= 5f -> stringResource(R.string.rpe_light)
        rpe <= 6f -> stringResource(R.string.rpe_moderate)
        rpe <= 7f -> stringResource(R.string.rpe_hard)
        rpe <= 8f -> stringResource(R.string.rpe_very_hard)
        rpe <= 9f -> stringResource(R.string.workout_rpe_very_hard)
        else -> stringResource(R.string.rpe_max)
    }
}
