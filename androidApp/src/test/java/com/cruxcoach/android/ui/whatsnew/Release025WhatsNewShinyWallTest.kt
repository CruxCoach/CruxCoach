package com.cruxcoach.android.ui.whatsnew

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 0.2.5 dialog collects one line per feature, so the Shiny Wall and wall
 * games lines are matched as parts of the body, not as the whole of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Release025WhatsNewShinyWallTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the 0_2_5 what's new names Shiny Wall and the wall games`() {
        compose.setContent { MaterialTheme { Release025WhatsNewDialog(onDismiss = {}) } }
        compose.onNodeWithText("New in 0.2.5").assertIsDisplayed()
        // The other features' lines stay.
        compose.onNodeWithText("• Community – join the CruxCoach community", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            "• Shiny Wall – choose your Shiny Wall in the board picker, browse its problems and light them over Bluetooth. " +
                "Import your Shiny Wall logbook under Settings → Board accounts & imports.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "• Wall games – Floor is lava, Endurance, Hold thief and Traverse, from the main menu or a problem’s menu (“Play as a game”).",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "de")
    fun `the German 0_2_5 what's new names Shiny Wall and the wall games`() {
        compose.setContent { MaterialTheme { Release025WhatsNewDialog(onDismiss = {}) } }
        compose.onNodeWithText("Neu in 0.2.5").assertIsDisplayed()
        compose.onNodeWithText("• Shiny Wall – wähle deine Shiny Wall in der Board-Auswahl", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Dein Shiny-Wall-Logbuch übernimmst du unter Einstellungen → Board-Konten & Importe.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("• Wand-Spiele – Der Boden ist Lava, Ausdauer, Griffdieb und Quergang", substring = true)
            .assertIsDisplayed()
    }
}
