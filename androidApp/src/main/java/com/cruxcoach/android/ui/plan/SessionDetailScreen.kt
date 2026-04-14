package com.cruxcoach.android.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.model.ExerciseBlock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onStartWorkout: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            state.session?.let { sessionTypeDisplayName(it.sessionType) }
                                ?: stringResource(R.string.plan_session_details)
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("sessiondetail_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
        floatingActionButton = {
            if (state.session != null) {
                ExtendedFloatingActionButton(
                    onClick = onStartWorkout,
                    modifier = Modifier.testTag("sessiondetail_start_workout"),
                    containerColor = OrangeAccent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.plan_start_workout))
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    com.cruxcoach.android.ui.common.ErrorCard(
                        error = state.error ?: stringResource(R.string.plan_error),
                        onDismiss = { viewModel.clearError() },
                        onReportBug = {
                            onNavigateToBugReport(
                                context.getString(R.string.error_bug_report_session_title),
                                state.error ?: ""
                            )
                            viewModel.clearError()
                        }
                    )
                }
            }

            else -> {
                val session = state.session!!

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    // Session summary header
                    item {
                        SessionSummaryCard(
                            sessionType = sessionTypeDisplayName(session.sessionType),
                            color = sessionTypeColor(session.sessionType),
                            exerciseCount = session.exercises.size,
                            durationMin = session.targetDurationMin,
                            targetRpe = session.targetRpe,
                            notes = session.notes
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.plan_exercises),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Exercise cards
                    itemsIndexed(session.exercises) { index, exercise ->
                        ExerciseCard(
                            index = index + 1,
                            exercise = exercise
                        )
                    }

                    // Bottom spacer for FAB
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    sessionType: String,
    color: androidx.compose.ui.graphics.Color,
    exerciseCount: Int,
    durationMin: Int,
    targetRpe: Float,
    notes: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sessionType,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip(label = stringResource(R.string.plan_exercises), value = "$exerciseCount")
                StatChip(label = stringResource(R.string.plan_duration), value = "${durationMin} min")
                StatChip(label = stringResource(R.string.plan_target_rpe), value = "${"%.1f".format(targetRpe)}")
            }
            if (!notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ExerciseCard(index: Int, exercise: ExerciseBlock) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Exercise number
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(OrangeAccent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Name (German preferred, fallback to English)
                Text(
                    text = exercise.nameDe.ifBlank { exercise.nameEn },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Category tag
                Text(
                    text = categoryDisplayName(exercise.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Parameters row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (exercise.sets > 0) {
                        ParamChip(stringResource(R.string.plan_sets_count, exercise.sets))
                    }
                    if (exercise.reps.isNotBlank()) {
                        ParamChip(exercise.reps)
                    }
                    if (exercise.duration.isNotBlank()) {
                        ParamChip(exercise.duration)
                    }
                    if (exercise.weight.isNotBlank()) {
                        ParamChip(exercise.weight)
                    }
                }

                if (exercise.restSeconds > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.plan_rest_label, formatRest(exercise.restSeconds)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (exercise.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = exercise.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun categoryDisplayName(category: String): String = when (category.uppercase()) {
    "HANGBOARD" -> stringResource(R.string.cat_hangboard)
    "PULL" -> stringResource(R.string.cat_pull)
    "PUSH" -> stringResource(R.string.cat_push)
    "CORE" -> stringResource(R.string.cat_core)
    "POWER" -> stringResource(R.string.cat_power)
    "ENDURANCE" -> stringResource(R.string.cat_endurance)
    "MOBILITY" -> stringResource(R.string.cat_mobility)
    "TECHNIQUE" -> stringResource(R.string.cat_technique)
    "ANTAGONIST" -> stringResource(R.string.cat_antagonist)
    else -> category
}

@Composable
private fun formatRest(seconds: Int): String {
    return if (seconds >= 60) stringResource(R.string.plan_rest_min, seconds / 60) else stringResource(R.string.plan_rest_sec, seconds)
}
