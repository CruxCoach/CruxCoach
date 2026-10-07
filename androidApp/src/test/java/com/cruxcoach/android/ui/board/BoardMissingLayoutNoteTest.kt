package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.domain.board.BoardHold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    }

    private fun noteShown() = compose.onAllNodesWithText(note).fetchSemanticsNodes().isNotEmpty()

    /**
     * Advances the paused clock frame by frame until [done] or [limitMs]. A
     * single fixed 1 s jump was sensitive to when the first frame and the
     * note's 800 ms delay landed; stepping frame by frame is not.
     */
    private fun advanceUntil(limitMs: Long, done: () -> Boolean): Boolean {
        var waited = 0L
        while (!done() && waited < limitMs) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            waited += FRAME_MS
        }
        return done()
    }

    @Test
    fun `holds without a layout explain where the positions come from`() {
        show(placements = emptyMap())

        assertTrue("note never appeared", advanceUntil(5_000) { noteShown() })
    }

    @Test
    fun `a loaded layout draws the holds without the note`() {
        show(placements = mapOf(1 to BoardPlacement(placementId = 1, holeId = 1, setId = 1, x = 10, y = 10)))

        // Well past the note's delay: it must still not be there.
        advanceUntil(2_000) { false }
        assertFalse(noteShown())
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
