package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
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
class AttemptLogConfirmationSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `durable send confirmation names the logbook and exposes one 48dp action`() {
        var opens = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.LIGHT) {
                AttemptLogConfirmation(
                    climbName = "Quiet Riot",
                    gradeLabel = "6c+",
                    angle = 40,
                    isSend = true,
                    onViewLogbook = { opens += 1 },
                )
            }
        }

        compose.onNodeWithText("Send logged to your logbook").assertExists()
        compose.onNodeWithText("Quiet Riot · 6c+ · 40° is now in your logbook.").assertExists()
        compose.onNodeWithTag("attempt_log_view_logbook")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, opens)
    }
}
