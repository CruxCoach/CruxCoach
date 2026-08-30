package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BrowserBoardContext
import com.cruxcoach.domain.board.BrowserConnection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardBrowserHeaderSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `physical board context and connection are named actionable and 48 dp`() {
        compose.setContent { header() }

        compose.onNodeWithText("Kilter · Original 12x12").assertExists()
        compose.onNodeWithText("Angle: 40°").assertExists()
        compose.onNodeWithText("Disconnected").assertExists()
        listOf("browser_board_context", "browser_connection", "browser_filter_candidate")
            .forEach { tag ->
                compose.onNodeWithTag(tag)
                    .assertHasClickAction()
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
            }
    }

    @Test
    fun `search is directly editable and exposes a named clear action`() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            header(query = query, onQueryChanged = { query = it })
        }

        compose.onNodeWithTag("browser_search_field_candidate")
            .assert(hasSetTextAction())
            .performTextInput("quiet")
        compose.onNodeWithContentDescription("Clear search").assertHasClickAction()
    }

    @Test
    fun `filter and connected states have text in addition to color`() {
        compose.setContent {
            header(
                activeFilterCount = 2,
                connection = BrowserConnection(
                    BoardConnectionState.CONNECTED,
                    "Kilter Board",
                ),
            )
        }

        compose.onNodeWithContentDescription("Filters, 2 active").assertHasClickAction()
        compose.onNodeWithText("Connected to Kilter Board").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun header(
        query: String = "",
        activeFilterCount: Int = 0,
        connection: BrowserConnection = BrowserConnection(BoardConnectionState.DISCONNECTED),
        onQueryChanged: (String) -> Unit = {},
    ) {
        CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
            BoardBrowserHeader(
                board = BrowserBoardContext(
                    brand = BoardBrand.KILTER,
                    layoutId = 1,
                    productName = "Original 12x12",
                    angle = 40,
                ),
                connection = connection,
                query = query,
                activeFilterCount = activeFilterCount,
                onSelectBoard = {},
                onConnectBoard = {},
                onQueryChanged = onQueryChanged,
                onOpenFilters = {},
            )
        }
    }
}
