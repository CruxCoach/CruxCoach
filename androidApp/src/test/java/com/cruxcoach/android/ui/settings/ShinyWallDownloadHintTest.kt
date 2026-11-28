package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ShinyWallVariant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shiny Wall is downloaded only once chosen. The Aurora boards said so in the picker ("we'll download the
 * catalogue"); the Shiny Wall section said nothing before the download dialog appeared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShinyWallDownloadHintTest {
    @get:Rule val compose = createComposeRule()

    private val hint: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.board_selection_shinywall_download_hint)

    private fun show(loaded: Set<String>) {
        compose.setContent { MaterialTheme {
            BoardSelectionDialog(
                initialBrand = BoardBrand.SHINYWALL.wireValue, productSizes = emptyList(),
                selectedKilterSizeId = 0, selectedMoonBoardVariant = null,
                selectedAuroraLayoutId = ShinyWallVariant.ST1.layoutId.toInt(),
                loadedAuroraBrands = loaded,
                onConfirmKilter = {}, onConfirmMoonBoard = { _, _ -> },
                onDismiss = {},
            )
        } }
    }

    @Test fun `a Shiny Wall without its catalogue says it will be downloaded`() {
        show(loaded = setOf(BoardBrand.KILTER.wireValue))
        compose.onNodeWithText(hint).assertExists()
    }

    @Test fun `a Shiny Wall with its catalogue shows no download hint`() {
        show(loaded = setOf(BoardBrand.KILTER.wireValue, BoardBrand.SHINYWALL.wireValue))
        compose.onNodeWithText(hint).assertDoesNotExist()
    }
}
