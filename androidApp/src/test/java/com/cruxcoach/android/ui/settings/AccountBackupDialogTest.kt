package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
class AccountBackupDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `copy transitions inside one dialog and explicit storage confirmation remains reachable at large font`() {
        val state = mutableStateOf(AccountBackupState(step = AccountBackupStep.COPY))
        var uploads = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    AccountBackupDialog(state.value,
                        onChooseBackup = { state.value = state.value.copy(wantsBackup = it) },
                        onCopy = { state.value = state.value.copy(step = AccountBackupStep.STORE, copiedInFlow = true) },
                        onConfirmStored = { uploads++ }, onDismiss = {})
                }
            }
        }
        compose.onNode(isToggleable()).assertIsOff().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, uploads) }
        val copyBounds = compose.onNodeWithTag("account_flow_copy").fetchSemanticsNode().boundsInRoot
        val cancelBefore = compose.onNodeWithTag("account_flow_cancel").fetchSemanticsNode().boundsInRoot
        assertTrue("Copy and cancel must not overlap", copyBounds.bottom <= cancelBefore.top)
        compose.onNodeWithTag("account_flow_copy").assertIsDisplayed().performClick()
        compose.onNodeWithTag("account_flow_copy").assertDoesNotExist()
        compose.onNodeWithTag("account_flow_confirm").assertIsDisplayed()
        compose.onNodeWithText("Schlüssel kopiert").assertIsDisplayed()
        val confirmBounds = compose.onNodeWithTag("account_flow_confirm").fetchSemanticsNode().boundsInRoot
        val cancelBounds = compose.onNodeWithTag("account_flow_cancel").fetchSemanticsNode().boundsInRoot
        assertTrue("Confirm and cancel must not overlap", confirmBounds.bottom <= cancelBounds.top)
        compose.onNodeWithTag("account_flow_cancel").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithTag("account_flow_confirm").performClick()
        compose.runOnIdle { assertEquals(1, uploads) }
    }

    @Test fun `optional key and privacy help preserves choice and never triggers an action`() {
        val state = mutableStateOf(AccountBackupState(step = AccountBackupStep.COPY))
        var copies = 0
        var uploads = 0
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            MaterialTheme { AccountBackupDialog(state.value,
                { state.value = state.value.copy(wantsBackup = it) }, { copies++ }, { uploads++ }, {}) }
        }
        compose.onNodeWithTag("info_dialog").assertDoesNotExist()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, app.getString(R.string.account_flow_title))).performClick()
        compose.onNodeWithTag("info_dialog").assertExists()
        compose.onNodeWithText(app.getString(R.string.account_flow_help).substringBefore("\n")).assertExists()
        compose.onNodeWithText(app.getString(R.string.action_close)).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(0, copies); assertEquals(0, uploads) }
    }

    @Test fun `progress prevents dismissal and failures expose retry instead of another dialog`() {
        val state = mutableStateOf(AccountBackupState(step = AccountBackupStep.RUNNING, wantsBackup = true))
        var retries = 0
        compose.setContent {
            MaterialTheme { AccountBackupDialog(state.value, {}, {}, { retries++ }, {}) }
        }
        compose.onNodeWithText("Abbrechen").assertDoesNotExist()
        compose.onNodeWithTag("account_flow_confirm").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(step = AccountBackupStep.ERROR) }
        compose.onNodeWithTag("account_flow_retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
