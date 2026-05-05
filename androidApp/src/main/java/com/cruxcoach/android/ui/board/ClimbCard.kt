package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.GradeScale
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.IntensityZones

@Composable
internal fun ClimbCard(
    climb: ClimbWithStats,
    gradeScale: GradeScale = GradeScale.V_SCALE,
    zones: IntensityZones? = null,
    onSetterClick: ((String) -> Unit)? = null,
    onClimbClick: (String) -> Unit
) {
    // Cache computed values — estimateMoveCount() parses the frames string (expensive),
    // and formatDifficulty() does a lookup + String.format. Both are stable across
    // recompositions for the same climb.
    val grade = remember(climb.difficultyAverage, gradeScale) {
        climb.difficultyAverage?.let { GradeDisplayHelper.formatDifficulty(it, gradeScale) } ?: "?"
    }
    val moveCount = climb.moveCount
    val qualityText = remember(climb.qualityAverage) {
        climb.qualityAverage?.let { "%.1f".format(it) + "★" }
    }

    Card(
        onClick = { onClimbClick(climb.uuid) },
        modifier = Modifier.fillMaxWidth().testTag("board_climb_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Grade badge
            Surface(
                color = zoneColorForDifficulty(climb.difficultyAverage ?: 0.0, zones),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        grade,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = DarkBackground
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        climb.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (climb.source == "local") {
                        Surface(
                            color = OrangeAccent.copy(alpha = 0.18f),
                            contentColor = OrangeAccent,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.climb_card_draft_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Setter line: prefer the Kind-0/setter_username already
                    // resolved into the row; else fall back to a short
                    // `npub:<…>` stub for cruxcoach/local rows so own drafts
                    // and own published climbs always show *something* even
                    // when the local Kind-0 profile hasn't been published
                    // yet (cache miss → setter_username column is NULL).
                    // Mirrors BoardClimbDetailViewModel.seedSetterProfile.
                    val setterDisplay = climb.setterUsername?.takeIf { it.isNotBlank() }
                        ?: if (climb.origin == "cruxcoach" || climb.source == "local") {
                            climb.createdByPubkey?.takeIf { it.isNotBlank() }
                                ?.let { "npub:${it.take(16)}" }
                        } else null
                    setterDisplay?.let { setter ->
                        Text(
                            stringResource(R.string.board_climb_by_setter, setter),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (onSetterClick != null) OrangeAccent
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (onSetterClick != null) {
                                Modifier.clickable { onSetterClick(setter) }
                            } else Modifier
                        )
                    }
                    if (climb.isRoute) {
                        Text(
                            stringResource(R.string.board_climb_frames, climb.framesCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            stringResource(R.string.board_climb_moves, moveCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (climb.benchmarkDifficulty > 0.0) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(R.string.board_detail_benchmark),
                        tint = OrangeAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                qualityText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = WarningYellow,
                        fontWeight = FontWeight.Bold
                    )
                }
                climb.ascensionistCount?.let {
                    Text(
                        stringResource(R.string.board_climb_sends_count, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun NoBoardDataCard(onSyncClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.board_climb_no_data_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.board_climb_no_data_message),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSyncClick,
                    modifier = Modifier.testTag("board_sync_navigate"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.board_sync_title), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Compact match/no-match icon for climb cards. */
@Composable
internal fun MatchIndicator(isNomatch: Boolean) {
    val color = if (isNomatch) ErrorRed else SuccessGreen
    Box(modifier = Modifier.size(16.dp)) {
        Icon(
            Icons.Default.PanTool,
            contentDescription = stringResource(if (isNomatch) R.string.board_detail_no_matching else R.string.board_detail_matching),
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        if (isNomatch) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}
