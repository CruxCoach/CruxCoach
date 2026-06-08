package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import java.time.LocalDate

@Composable
internal fun AscentCard(
    ascent: AscentWithClimb,
    gradeScale: GradeScale,
    zones: IntensityZones? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val grade = ascent.difficultyAverage?.let {
        GradeDisplayHelper.formatDifficulty(it, gradeScale)
    } ?: "?"

    val containerColor = if (isSelected) {
        OrangeAccent.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("logbook_ascent_card"),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = OrangeAccent),
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                color = zoneColorForDifficulty(ascent.difficultyAverage ?: 0.0, zones),
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
                Text(
                    ascent.climbName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Board badge: tells a multi-board user at a glance which
                    // board this send was on (Kilter / Tension / MoonBoard …).
                    // Unobtrusive — same muted colour as the meta line.
                    BoardBrandBadge(BoardBrand.fromWire(ascent.boardBrand))
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
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (ascent.isSend) {
                    val attemptsLabel = if (ascent.bidCount <= 1L) "Flash" else stringResource(R.string.board_ascent_tries, ascent.bidCount)
                    val attemptsColor = if (ascent.bidCount <= 1L) SuccessGreen else OrangeAccent
                    Text(
                        attemptsLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = attemptsColor
                    )
                } else {
                    val attemptsText = if (ascent.bidCount > 1L) {
                        stringResource(R.string.board_ascent_attempts_count, ascent.bidCount)
                    } else {
                        stringResource(R.string.board_ascent_attempt)
                    }
                    Text(
                        attemptsText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ErrorRed
                    )
                }
                ascent.quality?.let {
                    Text(
                        "$it★",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningYellow
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.cd_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Tiny, muted pill naming the board family a logbook entry was logged on.
 *  Uses [BoardBrand.displayName] (proper noun, not localized) so a newly
 *  promoted board needs no per-board string. Sits on the card's meta line
 *  next to angle/date. */
@Composable
private fun BoardBrandBadge(brand: BoardBrand) {
    Surface(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = brand.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
internal fun EmptyLogbookMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.board_logbook_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.board_logbook_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun DayHeader(dateKey: String, count: Int) {
    val dayNames = arrayOf(
        stringResource(R.string.day_mo), stringResource(R.string.day_tu),
        stringResource(R.string.day_we), stringResource(R.string.day_th),
        stringResource(R.string.day_fr), stringResource(R.string.day_sa),
        stringResource(R.string.day_su)
    )
    val label = try {
        val date = LocalDate.parse(dateKey)
        val dayName = dayNames[date.dayOfWeek.value - 1]
        val formatted = "${dateKey.substring(8, 10)}.${dateKey.substring(5, 7)}.${dateKey.substring(0, 4)}"
        "$dayName, $formatted"
    } catch (_: Exception) {
        formatDate(dateKey)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = OrangeAccent.copy(alpha = 0.25f),
            thickness = 1.dp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.board_logbook_day_entries, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
internal fun StatsSummaryRow(stats: BoardLogbookStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryCard(
                value = stats.hardestGrade ?: "-",
                label = stringResource(R.string.board_logbook_best_grade),
                color = GradeHard,
                modifier = Modifier.weight(1f)
            )
            val sendsLabel = if (stats.routeSends > 0) {
                "${stats.boulderSends}B / ${stats.routeSends}R"
            } else {
                "${stats.totalSends}"
            }
            SummaryCard(
                value = sendsLabel,
                label = stringResource(R.string.board_sends),
                color = SuccessGreen,
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                value = "${stats.totalAttempts}",
                label = stringResource(R.string.board_logbook_attempts),
                color = ErrorRed,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryCard(
                value = "${stats.sessionCount}",
                label = stringResource(R.string.board_logbook_sessions),
                color = OrangeAccent,
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                value = "${stats.uniqueClimbs}",
                label = stringResource(R.string.board_logbook_unique_climbs),
                color = WarningYellow,
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                value = if (stats.totalSends > 0) "${"%.0f".format(stats.flashRate)}%" else "-",
                label = stringResource(R.string.board_logbook_flash),
                color = InfoBlue,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun SummaryCard(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatDate(isoDate: String): String {
    return try {
        // Locale-aware date instead of a hardcoded German dd.MM.yyyy. java.text.*
        // works on every API level (no java.time core-library desugaring needed).
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .parse(isoDate.take(10))!!
        java.text.DateFormat
            .getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
            .format(parsed)
    } catch (_: Exception) {
        isoDate.take(10)
    }
}
