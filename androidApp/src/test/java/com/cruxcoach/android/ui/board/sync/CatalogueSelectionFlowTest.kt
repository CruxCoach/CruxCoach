package com.cruxcoach.android.ui.board.sync

import android.app.Application
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogueSelectionFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun checkSelection(compact: Boolean, fontScale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
        val vm = mockk<BoardSyncViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(BoardSyncState(isSyncing = true, networkAvailable = true, wifiConnected = true))
        every { vm.modelState } returns MutableStateFlow(BoardModelSelectionState())
        every { vm.boardCounts } returns MutableStateFlow(emptyMap())
        every { vm.downloadBrands } returns MutableStateFlow(setOf(BoardBrand.KILTER))
        every { vm.activeBrand } returns MutableStateFlow(BoardBrand.KILTER)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                CruxCoachTheme(DarkModeSetting.DARK) {
                    Surface {
                        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            BoardSyncInlineCard(viewModel = vm, compact = compact)
                        }
                    }
                }
            }
        }
        if (compact) compose.onNodeWithTag("board_download_selection").assertIsEnabled().performClick()
        else compose.onNodeWithTag("board_status_moonboard").performScrollTo().performClick()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().assertIsOff()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().performClick().assertIsOn()
        verify(exactly = 0) { vm.saveDownloadSelection(any(), any()) }
        compose.onNodeWithText("Abbrechen").performClick()
        compose.onNodeWithTag("board_download_selection").performScrollTo().performClick()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().assertIsOff().performClick()
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory).mkdirs()
                java.io.File(directory, "catalogue-dialog-$fontScale.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        val confirmBounds = compose.onNodeWithTag("catalogue_confirm").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cancelBounds = compose.onNodeWithText("Abbrechen").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertFalse("Dialog actions must not overlap", confirmBounds.overlaps(cancelBounds))
        compose.onNodeWithTag("catalogue_confirm").performClick()
        verify(exactly = 1) { vm.saveDownloadSelection(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), false) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun `onboarding can edit during sync but only confirmation saves additions`() = checkSelection(true, 1f)

    @Test @Config(qualifiers = "de-w320dp-h640dp")
    fun `settings can edit during sync at large font without losing confirmation`() = checkSelection(false, 2f)
}
