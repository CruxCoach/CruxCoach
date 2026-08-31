package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AscentWithClimb

internal data class BoardLogbookScenario(
    val id: String,
    val ascent: AscentWithClimb? = null,
    val selected: Boolean = false,
    val isInitialError: Boolean = false,
    val hasPageError: Boolean = false,
)

internal object BoardLogbookScenarios {
    val Content = BoardLogbookScenario(
        id = "logbook/content",
        selected = true,
        ascent = AscentWithClimb(
            uuid = "entry-1",
            climbUuid = "quiet-riot",
            angle = 40,
            isMirror = true,
            bidCount = 2,
            quality = 4,
            difficulty = 21,
            comment = null,
            climbedAt = "2026-08-30T11:45:00Z",
            climbName = "Quiet Riot",
            climbFrames = "p1100r12",
            difficultyAverage = 21.0,
            isSend = true,
            boardBrand = "kilter",
            layoutId = 1,
        ),
    )
    val Error = BoardLogbookScenario(
        id = "logbook/error",
        isInitialError = true,
    )
    val PageError = Content.copy(
        id = "logbook/page-error",
        hasPageError = true,
    )

    fun require(id: String): BoardLogbookScenario = when (id) {
        Content.id -> Content
        Error.id -> Error
        PageError.id -> PageError
        else -> throw IllegalArgumentException("Unknown DesignLab scenario: $id")
    }
}

@Preview(name = "EN light compact", group = "Board logbook", locale = "en", widthDp = 360, heightDp = 720)
@Preview(name = "DE light compact", group = "Board logbook", locale = "de", widthDp = 360, heightDp = 720)
@Preview(name = "EN dark compact", group = "Board logbook", locale = "en", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Board logbook", locale = "de", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Board logbook", locale = "en", widthDp = 840, heightDp = 720)
@Preview(name = "DE light expanded", group = "Board logbook", locale = "de", widthDp = 840, heightDp = 720)
@Preview(name = "EN dark expanded", group = "Board logbook", locale = "en", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Board logbook", locale = "de", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Board logbook large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Board logbook large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Board logbook large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Board logbook large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Board logbook large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Board logbook large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Board logbook large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Board logbook large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class BoardLogbookPreviewMatrix

@BoardLogbookPreviewMatrix
@Composable
private fun BoardLogbookContentPreview() {
    CruxCoachTheme { BoardLogbookScenarioContent(BoardLogbookScenarios.Content) }
}

@Composable
internal fun BoardLogbookScenarioContent(scenario: BoardLogbookScenario) {
    if (scenario.isInitialError) {
        BoardLogbookErrorMessage(onRetry = {})
        return
    }
    val ascent = requireNotNull(scenario.ascent)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.board_logbook_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.board_logbook_ascent_count, 1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DayHeader(dateKey = "2026-08-30", count = 1)
        AscentCard(
            ascent = ascent,
            gradeScale = GradeScale.V_SCALE,
            isSelected = scenario.selected,
            onClick = {},
            onToggleSelect = {},
            onEdit = {},
        )
        if (scenario.hasPageError) BoardLogbookLoadMoreError(onRetry = {})
    }
}
