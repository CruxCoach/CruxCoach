package com.cruxcoach.android.ui.climb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.util.GradeConverter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClimbLogScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBodyStat: () -> Unit = {},
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: ClimbLogViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = SnackbarHostState()

    // Show saved message as snackbar
    LaunchedEffect(state.savedMessage) {
        state.savedMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            delay(500)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.climb_log_title)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("climblog_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToBodyStat) {
                            Icon(Icons.Default.MonitorWeight, contentDescription = stringResource(R.string.cd_body_stats))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Today's session summary
            if (state.todaySends > 0 || state.todayFlashes > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = OrangeAccent.copy(alpha = 0.12f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.todayClimbs.size}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent
                            )
                            Text(stringResource(R.string.climb_label_boulder), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.todaySends}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(stringResource(R.string.climb_label_sends), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.todayFlashes}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = WarningYellow
                            )
                            Text(stringResource(R.string.climb_label_flashes), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Grade selector
            SectionLabel(stringResource(R.string.climb_log_grade))
            GradeSelector(
                gradeIndex = state.gradeIndex,
                gradeScale = state.gradeScale,
                onGradeUp = { viewModel.gradeUp() },
                onGradeDown = { viewModel.gradeDown() }
            )

            // Send / Flash toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sent toggle
                BigToggleButton(
                    label = stringResource(R.string.climb_log_sent),
                    isActive = state.sent,
                    activeColor = SuccessGreen,
                    onClick = { viewModel.toggleSent() },
                    modifier = Modifier.weight(1f).testTag("climblog_sent_toggle")
                )

                // Flash toggle
                BigToggleButton(
                    label = stringResource(R.string.climb_log_flash),
                    isActive = state.flash,
                    activeColor = WarningYellow,
                    enabled = state.sent,
                    onClick = { viewModel.toggleFlash() },
                    modifier = Modifier.weight(1f).testTag("climblog_flash_toggle")
                )
            }

            // Attempts counter
            SectionLabel(stringResource(R.string.climb_log_attempts))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.decrementAttempts() },
                    enabled = state.attempts > 1,
                    modifier = Modifier.testTag("climblog_attempts_down")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.cd_less))
                }

                Text(
                    text = "${state.attempts}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = OrangeAccent
                )

                IconButton(
                    onClick = { viewModel.incrementAttempts() },
                    modifier = Modifier.testTag("climblog_attempts_up")
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_more))
                }
            }

            // Style tags
            SectionLabel(stringResource(R.string.climb_log_style))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val styles = listOf(
                    "SLAB" to stringResource(R.string.style_slab),
                    "VERT" to stringResource(R.string.style_vert),
                    "OVERHANG" to stringResource(R.string.style_overhang),
                    "ROOF" to stringResource(R.string.style_roof),
                    "DYNO" to stringResource(R.string.style_dyno),
                    "COMP" to stringResource(R.string.style_comp)
                )
                styles.forEach { (key, label) ->
                    FilterChip(
                        selected = key in state.selectedStyles,
                        onClick = { viewModel.toggleStyle(key) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        )
                    )
                }
            }

            // Hold type tags
            SectionLabel(stringResource(R.string.climb_log_holds))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val holdTypes = listOf(
                    "CRIMP" to stringResource(R.string.hold_crimp),
                    "SLOPER" to stringResource(R.string.hold_sloper),
                    "PINCH" to stringResource(R.string.hold_pinch),
                    "JUG" to stringResource(R.string.hold_jug),
                    "POCKET" to stringResource(R.string.hold_pocket),
                    "VOLUME" to stringResource(R.string.hold_volume),
                    "UNDERCLING" to stringResource(R.string.hold_undercling)
                )
                holdTypes.forEach { (key, label) ->
                    FilterChip(
                        selected = key in state.selectedHoldTypes,
                        onClick = { viewModel.toggleHoldType(key) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = InfoBlue.copy(alpha = 0.2f),
                            selectedLabelColor = InfoBlue
                        )
                    )
                }
            }

            // Board type
            SectionLabel(stringResource(R.string.climb_log_board))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val boards = listOf("Kilter" to "KILTER", "Gym" to null)
                boards.forEach { (label, type) ->
                    val isSelected = state.boardType == type
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setBoardType(type) },
                        label = { Text(label) }
                    )
                }
            }

            // Notes
            SectionLabel(stringResource(R.string.climb_log_notes))
            OutlinedTextField(
                value = state.notes,
                onValueChange = { viewModel.updateNotes(it) },
                modifier = Modifier.fillMaxWidth().testTag("climblog_notes_field"),
                placeholder = { Text(stringResource(R.string.climb_notes_placeholder)) },
                maxLines = 2
            )

            // Save button
            Button(
                onClick = { viewModel.saveAndNext() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("climblog_save_button"),
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
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.climb_log_save_next), fontWeight = FontWeight.Bold)
                }
            }

            state.error?.let { error ->
                val context = androidx.compose.ui.platform.LocalContext.current
                com.cruxcoach.android.ui.common.ErrorCard(
                    error = error,
                    onDismiss = { viewModel.clearError() },
                    onReportBug = {
                        onNavigateToBugReport(
                            context.getString(R.string.error_bug_report_climblog_title),
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
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun GradeSelector(
    gradeIndex: Int,
    gradeScale: GradeScale = GradeScale.V_SCALE,
    onGradeUp: () -> Unit,
    onGradeDown: () -> Unit
) {
    val colorNumeric = GradeDisplayHelper.indexToColorNumeric(gradeIndex)
    val primaryGrade = GradeDisplayHelper.formatByIndex(gradeIndex, gradeScale)
    val altGrade = when (gradeScale) {
        GradeScale.V_SCALE -> GradeConverter.indexToFrench(gradeIndex)
        GradeScale.FRENCH -> GradeConverter.indexToVScale(gradeIndex)
    }
    val gradeColor = when {
        colorNumeric <= 2 -> GradeEasy
        colorNumeric <= 5 -> GradeMedium
        colorNumeric <= 9 -> GradeHard
        else -> GradeElite
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = gradeColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onGradeDown,
                enabled = GradeConverter.canGoDown(gradeIndex),
                modifier = Modifier.testTag("climblog_grade_down")
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.cd_grade_down),
                    modifier = Modifier.size(32.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = primaryGrade,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = gradeColor
                )
                Text(
                    text = "($altGrade)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onGradeUp,
                enabled = GradeConverter.canGoUp(gradeIndex),
                modifier = Modifier.testTag("climblog_grade_up")
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.cd_grade_up),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun BigToggleButton(
    label: String,
    isActive: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isActive) activeColor.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface,
            contentColor = if (isActive) activeColor
            else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        ),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) activeColor
            else if (enabled) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            label,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            fontSize = 16.sp
        )
    }
}
