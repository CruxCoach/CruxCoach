package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb

/**
 * Fallback content for a climb the user has logged locally (Kilter ascent
 * imported via FEAT-030) but which is absent from the curated CruxCoach
 * board DB — it lives only in Kilter's new PowerSync world (dashed-lowercase
 * uuid, never mirrored, not in BoardSesh). Instead of dead-ending on the raw
 * "Climb nicht gefunden [uuid=… angle=…]" error, this shows a friendly
 * explanation plus exactly what the local ascent rows already carry (angle,
 * date, attempts, comment). The diagnostic uuid/angle string is intentionally
 * NOT surfaced here — only the genuinely-unknown (no-history) path shows it.
 */
@Composable
internal fun LogbookOnlyClimbContent(
    logbookOnly: LogbookOnlyState,
    gradeScale: GradeScale,
    modifier: Modifier = Modifier
) {
    // Prefer a denormalized name carried on any ascent row; logbook imports
    // for unknown climbs leave it blank, so fall back to a generic label.
    val climbName = logbookOnly.ascents
        .firstNotNullOfOrNull { it.climbName.takeIf { n -> n.isNotBlank() } }
        ?: stringResource(R.string.error_climb_not_in_db_unnamed)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            climbName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.error_climb_not_in_db_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.error_climb_not_in_db_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            stringResource(R.string.error_climb_not_in_db_your_logs),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Sort newest-first so the most recent attempt is on top.
        logbookOnly.ascents
            .sortedByDescending { it.climbedAt }
            .forEach { ascent ->
                LogbookOnlyAscentRow(ascent)
            }
    }
}

@Composable
private fun LogbookOnlyAscentRow(ascent: AscentWithClimb) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (ascent.isSend) {
                        stringResource(R.string.error_climb_not_in_db_log_send)
                    } else {
                        stringResource(R.string.error_climb_not_in_db_log_attempt)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${ascent.angle}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDate(ascent.climbedAt),
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
            }
            Text(
                stringResource(R.string.error_climb_not_in_db_log_attempts, ascent.bidCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ascent.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                Text(
                    "\"$comment\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
