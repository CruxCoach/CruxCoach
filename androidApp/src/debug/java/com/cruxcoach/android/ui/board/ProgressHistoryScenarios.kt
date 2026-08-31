package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HistoryRetentionPeriod
import com.cruxcoach.domain.board.ProgressHistoryEntry
import com.cruxcoach.domain.board.ProgressHistoryIssue
import com.cruxcoach.domain.board.ProgressHistoryScreenState

internal data class ProgressHistoryScenario(
    val id: String,
    val state: ProgressHistoryScreenState,
)

internal object ProgressHistoryScenarios {
    private val entries = listOf(
        ProgressHistoryEntry(
            id = 11,
            climbUuid = "quiet-riot",
            climbName = "Quiet Riot",
            angle = 40,
            boardBrand = BoardBrand.KILTER,
            layoutId = 1,
            difficultyAverage = 21.0,
            recordedAt = "2026-08-30T11:45:00",
        ),
        ProgressHistoryEntry(
            id = 12,
            climbUuid = "measured-progress",
            climbName = "A deliberately long project name for large text",
            angle = 35,
            boardBrand = BoardBrand.TENSION,
            layoutId = 7,
            difficultyAverage = null,
            recordedAt = "2026-08-29T18:10:00",
        ),
    )

    val History = ProgressHistoryScenario(
        id = "progress/history",
        state = ProgressHistoryScreenState.Content(
            entries = entries,
            retention = HistoryRetentionPeriod.DAYS_30,
            selectedIds = setOf(12),
        ),
    )
    val Empty = ProgressHistoryScenario(
        id = "progress/empty",
        state = ProgressHistoryScreenState.Empty(HistoryRetentionPeriod.DAYS_90),
    )
    val Error = ProgressHistoryScenario(
        id = "progress/error",
        state = ProgressHistoryScreenState.Error(
            issue = ProgressHistoryIssue.LOAD_FAILED,
            retention = HistoryRetentionPeriod.DAYS_30,
        ),
    )
    val ActionError = ProgressHistoryScenario(
        id = "progress/action-error",
        state = ProgressHistoryScreenState.Content(
            entries = entries,
            retention = HistoryRetentionPeriod.DAYS_30,
            selectedIds = setOf(12),
            transientIssue = ProgressHistoryIssue.DELETE_FAILED,
        ),
    )

    val all = sequenceOf(History, Empty, Error, ActionError)

    fun require(id: String): ProgressHistoryScenario = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown DesignLab scenario: $id")
}

internal class ProgressHistoryScenarioProvider : PreviewParameterProvider<ProgressHistoryScenario> {
    override val values: Sequence<ProgressHistoryScenario> = ProgressHistoryScenarios.all
}

@Preview(name = "EN light compact", group = "Progress history", locale = "en", widthDp = 360, heightDp = 720)
@Preview(name = "DE light compact", group = "Progress history", locale = "de", widthDp = 360, heightDp = 720)
@Preview(name = "EN dark compact", group = "Progress history", locale = "en", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Progress history", locale = "de", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Progress history", locale = "en", widthDp = 840, heightDp = 720)
@Preview(name = "DE light expanded", group = "Progress history", locale = "de", widthDp = 840, heightDp = 720)
@Preview(name = "EN dark expanded", group = "Progress history", locale = "en", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Progress history", locale = "de", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Progress history large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Progress history large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Progress history large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Progress history large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Progress history large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Progress history large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Progress history large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Progress history large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class ProgressHistoryPreviewMatrix

@ProgressHistoryPreviewMatrix
@Composable
private fun ProgressHistoryPreview(
    @PreviewParameter(ProgressHistoryScenarioProvider::class) scenario: ProgressHistoryScenario,
) {
    CruxCoachTheme { ProgressHistoryScenarioContent(scenario) }
}

@Composable
internal fun ProgressHistoryScenarioContent(scenario: ProgressHistoryScenario) {
    val today = stringResource(R.string.history_fixture_today)
    val yesterday = stringResource(R.string.history_fixture_yesterday)
    ProgressHistoryContent(
        state = scenario.state,
        labelsFor = { entry ->
            ProgressHistoryEntryLabels(
                grade = if (entry.difficultyAverage == null) "?" else "6c+",
                board = if (entry.boardBrand == BoardBrand.KILTER) {
                    "Kilter Original"
                } else {
                    "Tension Board 2"
                },
                date = if (entry.id == 11L) today else yesterday,
            )
        },
        onChooseRetention = {},
        onOpenEntry = {},
        onToggleSelection = {},
        onRetry = {},
    )
}
