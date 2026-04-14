package com.cruxcoach.android.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.domain.model.SessionType
import com.cruxcoach.domain.model.TrainingPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekOverviewScreen(
    onNavigateToSession: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PlanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.plan_week_plan)) },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("weekoverview_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.adaptPlan() },
                            modifier = Modifier.testTag("weekoverview_adapt_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_adapt_plan))
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = OrangeAccent)
                }
            }

            state.error != null && state.plan == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.error ?: stringResource(R.string.plan_error),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.generateNewPlan() },
                        modifier = Modifier.testTag("weekoverview_generate_button")
                    ) {
                        Text(stringResource(R.string.plan_generate))
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    // Phase badge
                    state.plan?.let { plan ->
                        item {
                            PhaseBadge(phase = plan.phase, focusAreas = plan.focusAreas)
                        }
                    }

                    // 7-day grid
                    itemsIndexed(state.weekDays) { index, dayInfo ->
                        DayCard(
                            dayInfo = dayInfo,
                            onClick = {
                                dayInfo.session?.let { session ->
                                    if (session.id > 0) onNavigateToSession(session.id)
                                }
                            },
                            modifier = Modifier.testTag("weekoverview_day_card_$index")
                        )
                    }

                    // Adaptation cards
                    if (state.adaptations.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.plan_adaptations),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(state.adaptations) { adaptation ->
                            AdaptationCard(
                                emoji = adaptation.emoji,
                                description = adaptation.description
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseBadge(phase: TrainingPhase, focusAreas: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = phaseColor(phase).copy(alpha = 0.15f)
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
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(phaseColor(phase)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = phaseEmoji(phase),
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = phaseDisplayName(phase),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (focusAreas.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plan_focus, focusAreas.joinToString(", ") { it.replace("_", " ") }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCard(dayInfo: WeekDayInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hasSession = dayInfo.session != null
    val bgColor = if (hasSession) {
        sessionTypeColor(dayInfo.session!!.sessionType).copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val borderColor = if (dayInfo.isToday) OrangeAccent else Color.Transparent

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hasSession) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp),
        border = if (dayInfo.isToday) {
            CardDefaults.outlinedCardBorder().copy(
                width = 2.dp,
                brush = androidx.compose.ui.graphics.SolidColor(borderColor)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Day label
            Column(
                modifier = Modifier.width(44.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dayInfo.dayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (dayInfo.isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (dayInfo.isToday) OrangeAccent else MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (hasSession) {
                val session = dayInfo.session!!
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessionTypeDisplayName(session.sessionType),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.plan_session_summary, session.exercises.size, session.targetDurationMin, session.targetRpe),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Session type indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(sessionTypeColor(session.sessionType))
                )
            } else {
                Text(
                    text = stringResource(R.string.plan_rest_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AdaptationCard(emoji: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = WarningYellow.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// --- Helper functions ---

fun sessionTypeColor(type: SessionType): Color = when (type) {
    SessionType.STRENGTH -> com.cruxcoach.android.ui.theme.SessionStrength
    SessionType.POWER -> com.cruxcoach.android.ui.theme.SessionPower
    SessionType.VOLUME -> com.cruxcoach.android.ui.theme.SessionVolume
    SessionType.TECHNIQUE -> com.cruxcoach.android.ui.theme.SessionTechnique
    SessionType.DELOAD -> com.cruxcoach.android.ui.theme.SessionDeload
    SessionType.REST -> com.cruxcoach.android.ui.theme.SessionRest
}

@Composable
fun sessionTypeDisplayName(type: SessionType): String = when (type) {
    SessionType.STRENGTH -> stringResource(R.string.session_strength)
    SessionType.POWER -> stringResource(R.string.session_power)
    SessionType.VOLUME -> stringResource(R.string.session_volume)
    SessionType.TECHNIQUE -> stringResource(R.string.session_technique)
    SessionType.DELOAD -> stringResource(R.string.session_deload)
    SessionType.REST -> stringResource(R.string.session_rest)
}

fun phaseColor(phase: TrainingPhase): Color = when (phase) {
    TrainingPhase.BASE -> com.cruxcoach.android.ui.theme.PhaseBase
    TrainingPhase.STRENGTH -> com.cruxcoach.android.ui.theme.PhaseStrength
    TrainingPhase.POWER -> com.cruxcoach.android.ui.theme.PhasePower
    TrainingPhase.PERFORMANCE -> com.cruxcoach.android.ui.theme.PhasePerformance
    TrainingPhase.DELOAD -> com.cruxcoach.android.ui.theme.PhaseDeload
}

@Composable
fun phaseDisplayName(phase: TrainingPhase): String = when (phase) {
    TrainingPhase.BASE -> stringResource(R.string.phase_base)
    TrainingPhase.STRENGTH -> stringResource(R.string.phase_strength)
    TrainingPhase.POWER -> stringResource(R.string.phase_power)
    TrainingPhase.PERFORMANCE -> stringResource(R.string.phase_performance)
    TrainingPhase.DELOAD -> stringResource(R.string.phase_deload)
}

fun phaseEmoji(phase: TrainingPhase): String = when (phase) {
    TrainingPhase.BASE -> "\uD83C\uDFD7\uFE0F" // 🏗️
    TrainingPhase.STRENGTH -> "\uD83D\uDCAA" // 💪
    TrainingPhase.POWER -> "\u26A1" // ⚡
    TrainingPhase.PERFORMANCE -> "\uD83C\uDFC6" // 🏆
    TrainingPhase.DELOAD -> "\uD83D\uDE34" // 😴
}
