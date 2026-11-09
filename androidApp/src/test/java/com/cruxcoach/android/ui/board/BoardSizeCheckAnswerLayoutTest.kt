package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.toSize
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "Yes" and "No" side by side while both labels fit on one line, stacked when they do not. Real text widths need native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardSizeCheckAnswerLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val s10 = BoardSize(10, 1, "12 x 12 with kickboard", 0, 144, 0, 156, null)
    private val points = listOf(BoardSizeCheckPoint(1, 4, 4, 33), BoardSizeCheckPoint(2, 136, 152, 476))
    private val lit = BoardSizeCheckState(
        brand = BoardBrand.KILTER, currentSizeId = 10, sizes = listOf(s10),
        plans = mapOf(10 to BoardSizeCheckSizePlan(s10, 1, points, emptySet())),
        size = s10, layoutId = 1, points = points, step = BoardSizeCheckStep.ASK, lit = true,
    )

    private fun answers(): Pair<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect> {
        compose.setContent {
            MaterialTheme {
                BoardSizeCheckContent(
                    state = lit, brand = BoardBrand.KILTER, connected = true, ready = true,
                    otherBoardConnected = false, applyOnConfirm = true,
                    onConnect = {}, onLight = {}, onYes = {}, onNo = {}, onTest = {}, onClose = {},
                )
            }
        }
        return bounds("board_size_check_yes") to bounds("board_size_check_no")
    }

    /** Where the button is laid out, also below the fold: clipped bounds would be empty there. */
    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return androidx.compose.ui.geometry.Rect(node.positionInRoot, node.size.toSize())
    }

    @Test fun `side by side when they fit`() {
        val (yes, no) = answers()
        assertEquals(yes.top, no.top, 1f)
        assertTrue("$yes / $no", no.left > yes.right)
    }

    @Test
    @Config(qualifiers = "de-w320dp-h640dp")
    fun `stacked at full width when a label does not fit`() {
        RuntimeEnvironment.setFontScale(2f)
        val (yes, no) = answers()
        assertTrue("$yes / $no", no.top >= yes.bottom)
        assertEquals(yes.width, no.width, 1f)
    }
}
