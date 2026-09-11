package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBackupDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `remote deletion consequences can be read to the end and cancelled without deleting`() {
        val visible = mutableStateOf(true)
        var deletions = 0
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) DeleteRemoteBackupsDialog(
                    onConfirm = { deletions++ },
                    onDismiss = { cancellations++; visible.value = false },
                )
            }
        }
        val body = compose.onNode(hasScrollAction())
        val initialRange = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertTrue("The long warning exceeds this short window", initialRange.maxValue() > 0f)
        }
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        compose.waitForIdle()
        val finalRange = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertEquals("The entire warning is reachable", finalRange.maxValue(), finalRange.value(), 0.5f)
        }
        compose.onNodeWithText("Abbrechen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Remote-Backups löschen?").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, deletions)
            assertEquals(1, cancellations)
        }
    }
}
