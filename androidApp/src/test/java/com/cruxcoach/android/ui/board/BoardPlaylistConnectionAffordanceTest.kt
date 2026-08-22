package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardPlaylistLogMark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardPlaylistConnectionAffordanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var lampTaps = 0
    private var connectTaps = 0

    private fun show(boardReady: Boolean, boardConnecting: Boolean = false) = compose.setContent {
        BoardPlaylistTransport(
            state = BoardPlaylistUiState(
                available = true,
                boardReady = boardReady,
                boardConnecting = boardConnecting,
                rows = listOf(
                    BoardPlaylistRow(
                        entryId = "e1",
                        climbUuid = "climb",
                        angle = 40,
                        name = "Climb",
                        gradeLabel = null,
                        restAfterSeconds = 0,
                        isPast = false,
                        mark = BoardPlaylistLogMark.UNATTEMPTED,
                        duplicateIndex = 1,
                        duplicateCount = 1,
                    ),
                ),
            ),
            onPrevious = {},
            onNext = {},
            onLamp = { lampTaps++ },
            onConnect = { connectTaps++ },
            onAdd = {},
            onAddRandom = {},
        )
    }

    @Test
    fun `disconnected playlist offers connect and never draws a lamp`() {
        show(boardReady = false)

        compose.onNodeWithContentDescription(context.getString(R.string.cd_board_connect))
            .assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.board_playlist_lamp))
            .assertCountEquals(0)
        compose.onNodeWithTag("board_playlist_connect").performClick()

        assertEquals(1, connectTaps)
        assertEquals(0, lampTaps)
    }

    @Test
    fun `ready playlist reserves the lamp for projection`() {
        show(boardReady = true)

        compose.onNodeWithContentDescription(context.getString(R.string.board_playlist_lamp))
            .assertIsDisplayed()
        compose.onNodeWithTag("board_playlist_lamp").performClick()

        assertEquals(1, lampTaps)
        assertEquals(0, connectTaps)
    }

    @Test
    fun `connecting playlist announces progress without drawing a lamp`() {
        show(boardReady = false, boardConnecting = true)

        compose.onNodeWithContentDescription(
            context.getString(R.string.cd_board_dock_connecting),
        ).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(context.getString(R.string.board_playlist_lamp))
            .assertCountEquals(0)
    }
}
