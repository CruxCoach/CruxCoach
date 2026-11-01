package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.gradeColorForDifficulty
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * The top of the statistics: the hardest send and flash as two big tiles,
 * then the last 30 days against the 30 before. Green means better, so fewer
 * tries per send is green as well. [boards] are all boards the user has
 * logged on; with more than one, each record names its board.
 */
@Composable
internal fun AtAGlanceCard(glance: AtAGlance, boards: List<String>) {
    if (glance.hardestSend == null && glance.recent == null) return
    val colors = boardSeriesColors(boards)
    val showBoard = boards.size > 1
    ChartSection(
        stringResource(R.string.board_stats_glance_title),
        help = stringResource(R.string.board_stats_glance_help, StatsHighlightsComputer.RECENT_DAYS.toInt()),
    ) {
        if (glance.hardestSend != null) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordTile(
                    glance.hardestSend, stringResource(R.string.board_stats_glance_hardest_send),
                    colors.takeIf { showBoard }, Modifier.weight(1f).fillMaxHeight(),
                )
                RecordTile(
                    glance.hardestFlash, stringResource(R.string.board_stats_glance_hardest_flash),
                    colors.takeIf { showBoard }, Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        glance.recent?.let { recent ->
            if (glance.hardestSend != null) Spacer(Modifier.height(16.dp))
            RecentRow(recent)
        }
    }
}

@Composable
private fun RecordTile(record: SentGrade?, label: String, boardColors: Map<String, Color>?, modifier: Modifier) {
    val tint = record?.let { gradeColorForDifficulty(it.difficulty) } ?: MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .background(tint.copy(alpha = if (record != null) 0.16f else 0.05f), RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            record?.grade ?: "–",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (record != null && boardColors != null) {
            Spacer(Modifier.height(4.dp))
            BoardTag(record.board, boardColors[record.board] ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A board's colour dot and name. */
@Composable
internal fun BoardTag(board: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            boardBrandLabel(board),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentRow(recent: RecentComparison) {
    val days = StatsHighlightsComputer.RECENT_DAYS.toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.board_stats_glance_recent, days) + " · " +
                pluralStringResource(R.plurals.board_stats_glance_days, recent.sessions, recent.sessions),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (recent.hasPrevious) {
            Text(
                stringResource(R.string.board_stats_glance_versus, days),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tile = Modifier.weight(1f).fillMaxHeight()
        DeltaTile(
            value = "${recent.sends}",
            label = stringResource(R.string.board_sends),
            delta = (recent.sends - recent.previousSends).takeIf { recent.hasPrevious }?.let { d ->
                Delta(signed(d), better = d > 0, worse = d < 0)
            },
            modifier = tile,
        )
        DeltaTile(
            value = recent.hardest?.grade ?: "–",
            label = stringResource(R.string.board_stats_glance_hardest),
            delta = recent.hardestStep?.let { d -> Delta(signed(d), better = d > 0, worse = d < 0) },
            modifier = tile,
        )
        val tries = recent.triesPerSend
        val previous = recent.previousTriesPerSend
        DeltaTile(
            value = tries?.let { stringResource(R.string.board_stats_cross_board_tries_number, it) } ?: "–",
            label = stringResource(R.string.board_stats_cross_board_tries_per_send),
            delta = if (tries != null && previous != null) {
                val d = tries - previous
                // Fewer tries per send is the good direction.
                val shown = stringResource(R.string.board_stats_glance_tries_delta, abs(d))
                val zero = shown == stringResource(R.string.board_stats_glance_tries_delta, 0.0)
                Delta(
                    if (zero) "±$shown" else (if (d > 0) "+" else "−") + shown,
                    better = !zero && d < 0,
                    worse = !zero && d > 0,
                    up = d > 0,
                )
            } else {
                null
            },
            modifier = tile,
        )
    }
}

private fun signed(value: Int) = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "±0"
}

/** A change and whether it is good news; [up] is the direction of the number itself. */
private class Delta(val text: String, val better: Boolean, val worse: Boolean, val up: Boolean = better)

@Composable
private fun DeltaTile(value: String, label: String, delta: Delta?, modifier: Modifier) {
    val description = listOfNotNull(label, value, delta?.text).joinToString(", ")
    Column(
        modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (delta != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (delta.better || delta.worse) {
                    Icon(
                        if (delta.up) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = if (delta.better) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    delta.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------- milestones

/** The badges in one row, newest first; the row scrolls when they do not fit. */
@Composable
internal fun MilestonesCard(milestones: List<Milestone>, boards: List<String>) {
    if (milestones.isEmpty()) return
    val colors = boardSeriesColors(boards)
    ChartSection(
        stringResource(R.string.board_stats_milestones),
        help = stringResource(
            R.string.board_stats_milestones_help,
            StatsHighlightsComputer.BIGGEST_DAY_MIN,
        ),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(milestones, key = { "${it.kind}-${it.date}-${it.count}" }) { milestone ->
                MilestoneBadge(milestone, colors.takeIf { boards.size > 1 })
            }
        }
    }
}

private fun milestoneIcon(kind: MilestoneKind): ImageVector = when (kind) {
    MilestoneKind.NEW_GRADE -> Icons.Filled.EmojiEvents
    MilestoneKind.SENDS -> Icons.Filled.Flag
    MilestoneKind.BIGGEST_DAY -> Icons.Filled.WbSunny
    MilestoneKind.WEEK_STREAK -> Icons.Filled.LocalFireDepartment
}

@Composable
private fun milestoneCaption(milestone: Milestone): String = when (milestone.kind) {
    MilestoneKind.NEW_GRADE -> stringResource(R.string.board_stats_milestone_new_grade)
    MilestoneKind.SENDS -> stringResource(R.string.board_sends)
    MilestoneKind.BIGGEST_DAY -> stringResource(R.string.board_stats_milestone_biggest_day)
    MilestoneKind.WEEK_STREAK ->
        pluralStringResource(R.plurals.board_stats_milestone_weeks, milestone.count)
}

/** "12. Okt." / "Oct 12": a badge's day, with the year when it is not this one. */
@Composable
internal fun milestoneDate(date: LocalDate): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val pattern = if (date.year == LocalDate.now().year) {
        android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM")
    } else {
        android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMyy")
    }
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** One badge: its icon, the number or grade it is about, what it is and when. */
@Composable
internal fun MilestoneBadge(milestone: Milestone, boardColors: Map<String, Color>?, showDate: Boolean = true) {
    val accent = milestone.grade?.let { gradeColorForDifficulty(it.difficulty) } ?: MaterialTheme.colorScheme.primary
    val value = milestone.grade?.grade ?: "${milestone.count}"
    val caption = milestoneCaption(milestone)
    val date = milestone.date?.takeIf { showDate }?.let { milestoneDate(it) }
    Column(
        Modifier
            .width(96.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(caption, value, date).joinToString(", ")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(milestoneIcon(milestone.kind), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        // Two caption lines and a date line on every badge, so the row keeps one height.
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (showDate) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val board = milestone.grade?.board
                if (board != null && boardColors != null) {
                    Spacer(
                        Modifier.size(6.dp).background(
                            boardColors[board] ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        ),
                    )
                }
                Text(
                    date ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}
