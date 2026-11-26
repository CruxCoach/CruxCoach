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

/** The banner above a randomly sorted list names the order and deals a new one per tap. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w320dp-h640dp")
class RandomOrderBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `it says the order is random and each tap asks for a new one`() {
        var asked = 0
        compose.setContent { MaterialTheme { RandomOrderBar(onReshuffle = { asked++ }) } }
        compose.onNodeWithText("Random order").assertIsDisplayed()
        compose.onNodeWithText("Shuffle again").assertIsDisplayed()
        compose.onNodeWithTag("board_reshuffle").performClick()
        compose.onNodeWithTag("board_reshuffle").performClick()
        assertEquals(2, asked)
    }
}
