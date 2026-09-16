package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class BoardDownloadDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `checkbox row toggles once and empty download selection can be saved`() {
        val selected = mutableStateOf(setOf(BoardBrand.KILTER))
        var saved: Set<BoardBrand>? = null
        compose.setContent {
            MaterialTheme {
                BoardMultiSelectDialog(
                    title = "Boards zum Herunterladen", message = "Auswahl", note = null,
                    confirmLabel = "Speichern", confirmColor = Color.Blue,
                    selectedBrands = selected.value,
                    onToggleBrand = { brand -> selected.value = if (brand in selected.value) selected.value - brand else selected.value + brand },
                    onToggleSelectAll = { selected.value = BoardBrand.entries.filter { it.isInteractive }.toSet() },
                    onConfirm = { saved = selected.value }, onDismiss = {}, allowEmpty = true,
                )
            }
        }
        compose.onNodeWithTag("board_selection_kilter").assertIsOn().performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Speichern").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(emptySet<BoardBrand>(), saved) }
    }

    @Test fun `initial download requires at least one board`() {
        val selected = mutableStateOf(emptySet<BoardBrand>())
        compose.setContent {
            MaterialTheme {
                BoardMultiSelectDialog(
                    title = "Boards", message = "Auswahl", note = null,
                    confirmLabel = "Herunterladen", confirmColor = Color.Blue,
                    selectedBrands = selected.value,
                    onToggleBrand = { selected.value = selected.value + it },
                    onToggleSelectAll = {}, onConfirm = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Herunterladen").assertIsNotEnabled()
        compose.onNodeWithTag("board_selection_moonboard").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Herunterladen").assertIsEnabled()
    }
}
