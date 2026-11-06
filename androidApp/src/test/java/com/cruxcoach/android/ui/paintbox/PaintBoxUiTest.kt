package com.cruxcoach.android.ui.paintbox

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.settings.LedColorPalette
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class PaintBoxUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `editor presents tools and a paused lamp without sending on entry`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            boardSize = BoardSize(10, 1, "Test board", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        every { vm.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        compose.setContent { MaterialTheme { PaintBoxScreen({}, {}, vm) } }
        compose.onNodeWithText("Brush").assertIsDisplayed()
        compose.onNodeWithText("Eraser").performClick()
        compose.onNodeWithContentDescription("Live painting off — send and enable").assertIsDisplayed()
        verify { vm.eraser() }
        verify(exactly = 0) { vm.sendOnce() }
        verify(exactly = 0) { vm.toggleLive() }
    }

    @Test fun `settings color picker limits MoonBoard choices and keeps selection semantics`() {
        var selected: Int? = null
        compose.setContent { MaterialTheme {
            LedColorPalette("Brush", 28, { selected = it }, PaintPalette.moon.toSet())
        } }
        compose.onNodeWithContentDescription("Green").assertIsSelected()
        compose.onNodeWithContentDescription("White").assertDoesNotExist()
        compose.onNodeWithContentDescription("Red").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(224, selected) }
    }
    @Test fun `drag paints crossed targets as one stroke`() {
        val painted = mutableSetOf<Int>()
        var starts = 0
        var ends = 0
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("canvas").pointerInput(Unit) {
                detectPaintGestures(
                    hit = { (it.x / size.width * 3).toInt().coerceIn(0, 2) },
                    begin = { starts++ }, paint = { painted.addAll(it) }, end = { ends++ }, transform = { _, _ -> },
                )
            })
        }
        compose.onNodeWithTag("canvas").performTouchInput {
            down(Offset(1f, centerY)); moveTo(Offset(width - 1f, centerY)); up()
        }
        compose.runOnIdle {
            assertEquals(setOf(0, 1, 2), painted)
            assertEquals(1, starts)
            assertEquals(1, ends)
        }
    }
    @Test fun `two finger gesture transforms without painting`() {
        val painted = mutableSetOf<Int>()
        var transformed = false
        compose.setContent {
            Box(Modifier.size(120.dp).testTag("canvas").pointerInput(Unit) {
                detectPaintGestures(hit = { 1 }, begin = {}, paint = { painted.addAll(it) }, end = {},
                    transform = { _, _ -> transformed = true })
            })
        }
        compose.onNodeWithTag("canvas").performTouchInput {
            down(0, Offset(width * .3f, centerY))
            down(1, Offset(width * .7f, centerY))
            moveTo(0, Offset(width * .2f, centerY))
            moveTo(1, Offset(width * .8f, centerY))
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(transformed); assertTrue(painted.isEmpty()) }
    }
}
