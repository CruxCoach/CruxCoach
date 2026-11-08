package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.holdtypes.typeName
import com.cruxcoach.android.ui.theme.GradeEasy
import com.cruxcoach.android.ui.theme.GradeHard
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.domain.holdtypes.HoldTypeStats

/** Rows whose share is below this for you and the board are left out (half a percent). */
private const val MIN_SHOWN_SHARE = 0.005

/**
 * Hold types (#63): the share of each type among the hand holds of your problems, with a mark for the same share on
 * the board's problems in your grades, and the types you are strong or weak on. Types you climb most come first.
 * Tap a type for its numbers. [boardName] names the board when the user climbs on several.
 */
@Composable
internal fun HoldTypesCard(stats: HoldTypeCardStats, gradeScale: GradeScale, boardName: String?, color: Color) {
    val title = boardName?.let { stringResource(R.string.board_stats_hold_types_title_board, it) }
        ?: stringResource(R.string.board_stats_hold_types_title)
    val rows = stats.rows
        .filter { it.yours >= MIN_SHOWN_SHARE || (it.board ?: 0.0) >= MIN_SHOWN_SHARE }
        .sortedByDescending { it.yours }
    ChartSection(title, help = stringResource(R.string.board_stats_hold_types_help)) {
        HoldTypesLegend(color, withBoard = stats.boardProblems > 0)
        Spacer(Modifier.height(10.dp))
        val most = rows.maxOf { maxOf(it.yours, it.board ?: 0.0) }.coerceAtLeast(0.01)
        var selected by remember(stats) { mutableStateOf<Int?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                val expanded = selected == row.type.ordinal
                HoldTypeRowView(row, stats.overall.sendRate, most, color, expanded, gradeScale) {
                    selected = if (expanded) null else row.type.ordinal
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            pluralStringResource(R.plurals.board_stats_hold_types_basis, stats.problems, stats.problems, stats.countedHolds, stats.handHolds),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HoldTypeRowView(
    row: HoldTypeRow,
    overallRate: Double?,
    most: Double,
    color: Color,
    expanded: Boolean,
    gradeScale: GradeScale,
    onClick: () -> Unit,
) {
    val name = typeName(row.type)
    val share = "${Math.round(row.yours * 100)} %"
    val strength = when (row.strength) {
        HoldTypeStats.Strength.STRONG -> stringResource(R.string.board_stats_hold_types_strong) to GradeEasy
        HoldTypeStats.Strength.WEAK -> stringResource(R.string.board_stats_hold_types_weak) to GradeHard
        else -> null
    }
    val details = holdTypeDetails(row, overallRate, gradeScale)
    val ink = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (expanded) ink.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = listOfNotNull("$name $share", strength?.first).plus(details).joinToString("; ")
            }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            strength?.let { (label, tint) ->
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(share, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val radius = CornerRadius(4.dp.toPx())
            val barTop = 2.dp.toPx()
            val barHeight = size.height - 2 * barTop
            drawRoundRect(ink.copy(alpha = 0.08f), Offset(0f, barTop), Size(size.width, barHeight), radius)
            val w = (size.width * (row.yours / most)).toFloat()
            if (w > 0f) drawRoundRect(color, Offset(0f, barTop), Size(w.coerceAtLeast(2.dp.toPx()), barHeight), radius)
            row.board?.let { board ->
                val tick = 3.dp.toPx()
                val x = ((size.width * (board / most)).toFloat()).coerceIn(tick / 2, size.width - tick / 2)
                drawRoundRect(ink, Offset(x - tick / 2, 0f), Size(tick, size.height), CornerRadius(tick / 2))
            }
        }
        if (expanded) {
            details.forEachIndexed { i, detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (i == 0) 6.dp else 2.dp),
                )
            }
        }
    }
}

/** The numbers behind a row, shown on tap: the board's share, and how you do where the type dominates. */
@Composable
private fun holdTypeDetails(row: HoldTypeRow, overallRate: Double?, gradeScale: GradeScale): List<String> {
    val level = row.level
    return listOfNotNull(
        row.board?.let { stringResource(R.string.board_stats_hold_types_board_share, Math.round(it * 100).toInt()) },
        level.sendRate?.let { rate ->
            pluralStringResource(
                R.plurals.board_stats_hold_types_send_rate,
                level.problems,
                Math.round(rate * 100).toInt(),
                level.problems,
                Math.round((overallRate ?: 0.0) * 100).toInt(),
            )
        },
        level.hardestSend?.let {
            stringResource(R.string.board_stats_hold_types_hardest, GradeDisplayHelper.formatDifficulty(it, gradeScale))
        },
        stringResource(R.string.board_stats_hold_types_too_few).takeIf { row.strength == HoldTypeStats.Strength.TOO_FEW },
    )
}

/** "▬ You  ┃ Board at your grades": the bar is yours, the mark is the board's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoldTypesLegend(color: Color, withBoard: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.size(width = 14.dp, height = 10.dp).background(color, RoundedCornerShape(2.dp)))
            Text(
                stringResource(R.string.board_stats_hold_types_you),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (withBoard) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.size(width = 3.dp, height = 14.dp).background(ink, RoundedCornerShape(2.dp)))
                Text(
                    stringResource(R.string.board_stats_hold_types_board),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
