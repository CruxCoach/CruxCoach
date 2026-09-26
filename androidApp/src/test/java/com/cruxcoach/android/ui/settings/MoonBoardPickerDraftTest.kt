package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoonBoardPickerDraftTest {
    @get:Rule val compose = createComposeRule()
    private val variant = MoonBoardVariant.MOONBOARD_2016
    private val sets = MoonBoardHoldSets.setIdsFor(variant)
    private var confirmed: List<Long>? = null
    private var dismissed = false

    private fun show(initial: List<Long> = sets) {
        compose.setContent { MaterialTheme {
            BoardSelectionDialog(
                initialBrand = BoardBrand.MOONBOARD.wireValue, productSizes = emptyList(),
                selectedKilterSizeId = 0, selectedMoonBoardVariant = variant,
                initialMoonBoardHoldSets = mapOf(variant to initial),
                onConfirmKilter = {}, onConfirmMoonBoard = { _, selected -> confirmed = selected },
                onDismiss = { dismissed = true },
            )
        } }
    }

    @Test fun `complete setup starts collapsed and cancel discards a changed draft`() {
        show()
        compose.onNodeWithTag("moonboard_hold_set_${sets.first()}").assertDoesNotExist()
        compose.onNodeWithTag("moonboard_setup_partial").performScrollTo().performClick()
        compose.onNodeWithTag("moonboard_hold_set_${sets.first()}").performScrollTo().performClick()
        assertNull(confirmed)
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertNull(confirmed)
    }

    @Test fun `existing subset is visible and confirmation keeps it`() {
        show(listOf(sets.first()))
        compose.onNodeWithTag("moonboard_hold_set_${sets.first()}").performScrollTo().assertExists()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(listOf(sets.first()), confirmed)
    }

    @Test fun `last set cannot be removed and complete setup restores all sets`() {
        show(listOf(sets.first()))
        compose.onNodeWithTag("moonboard_hold_set_${sets.first()}").performScrollTo().performClick()
        compose.onNodeWithTag("moonboard_hold_sets_min_one").performScrollTo().assertExists()
        compose.onNodeWithTag("moonboard_setup_complete").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(sets, confirmed)
    }
}
