package com.cruxcoach.android.ui.board.games

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h800dp")
class KnotStepSliderTest {

    @get:Rule val compose = createComposeRule()

    private val steps = listOf("close", "medium", "wide")

    private class Slider(start: String) {
        var selected by mutableStateOf(start)
        val changes = mutableListOf<String>()
        var settled = 0
    }

    /** Moves the slider to [value] as a drag or an accessibility service does, and lets go. */
    private fun moveTo(value: Float) {
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    private fun show(slider: Slider, onOffer: Set<String>) {
        compose.setContent {
            CruxCoachTheme(DarkModeSetting.LIGHT) {
                KnotStepSlider(
                    title = "Spacing",
                    steps = steps,
                    selected = slider.selected,
                    enabled = { it in onOffer },
                    label = { it },
                    tag = "slider",
                    onChange = { slider.changes += it; slider.selected = it },
                    onSettled = { slider.settled++ },
                    note = "Wide: no room here",
                )
            }
        }
    }

    @Test
    fun aGreyedStopDoesNotTakeTheThumb() {
        val slider = Slider("close")
        show(slider, onOffer = setOf("close", "medium"))
        // Moved all the way to the greyed end: the thumb stops at the nearest step on offer and stays there.
        compose.onNodeWithTag("slider").assertIsEnabled()
        moveTo(2f)
        assertEquals(listOf("medium"), slider.changes)
        assertEquals("medium", slider.selected)
        assertEquals(1, slider.settled)
        compose.onNodeWithTag("slider").assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..2f, 1))
        // And back down: close is on offer again.
        moveTo(0f)
        assertEquals("close", slider.selected)
        compose.onNodeWithTag("slider").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..2f, 1))
    }

    @Test
    fun aGreyedStopInTheMiddleIsSkipped() {
        val slider = Slider("close")
        show(slider, onOffer = setOf("close", "wide"))
        // One step up, as a key or a screen reader moves it, onto the greyed middle: on to the next step on offer that way.
        moveTo(1f)
        assertEquals("wide", slider.selected)
        compose.onNodeWithTag("slider").assertRangeInfoEquals(ProgressBarRangeInfo(2f, 0f..2f, 1))
        // And one step down from there: back to close.
        moveTo(1f)
        assertEquals("close", slider.selected)
        assertEquals(listOf("wide", "close"), slider.changes)
    }

    @Test
    fun withOneStepOnOfferTheSliderIsDisabled() {
        val slider = Slider("close")
        show(slider, onOffer = setOf("close"))
        compose.onNodeWithTag("slider").assertIsNotEnabled()
        compose.onNodeWithTag("slider_note").assertExists()
        assertEquals(emptyList<String>(), slider.changes)
        assertEquals("close", slider.selected)
    }
}
