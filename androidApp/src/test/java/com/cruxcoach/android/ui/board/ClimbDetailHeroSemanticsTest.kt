package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
@Config(application = Application::class, qualifiers = "w360dp-h720dp")
class ClimbDetailHeroSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `disconnected hero offers one 48dp connect action before logging`() {
        var connects = 0
        compose.setContent { scenario(ClimbDetailScenarios.Disconnected, onConnect = { connects += 1 }) }

        compose.onNodeWithContentDescription("Board preview for Quiet Riot").assertExists()
        compose.onNodeWithText("Connect board")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("detail_delivery").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("detail_log_attempt").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("detail_log_send").assertHeightIsAtLeast(48.dp)
        assertEquals(1, connects)
    }

    @Test
    fun `connected fixture changes delivery action without changing identity`() {
        var deliveries = 0
        compose.setContent { scenario(ClimbDetailScenarios.Connected, onDeliver = { deliveries += 1 }) }

        compose.onNodeWithText("Quiet Riot").assertExists()
        compose.onNodeWithText("6c+ · 40° · Kilter Original 12×12").assertExists()
        compose.onNodeWithText("Light climb on board")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, deliveries)
    }

    @androidx.compose.runtime.Composable
    private fun scenario(
        value: ClimbDetailScenario,
        onConnect: () -> Unit = {},
        onDeliver: () -> Unit = {},
    ) {
        CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
            ClimbDetailScenarioContent(
                scenario = value,
                onConnect = onConnect,
                onDeliver = onDeliver,
            )
        }
    }
}
