package com.cruxcoach.android.ui.common

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SuccessorJoinDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `dialog names the unverified group and exposes one explicit answer`() {
        var answer = ""
        compose.setContent {
            MaterialTheme {
                SuccessorJoinDialog(
                    hostName = "Gym crew",
                    onJoin = { answer = "join" },
                    onKeepQueue = { answer = "keep" },
                )
            }
        }

        compose.onNodeWithText("Continue with nearby group?").assertExists()
        compose.onNodeWithText(
            "“Gym crew” appeared nearby. Session names are not verified. " +
                "Join only if you recognize this group.",
        ).assertExists()

        compose.onNodeWithTag("successor_join_confirm").performClick()
        assertEquals("join", answer)
        compose.onNodeWithTag("successor_join_keep_queue").performClick()
        assertEquals("keep", answer)
    }
}
