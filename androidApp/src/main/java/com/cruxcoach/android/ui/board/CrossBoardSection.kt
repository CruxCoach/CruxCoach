package com.cruxcoach.android.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.GradeHard

private val LABEL_COLUMN_WIDTH = 64.dp
// Wide enough for "2,4 Versuche" on one line at the default font scale.
private val BOARD_COLUMN_WIDTH = 96.dp

@Composable
private fun crossBoardViewLabel(view: CrossBoardView): String = when (view) {
    CrossBoardView.TRIES_PER_GRADE -> stringResource(R.string.board_stats_cross_board_tries)
    CrossBoardView.PER_MONTH -> stringResource(R.string.board_stats_cross_board_months)
}

/**
 * Stats across boards (#44): a table with one column per board. "Tries per
 * grade" shows how many tries a grade takes on each board; "Per month" the
 * hardest new send and the tries of that month. Averages over fewer than
 * [CrossBoardStatsComputer.SMALL_SAMPLE] problems are dimmed. Scrolls
 * sideways when the boards do not fit the width.
 */
@Composable
internal fun CrossBoardSection(
    stats: CrossBoardStats,
    view: CrossBoardView,
    onViewSelect: (CrossBoardView) -> Unit,
) {
    ChartSectionWithSelector(
        options = CrossBoardView.entries.toTypedArray(),
        selected = view,
        onSelect = onViewSelect,
        labelOf = { crossBoardViewLabel(it) },
    ) {
        Text(
            stringResource(
                when (view) {
                    CrossBoardView.TRIES_PER_GRADE -> R.string.board_stats_cross_board_tries_hint
                    CrossBoardView.PER_MONTH -> R.string.board_stats_cross_board_months_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row {
                HeaderCell(
                    stringResource(
                        when (view) {
                            CrossBoardView.TRIES_PER_GRADE -> R.string.board_stats_cross_board_grade
                            CrossBoardView.PER_MONTH -> R.string.board_stats_cross_board_month
                        },
                    ),
                    LABEL_COLUMN_WIDTH,
                    TextAlign.Start,
                )
                stats.boards.forEach { HeaderCell(boardBrandLabel(it), BOARD_COLUMN_WIDTH, TextAlign.End) }
            }
            // fillMaxWidth has no effect inside horizontalScroll: size it to the table.
            HorizontalDivider(Modifier.width(LABEL_COLUMN_WIDTH + BOARD_COLUMN_WIDTH * stats.boards.size))
            when (view) {
                CrossBoardView.TRIES_PER_GRADE -> stats.grades.forEach { row ->
                    Row(verticalAlignment = Alignment.Top) {
                        LabelCell(row.grade)
                        stats.boards.forEach { board -> TriesCell(row.byBoard[board]) }
                    }
                }
                CrossBoardView.PER_MONTH -> stats.months.forEach { row ->
                    Row(verticalAlignment = Alignment.Top) {
                        LabelCell(row.label)
                        stats.boards.forEach { board -> MonthCell(row.byBoard[board]) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.board_stats_cross_board_small_sample, CrossBoardStatsComputer.SMALL_SAMPLE),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, align: TextAlign) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
    )
}

@Composable
private fun LabelCell(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.width(LABEL_COLUMN_WIDTH).padding(horizontal = 4.dp),
    )
}

/** Dims a cell whose average rests on too few problems to say much. */
@Composable
private fun sampleColor(tries: TriesToSend, color: Color): Color =
    if (tries.problems < CrossBoardStatsComputer.SMALL_SAMPLE) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        color
    }

@Composable
private fun EmptyCell() {
    Text(
        "–",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.width(BOARD_COLUMN_WIDTH).padding(horizontal = 4.dp),
    )
}

@Composable
private fun TriesCell(tries: TriesToSend?) {
    if (tries == null) {
        EmptyCell()
        return
    }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.width(BOARD_COLUMN_WIDTH).padding(horizontal = 4.dp),
    ) {
        TriesText(tries, MaterialTheme.typography.bodyMedium, FontWeight.Bold)
        ProblemsText(tries)
    }
}

@Composable
private fun MonthCell(cell: CrossBoardMonthCell?) {
    if (cell == null) {
        EmptyCell()
        return
    }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.width(BOARD_COLUMN_WIDTH).padding(horizontal = 4.dp),
    ) {
        Text(
            cell.hardestGrade ?: "–",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            // The hardest send is a fact, not an average: never dimmed.
            color = GradeHard,
            maxLines = 1,
        )
        TriesText(cell.tries, MaterialTheme.typography.labelMedium, FontWeight.Normal)
        ProblemsText(cell.tries)
    }
}

@Composable
private fun TriesText(tries: TriesToSend, style: androidx.compose.ui.text.TextStyle, weight: FontWeight) {
    Text(
        stringResource(R.string.board_stats_cross_board_tries_value, tries.averageTries),
        style = style,
        fontWeight = weight,
        color = sampleColor(tries, MaterialTheme.colorScheme.onSurface),
        maxLines = 1,
    )
}

@Composable
private fun ProblemsText(tries: TriesToSend) {
    Text(
        pluralStringResource(R.plurals.board_stats_cross_board_problems, tries.problems, tries.problems),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}
