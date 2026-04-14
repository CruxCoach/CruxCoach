package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.data.repository.BoardSession
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.SessionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSummarySheet(
    session: BoardSession,
    summary: EnhancedSessionSummary?,
    zones: IntensityZones?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.board_session_summary_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Highlight cards row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HighlightCard(
                    value = "${session.ascentCount}",
                    label = stringResource(R.string.board_sends),
                    color = SuccessGreen,
                    modifier = Modifier.weight(1f)
                )
                HighlightCard(
                    value = "${session.bidCount}",
                    label = stringResource(R.string.board_session_summary_attempts),
                    color = OrangeAccent,
                    modifier = Modifier.weight(1f)
                )
                if (summary != null && summary.flashCount > 0) {
                    HighlightCard(
                        value = "${summary.flashCount}",
                        label = stringResource(R.string.board_session_flashes),
                        color = GradeElite,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (summary?.uniqueClimbs != null && summary.uniqueClimbs > 0) {
                    HighlightCard(
                        value = "${summary.uniqueClimbs}",
                        label = stringResource(R.string.board_stats_unique),
                        color = Slate80,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Hardest send
            summary?.hardestSendGrade?.let { grade ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = OrangeAccent.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.board_session_summary_hardest_send),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            summary.hardestSendName?.let { name ->
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                        Text(
                            grade,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = OrangeAccent
                        )
                    }
                }
            }

            // Time breakdown
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val activeSeconds = session.totalDurationSeconds - session.pauseDurationSeconds
                    SessionSummaryRow(stringResource(R.string.board_session_summary_total_time), formatSessionTime(session.totalDurationSeconds.toInt()))
                    SessionSummaryRow(stringResource(R.string.board_session_summary_active_time), formatSessionTime(activeSeconds.toInt()))
                    SessionSummaryRow(stringResource(R.string.board_session_summary_pause_time), formatSessionTime(session.pauseDurationSeconds.toInt()))
                }
            }

            // Mini grade pyramid
            if (summary != null && summary.gradeDistribution.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.board_session_summary_grade_distribution),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        BoardGradePyramidChart(
                            entries = summary.gradeDistribution,
                            zones = zones,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((summary.gradeDistribution.size * 36 + 20).dp)
                        )
                    }
                }
            }

            // Zone breakdown
            if (summary != null && summary.zoneTotal > 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.board_session_summary_intensity_zones),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ZoneBar(stringResource(R.string.board_zone_warmup), summary.warmupCount, summary.zoneTotal, GradeEasy)
                        ZoneBar(stringResource(R.string.board_zone_optimal), summary.optimalCount, summary.zoneTotal, GradeMedium)
                        ZoneBar(stringResource(R.string.board_zone_limit), summary.limitCount, summary.zoneTotal, GradeHard)
                        Spacer(modifier = Modifier.height(4.dp))
                        val typeLabel = when (summary.sessionType) {
                            SessionType.WARMUP_SESSION -> stringResource(R.string.board_session_type_warmup)
                            SessionType.VOLUME_SESSION -> stringResource(R.string.board_session_type_volume)
                            SessionType.LIMIT_SESSION -> stringResource(R.string.board_session_type_limit)
                            SessionType.PYRAMID_SESSION -> stringResource(R.string.board_session_type_pyramid)
                        }
                        SessionSummaryRow(stringResource(R.string.board_session_summary_session_type), typeLabel)
                    }
                }
            }

            // OK button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HighlightCard(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}
