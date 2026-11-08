package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
@OptIn(ExperimentalLayoutApi::class)
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
        Column(Modifier.fillMaxWidth().padding(start = CARD_PADDING, end = CARD_PADDING, top = 4.dp, bottom = 14.dp)) {
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
                val sendLabel = stringResource(R.string.board_stats_glance_hardest_send)
                val flashLabel = stringResource(R.string.board_stats_glance_hardest_flash)
                val boardColors = colors.takeIf { boards.size > 1 }
                NumberGrid(
                    perRow = 2,
                    labels = listOf(sendLabel, flashLabel),
                    labelStyle = MaterialTheme.typography.labelMedium,
                    spacing = 8.dp,
                    slack = 24.dp,
                    cells = listOf<@Composable (Modifier) -> Unit>(
                        { column -> RecordTile(glance.hardestSend, sendLabel, boardColors, column) },
                        { column -> RecordTile(glance.hardestFlash, flashLabel, boardColors, column) },
                    ),
                )
            }
            glance.recent?.let { recent ->
                Spacer(Modifier.height(8.dp))
                RecentRow(recent)
            }
            if (milestones.isNotEmpty() || glance.sessionsPerWeek > 0.0) {
                Spacer(Modifier.height(10.dp))
                // One row at every font size, so a long list of badges never pushes the
                // entries down. It scrolls under the card's edges, so a cut chip reads as
                // "more to the side"; a chip is at most as wide as the card and wraps its text.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val chipMax = maxWidth
                    Row(
                        Modifier
                            .layout { measurable, constraints ->
                                val edge = CARD_PADDING.roundToPx()
                                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + 2 * edge))
                                layout(constraints.maxWidth, placeable.height) { placeable.place(-edge, 0) }
                            }
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = CARD_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // How regularly, then the badges.
                        if (glance.sessionsPerWeek > 0.0) PerWeekChip(glance.sessionsPerWeek, Modifier.widthIn(max = chipMax))
                        milestones.forEach { milestone ->
                            MilestoneChip(milestone, colors.takeIf { boards.size > 1 }, Modifier.widthIn(max = chipMax))
                        }
                    }
                }
            }
        }
    }
}

/**
 * From this font scale on, the overview's header drops the words of its link
 * and rows of labelled values stack their label above the value.
 */
internal const val LARGE_FONT_SCALE = 1.5f

private val CARD_PADDING = 16.dp

/** The width of the widest of [texts] on one line in [style]. */
@Composable
internal fun widestText(texts: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, style, density, measurer) {
        val widest = texts.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        with(density) { widest.toDp() }
    }
}

/** The height of one line of text in [style]. */
@Composable
internal fun textLineHeight(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density, measurer) { with(density) { measurer.measure("Ag", style).size.height.toDp() } }
}

/** The narrowest a column of [texts] may get without breaking a word. */
@Composable
internal fun longestWord(texts: List<String>, style: TextStyle): Dp =
    widestText(texts.flatMap { it.split(' ', '\n') }.filter { it.isNotBlank() }, style)

/**
 * [cells] in equal columns: at most [perRow] to a row, and only as many as
 * keep the longest word of their [labels] (in [labelStyle], plus the cells'
 * own padding as [slack]) in one piece. The rows are filled evenly, so four
 * cells that do not fit in a row become two rows of two. Each cell gets its
 * column's modifier.
 */
@Composable
internal fun NumberGrid(
    cells: List<@Composable (Modifier) -> Unit>,
    perRow: Int,
    labels: List<String>,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    spacing: Dp = 0.dp,
    slack: Dp = 12.dp,
) {
    val word = longestWord(labels, labelStyle) + slack
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth + spacing) / (word + spacing)).toInt().coerceIn(1, perRow)
        val rows = (cells.size + fit - 1) / fit
        val columns = (cells.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(if (spacing > 0.dp) spacing else 10.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { cell -> cell(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
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
            labels = listOf(sessions, sends, hardest),
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
                        reserveDelta = recent.hasPrevious,
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
private fun DeltaColumn(value: String, label: String, delta: Delta?, modifier: Modifier, reserveDelta: Boolean = false) {
    val description = listOfNotNull(label, value, delta?.text).joinToString(", ")
    Column(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (delta == null && reserveDelta) {
            // Keeps the label on the line of its neighbours' labels.
            Text("", style = MaterialTheme.typography.labelMedium, modifier = Modifier.heightIn(min = 18.dp))
        }
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
private fun PerWeekChip(perWeek: Double, modifier: Modifier = Modifier) {
    val value = stringResource(R.string.board_stats_glance_per_week_value, perWeek)
    val caption = stringResource(R.string.board_stats_glance_per_week)
    OverviewChip(
        icon = Icons.Filled.CalendarMonth,
        tint = OrangeAccent,
        value = value,
        caption = caption,
        description = "$caption: $value",
        modifier = modifier,
    )
}

/** A milestone as one small chip: its icon, what it is and when. */
@Composable
private fun MilestoneChip(milestone: Milestone, boardColors: Map<String, Color>?, modifier: Modifier = Modifier) {
    val value = milestone.grade?.grade ?: "${milestone.count}"
    val caption = milestoneCaption(milestone)
    val date = milestone.date?.let { milestoneDate(it) }
    val board = milestone.grade?.board
    OverviewChip(
        icon = milestoneIcon(milestone.kind),
        tint = milestone.grade?.let { gradeColorForDifficulty(it.difficulty) } ?: OrangeAccent,
        value = value,
        caption = caption,
        date = date,
        // The board's dot before the date, when several boards are logged.
        dot = board?.let { b -> boardColors?.let { it[b] ?: MaterialTheme.colorScheme.onSurfaceVariant } },
        description = listOfNotNull(caption, value, date).joinToString(", "),
        modifier = modifier,
    )
}

/**
 * The overview's chip: icon, value, caption and an optional date as one text,
 * so a chip that is narrower than its text wraps instead of cutting words off.
 */
@Composable
private fun OverviewChip(
    icon: ImageVector,
    tint: Color,
    value: String,
    caption: String,
    description: String,
    modifier: Modifier = Modifier,
    date: String? = null,
    dot: Color? = null,
) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelMedium
    val text = buildAnnotatedString {
        withStyle(MaterialTheme.typography.labelLarge.toSpanStyle().copy(fontWeight = FontWeight.Bold)) { append(value) }
        append(" ")
        withStyle(small.toSpanStyle().copy(color = variant)) { append(caption) }
        if (date != null) {
            append(" ")
            if (dot != null) withStyle(small.toSpanStyle().copy(color = dot)) { append("• ") }
            withStyle(small.toSpanStyle().copy(color = variant.copy(alpha = 0.7f))) { append(date.replace(' ', '\u00A0')) }
        }
    }
    Row(
        modifier
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = small)
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
    // As wide as the caption's longest word needs, so large fonts do not break it.
    val width = maxOf(96.dp, longestWord(listOf(caption), MaterialTheme.typography.labelSmall) + 12.dp)
    Column(
        Modifier
            .width(width)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(caption, value, date).joinToString(", ")
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(milestoneIcon(milestone.kind), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        // At least two caption lines and a date line on every badge, so a row keeps one height.
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 3,
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
