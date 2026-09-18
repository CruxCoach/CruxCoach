package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
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
        compose.onNodeWithTag("account_flow_copy").assertIsDisplayed().performClick()
        compose.onNodeWithTag("account_flow_copy").assertDoesNotExist()
        compose.onNodeWithTag("account_flow_confirm").assertIsDisplayed()
        compose.onNodeWithText("Schlüssel kopiert").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithTag("account_flow_confirm").performClick()
        compose.runOnIdle { assertEquals(1, uploads) }
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
