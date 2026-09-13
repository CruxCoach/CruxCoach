package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.ui.common.InfoButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SettingsInfoTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `touching help never changes a setting and closing preserves its state`() {
        val checked = mutableStateOf(false)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    SettingsToggleRow("Automatisch starten", "Ausführliche Erklärung", checked.value, {
                        checked.value = it; changes++
                    })
                }
            }
        }
        compose.onNodeWithText("Ausführliche Erklärung").assertDoesNotExist()
        // Touch dispatch also exercises nested click handling; a semantic
        // performClick alone would miss accidental parent-row activation.
        compose.onNodeWithContentDescription("Informationen zu Automatisch starten anzeigen")
            .performTouchInput { click() }
        compose.onNodeWithText("Ausführliche Erklärung").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, changes) }
        compose.onNodeWithText("Schließen").performClick()
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Automatisch starten").performTouchInput { click() }
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test fun `destination help does not navigate and the row still opens its destination`() {
        var navigations = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    SettingsDestinationRow("Board-Kataloge", "Alle Kataloge verwalten", Icons.Outlined.Settings) {
                        navigations++
                    }
                }
            }
        }
        compose.onNodeWithText("Alle Kataloge verwalten").assertDoesNotExist()
        compose.onNodeWithContentDescription("Informationen zu Board-Kataloge anzeigen")
            .performTouchInput { click() }
        compose.onNodeWithText("Alle Kataloge verwalten").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigations) }
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithText("Board-Kataloge").performClick()
        compose.runOnIdle { assertEquals(1, navigations) }
    }

    @Test fun `help stays available when its setting is disabled`() {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                SettingsToggleRow("Sicherung", "Umfang und Voraussetzungen", false, { changes++ }, enabled = false)
            }
        }
        compose.onNode(isToggleable()).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Informationen zu Sicherung anzeigen")
            .assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithText("Umfang und Voraussetzungen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun `long help scrolls at large font and survives state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    InfoButton("Konto", List(40) { "Absatz $it\nEine ausführliche Erklärung zum Konto." }.joinToString("\n\n"))
                }
            }
        }
        compose.onNodeWithContentDescription("Informationen zu Konto anzeigen").performClick()
        compose.onNodeWithText("Absatz 0")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Schließen").assertIsDisplayed()
        val scroll = compose.onNodeWithTag("info_dialog_content")
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Long help must be scrollable", range.maxValue() > 0f)
        scroll.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(range.value() > 0f) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("info_dialog").assertIsDisplayed()
        val restoredRange = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue("Reading position must survive recreation", restoredRange.value() > 0f) }
        compose.onNodeWithText("Schließen").assertIsDisplayed().performClick()
        compose.onNodeWithTag("info_dialog").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en")
    fun `English help has a contextual accessible label and close action`() {
        compose.setContent { MaterialTheme { InfoButton("Backup", "Backup details") } }
        compose.onNodeWithContentDescription("Show information about Backup").performClick()
        compose.onNodeWithText("Backup details").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNodeWithText("Backup details").assertDoesNotExist()
    }

    @Test fun `board send help formats both actual option labels without changing the selection`() {
        verifyBoardSendHelp()
    }

    @Test
    @Config(qualifiers = "en")
    fun `English board send help formats both actual option labels`() {
        verifyBoardSendHelp()
    }

    private fun verifyBoardSendHelp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val automatic = app.getString(R.string.settings_board_send_mode_automatic)
        val explicit = app.getString(R.string.settings_board_send_mode_explicit)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    BoardSendModeSection(
                        BoardSendMode.AUTOMATIC, BoardSendMode.EXPLICIT,
                        { changes++ }, { changes++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(app.getString(
            R.string.action_show_info, app.getString(R.string.settings_board_send_mode_title),
        )).performTouchInput { click() }
        val body = app.getString(R.string.settings_board_send_mode_desc, automatic, explicit)
        val inDialog = hasAnyAncestor(hasTestTag("info_dialog_content"))
        // Each option and exception is a distinct, accessible heading. Verify
        // that formatting retains every explanation, including the final one.
        body.split("\n\n").forEach { section ->
            val heading = section.substringBefore('\n')
            compose.onNode(hasText(heading) and inDialog)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            compose.onNode(hasText(section.substringAfter('\n')) and inDialog).assertExists()
        }
        compose.onNode(hasText(body.substringAfterLast("\n\n").substringAfter('\n')) and inDialog)
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("%1\$s", substring = true) and inDialog).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.action_close)).performClick()
        compose.onNodeWithTag("settings_board_send_mode_single")
            .onChildren().filter(hasText(automatic)).onFirst().assertIsSelected()
        compose.onNodeWithTag("settings_board_send_mode_multi")
            .onChildren().filter(hasText(explicit)).onFirst().assertIsSelected()
        compose.runOnIdle { assertEquals(0, changes) }
    }
}
