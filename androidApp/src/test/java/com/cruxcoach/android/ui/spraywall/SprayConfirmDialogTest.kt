package com.cruxcoach.android.ui.spraywall

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.cruxcoach.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At the largest font the publish dialog fills the screen. Its text scrolls, so the line about people in the photos,
 * which comes last, can be read before "Make public".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SprayConfirmDialogTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetFont() = RuntimeEnvironment.setFontScale(1f)

    @Test fun `the publish dialog text scrolls to its last line at the largest font`() {
        RuntimeEnvironment.setFontScale(2f)
        var confirmed = 0
        compose.setContent {
            MaterialTheme {
                SprayConfirmDialog(
                    title = stringResource(R.string.spray_publish_confirm_title),
                    text = stringResource(R.string.spray_publish_confirm_text) + "\n\n" +
                        stringResource(R.string.spray_publish_no_people),
                    confirmLabel = stringResource(R.string.spray_publish_confirm),
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                )
            }
        }
        val noPeople = RuntimeEnvironment.getApplication().getString(R.string.spray_publish_no_people)
        val body = compose.onNode(hasScrollAction() and hasText(noPeople, substring = true))
        val initialRange = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue("The text is longer than this window", initialRange.maxValue() > 0f) }
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.waitForIdle()
        val finalRange = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertEquals("The whole text is reachable", finalRange.maxValue(), finalRange.value(), 0.5f) }
        compose.onNodeWithText("Öffentlich machen").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }
}
