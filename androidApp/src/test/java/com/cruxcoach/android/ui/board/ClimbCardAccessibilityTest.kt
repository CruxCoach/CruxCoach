package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.ClimbWithStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClimbCardAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `large catalogue count stays clear of moves and queue action stays independent`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val climb = ClimbWithStats(
            uuid = "fixture", layoutId = 1, setterUsername = "A setter",
            name = "Moon girl", frames = "", framesCount = 1,
            difficultyAverage = 15.0, qualityAverage = 5.0,
            ascensionistCount = 56569, storedMoveCount = 8,
        )
        var opened = 0
        var queued = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Box(Modifier.width(288.dp)) {
                        ClimbCard(climb, onClimbClick = { opened++ }, onAddToBoardPlaylist = { queued++ })
                    }
                }
            }
        }
        val moves = compose.onNodeWithText(app.getString(R.string.board_climb_moves, 8), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val sends = compose.onNodeWithText(app.getString(R.string.board_climb_sends_count, 56569), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("board_climb_card").fetchSemanticsNode().boundsInRoot
        assertTrue("Moves and catalogue sends must not overlap", !moves.overlaps(sends))
        assertTrue("The complete catalogue count must stay within the card", sends.left >= card.left && sends.right <= card.right)
        compose.onNodeWithTag("board_climb_add_to_playlist").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, queued); assertEquals(0, opened) }
        compose.onNodeWithText(climb.name, useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
