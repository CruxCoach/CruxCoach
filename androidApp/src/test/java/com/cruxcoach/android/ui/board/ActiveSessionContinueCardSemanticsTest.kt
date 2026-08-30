package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActiveSessionContinueCardSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `active session is one actionable surface with text metrics`() {
        var continues = 0
        compose.setContent { card(ActiveSessionScenarios.Active, onContinue = { continues += 1 }) }

        compose.onNodeWithText("Active session").assertExists()
        compose.onNodeWithText("Quiet Riot").assertExists()
        compose.onNodeWithText("40°").assertExists()
        compose.onNodeWithText("28:00 active · 3 sends · 7 attempts").assertExists()
        compose.onNodeWithTag("session_continue")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, continues)
    }

    @Test
    fun `resting phase names its fixed countdown`() {
        compose.setContent { card(ActiveSessionScenarios.Resting) }
        compose.onNodeWithText("Rest").assertExists()
        compose.onNodeWithText("1:15 remaining").assertExists()
    }

    @Test
    fun `manual pause does not expose a rest countdown`() {
        compose.setContent { card(ActiveSessionScenarios.Paused) }
        compose.onNodeWithText("Session paused").assertExists()
        compose.onNodeWithText("Disconnected").assertExists()
        compose.onNodeWithText("1:15 remaining").assertDoesNotExist()
    }

    @Test
    fun `missing current climb degrades to explicit copy`() {
        compose.setContent { card(ActiveSessionScenarios.ActiveNoClimb) }

        compose.onNodeWithText("No current climb").assertExists()
    }

    @Test
    fun `duration formatting is fixed and never reads a clock`() {
        assertEquals("0:00", formatPortableDuration(-1))
        assertEquals("1:15", formatPortableDuration(75))
        assertEquals("1:01:01", formatPortableDuration(3_661))
    }

    @androidx.compose.runtime.Composable
    private fun card(
        scenario: ActiveSessionScenario,
        onContinue: () -> Unit = {},
    ) {
        CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
            ActiveSessionContinueCard(state = scenario.state, onContinue = onContinue)
        }
    }
}
