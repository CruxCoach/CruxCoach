package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardBrowserScreenState
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BrowserBoardContext
import com.cruxcoach.domain.board.BrowserClimb
import com.cruxcoach.domain.board.BrowserConnection
import com.cruxcoach.domain.board.BrowserEmptyKind
import com.cruxcoach.domain.board.BrowserIssue

internal data class BoardBrowserScenario(
    val id: String,
    val state: BoardBrowserScreenState,
)

internal object BoardBrowserScenarios {
    private val board = BrowserBoardContext(
        brand = BoardBrand.KILTER,
        layoutId = 1,
        productName = "Original 12x12",
        angle = 40,
    )
    private val disconnected = BrowserConnection(BoardConnectionState.DISCONNECTED)

    val Content = BoardBrowserScenario(
        id = "browser/content",
        state = BoardBrowserScreenState.Content(
            board = board,
            connection = disconnected,
            query = "",
            activeFilterCount = 0,
            climbs = listOf(
                BrowserClimb("quiet-riot", "Quiet Riot", "Alex", 21.0, 4.3, 142, false, false),
                BrowserClimb("benchmark-one", "Benchmark One", "Sam", 22.0, 4.8, 98, true, false),
                BrowserClimb("project-zero", "Project Zero", null, null, null, null, false, false),
            ),
            totalResultCount = 312,
            canLoadMore = true,
        ),
    )
    val Empty = BoardBrowserScenario(
        id = "browser/empty",
        state = BoardBrowserScreenState.Empty(
            kind = BrowserEmptyKind.NO_RESULTS,
            board = board,
            connection = disconnected,
            activeFilterCount = 2,
        ),
    )
    val Error = BoardBrowserScenario(
        id = "browser/error",
        state = BoardBrowserScreenState.Error(
            issue = BrowserIssue.QUERY_FAILED,
            board = board,
        ),
    )

    val all = sequenceOf(Content, Empty, Error)

    fun require(id: String): BoardBrowserScenario = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown DesignLab scenario: $id")
}

internal class BoardBrowserScenarioProvider : PreviewParameterProvider<BoardBrowserScenario> {
    override val values: Sequence<BoardBrowserScenario> = BoardBrowserScenarios.all
}

@Preview(name = "EN light compact", group = "Board browser", locale = "en", widthDp = 360, heightDp = 720)
@Preview(name = "DE light compact", group = "Board browser", locale = "de", widthDp = 360, heightDp = 720)
@Preview(name = "EN dark compact", group = "Board browser", locale = "en", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Board browser", locale = "de", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Board browser", locale = "en", widthDp = 840, heightDp = 720)
@Preview(name = "DE light expanded", group = "Board browser", locale = "de", widthDp = 840, heightDp = 720)
@Preview(name = "EN dark expanded", group = "Board browser", locale = "en", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Board browser", locale = "de", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Board browser large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Board browser large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Board browser large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Board browser large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Board browser large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Board browser large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Board browser large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Board browser large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class BoardBrowserPreviewMatrix

@BoardBrowserPreviewMatrix
@Composable
private fun BoardBrowserPreview(
    @PreviewParameter(BoardBrowserScenarioProvider::class) scenario: BoardBrowserScenario,
) {
    CruxCoachTheme { BoardBrowserScenarioContent(scenario) }
}

@Composable
internal fun BoardBrowserScenarioContent(scenario: BoardBrowserScenario) {
    var query by remember(scenario.id) {
        mutableStateOf((scenario.state as? BoardBrowserScreenState.Content)?.query.orEmpty())
    }
    val board = when (val state = scenario.state) {
        is BoardBrowserScreenState.Content -> state.board
        is BoardBrowserScreenState.Empty -> state.board
        is BoardBrowserScreenState.Error -> state.board
        is BoardBrowserScreenState.Loading -> state.board
        is BoardBrowserScreenState.FirstRun -> state.selectedBoard
    }
    val connection = when (val state = scenario.state) {
        is BoardBrowserScreenState.Content -> state.connection
        is BoardBrowserScreenState.Empty -> state.connection
        else -> BrowserConnection(BoardConnectionState.DISCONNECTED)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        board?.let {
            BoardBrowserHeader(
                board = it,
                connection = connection,
                query = query,
                activeFilterCount = when (val state = scenario.state) {
                    is BoardBrowserScreenState.Content -> state.activeFilterCount
                    is BoardBrowserScreenState.Empty -> state.activeFilterCount
                    else -> 0
                },
                onSelectBoard = {},
                onConnectBoard = {},
                onQueryChanged = { value -> query = value },
                onOpenFilters = {},
            )
        }
        when (val state = scenario.state) {
            is BoardBrowserScreenState.Content -> Column(
                modifier = Modifier
                    .padding(CruxCoachSpacing.large)
                    .testTag("browser_scenario_results"),
                verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
            ) {
                state.climbs.forEach { climb ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CruxCoachDesign.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = climb.name,
                            modifier = Modifier.padding(CruxCoachSpacing.large),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            is BoardBrowserScreenState.Empty -> ScenarioRecovery(
                title = stringResource(R.string.board_browser_empty_no_results),
                action = stringResource(R.string.board_browser_empty_clear_filters),
                tag = "browser_scenario_empty",
            )
            is BoardBrowserScreenState.Error -> BoardBrowserErrorContent(onRetry = {})
            else -> Unit
        }
    }
}

@Composable
private fun ScenarioRecovery(title: String, action: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CruxCoachSpacing.xLarge)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.large),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Button(onClick = {}, modifier = Modifier.testTag("$tag-action")) {
            Text(action)
        }
    }
}
