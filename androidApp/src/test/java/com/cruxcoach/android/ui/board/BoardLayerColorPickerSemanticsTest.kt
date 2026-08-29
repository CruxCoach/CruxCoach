package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ble.BoardLayerManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four Quantum layer colours are how climbers tell their own projection
 * apart from the other three on the wall. In the picker itself there are no
 * layer numbers to fall back on — only four circles — so without semantics a
 * screen reader reaches four identical unlabelled buttons and the feature is
 * unusable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardLayerColorPickerSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val green = BoardLayerManager.LAYER_COLORS[0]
    private val cyan = BoardLayerManager.LAYER_COLORS[1]
    private val magenta = BoardLayerManager.LAYER_COLORS[2]
    private val yellow = BoardLayerManager.LAYER_COLORS[3]

    private fun setContent(
        selectedColor: Int? = null,
        unavailable: Set<Int> = emptySet(),
        onSelect: (Int) -> Unit = {},
    ) {
        compose.setContent {
            BoardLayerColorPicker(
                selectedColor = selectedColor,
                unavailableColors = unavailable,
                onSelectColor = onSelect,
            )
        }
    }

    @Test
    fun `every swatch names its own colour`() {
        setContent()

        listOf("Green", "Cyan", "Magenta", "Yellow").forEach { name ->
            compose.onNodeWithContentDescription(name).assertExists(
                "a screen reader must be able to tell the four swatches apart",
            )
        }
    }

    @Test
    fun `the picked colour is the selected one and the others are not`() {
        setContent(selectedColor = magenta)

        compose.onNodeWithTag("board_layer_color_2").assertIsSelected()
        compose.onNodeWithTag("board_layer_color_0").assertIsNotSelected()
        compose.onNodeWithTag("board_layer_color_1").assertIsNotSelected()
        compose.onNodeWithTag("board_layer_color_3").assertIsNotSelected()
    }

    @Test
    fun `a colour already on the board says so instead of going quiet`() {
        var picked: Int? = null
        setContent(unavailable = setOf(cyan), onSelect = { picked = it })

        compose.onNodeWithContentDescription("Cyan — already in use on the board")
            .assertExists("an unavailable swatch must say why, not just fail to respond")
        compose.onNodeWithTag("board_layer_color_1").performClick()
        assertEquals("an occupied colour cannot be chosen", null, picked)
    }

    @Test
    fun `an available colour is still choosable`() {
        var picked: Int? = null
        setContent(unavailable = setOf(cyan), onSelect = { picked = it })

        compose.onNodeWithTag("board_layer_color_3").performClick()
        assertEquals(yellow, picked)
    }

    @Test
    fun `the swatches carry a control role, not decoration`() {
        setContent()

        compose.onNodeWithTag("board_layer_color_0")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
    }

    /** Green is index 0 — pin the mapping the names depend on. */
    @Test
    fun `the palette order matches the names`() {
        setContent(selectedColor = green)
        compose.onNodeWithContentDescription("Green").assertIsSelected()
    }
}
