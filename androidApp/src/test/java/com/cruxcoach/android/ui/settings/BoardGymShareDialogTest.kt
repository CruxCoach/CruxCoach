package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cruxcoach.android.data.BoardGymShares
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import org.junit.Assert.assertEquals
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
class BoardGymShareDialogTest {
    @get:Rule val compose = createComposeRule()

    // MoonBoard: the procedural previews need no image loading (Kilter's loading spinner never
    // lets Compose idle under Robolectric). 2016 common, Mini 2020 rare.
    private val mini = MoonBoardVariant.entries.first { it.layoutId == 6L }
    private val shares = BoardGymShares.from(
        sizeWalls = mapOf(10 to 90L),
        locations = mapOf(("moonboard" to 2) to 60L, ("moonboard" to 4) to 39L, ("moonboard" to 6) to 1L),
    )

    private fun show(gymShares: BoardGymShares?, confirmed: MutableList<MoonBoardVariant> = mutableListOf()) {
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.DARK) {
                BoardSelectionDialog(
                    initialBrand = BoardBrand.MOONBOARD.wireValue,
                    productSizes = emptyList(),
                    selectedKilterSizeId = 0,
                    selectedMoonBoardVariant = MoonBoardVariant.MOONBOARD_2016,
                    onConfirmKilter = {},
                    onConfirmMoonBoard = { variant, _ -> confirmed += variant },
                    onDismiss = {},
                    gymShares = gymShares,
                    onFindViaBluetooth = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
    }

    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun advanceUntil(limitMs: Long, done: () -> Boolean): Boolean {
        var waited = 0L
        while (!done() && waited < limitMs) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            waited += FRAME_MS
        }
        return done()
    }

    @Test fun `each version shows its share and a rare one may just be new`() {
        val confirmed = mutableListOf<MoonBoardVariant>()
        show(shares, confirmed)
        assertTrue(compose.onAllNodesWithText("In Gyms:", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Few gyms list the newest versions yet: a version is never called rare.
        compose.onAllNodesWithText("Selten in Gyms:", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("board_gym_share_rare_hint").assertDoesNotExist()

        compose.onNodeWithText(mini.displayName).performScrollTo().performClick()
        assertTrue("hint never appeared", advanceUntil(2_000) { shown("board_gym_share_rare_hint") })
        assertTrue(compose.onAllNodesWithText("vielleicht weil sie neu ist", substring = true).fetchSemanticsNodes().isNotEmpty())
        // The hint only asks; the rare version can still be confirmed.
        compose.onNodeWithText("Bestätigen").performClick()
        compose.mainClock.advanceTimeBy(FRAME_MS)
        assertEquals(listOf(mini), confirmed)
    }

    @Test fun `without the gym list the picker shows no shares`() {
        show(null)
        compose.onAllNodesWithTag("board_gym_share").assertCountEquals(0)
        compose.onAllNodesWithText("Selten in Gyms:", substring = true).assertCountEquals(0)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
