package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmberRestoreScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Amber method is reachable and selecting it does not import or switch accounts`() {
        val vm = mockk<KeyImportViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(KeyImportState())
        compose.setContent { MaterialTheme { KeyImportScreen({}, vm) } }
        compose.onNodeWithText("Mit Amber").performScrollTo().performClick()
        compose.onNodeWithTag("restore_with_amber").performScrollTo().assertIsDisplayed().assertIsEnabled()
        verify(exactly = 0) { vm.confirmAmberImport() }
        verify(exactly = 0) { vm.startImport() }
        compose.onNodeWithTag("restore_with_amber").performClick()
        // This test device has no Amber; presenting installation options must not switch identity.
        compose.onNodeWithText("Abbrechen").assertIsDisplayed().performClick()
        verify(exactly = 0) { vm.confirmAmberImport() }
        verify(exactly = 0) { vm.previewAmberAccount(any(), any()) }
    }
    @Test fun `returned public account requires a separate confirmation and can be cancelled`() {
        val state = MutableStateFlow(KeyImportState())
        val vm = mockk<KeyImportViewModel>(relaxed = true)
        every { vm.state } returns state
        every { vm.dismissAmberImport() } answers { state.value = KeyImportState() }
        every { vm.confirmAmberImport() } answers { state.value = KeyImportState() }
        compose.setContent { MaterialTheme { KeyImportScreen({}, vm) } }
        compose.runOnIdle {
            state.value = KeyImportState(amberTargetNpub = "npub-public-test-account", sameAccount = false)
        }
        compose.onNodeWithText("npub-public-test-account").assertIsDisplayed()
        verify(exactly = 0) { vm.confirmAmberImport() }
        compose.onNodeWithText("Abbrechen").performClick()
        verify(exactly = 1) { vm.dismissAmberImport() }
        verify(exactly = 0) { vm.confirmAmberImport() }
        compose.runOnIdle {
            state.value = KeyImportState(amberTargetNpub = "npub-public-test-account", sameAccount = true)
        }
        val confirm = org.robolectric.RuntimeEnvironment.getApplication().getString(com.cruxcoach.android.R.string.account_access_confirm)
        compose.onNodeWithText(confirm).performClick()
        verify(exactly = 1) { vm.confirmAmberImport() }
        verify(exactly = 0) { vm.startImport() }
    }

}
