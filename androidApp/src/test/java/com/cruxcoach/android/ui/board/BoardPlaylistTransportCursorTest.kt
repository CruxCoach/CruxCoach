package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.data.BoardPlaylistLogMark
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Arrows browse. The lamp writes. Nothing else does either.
 *
 * This used to be one action: stepping the list projected the next occurrence,
 * so looking through what the group had queued changed what everybody at the
 * wall was climbing. Splitting them is only worth anything if the arrows stay
 * split — hence a test that watches the lamp callback while the arrows are
 * pressed, rather than one that watches the arrows.
 *
 * The same property is what makes lanes safe: on a four-lane board a stray
 * projection is not merely the wrong climb, it is a lane taken from somebody.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardPlaylistTransportCursorTest {

    @get:Rule
    val compose = createComposeRule()

    @Suppress("unused")
    private val context: Application = ApplicationProvider.getApplicationContext()

    private var previousTaps = 0
    private var nextTaps = 0
    private var lampTaps = 0
    private var connectTaps = 0

    private fun row(entryId: String) = BoardPlaylistRow(
        entryId = entryId,
        climbUuid = "climb-$entryId",
        angle = 40,
        name = "Climb $entryId",
        gradeLabel = null,
        restAfterSeconds = 0,
        isPast = false,
        mark = BoardPlaylistLogMark.UNATTEMPTED,
        duplicateIndex = 1,
        duplicateCount = 1,
    )

    private fun show(selectedIndex: Int) = compose.setContent {
        BoardPlaylistTransport(
            state = BoardPlaylistUiState(
                available = true,
                boardReady = true,
                rows = listOf(row("e1"), row("e2"), row("e3")),
                currentIndex = 0,
                selectedIndex = selectedIndex,
                selectedEntryId = "e${selectedIndex + 1}",
            ),
            onPrevious = { previousTaps++ },
            onNext = { nextTaps++ },
            onLamp = { lampTaps++ },
            onConnect = { connectTaps++ },
            onAdd = {},
            onAddRandom = {},
        )
    }

    @Test
    fun `stepping the list never reaches the board`() {
        show(selectedIndex = 1)

        compose.onNodeWithTag("board_playlist_next").performClick()
        compose.onNodeWithTag("board_playlist_prev").performClick()

        assertEquals(1, nextTaps)
        assertEquals(1, previousTaps)
        assertEquals(0, lampTaps)
        assertEquals(0, connectTaps)
    }

    @Test
    fun `the lamp is the one control that asks for a write`() {
        show(selectedIndex = 1)

        compose.onNodeWithTag("board_playlist_lamp").performClick()

        assertEquals(1, lampTaps)
        assertEquals(0, nextTaps)
        assertEquals(0, previousTaps)
    }

    @Test
    fun `the arrows follow the local cursor, not the confirmed current`() {
        // Cursor on the first row: there is nothing behind it to step to, even
        // though the board's confirmed current is also the first row. The two
        // are separate facts and the arrows belong to the first one.
        show(selectedIndex = 0)

        compose.onNodeWithTag("board_playlist_prev").performClick()

        assertEquals(0, previousTaps)
        assertEquals(0, lampTaps)
    }
}
