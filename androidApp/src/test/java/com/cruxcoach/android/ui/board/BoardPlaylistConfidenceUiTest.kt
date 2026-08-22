package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.boardcell.BoardProjectionConfidence
import com.cruxcoach.android.data.BoardPlaylistLogMark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status line, which is the only place the five-state confidence model is
 * visible to somebody standing at the wall.
 *
 * "Sent" and "the controller says it is up there" are different claims, and
 * only Quantum can make the second one. Collapsing them — which is what the
 * screen did — told everybody on every board that the climb was on the wall on
 * the strength of a write having gone out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardPlaylistConfidenceUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun row() = BoardPlaylistRow(
        entryId = "e1", climbUuid = "climb-a", angle = 40, name = "Test climb",
        gradeLabel = "6A", restAfterSeconds = 0, isCurrent = true, isPast = false,
        mark = BoardPlaylistLogMark.UNATTEMPTED, duplicateIndex = 1, duplicateCount = 1,
    )

    private fun show(state: BoardPlaylistUiState) = compose.setContent {
        BoardPlaylistStatus(state = state)
    }

    private fun state(
        confidence: BoardProjectionConfidence,
        selectionOnBoard: Boolean = false,
        confirmedClimbName: String? = null,
        pending: BoardPlaylistPendingProjection? = null,
    ) = BoardPlaylistUiState(
        available = true,
        rows = listOf(row()),
        currentIndex = 0,
        selectionOnBoard = selectionOnBoard,
        projectionConfidence = confidence,
        boardClimbUnknown = confidence == BoardProjectionConfidence.UNKNOWN,
        confirmedClimbName = confirmedClimbName,
        pendingProjection = pending,
    )

    @Test
    fun `a write on its way says so instead of claiming the wall`() {
        show(state(BoardProjectionConfidence.PENDING))

        compose.onNodeWithText(context.getString(R.string.board_playlist_sending))
            .assertIsDisplayed()
    }

    @Test
    fun `a completed transport says sent, not confirmed`() {
        show(state(BoardProjectionConfidence.TRANSPORTED, selectionOnBoard = true))

        compose.onNodeWithText(context.getString(R.string.board_playlist_on_board))
            .assertIsDisplayed()
    }

    @Test
    fun `a controller that names the climb is quoted as the source`() {
        show(state(BoardProjectionConfidence.CONTROLLER_CONFIRMED, selectionOnBoard = true))

        compose.onNodeWithText(context.getString(R.string.board_playlist_on_board_confirmed))
            .assertIsDisplayed()
    }

    @Test
    fun `an unknown wall is never reported as somebody else's climb`() {
        show(state(BoardProjectionConfidence.UNKNOWN))

        compose.onNodeWithText(context.getString(R.string.board_playlist_board_unknown))
            .assertIsDisplayed()
    }

    @Test
    fun `a write that did not land keeps its own reason`() {
        show(
            state(
                BoardProjectionConfidence.FAILED,
                pending = BoardPlaylistPendingProjection(
                    "e1", "climb-a", 40,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
                ),
            ),
        )

        compose.onNodeWithText(context.getString(R.string.board_playlist_send_write_failed))
            .assertIsDisplayed()
    }

    /** The board is showing something, and it is not the selected occurrence. */
    @Test
    fun `a board holding another climb names it`() {
        show(
            state(
                BoardProjectionConfidence.TRANSPORTED,
                selectionOnBoard = false,
                confirmedClimbName = "Other climb",
            ),
        )

        compose.onNodeWithText(
            context.getString(R.string.board_playlist_board_shows, "Other climb"),
        ).assertIsDisplayed()
    }
}
