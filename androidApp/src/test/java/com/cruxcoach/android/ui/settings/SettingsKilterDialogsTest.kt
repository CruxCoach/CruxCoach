package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.KilterDataInfoButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h480dp")
class SettingsKilterDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `all data exchange consequences are reachable and the dialog survives recreation`() {
        val finalParagraph = ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.kilter_data_info_publish)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { KilterDataInfoButton() } }
        compose.onNodeWithContentDescription("Kilter-Datenaustausch — Info").performClick()
        compose.onNodeWithText(finalParagraph).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(finalParagraph).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(finalParagraph).assertDoesNotExist()
    }

    @Test fun `login explanation and button remain reachable in a short window without submitting`() {
        var logins = 0
        compose.setContent {
            MaterialTheme {
                KilterLoginSheet(
                    email = "", password = "", error = null, isLoading = false,
                    onEmailChanged = {}, onPasswordChanged = {}, onLogin = { logins++ }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Dein Passwort wird nicht gespeichert", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anmelden").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, logins) }
    }
}
