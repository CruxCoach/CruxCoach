package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.domain.board.IntensityZones
import com.cruxcoach.domain.board.SessionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSummarySheet(
    session: Board_sessions,
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
        SessionSummaryContent(session, summary, zones, onDone = onDismiss)
    }
}

/** The end-of-session summary: the session's card or its tiles, the times and the zones. */
@Composable
internal fun SessionSummaryContent(
    session: Board_sessions,
    summary: EnhancedSessionSummary?,
    zones: IntensityZones?,
    onDone: () -> Unit,
) {
    // The session's card, the same one the logbook shares; without the
    // logbook history the sheet falls back to its own tiles.
    val recap = summary?.recap
    val recapLayer = rememberGraphicsLayer()

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

        if (recap != null) {
            SessionRecapImage(recap, recapLayer)
        } else {
            // Highlight cards, in as many columns as keep their labels whole
            val tiles = buildList<Triple<String, String, androidx.compose.ui.graphics.Color>> {
                add(Triple("${session.ascentCount}", stringResource(R.string.board_sends), SuccessGreen))
                // Same definition as the logbook stats: every logged row counts at least one try.
                add(Triple("${summary?.totalAttempts ?: session.bidCount}", stringResource(R.string.board_session_summary_attempts), OrangeAccent))
                if (summary != null && summary.flashCount > 0) {
                    add(Triple("${summary.flashCount}", stringResource(R.string.board_session_flashes), GradeElite))
                }
                if (summary?.uniqueClimbs != null && summary.uniqueClimbs > 0) {
                    add(Triple("${summary.uniqueClimbs}", stringResource(R.string.board_stats_unique), Slate80))
                }
            }
            NumberGrid(
                perRow = tiles.size,
                labels = tiles.map { it.second },
                spacing = 8.dp,
                cells = tiles.map<Triple<String, String, androidx.compose.ui.graphics.Color>, @Composable (Modifier) -> Unit> {
                    (value, label, color) -> { cell -> HighlightCard(value = value, label = label, color = color, modifier = cell) }
                },
            )

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

        // Mini grade pyramid, when there is no card that shows the grades
        if (recap == null && summary != null && summary.gradeDistribution.isNotEmpty()) {
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

        if (recap != null) ShareRecapButton(recap, recapLayer, filled = false)

        // OK button
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
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
