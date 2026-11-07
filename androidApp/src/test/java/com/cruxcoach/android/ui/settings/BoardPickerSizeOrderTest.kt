package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.BoardGymShares
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardPickerSizeOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun top(label: String): Float =
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    @Test fun `counts that arrive later do not move a size under the finger`() {
        // The board pictures load in the background; their spinner must not hold the test clock.
        compose.mainClock.autoAdvance = false
        val sizes = BoardConstants.KILTER_KNOWN_SIZES.filter { it.id == 7L || it.id == 14L }
        // As in the picker: bundled counts first, the gym list a moment later, in the other order.
        val shares = mutableStateOf<BoardGymShares?>(null)
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.DARK) {
                BoardSelectionDialog(
                    initialBrand = BoardBrand.KILTER.wireValue, productSizes = sizes,
                    selectedKilterSizeId = 7, selectedMoonBoardVariant = null,
                    onConfirmKilter = {}, onConfirmMoonBoard = { _, _ -> }, onDismiss = {},
                    frequency = shares.value?.kilterSizeFrequency ?: mapOf(7 to 10L, 14 to 9L),
                    gymShares = shares.value,
                )
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertTrue("12 x 14 starts first", top(SUPER_TALL) < top(SMALL))

        compose.runOnUiThread {
            shares.value = BoardGymShares.from(mapOf(7 to 60L, 14 to 90L), emptyMap())
            // With the clock held nothing else hands the write to the recomposer.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val texts = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
        assertTrue("the shares never arrived: $texts", texts.any { "60" in it })
        assertTrue("the live counts re-sorted the list", top(SUPER_TALL) < top(SMALL))
    }

    private companion object {
        /** Several frames: with the clock held, nothing recomposes between them on its own. */
        const val SETTLE_MS = 500L
        val SUPER_TALL = BoardConstants.sizeLabel(7L, "12 x 14")
        val SMALL = BoardConstants.sizeLabel(14L, "7 x 10")
    }
}
