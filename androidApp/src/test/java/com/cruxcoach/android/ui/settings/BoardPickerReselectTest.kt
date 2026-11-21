package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The board list resets the choice below it (size, version) with every entry, also with the one
 * that is already chosen: picking "MoonBoard" again put the version back to 2016, and on a Kilter
 * board it cleared the size, so "Confirm" went grey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardPickerReselectTest {
    @get:Rule val compose = createComposeRule()
    private val confirmed = mutableListOf<MoonBoardVariant>()

    private fun show(variant: MoonBoardVariant) {
        compose.setContent { MaterialTheme {
            BoardSelectionDialog(
                initialBrand = BoardBrand.MOONBOARD.wireValue, productSizes = emptyList(),
                selectedKilterSizeId = 0, selectedMoonBoardVariant = variant,
                onConfirmKilter = {}, onConfirmMoonBoard = { chosen, _ -> confirmed += chosen },
                onDismiss = {},
            )
        } }
    }

    /** Opens the board list and taps its entry [label]; the collapsed field shows the same text. */
    private fun chooseBoard(current: String, label: String) {
        compose.onNodeWithText(current).performClick()
        compose.onNode(hasText(label) and hasAnyAncestor(isPopup())).performClick()
    }

    @Test fun `choosing the chosen board again keeps the version below it`() {
        show(MoonBoardVariant.MINI_2020)

        chooseBoard(current = "MoonBoard", label = "MoonBoard")
        compose.onNodeWithText("Confirm").performClick()

        assertEquals(listOf(MoonBoardVariant.MINI_2020), confirmed)
    }
}
