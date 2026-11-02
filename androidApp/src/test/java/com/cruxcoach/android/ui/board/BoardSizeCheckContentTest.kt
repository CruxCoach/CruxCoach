package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cruxcoach.android.data.BoardSizeCheckPoint
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class BoardSizeCheckContentTest {
    @get:Rule val compose = createComposeRule()

    private val s10 = BoardSize(10, 1, "12 x 12 with kickboard", 0, 144, 0, 156, null)
    private val s7 = BoardSize(7, 1, "12 x 14", 0, 144, 0, 180, null)
    private val points = listOf(BoardSizeCheckPoint(1, 4, 4, 33), BoardSizeCheckPoint(2, 136, 152, 476))

    private fun ask(lit: Boolean = true) = BoardSizeCheckState(
        brand = BoardBrand.KILTER, currentSizeId = 10, sizes = listOf(s10, s7), size = s10,
        layoutId = 1, points = points, step = BoardSizeCheckStep.ASK, lit = lit,
    )

    private fun show(
        state: BoardSizeCheckState,
        connected: Boolean = true,
        ready: Boolean = true,
        otherBoard: Boolean = false,
        apply: Boolean = true,
        events: MutableList<String> = mutableListOf(),
    ) = compose.setContent {
        MaterialTheme {
            BoardSizeCheckContent(
                state = state, brand = BoardBrand.KILTER, connected = connected, ready = ready,
                otherBoardConnected = otherBoard, applyOnConfirm = apply,
                onConnect = { events += "connect" }, onLight = { events += "light" },
                onYes = { events += "yes" }, onNo = { events += "no" },
                onTest = { events += "test $it" }, onClose = { events += "close" },
            )
        }
    }

    @Test fun `without a board it offers the existing connect flow, not an answer`() {
        val events = mutableListOf<String>()
        show(ask(lit = false), connected = false, ready = false, events = events)
        compose.onNodeWithTag("board_size_check_yes").assertDoesNotExist()
        compose.onNodeWithTag("board_size_check_connect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("connect"), events) }
    }

    @Test fun `another brand connected asks for the right board`() {
        show(ask(lit = false), connected = true, ready = false, otherBoard = true)
        compose.onNodeWithTag("board_size_check_connect").assertExists()
        compose.onNodeWithTag("board_size_check_yes").assertDoesNotExist()
    }

    @Test fun `yes waits until the holds are lit`() {
        show(ask(lit = false))
        compose.onNodeWithTag("board_size_check_yes").assertIsNotEnabled()
    }

    @Test fun `lit holds can be answered yes or no`() {
        val events = mutableListOf<String>()
        show(ask(), events = events)
        compose.onNodeWithTag("board_size_check_yes").performScrollTo().performClick()
        compose.onNodeWithTag("board_size_check_no").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("yes", "no"), events) }
    }

    @Test fun `no offers the other sizes, each one testable`() {
        val events = mutableListOf<String>()
        show(ask().copy(step = BoardSizeCheckStep.PICK_OTHER, tried = setOf(10)), events = events)
        compose.onNodeWithTag("board_size_check_option_10").assertDoesNotExist()
        compose.onNodeWithTag("board_size_check_option_7").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("test 7"), events) }
    }

    @Test fun `a changed size reads saved in the climb view and selected in the picker`() {
        val confirmed = ask().copy(size = s7, step = BoardSizeCheckStep.CONFIRMED, confirmedSizeId = 7)
        show(confirmed, apply = true)
        compose.onNodeWithText("Gespeichert", substring = true).assertExists()
    }

    @Test fun `a changed size in the picker still needs Confirm`() {
        val confirmed = ask().copy(size = s7, step = BoardSizeCheckStep.CONFIRMED, confirmedSizeId = 7)
        show(confirmed, apply = false)
        compose.onNodeWithText("Bestätigen", substring = true).assertExists()
    }
}
