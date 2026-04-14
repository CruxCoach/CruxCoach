package com.cruxcoach.android.ui.workout

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
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
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.domain.model.ExerciseBlock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    sessionId: Long,
    onFinish: (sessionId: Long, durationMin: Int, completedCount: Int) -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: ActiveWorkoutViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.isFinished) {
        onFinish(
            viewModel.sessionId,
            viewModel.getElapsedMinutes(),
            viewModel.getCompletedExercises().size
        )
        return
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                state.session?.let { sessionTypeLabel(it.sessionType) } ?: stringResource(R.string.workout_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                formatElapsed(state.elapsedSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                viewModel.skipExercise() // Will finish if last
                            },
                            modifier = Modifier.testTag("workout_end_button")
                        ) {
                            Text(stringResource(R.string.workout_finish), color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            }

            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    com.cruxcoach.android.ui.common.ErrorCard(
                        error = state.error ?: stringResource(R.string.workout_error),
                        onDismiss = { viewModel.clearError() },
                        onReportBug = {
                            onNavigateToBugReport(
                                context.getString(R.string.error_bug_report_workout_title),
                                state.error ?: ""
                            )
                            viewModel.clearError()
                        }
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    // Progress bar
                    val progress by animateFloatAsState(
                        targetValue = state.overallProgress,
                        label = "progress"
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = OrangeAccent,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Exercise counter
                    Text(
                        text = stringResource(R.string.workout_exercise_of, state.currentExerciseIndex + 1, state.totalExercises),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Current exercise card
                    val current = state.currentExercise
                    if (current != null) {
                        AnimatedContent(
                            targetState = state.currentExerciseIndex,
                            label = "exercise"
                        ) { _ ->
                            CurrentExerciseCard(
                                exercise = current.exercise,
                                currentSet = state.currentSet,
                                completedSets = current.completedSets,
                                totalSets = current.exercise.sets
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Rest timer or action buttons
                    if (state.isResting) {
                        RestTimerSection(
                            seconds = state.restSecondsRemaining,
                            onSkip = { viewModel.skipRest() }
                        )
                    } else {
                        ActionButtons(
                            onCompleteSet = { viewModel.completeSet() },
                            onSkipExercise = { viewModel.skipExercise() },
                            onPreviousExercise = { viewModel.previousExercise() },
                            canGoBack = state.currentExerciseIndex > 0,
                            setLabel = stringResource(R.string.workout_set_label, state.currentSet, current?.exercise?.sets ?: 0)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CurrentExerciseCard(
    exercise: ExerciseBlock,
    currentSet: Int,
    completedSets: Int,
    totalSets: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Exercise name
            Text(
                text = exercise.nameDe.ifBlank { exercise.nameEn },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Category
            Text(
                text = categoryLabel(exercise.category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Set progress dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 1..totalSets) {
                    Box(
                        modifier = Modifier
                            .size(if (i == currentSet) 16.dp else 12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    i < currentSet || i <= completedSets -> SuccessGreen
                                    i == currentSet -> OrangeAccent
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Exercise parameters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (exercise.sets > 0) {
                    ParamDisplay(value = "${exercise.sets}", label = stringResource(R.string.workout_sets_label))
                }
                if (exercise.reps.isNotBlank()) {
                    ParamDisplay(value = exercise.reps, label = stringResource(R.string.workout_reps_label))
                }
                if (exercise.duration.isNotBlank()) {
                    ParamDisplay(value = exercise.duration, label = stringResource(R.string.session_detail_duration))
                }
                if (exercise.weight.isNotBlank()) {
                    ParamDisplay(value = exercise.weight, label = stringResource(R.string.workout_weight))
                }
            }

            if (exercise.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = exercise.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ParamDisplay(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = OrangeAccent
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RestTimerSection(seconds: Int, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.workout_rest),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Large timer display
        Text(
            text = formatTimer(seconds),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = if (seconds <= 10) MaterialTheme.colorScheme.error else OrangeAccent,
            fontSize = 64.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.testTag("workout_skip_rest"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.workout_skip_rest))
        }
    }
}

@Composable
private fun ActionButtons(
    onCompleteSet: () -> Unit,
    onSkipExercise: () -> Unit,
    onPreviousExercise: () -> Unit,
    canGoBack: Boolean,
    setLabel: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main action button
        Button(
            onClick = onCompleteSet,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("workout_complete_set"),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.workout_set_done_label, setLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Secondary actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (canGoBack) {
                OutlinedButton(
                    onClick = onPreviousExercise,
                    modifier = Modifier.testTag("workout_previous")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_back))
                }
            }

            OutlinedButton(
                onClick = onSkipExercise,
                modifier = Modifier.testTag("workout_skip_exercise")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.workout_skip_exercise))
            }
        }
    }
}

@Composable
private fun sessionTypeLabel(sessionType: com.cruxcoach.domain.model.SessionType): String {
    return when (sessionType) {
        com.cruxcoach.domain.model.SessionType.STRENGTH -> stringResource(R.string.workout_type_strength)
        com.cruxcoach.domain.model.SessionType.POWER -> stringResource(R.string.workout_type_power)
        com.cruxcoach.domain.model.SessionType.VOLUME -> stringResource(R.string.workout_type_volume)
        com.cruxcoach.domain.model.SessionType.TECHNIQUE -> stringResource(R.string.workout_type_technique)
        com.cruxcoach.domain.model.SessionType.DELOAD -> stringResource(R.string.workout_type_deload)
        com.cruxcoach.domain.model.SessionType.REST -> stringResource(R.string.week_plan_rest_day)
    }
}

@Composable
private fun categoryLabel(category: String): String = when (category.uppercase()) {
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

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun formatTimer(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
