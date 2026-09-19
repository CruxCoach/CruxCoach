package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
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
        compose.waitUntil(15_000) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(image).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isDialog()).assertExists()
        assertEquals(0, selections)
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText(label).performClick()
        assertEquals(1, selections)
    }
    @Test fun `previewing another variant keeps the confirmed board unchanged`() {
        val initial = MoonBoardVariant.MOONBOARD_2016
        val other = MoonBoardVariant.entries.first { it != initial }
        var confirmed: MoonBoardVariant? = null
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.DARK) {
                BoardSelectionDialog(
                    initialBrand = BoardBrand.MOONBOARD.wireValue, productSizes = emptyList(),
                    selectedKilterSizeId = 0, selectedMoonBoardVariant = initial,
                    onConfirmKilter = {}, onConfirmMoonBoard = { variant, _ -> confirmed = variant }, onDismiss = {},
                )
            }
        }
        val image = hasContentDescription("Boardbild vergrößern: ${other.displayName}")
        compose.waitUntil(15_000) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
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
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Schließen")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.onNodeWithText("Bestätigen").performClick()
        assertEquals(initial, confirmed)
    }

}
