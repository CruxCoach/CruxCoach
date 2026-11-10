package com.cruxcoach.android.ui.paintbox

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.settings.LedColorPalette
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
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

    @Test fun `photo mode replaces the tools and offers background, apply and cancel`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val state = MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            imageImport = PaintImageSession(bitmap, pickingBackground = true, ready = true),
            boardSize = BoardSize(10, 1, "Test board", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        every { vm.state } returns state
        every { vm.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        compose.setContent { MaterialTheme { PaintBoxScreen({}, {}, vm) } }
        compose.onNodeWithText("Photo").assertIsDisplayed()
        compose.onNodeWithTag("paintbox_brush").assertDoesNotExist()
        compose.onNodeWithTag("paintbox_menu").assertDoesNotExist()
        compose.onNodeWithTag("paintbox_send").assertDoesNotExist()
        compose.onNodeWithText("Tap the background color in the image.").assertIsDisplayed()
        compose.onNodeWithTag("paintbox_image_bg_off").assertTextContains("Keep background dark").assertIsOff().performClick()
        verify { vm.imageBackgroundOff(true) }
        compose.onNodeWithText("Background").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithContentDescription("Cancel").performClick()
        verify { vm.imageBackgroundTool(); vm.acceptImage(); vm.cancelImage() }
    }

    @Test fun `import canvas responds to one finger pan and two finger zoom`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val state = MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            imageImport = PaintImageSession(bitmap),
            boardSize = BoardSize(10, 1, "Test", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        every { vm.imageTransform(any()) } answers {
            state.value = state.value.copy(imageImport = state.value.imageImport!!.copy(transform = firstArg(), ready = false))
        }
        every { vm.imagePreview(any(), any()) } answers {
            if (state.value.imageImport === firstArg<PaintImageSession>()) {
                state.value = state.value.copy(imageImport = state.value.imageImport!!.copy(colors = secondArg(), ready = true))
            }
        }
        compose.setContent { MaterialTheme { PaintImageEditor(state.collectAsState().value, vm) } }
        compose.onNodeWithTag("paintbox_image_canvas").performTouchInput {
            down(center); moveTo(center + Offset(80f, 30f)); up()
        }
        compose.runOnIdle { assertTrue(state.value.imageImport!!.transform.center.x > .5f) }
        compose.onNodeWithTag("paintbox_image_canvas").performTouchInput {
            down(0, Offset(width * .3f, centerY)); down(1, Offset(width * .7f, centerY))
            moveTo(0, Offset(width * .1f, centerY)); moveTo(1, Offset(width * .9f, centerY))
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(state.value.imageImport!!.transform.zoom > 1f) }
    }

    @Test fun `photo preview maps the newest placement and hands it on`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        val state = MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            imageImport = PaintImageSession(bitmap),
            boardSize = BoardSize(10, 1, "Test", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        val mapped = java.util.concurrent.CopyOnWriteArrayList<PaintImageSession>()
        every { vm.imagePreview(any(), any()) } answers { mapped.add(firstArg()) }
        compose.setContent { MaterialTheme { PaintImageEditor(state.collectAsState().value, vm) } }
        // The photo moves on while mappings run: the last placement is mapped in the end, whatever was skipped.
        repeat(5) { step ->
            state.value = state.value.copy(imageImport = state.value.imageImport!!.copy(transform = PaintImageTransform(zoom = 1.1f + step / 10f)))
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) { mapped.lastOrNull() === state.value.imageImport }
        assertTrue(mapped.size <= 6)
    }

    private fun editor(connection: ConnectionState): PaintBoxViewModel {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            boardSize = BoardSize(10, 1, "Test board", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)),
        ))
        every { vm.connection } returns MutableStateFlow(connection)
        compose.setContent { MaterialTheme { PaintBoxScreen({}, {}, vm) } }
        return vm
    }

    @Test fun `editor presents tools and live off without sending on entry`() {
        val vm = editor(ConnectionState.CONNECTED)
        compose.onNodeWithContentDescription("Brush").assertIsDisplayed()
        compose.onNodeWithContentDescription("Eraser").performClick()
        compose.onNodeWithTag("paintbox_live").assertIsOff()
        compose.onNodeWithTag("paintbox_send").assertIsEnabled()
        verify { vm.eraser() }
        verify(exactly = 0) { vm.sendOnce() }
        verify(exactly = 0) { vm.toggleLive() }
        compose.onNodeWithTag("paintbox_live").performClick()
        verify { vm.toggleLive() }
    }

    @Test
    @Config(qualifiers = "+w320dp-h640dp")
    fun `tools with the pipette fit a narrow screen at the largest font`() {
        val vm = mockk<PaintBoxViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(PaintBoxState(
            document = PaintDocument(board = PaintBoard("kilter", 1, 10)), loading = false,
            boardSize = BoardSize(10, 1, "Test board", 0, 100, 0, 100, null),
            placements = mapOf(1 to BoardPlacement(1, 1, 1, 30, 30)), pipette = true, canUndo = true, canRedo = true,
        ))
        every { vm.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { PaintBoxScreen({}, {}, vm) }
            }
        }
        compose.onNodeWithTag("paintbox_pipette").assertIsSelected()
        compose.onNodeWithTag("paintbox_brush").assertIsNotSelected()
        compose.onNodeWithTag("paintbox_eraser").assertIsNotSelected()
        // Six targets, each with 48 dp to itself, inside the screen's 12 dp margins.
        val boxes = listOf(
            compose.onNodeWithTag("paintbox_brush"), compose.onNodeWithTag("paintbox_eraser"),
            compose.onNodeWithTag("paintbox_pipette"), compose.onNodeWithTag("paintbox_color"),
            compose.onNodeWithContentDescription("Undo"), compose.onNodeWithContentDescription("Redo"),
        ).map { it.assertIsDisplayed().getUnclippedBoundsInRoot() }
        val centres = boxes.map { (it.left + it.right) / 2 }
        val layout = boxes.joinToString { "${it.left}..${it.right}" }
        assertTrue(layout, centres.first() >= 12.dp + 23.5.dp && centres.last() <= 320.dp - 12.dp - 23.5.dp)
        // Neighbouring segments share their 1 dp border.
        centres.zipWithNext().forEach { (left, right) -> assertTrue(layout, right - left >= 47.dp) }
        // The three tools keep the full height and width of a touch target.
        boxes.take(3).forEach { assertTrue(layout, it.right - it.left >= 47.5.dp && it.bottom - it.top >= 40.dp) }
        compose.onNodeWithContentDescription("Pick up the color of a hold").performClick()
        verify { vm.pipette() }
    }

    @Test fun `color sheet offers the picture's unnamed colors first`() {
        var selected: Int? = null
        compose.setContent { MaterialTheme {
            LedColorPalette("Brush", 0xf1, { selected = it }, PaintPalette.forBoard(BoardBrand.KILTER).toSet(),
                listOf(0xf1, 0x91), "In this picture")
        } }
        compose.onNodeWithText("In this picture").assertIsDisplayed()
        compose.onNodeWithContentDescription("Picture color 1, similar to", substring = true).assertIsSelected()
        compose.onNodeWithContentDescription("Picture color 2, similar to", substring = true).performClick()
        compose.runOnIdle { assertEquals(0x91, selected) }
        compose.onNodeWithContentDescription("Green").performScrollTo().assertIsDisplayed()
    }

    @Test fun `without a connection the main action connects the board`() {
        editor(ConnectionState.DISCONNECTED)
        compose.onNodeWithTag("paintbox_connect").assertIsDisplayed().assertTextContains("Connect board")
        compose.onNodeWithTag("paintbox_live").assertDoesNotExist()
        compose.onNodeWithTag("paintbox_send").assertDoesNotExist()
    }

    @Test fun `rarer actions sit in the menu`() {
        editor(ConnectionState.CONNECTED)
        compose.onNodeWithTag("paintbox_menu").performClick()
        listOf("Use a photo", "Pictures", "Save", "Save as", "Clear picture").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
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
