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
class BoardBrowserErrorContentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `initial failure names the problem and exposes a 48 dp retry`() {
        var retries = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserErrorContent(onRetry = { retries += 1 })
            }
        }

        compose.onNodeWithText("Climbs couldn’t be loaded").assertExists()
        compose.onNodeWithText("Your filters are unchanged. Try loading the results again.")
            .assertExists()
        compose.onNodeWithTag("board_browser_error_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `pagination failure has an inline named retry`() {
        var retries = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                BoardBrowserLoadMoreError(onRetry = { retries += 1 })
            }
        }

        compose.onNodeWithText("More climbs couldn’t be loaded.").assertExists()
        compose.onNodeWithTag("board_browser_load_more_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }
}
