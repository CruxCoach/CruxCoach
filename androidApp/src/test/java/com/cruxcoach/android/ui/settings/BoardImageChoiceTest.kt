package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.board.BoardPreviewImage
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardImageChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `image opens fullscreen without selecting and text selects independently`() {
        var selections = 0
        val label = "MoonBoard 2016"
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.DARK) {
                Surface {
                    BoardImageChoiceRow(label, false, { selections++ }, BoardBrand.MOONBOARD,
                        0L, MoonBoardVariant.MOONBOARD_2016.layoutId)
                }
            }
        }
        val image = hasContentDescription("Boardbild vergrößern: $label")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(image).performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isDialog()).assertExists()
        assertEquals(0, selections)
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText(label).performClick()
        assertEquals(1, selections)
    }
    @Test fun `a tap that is down when the image arrives still opens it`() {
        // The picture decodes in the background and may arrive at any moment of a tap, also between
        // finger down and finger up: then the click of the test above opened nothing (CI publish
        // run 43232847474).
        var loaded by mutableStateOf<List<ImageBitmap>?>(null)
        var taps = 0
        compose.setContent {
            BoardPreviewImage(loaded, Modifier.size(104.dp, 144.dp).testTag("preview"), onClick = { taps++ })
        }
        compose.onNodeWithTag("preview").performTouchInput { down(center) }
        loaded = listOf(ImageBitmap(8, 8))
        compose.onNodeWithTag("preview").performTouchInput { up() }
        assertEquals(1, taps)
    }
    @Test fun `previewing another variant keeps the confirmed board unchanged`() {
        val initial = MoonBoardVariant.MOONBOARD_2016
        val other = MoonBoardVariant.entries.first { it != initial }
        var confirmed: MoonBoardVariant? = null
        var searches = 0
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.DARK) {
                BoardSelectionDialog(
                    initialBrand = BoardBrand.MOONBOARD.wireValue, productSizes = emptyList(),
                    selectedKilterSizeId = 0, selectedMoonBoardVariant = initial,
                    onConfirmKilter = {}, onConfirmMoonBoard = { variant, _ -> confirmed = variant }, onDismiss = {},
                    onFindViaBluetooth = { searches++ },
                )
            }
        }
        compose.onNodeWithTag("setup_connect").performScrollTo().performClick()
        assertEquals(1, searches)
        assertNull(confirmed)
        val image = hasContentDescription("Boardbild vergrößern: ${other.displayName}")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(image).performScrollTo()
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory).mkdirs()
                java.io.File(directory, "board-picker-inline.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        compose.onNode(image).performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("Schließen")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNodeWithText("Bestätigen").performClick()
        assertEquals(initial, confirmed)
    }

    private companion object {
        /** Generous like the other Compose tests: the zoomed board image decodes slowly on a busy CI runner (publish run 39161597342). */
        const val WAIT_MS = 60_000L
    }
}
