package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared row of the training area (UI pass 0.2.5, review of #93): label and stepper side by side or the stepper
 * below, at 320 dp and font 2.0, in a row whose accent bar takes its height from the layout's intrinsics, and mirrored
 * for right-to-left languages. Native graphics, because the choice depends on real text widths. CI skips the screenshot
 * renders, so this is the check that runs there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelBesideOrAboveTest {
    @get:Rule val compose = createComposeRule()

    private fun show(label: String, stepperWidth: Dp, fontScale: Float, rtl: Boolean = false, keepBeside: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                // 320 dp wide; the bar is as tall as the row, as a card's accent bar beside the comeback notice.
                Row(Modifier.width(320.dp).height(IntrinsicSize.Min).testTag("row")) {
                    Box(Modifier.width(4.dp).fillMaxHeight().testTag("bar"))
                    LabelBesideOrAbove(modifier = Modifier.weight(1f).testTag("layout"), keepBeside = keepBeside,
                        label = { Text(label, fontSize = 16.sp, modifier = Modifier.testTag("label")) }) {
                        Box(Modifier.size(stepperWidth, 48.dp).testTag("stepper"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()
    private fun near(expected: Dp, actual: Dp, what: String) =
        assertTrue("$what: expected $expected, was $actual", (expected - actual).value in -1f..1f)

    private fun assertBeside() {
        val l = bounds("label"); val s = bounds("stepper")
        assertTrue("stepper beside the label: label $l, stepper $s", s.top < l.bottom && l.top < s.bottom)
        // Side by side without overlapping, in either direction.
        assertTrue("label and stepper overlap: label $l, stepper $s", l.right <= s.left + 1.dp || s.right <= l.left + 1.dp)
    }

    private fun assertBelow() {
        val l = bounds("label"); val s = bounds("stepper")
        assertTrue("stepper below the label: label $l, stepper $s", s.top >= l.bottom - 1.dp)
    }

    /** The intrinsic height the bar got matches what the layout drew: nothing sticks out, nothing is left empty. */
    private fun assertEqualHeight() {
        val bar = bounds("bar"); val l = bounds("label"); val s = bounds("stepper")
        near(bar.bottom, maxOf(l.bottom, s.bottom), "content bottom")
    }

    /** Left to right: label at the start, stepper at the end, on the side or the line below. */
    private fun assertLtr() {
        val layout = bounds("layout")
        near(layout.left, bounds("label").left, "label start")
        near(layout.right, bounds("stepper").right, "stepper end")
    }

    /** Right to left: the label starts on the right, the stepper ends on the left. */
    private fun assertRtl() {
        val layout = bounds("layout")
        near(layout.right, bounds("label").right, "label start")
        near(layout.left, bounds("stepper").left, "stepper end")
    }

    private val shortLabel = "Sätze"
    private val longWord = "Gewichtswestenzusatzgewicht"
    private val manyWords = "one two three four five six seven eight nine ten eleven twelve"

    @Test fun `a short label stays beside the stepper at 320 dp and font 2`() {
        show(shortLabel, 112.dp, 2f)
        assertBeside(); assertEqualHeight(); assertLtr()
    }

    @Test fun `a word that would break moves the stepper below at 320 dp and font 2`() {
        show(longWord, 112.dp, 2f)
        assertBelow(); assertEqualHeight(); assertLtr()
    }

    @Test fun `at font 2 a label that would wrap past two lines moves the stepper below`() {
        show(manyWords, 112.dp, 2f)
        assertBelow(); assertEqualHeight()
    }

    @Test fun `at font 1 the same label wraps beside the stepper as in a row`() {
        show(manyWords, 112.dp, 1f)
        assertBeside(); assertEqualHeight()
        assertTrue("wraps to several lines", bounds("label").height > 40.dp)
    }

    @Test fun `at font 1 a word that would break still moves the stepper below`() {
        show(longWord, 160.dp, 1f)
        assertBelow(); assertEqualHeight(); assertLtr()
    }

    @Test fun `a narrow value is no icon and moves below when its label does not fit`() {
        show(longWord, 48.dp, 2f)
        assertBelow(); assertEqualHeight()
    }

    @Test fun `keepBeside keeps an icon beside its label`() {
        show(longWord, 48.dp, 2f, keepBeside = true)
        assertBeside(); assertEqualHeight(); assertLtr()
    }

    @Test fun `right to left mirrors the row beside`() {
        show(shortLabel, 112.dp, 2f, rtl = true)
        assertBeside(); assertEqualHeight(); assertRtl()
        // The bar sits at the start, which is on the right now.
        near(bounds("row").right, bounds("bar").right, "bar on the right")
    }

    @Test fun `right to left mirrors the stepper below`() {
        show(longWord, 112.dp, 2f, rtl = true)
        assertBelow(); assertEqualHeight(); assertRtl()
    }

    @Test fun `a stepper wider than the row goes below`() {
        show(shortLabel, 400.dp, 1f)
        assertBelow(); assertEqualHeight()
    }

    @Test fun `kept beside, the narrowest row is the label's narrowest plus the stepper`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Box(Modifier.width(IntrinsicSize.Min).testTag("box")) {
                    LabelBesideOrAbove(keepBeside = true, label = { Text("Sätze", fontSize = 16.sp, modifier = Modifier.testTag("label")) }) {
                        Box(Modifier.size(48.dp).testTag("stepper"))
                    }
                }
            }
        }
        compose.waitForIdle()
        near(bounds("label").width + 48.dp, bounds("box").width, "min width")
        assertBeside()
    }

    @Test fun `keepBeside with a stepper wider than the row does not crash`() {
        // The row asks for the intrinsic height beside a 400 dp stepper: no room is no width, not a negative one.
        show(shortLabel, 400.dp, 1f, keepBeside = true)
        near(bounds("layout").width, bounds("stepper").width, "stepper clamped to the row")
    }
}
