package com.cruxcoach.android.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class ImportSourceCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `import help at large font never triggers account navigation`() {
        var imports = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Box(Modifier.width(240.dp)) {
                        ImportSourceCard(
                            icon = {},
                            title = "Backup wiederherstellen",
                            description = "Kontozugang\nDas bisherige Konto wird benötigt.",
                            action = "Konto importieren",
                            onClick = { imports++ },
                            highlighted = true,
                            testTag = "restore_card",
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Informationen zu Backup wiederherstellen anzeigen")
            .assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Das bisherige Konto wird benötigt.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, imports) }
        compose.onNodeWithText("Schließen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Konto importieren").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, imports) }
    }
}
