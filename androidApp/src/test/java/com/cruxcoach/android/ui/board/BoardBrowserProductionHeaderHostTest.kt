package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ble.ConnectionState
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
class BoardBrowserProductionHeaderHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `production host preserves board context connection and callbacks`() {
        var boardSelections = 0
        var connections = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserProductionHeaderHost(
                    state = BoardBrowserState(
                        hasBoardData = true,
                        filter = BrowserFilterState(angle = 40),
                        ble = BrowserBleState(ConnectionState.CONNECTED, "Kilter Board"),
                    ),
                    onSelectBoard = { boardSelections += 1 },
                    onConnectBoard = { connections += 1 },
                )
            }
        }

        compose.onNodeWithText("Kilter").assertExists()
        compose.onNodeWithText("Angle: 40°").assertExists()
        compose.onNodeWithText("Connected to Kilter Board").assertExists()
        compose.onNodeWithTag("browser_board_context")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("board_ble_button")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, boardSelections)
        assertEquals(1, connections)
    }

    @Test
    fun `first run does not expose a fake board context`() {
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserProductionHeaderHost(
                    state = BoardBrowserState(hasBoardData = false),
                    onSelectBoard = {},
                    onConnectBoard = {},
                )
            }
        }

        compose.onNodeWithTag("browser_board_context").assertDoesNotExist()
        compose.onNodeWithTag("board_ble_button").assertDoesNotExist()
    }
}
