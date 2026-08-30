package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.data.BoardConfigurationMismatch
import com.cruxcoach.android.data.BoardPickerPrefillSource
import com.cruxcoach.android.data.BoardSendIdentity
import com.cruxcoach.android.data.resolveBoardConfigurationMismatch
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardHubComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `all boards are visible and viewing another card does not change active marker`() {
        val active = BoardBrand.QUANTUM
        var viewed by mutableStateOf(active)
        val cards = boardSettingsCards(active)
        compose.setContent {
            MaterialTheme {
                Row {
                    cards.forEach { card ->
                        BoardHubCard(
                            card = card,
                            selectedForSettings = viewed == card.brand,
                            onSelect = { viewed = card.brand },
                        )
                    }
                }
            }
        }

        cards.forEach { compose.onNodeWithTag("settings_board_card_${it.brand.wireValue}").assertExists() }
        compose.onNodeWithText("Active").assertExists()
        compose.onNodeWithTag("settings_board_card_quantum").assertIsSelected()

        compose.onNodeWithTag("settings_board_card_kilter").performClick()

        assertEquals(BoardBrand.KILTER, viewed)
        assertEquals(active, cards.single { it.isActive }.brand)
        compose.onNodeWithTag("settings_board_card_kilter").assertIsSelected()
        compose.onNodeWithText("Active").assertExists()
    }

    @Test
    fun `mismatch action passes typed controller prefill to picker host`() {
        val mismatch = resolveBoardConfigurationMismatch(
            BoardSendIdentity(
                climbBrand = BoardBrand.MOONBOARD,
                climbLayoutId = 2L,
                activeBrand = BoardBrand.MOONBOARD,
                activeLayoutId = 2L,
                activeProductSizeId = null,
                connectedBrand = BoardBrand.KILTER,
            )
        )!!
        var opened: BoardConfigurationMismatch? = null
        compose.setContent {
            MaterialTheme {
                BoardMismatchFixAction(mismatch, onOpenPicker = { opened = it })
            }
        }

        compose.onNodeWithTag("board_mismatch_fix_action").performClick()

        assertEquals(mismatch, opened)
        assertEquals(BoardBrand.KILTER, opened?.prefill?.brand)
        assertEquals(BoardPickerPrefillSource.CONNECTED_CONTROLLER, opened?.prefill?.source)
    }

    @Test
    fun `cancelling picker does not invoke a persistence callback`() {
        var dismissed = false
        var persisted = false
        compose.setContent {
            MaterialTheme {
                BoardSelectionDialog(
                    initialBrand = BoardBrand.MOONBOARD.wireValue,
                    productSizes = emptyList(),
                    selectedKilterSizeId = 0,
                    selectedMoonBoardVariant = MoonBoardVariant.MOONBOARD_2016,
                    onConfirmKilter = { persisted = true },
                    onConfirmMoonBoard = { persisted = true },
                    onConfirmQuantum = { persisted = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(true, dismissed)
        assertEquals(false, persisted)
    }
}
