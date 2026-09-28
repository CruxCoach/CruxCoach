package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.domain.board.BoardHold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A community climb on a board without its catalogue must not look like a climb without holds. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardMissingLayoutNoteTest {
    @get:Rule val compose = createComposeRule()

    private val note = "Hold positions come with this board’s catalogue. " +
        "Tap “Load catalogue” in the climb list to see the holds."
    private val holds = listOf(BoardHold(placementId = 1, roleId = 12))

    private fun show(placements: Map<Int, BoardPlacement>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                KilterBoardVisualization(
                    holds = holds,
                    placements = placements,
                    boardSize = null,
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun `holds without a layout explain where the positions come from`() {
        show(placements = emptyMap())

        compose.onNodeWithText(note).assertExists()
    }

    @Test
    fun `a loaded layout draws the holds without the note`() {
        show(placements = mapOf(1 to BoardPlacement(placementId = 1, holeId = 1, setId = 1, x = 10, y = 10)))

        compose.onNodeWithText(note).assertDoesNotExist()
    }
}
