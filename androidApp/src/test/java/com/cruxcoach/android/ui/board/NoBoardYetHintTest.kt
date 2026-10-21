package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An empty scan explains itself after a while instead of spinning silently. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NoBoardYetHintTest {
    @get:Rule val compose = createComposeRule()

    private val noBoards = mutableStateOf(true)

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { NoBoardYetHint(noBoardsFound = noBoards.value) }
        }
    }

    @Test
    fun `the hint appears only after the scan stayed empty for the delay`() {
        show()
        compose.mainClock.advanceTimeBy(NO_BOARD_HINT_DELAY_MS - 1_000)
        compose.onNodeWithTag("ble_no_board_yet_hint").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("ble_no_board_yet_hint").assertExists()
    }

    @Test
    fun `a board showing up removes the hint`() {
        show()
        compose.mainClock.advanceTimeBy(NO_BOARD_HINT_DELAY_MS + 1_000)
        compose.onNodeWithTag("ble_no_board_yet_hint").assertExists()

        // Applied as a snapshot so the paused clock's next frame recomposes it.
        Snapshot.withMutableSnapshot { noBoards.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("ble_no_board_yet_hint").assertDoesNotExist()
    }

    @Test
    fun `a board found early never shows the hint`() {
        noBoards.value = false
        show()
        compose.mainClock.advanceTimeBy(NO_BOARD_HINT_DELAY_MS * 2)
        compose.onNodeWithTag("ble_no_board_yet_hint").assertDoesNotExist()
    }
}
