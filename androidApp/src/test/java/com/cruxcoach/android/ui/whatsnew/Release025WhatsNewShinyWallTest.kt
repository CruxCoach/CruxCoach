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
 * The 0.2.5 dialog collects one line per feature, so the Shiny Wall line is
 * matched as a part of the body, not as the whole of it. The wall games and
 * spray walls are switched off in 0.2.5, so the dialog must not name them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Release025WhatsNewShinyWallTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the 0_2_5 what's new names Shiny Wall but not the wall games or spray walls`() {
        compose.setContent { MaterialTheme { Release025WhatsNewDialog(onDismiss = {}) } }
        compose.onNodeWithText("New in 0.2.5").assertIsDisplayed()
        // The other features' lines stay.
        compose.onNodeWithText("• Community on Buzz\u00A0– talk to the developers", substring = true).assertIsDisplayed()
        compose.onNodeWithText(
            "• Shiny Wall\u00A0– choose your Shiny Wall in the board picker, browse its climbs and light them over Bluetooth. " +
                "Import your Shiny Wall logbook under Settings → Board accounts & imports.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Wall games", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Spray walls", substring = true).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "de")
    fun `the German 0_2_5 what's new names Shiny Wall but not the wall games or spray walls`() {
        compose.setContent { MaterialTheme { Release025WhatsNewDialog(onDismiss = {}) } }
        compose.onNodeWithText("Neu in 0.2.5").assertIsDisplayed()
        compose.onNodeWithText("• Community auf Buzz\u00A0– sprich mit den Entwicklern", substring = true).assertIsDisplayed()
        // The app says "Boulder", so the lines do too.
        compose.onNodeWithText(
            "• Shiny Wall\u00A0– wähle deine Shiny Wall in der Board-Auswahl, durchsuche ihre Boulder und lass sie per Bluetooth leuchten.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Dein Shiny-Wall-Logbuch übernimmst du unter Einstellungen → Board-Konten & Importe.", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("Wand-Spiele", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Spraywände", substring = true).assertDoesNotExist()
    }
}
