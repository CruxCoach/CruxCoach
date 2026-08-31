package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.ProgressHistoryIssue
import com.cruxcoach.domain.board.ProgressHistoryScreenState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProgressHistoryContentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `content exposes labelled rows selection and 48dp retention targets`() {
        var toggled = 0
        compose.setContent {
            content(ProgressHistoryScenarios.History, onToggle = { toggled += 1 })
        }

        compose.onNodeWithText("Keep history").assertExists()
        compose.onNodeWithTag("history_retention_days_30")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(
            "Quiet Riot, 6c+, 40 degrees, Kilter Original, Today, 11:45",
        ).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Select Quiet Riot")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("history_entry_11").assertHeightIsAtLeast(48.dp)
        assertEquals(1, toggled)
    }

    @Test
    fun `empty state retains controls and local-only disclosure`() {
        compose.setContent { content(ProgressHistoryScenarios.Empty) }

        compose.onNodeWithText("No history yet").assertExists()
        compose.onNodeWithText("Saved only on this device — not included in the backup.").assertExists()
        compose.onNodeWithTag("history_retention_days_90").assertIsSelected()
    }

    @Test
    fun `error has one explicit retry target`() {
        var retries = 0
        compose.setContent { content(ProgressHistoryScenarios.Error, onRetry = { retries += 1 }) }

        compose.onNodeWithText("History unavailable").assertExists()
        compose.onNodeWithTag("history_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `action failure keeps history usable and exposes retry`() {
        var retries = 0
        val base = ProgressHistoryScenarios.History
        val scenario = base.copy(
            state = (base.state as ProgressHistoryScreenState.Content).copy(
                transientIssue = ProgressHistoryIssue.DELETE_FAILED,
            ),
        )
        compose.setContent { content(scenario, onRetry = { retries += 1 }) }

        compose.onNodeWithText(
            "The selected entries couldn’t be deleted. Your selection is still available.",
        ).assertExists()
        compose.onNodeWithTag("history_action_issue").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        compose.onNodeWithTag("history_list").assertExists()
        compose.onNodeWithTag("history_action_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @androidx.compose.runtime.Composable
    private fun content(
        scenario: ProgressHistoryScenario,
        onOpen: () -> Unit = {},
        onToggle: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
            ProgressHistoryContent(
                state = scenario.state,
                labelsFor = { entry ->
                    ProgressHistoryEntryLabels(
                        grade = if (entry.difficultyAverage == null) "?" else "6c+",
                        board = if (entry.id == 11L) "Kilter Original" else "Tension Board 2",
                        date = if (entry.id == 11L) "Today, 11:45" else "Yesterday, 18:10",
                    )
                },
                onChooseRetention = {},
                onOpenEntry = { onOpen() },
                onToggleSelection = { onToggle() },
                onRetry = onRetry,
            )
        }
    }
}
