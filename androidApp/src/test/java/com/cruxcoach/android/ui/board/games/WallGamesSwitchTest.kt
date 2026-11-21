package com.cruxcoach.android.ui.board.games

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.ui.navigation.BrowserMainDrawer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With [WallGamesFeature] off, the main menu does not offer the wall games; its other entries stay. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class WallGamesSwitchTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the main menu has the wall games only while the switch is on`() {
        var on by mutableStateOf(true)
        compose.setContent { MaterialTheme { BrowserMainDrawer(sprayWalls = false, wallGames = on) {} } }
        compose.onNodeWithTag("menu_wall_games").assertExists()

        on = false
        compose.waitForIdle()
        compose.onNodeWithTag("menu_wall_games").assertDoesNotExist()
        compose.onNodeWithTag("menu_board").assertExists()
        compose.onNodeWithTag("menu_board_map").assertExists()
        compose.onNodeWithTag("menu_today").assertExists()
    }
}
