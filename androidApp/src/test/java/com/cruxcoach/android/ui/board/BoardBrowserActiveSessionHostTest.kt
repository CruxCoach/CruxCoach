package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.RestTimerState
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardBrowserActiveSessionHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `active queue is projected without changing its uuid or angle`() {
        var continues = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserActiveSessionHost(
                    sessionState = MutableStateFlow(activeSession()),
                    restTimerState = MutableStateFlow(RestTimerState()),
                    currentQueueClimb = QueueItem("exact-uuid", 40),
                    currentClimbName = MutableStateFlow("Quiet Riot"),
                    connectionState = ConnectionState.CONNECTED,
                    onContinue = { continues += 1 },
                )
            }
        }

        compose.onNodeWithText("Quiet Riot").assertExists()
        compose.onNodeWithText("40°").assertExists()
        compose.onNodeWithTag("session_continue")
            .assertHasClickAction()
            .performClick()
        assertEquals(1, continues)
    }

    @Test
    fun `inactive board session does not create a competing surface`() {
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserActiveSessionHost(
                    sessionState = MutableStateFlow(BoardSessionState()),
                    restTimerState = MutableStateFlow(RestTimerState()),
                    currentQueueClimb = null,
                    currentClimbName = MutableStateFlow(null),
                    connectionState = ConnectionState.DISCONNECTED,
                    onContinue = {},
                )
            }
        }

        compose.onNodeWithTag("session_continue").assertDoesNotExist()
    }
}

private fun activeSession() = BoardSessionState(
    isActive = true,
    elapsedSeconds = 1_800,
    pauseSeconds = 120,
    ascentCount = 3,
    bidCount = 7,
    startedAt = "2026-08-30T11:30:00Z",
)
