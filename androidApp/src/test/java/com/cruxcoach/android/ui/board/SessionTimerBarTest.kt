package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The button for chance above the list: one climb in a sorted list, a new order in a list sorted at random. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w320dp-h640dp")
class SessionTimerBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `in a sorted list it opens one climb by chance`() {
        var picked = 0; var shuffled = 0
        compose.setContent { MaterialTheme { SessionTimerBar(shuffleList = false, onRandomClimb = { picked++ }, onReshuffle = { shuffled++ }) } }
        compose.onNodeWithText("Random").assertIsDisplayed()
        compose.onNodeWithTag("board_random_climb").performClick()
        assertEquals(1 to 0, picked to shuffled)
    }

    @Test fun `in a list sorted at random it deals the list again`() {
        var picked = 0; var shuffled = 0
        compose.setContent { MaterialTheme { SessionTimerBar(shuffleList = true, onRandomClimb = { picked++ }, onReshuffle = { shuffled++ }) } }
        compose.onNodeWithText("Shuffle again").assertIsDisplayed()
        compose.onNodeWithTag("board_reshuffle").performClick()
        compose.onNodeWithTag("board_reshuffle").performClick()
        assertEquals(0 to 2, picked to shuffled)
    }
}
