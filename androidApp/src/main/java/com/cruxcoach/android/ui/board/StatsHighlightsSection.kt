package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.gradeColorForDifficulty
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The overview at the top of the logbook: the hardest send and flash, the last
 * 30 days against the 30 before, and the milestones as small chips, over every
 * board and the whole logbook. Green means better, so fewer tries per send is
 * green as well. [boards] are all boards the user has logged on; with more
 * than one, each record names its board. Tapping it opens the statistics.
 */
@Composable
internal fun LogbookOverviewCard(
    glance: AtAGlance,
    milestones: List<Milestone>,
    boards: List<String>,
    onOpenStats: () -> Unit,
) {
    if (glance.hardestSend == null && glance.recent == null) return
    val colors = boardSeriesColors(boards)
    val openStats = stringResource(R.string.board_stats_open)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = openStats, onClick = onOpenStats),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = stringResource(R.string.board_stats_glance_title)
                // The title may wrap; the link to the statistics keeps its line.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                    )
                    InfoButton(
                        title,
                        stringResource(R.string.board_stats_glance_help, StatsHighlightsComputer.RECENT_DAYS.toInt()) +
                            "\n\n" + stringResource(R.string.board_stats_milestones_help, StatsHighlightsComputer.BIGGEST_DAY_MIN),
                    )
                }
                // At the largest font sizes the arrow alone stands for the link:
                // the whole card opens the statistics and says so to screen readers.
                if (LocalDensity.current.fontScale < LARGE_FONT_SCALE) {
                    Text(
                        stringResource(R.string.board_stats_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = OrangeAccent,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (glance.hardestSend != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RecordTile(
                        glance.hardestSend, stringResource(R.string.board_stats_glance_hardest_send),
                        colors.takeIf { boards.size > 1 }, Modifier.weight(1f).fillMaxHeight(),
                    )
                    RecordTile(
                        glance.hardestFlash, stringResource(R.string.board_stats_glance_hardest_flash),
                        colors.takeIf { boards.size > 1 }, Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            glance.recent?.let { recent ->
                Spacer(Modifier.height(8.dp))
                RecentRow(recent)
            }
            if (milestones.isNotEmpty() || glance.sessionsPerWeek > 0.0) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // How regularly, then the badges.
                    if (glance.sessionsPerWeek > 0.0) {
                        item(key = "per-week") { PerWeekChip(glance.sessionsPerWeek) }
                    }
                    items(milestones, key = { "${it.kind}-${it.date}-${it.count}" }) { milestone ->
                        MilestoneChip(milestone, colors.takeIf { boards.size > 1 })
                    }
                }
            }
        }
    }
}

/**
 * From this font scale on, the overview's header drops the words of its link
 * and the rows of numbers put two to a row, so their labels keep whole words.
 */
internal const val LARGE_FONT_SCALE = 1.5f

/**
 * [cells] in equal columns, [perRow] to a row, or two to a row from
 * [LARGE_FONT_SCALE] on. Each cell gets its column's modifier.
 */
@Composable
internal fun NumberGrid(cells: List<@Composable (Modifier) -> Unit>, perRow: Int) {
    val columns = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) minOf(2, perRow) else perRow
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { cell -> cell(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecordTile(record: SentGrade?, label: String, boardColors: Map<String, Color>?, modifier: Modifier) {
    val tint = record?.let { gradeColorForDifficulty(it.difficulty) } ?: MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .background(tint.copy(alpha = if (record != null) 0.16f else 0.05f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            record?.grade ?: "–",
            style = MaterialTheme.typography.headlineSmall,
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
            Spacer(Modifier.height(2.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentRow(recent: RecentComparison) {
    val days = StatsHighlightsComputer.RECENT_DAYS.toInt()
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        // The comparison moves below the title when both do not fit on one line.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.board_stats_glance_recent, days),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
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
        // Climbing days, sends and the hardest grade side by side.
        val sessions = stringResource(R.string.board_logbook_sessions)
        val sends = stringResource(R.string.board_sends)
        val hardest = stringResource(R.string.board_stats_glance_hardest)
        NumberGrid(
            perRow = 3,
            cells = listOf<@Composable (Modifier) -> Unit>(
                { column ->
                    DeltaColumn(
                        value = "${recent.sessions}",
                        label = sessions,
                        delta = (recent.sessions - recent.previousSessions).takeIf { recent.hasPrevious }?.let { d ->
                            Delta(signed(d), better = d > 0, worse = d < 0)
                        },
                        modifier = column,
                    )
                },
                { column ->
                    DeltaColumn(
                        value = "${recent.sends}",
                        label = sends,
                        delta = (recent.sends - recent.previousSends).takeIf { recent.hasPrevious }?.let { d ->
                            Delta(signed(d), better = d > 0, worse = d < 0)
                        },
                        modifier = column,
                    )
                },
                { column ->
                    DeltaColumn(
                        value = recent.hardest?.grade ?: "–",
                        label = hardest,
                        delta = recent.hardestStep?.let { d -> Delta(signed(d), better = d > 0, worse = d < 0) },
                        modifier = column,
                    )
                },
            ),
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
private fun DeltaColumn(value: String, label: String, delta: Delta?, modifier: Modifier) {
    val description = listOfNotNull(label, value, delta?.text).joinToString(", ")
    Column(
        modifier.clearAndSetSemantics { contentDescription = description },
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

/** Climbing days per week over the last eight weeks, as a chip beside the milestones. */
@Composable
private fun PerWeekChip(perWeek: Double) {
    val value = stringResource(R.string.board_stats_glance_per_week_value, perWeek)
    val caption = stringResource(R.string.board_stats_glance_per_week)
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(50))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = "$caption: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = OrangeAccent, modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A milestone as one small chip: its icon, what it is and when. */
@Composable
private fun MilestoneChip(milestone: Milestone, boardColors: Map<String, Color>?) {
    val accent = milestone.grade?.let { gradeColorForDifficulty(it.difficulty) } ?: OrangeAccent
    val value = milestone.grade?.grade ?: "${milestone.count}"
    val caption = milestoneCaption(milestone)
    val date = milestone.date?.let { milestoneDate(it) }
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(50))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = listOfNotNull(caption, value, date).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(milestoneIcon(milestone.kind), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (date != null) {
            val board = milestone.grade?.board
            if (board != null && boardColors != null) {
                Spacer(Modifier.size(6.dp).background(boardColors[board] ?: MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
            Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
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
