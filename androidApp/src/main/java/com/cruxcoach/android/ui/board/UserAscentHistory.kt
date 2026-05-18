package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.GradeScale
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.data.repository.AscentWithClimb

@Composable
internal fun UserAscentHistory(
    ascents: List<AscentWithClimb>,
    gradeScale: GradeScale,
    onEdit: (AscentWithClimb) -> Unit,
    onDelete: (AscentWithClimb) -> Unit
) {
    Text(stringResource(R.string.board_ascenthistory_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ascents.forEach { ascent ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            formatAscentDate(ascent.climbedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (ascent.isSend) {
                                Text(
                                    stringResource(R.string.board_ascent_send),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            } else {
                                Text(
                                    stringResource(R.string.board_ascent_attempt),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                            }
                            Text(
                                "${ascent.angle}°",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (ascent.isMirror) {
                                Icon(
                                    Icons.Default.SwapHoriz,
                                    contentDescription = stringResource(R.string.cd_mirrored),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (ascent.isSend) {
                                val attemptsLabel = if (ascent.bidCount <= 1L) "Flash" else stringResource(R.string.board_ascent_tries, ascent.bidCount)
                                val attemptsColor = if (ascent.bidCount <= 1L) SuccessGreen else OrangeAccent
                                Text(
                                    attemptsLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = attemptsColor
                                )
                            }
                            ascent.quality?.let { q ->
                                if (q > 0) {
                                    Text(
                                        "$q★",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = WarningYellow
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { onEdit(ascent) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.cd_edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = { onDelete(ascent) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (ascent != ascents.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                }
            }
        }
    }
}

internal fun formatAscentDate(isoDate: String): String {
    return try {
        val parts = isoDate.take(10).split("-")
        if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else isoDate.take(10)
    } catch (_: Exception) {
        isoDate.take(10)
    }
}
