package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.boardcell.BoardProjectionConfidence
import com.cruxcoach.android.data.BoardPlaylistLogMark
import org.junit.Assert.assertEquals
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
        gradeLabel = "6A", restAfterSeconds = 0, isPast = false,
        mark = BoardPlaylistLogMark.UNATTEMPTED, duplicateIndex = 1, duplicateCount = 1,
    )

    private val retried = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun show(state: BoardPlaylistUiState) = compose.setContent {
        BoardPlaylistStatus(
            state = state,
            onRetryFailed = { retried += it },
            onRemoveFailed = { removed += it },
        )
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

    /**
     * The two facts a failed send leaves behind, and the contract says both
     * out loud: the occurrence that did not get there, and the climb the wall
     * is still showing instead.
     */
    @Test
    fun `a write that did not land names it and says what the board still shows`() {
        show(failedState())

        compose.onNodeWithText(
            context.getString(R.string.board_playlist_send_write_failed, "Test climb"),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.board_playlist_board_still_shows, "Black Pearl"),
        ).assertIsDisplayed()
    }

    /** Recovery sits next to the statement, and retry keeps the same identity. */
    @Test
    fun `a failed send offers retry and remove for that occurrence`() {
        show(failedState())

        compose.onNodeWithTag("board_playlist_retry").performClick()
        compose.onNodeWithTag("board_playlist_remove_failed").performClick()

        assertEquals(listOf("e1"), retried)
        assertEquals(listOf("e1"), removed)
    }

    /** A red row is not a message: the state is written out either way. */
    @Test
    fun `the failure is stated in words, not only in colour`() {
        show(failedState())

        compose.onNodeWithTag("board_playlist_failure").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.board_playlist_send_retry))
            .assertIsDisplayed()
    }

    private fun failedState() = state(
        BoardProjectionConfidence.TRANSPORTED,
        selectionOnBoard = true,
        pending = BoardPlaylistPendingProjection(
            "e1", "climb-a", 40,
            BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
        ),
    ).copy(failedClimbName = "Test climb", boardClimbName = "Black Pearl")

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
