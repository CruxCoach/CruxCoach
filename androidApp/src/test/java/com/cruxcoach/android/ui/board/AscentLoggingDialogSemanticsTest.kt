package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AscentLoggingDialogSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `outcome exposes one selected option and changes through its callback`() {
        compose.setContent {
            var isSend by remember { mutableStateOf(true) }
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                dialog(
                    scenario = AscentLoggingScenarios.NewSend.copy(isSend = isSend),
                    onIsSendChanged = { isSend = it },
                )
            }
        }

        compose.onNodeWithText("Send").assertIsSelected()
        compose.onNodeWithText("Attempt").assertIsNotSelected().performClick()
        compose.onNodeWithText("Attempt").assertIsSelected()
        compose.onNodeWithText("Send").assertIsNotSelected()
    }

    @Test
    fun `rating and terminal actions are named and actionable`() {
        var saved = 0
        var cancelled = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                dialog(
                    scenario = AscentLoggingScenarios.EditSend,
                    onSave = { saved += 1 },
                    onDismiss = { cancelled += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("4 stars").assertHasClickAction()
        compose.onNodeWithTag("ascent_quality_4").assertIsSelected()
        compose.onNodeWithTag("ascent_benchmark").assertIsSelected()
        compose.onNodeWithText("Save").assertHasClickAction().performClick()
        compose.onNodeWithText("Cancel").assertHasClickAction().performClick()

        assertEquals(1, saved)
        assertEquals(1, cancelled)
    }

    @Test
    fun `unfinished attempt hides fields that are not persisted`() {
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                dialog(scenario = AscentLoggingScenarios.NewAttempt)
            }
        }

        compose.onNodeWithText("Quality").assertDoesNotExist()
        compose.onNodeWithTag("ascent_benchmark").assertDoesNotExist()
        compose.onNodeWithText("Comment (optional)").assertExists()
    }

    @Test
    fun `compact controls meet minimum target height`() {
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                dialog(scenario = AscentLoggingScenarios.NewSend)
            }
        }

        listOf(
            "ascent_outcome_send",
            "ascent_outcome_attempt",
            "ascent_attempt_decrease",
            "ascent_attempt_increase",
            "ascent_quality_1",
            "ascent_quality_2",
            "ascent_quality_3",
            "ascent_quality_4",
            "ascent_quality_5",
            "ascent_benchmark",
            "ascent_save",
            "ascent_cancel",
        ).forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun `attempt count callback preserves form behavior`() {
        var requestedCount: Int? = null
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                dialog(
                    scenario = AscentLoggingScenarios.NewSend,
                    onBidCountChanged = { requestedCount = it },
                )
            }
        }

        compose.onNodeWithTag("ascent_attempt_decrease").assertIsNotEnabled()
        compose.onNodeWithTag("ascent_attempt_increase").performClick()

        assertEquals(2, requestedCount)
    }

    @Composable
    private fun dialog(
        scenario: AscentLoggingScenario,
        onIsSendChanged: (Boolean) -> Unit = {},
        onBidCountChanged: (Int) -> Unit = {},
        onQualityChanged: (Int) -> Unit = {},
        onIsBenchmarkChanged: (Boolean) -> Unit = {},
        onSave: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        AscentLoggingDialog(
            isEditing = scenario.isEditing,
            isSend = scenario.isSend,
            bidCount = scenario.attemptCount,
            quality = scenario.quality,
            comment = scenario.comment,
            isBenchmark = scenario.isBenchmark,
            onIsBenchmarkChanged = onIsBenchmarkChanged,
            onIsSendChanged = onIsSendChanged,
            onBidCountChanged = onBidCountChanged,
            onQualityChanged = onQualityChanged,
            onCommentChanged = {},
            onSave = onSave,
            onDismiss = onDismiss,
        )
    }
}
