package com.cruxcoach.android.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.common.BleStatusArea
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.model.SessionType
import com.cruxcoach.domain.model.TrainingPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToWeekPlan: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToClimbLog: () -> Unit,
    onNavigateToExerciseLibrary: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (state.userName.isNotBlank()) stringResource(R.string.dashboard_greeting, state.userName) else "CruxCoach",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.loadDashboard() },
                            modifier = Modifier.testTag("dashboard_refresh_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                        }
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("dashboard_settings_button")
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
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
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.dashboard_error), textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadDashboard() },
                            modifier = Modifier.testTag("dashboard_retry_button")
                        ) {
                            Text(stringResource(R.string.dashboard_retry))
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Phase + Week Progress Card
                    if (state.activePlan != null) {
                        PhaseCard(
                            phase = state.currentPhase,
                            sessionsCompleted = state.sessionsThisWeek,
                            sessionsTotal = state.totalSessionsPlanned
                        )
                    } else {
                        NoPlanCard(onNavigateToWeekPlan)
                    }

                    // Training Streak + Quick Stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatMiniCard(
                            value = "${state.trainingStreak}",
                            label = stringResource(R.string.dashboard_streak),
                            color = if (state.trainingStreak > 0) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )

                        StatMiniCard(
                            value = state.highestGrade?.let { GradeDisplayHelper.formatGrade(it, state.gradeScale) } ?: "--",
                            label = stringResource(R.string.dashboard_best_grade),
                            color = GradeHard,
                            modifier = Modifier.weight(1f)
                        )

                        StatMiniCard(
                            value = state.avgRpeLast7?.let { "%.1f".format(it) } ?: "--",
                            label = stringResource(R.string.dashboard_avg_rpe),
                            color = rpeColor(state.avgRpeLast7),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Next Session Card
                    if (state.nextSession != null) {
                        NextSessionCard(
                            sessionType = state.nextSession!!.sessionType,
                            dayName = state.nextSessionDay,
                            durationMin = state.nextSession!!.targetDurationMin,
                            exerciseCount = state.nextSession!!.exercises.size,
                            onClick = onNavigateToWeekPlan
                        )
                    }

                    // Today's climbing
                    if (state.totalClimbsToday > 0) {
                        TodayClimbCard(
                            total = state.totalClimbsToday,
                            sends = state.sendsToday
                        )
                    }

                    // Quick actions
                    Text(
                        stringResource(R.string.dashboard_quick_actions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            icon = Icons.Default.CalendarMonth,
                            label = stringResource(R.string.nav_week_plan),
                            color = OrangeAccent,
                            onClick = onNavigateToWeekPlan,
                            modifier = Modifier.weight(1f).testTag("dashboard_quick_weekplan")
                        )
                        QuickActionCard(
                            icon = Icons.Default.FitnessCenter,
                            label = stringResource(R.string.dashboard_log_boulder),
                            color = SuccessGreen,
                            onClick = onNavigateToClimbLog,
                            modifier = Modifier.weight(1f).testTag("dashboard_quick_boulder")
                        )
                        QuickActionCard(
                            icon = Icons.AutoMirrored.Filled.TrendingUp,
                            label = stringResource(R.string.nav_stats),
                            color = InfoBlue,
                            onClick = onNavigateToStats,
                            modifier = Modifier.weight(1f).testTag("dashboard_quick_stats")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(
    phase: TrainingPhase?,
    sessionsCompleted: Int,
    sessionsTotal: Int
) {
    val phaseColor = when (phase) {
        TrainingPhase.BASE -> PhaseBase
        TrainingPhase.STRENGTH -> PhaseStrength
        TrainingPhase.POWER -> PhasePower
        TrainingPhase.PERFORMANCE -> PhasePerformance
        TrainingPhase.DELOAD -> PhaseDeload
        null -> MaterialTheme.colorScheme.primary
    }

    val phaseName = when (phase) {
        TrainingPhase.BASE -> stringResource(R.string.phase_base)
        TrainingPhase.STRENGTH -> stringResource(R.string.phase_strength)
        TrainingPhase.POWER -> stringResource(R.string.phase_power)
        TrainingPhase.PERFORMANCE -> stringResource(R.string.phase_performance)
        TrainingPhase.DELOAD -> stringResource(R.string.phase_deload)
        null -> stringResource(R.string.dashboard_no_plan)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = phaseColor.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(phaseColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dashboard_phase, phaseName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "$sessionsCompleted / $sessionsTotal",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = phaseColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.dashboard_sessions_week),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val progress by animateFloatAsState(
                targetValue = if (sessionsTotal > 0) sessionsCompleted.toFloat() / sessionsTotal else 0f,
                label = "weekProgress"
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = phaseColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun NoPlanCard(onGenerate: () -> Unit) {
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
            Text(
                stringResource(R.string.dashboard_no_plan),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.dashboard_no_plan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onGenerate,
                modifier = Modifier.testTag("dashboard_to_weekplan"),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)
            ) {
                Text(stringResource(R.string.dashboard_to_weekplan))
            }
        }
    }
}

@Composable
private fun StatMiniCard(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NextSessionCard(
    sessionType: SessionType,
    dayName: String,
    durationMin: Int,
    exerciseCount: Int,
    onClick: () -> Unit
) {
    val typeColor = when (sessionType) {
        SessionType.STRENGTH -> SessionStrength
        SessionType.POWER -> SessionPower
        SessionType.VOLUME -> SessionVolume
        SessionType.TECHNIQUE -> SessionTechnique
        SessionType.DELOAD -> SessionDeload
        SessionType.REST -> SessionRest
    }

    val typeName = when (sessionType) {
        SessionType.STRENGTH -> stringResource(R.string.session_strength)
        SessionType.POWER -> stringResource(R.string.session_power)
        SessionType.VOLUME -> stringResource(R.string.session_volume)
        SessionType.TECHNIQUE -> stringResource(R.string.session_technique)
        SessionType.DELOAD -> stringResource(R.string.session_deload)
        SessionType.REST -> stringResource(R.string.session_rest)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("dashboard_next_session"),
        colors = CardDefaults.cardColors(
            containerColor = typeColor.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(typeColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = typeName.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = typeColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_next_session),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$dayName: $typeName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.dashboard_session_summary,
                        exerciseCount,
                        exerciseCount,
                        durationMin,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TodayClimbCard(total: Int, sends: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SuccessGreen.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.dashboard_today_climbed),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$total",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent
                    )
                    Text(stringResource(R.string.climb_label_boulder), style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$sends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen
                    )
                    Text(stringResource(R.string.climb_label_sends), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                fontSize = 11.sp
            )
        }
    }
}

private fun rpeColor(rpe: Double?): androidx.compose.ui.graphics.Color {
    return when {
        rpe == null -> SessionRest
        rpe <= 5.0 -> SuccessGreen
        rpe <= 7.0 -> WarningYellow
        rpe <= 8.5 -> OrangeAccent
        else -> ErrorRed
    }
}
