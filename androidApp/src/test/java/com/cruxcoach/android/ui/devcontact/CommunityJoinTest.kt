package com.cruxcoach.android.ui.devcontact

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.whatsnew.Release025WhatsNewDialog
import com.cruxcoach.android.ui.whatsnew.WhatsNewItems
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CommunityJoinTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var hasBrowser = true
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            // What AndroidUriHandler throws when no app takes the link.
            if (!hasBrowser) throw IllegalArgumentException("Can't open $uri.")
            opened += uri
        }
    }

    private val sendSuccess = mutableStateOf<Boolean?>(null)
    private var offers = true
    private var consumed = 0
    private var hidden = 0
    private var backs = 0

    private fun renderForm(hideForGood: suspend () -> Unit = { hidden++ }): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MaterialTheme {
                    ReportSentHandler(
                        sendSuccess = sendSuccess.value,
                        sentTitle = "Bug report sent",
                        hintText = "Follow your report and test the fix early: join the community on Buzz.",
                        offersCommunity = { offers },
                        hideForGood = hideForGood,
                        onResultConsumed = { consumed++; sendSuccess.value = null },
                        onNavigateBack = { backs++ },
                    )
                }
            }
        }
        return restoration
    }

    private fun send() {
        compose.runOnIdle { sendSuccess.value = true }
        compose.waitForIdle()
    }

    @Test fun `the community page says what it is and only opens the fixed link`() {
        compose.setContent {
            MaterialTheme { CommunityJoinSection(onJoin = { uriHandler.openUri(COMMUNITY_JOIN_URL) }) }
        }
        compose.onNodeWithText("Join the CruxCoach community").assertIsDisplayed()
        compose.onNodeWithText("Talk to the developers").assertIsDisplayed()
        compose.onNodeWithText("Test new builds first").assertIsDisplayed()
        compose.onNodeWithText("Help shape what comes next").assertIsDisplayed()
        compose.onNodeWithText("Buzz is a separate app with its own account. Nothing from CruxCoach is shared.")
            .assertIsDisplayed()
        compose.onNodeWithText("Join on Buzz").performClick()
        assertEquals(listOf("https://community.cruxcoach.org/join"), opened)
    }

    @Config(qualifiers = "de")
    @Test fun `the community page and hint read in German`() {
        compose.setContent {
            MaterialTheme {
                CommunityJoinSection(onJoin = {})
                CommunityReportHintDialog(
                    title = "Fehlerbericht gesendet",
                    text = "Verfolge deine Meldung und teste die Lösung früh: Tritt der Community auf Buzz bei.",
                    onJoin = {}, onAlreadyMember = {}, onClose = {},
                )
            }
        }
        compose.onNodeWithText("Auf Buzz beitreten").assertExists()
        compose.onNodeWithText("Buzz ist eine separate App mit eigenem Konto. CruxCoach gibt nichts weiter.")
            .assertExists()
        compose.onNodeWithText("Bereits Mitglied").assertIsDisplayed()
        compose.onNodeWithText("Später").assertIsDisplayed()
    }

    @Test fun `the hint text scrolls when the dialog fills the screen`() {
        val text = "Follow your report and test the fix early: join the community on Buzz."
        compose.setContent {
            MaterialTheme {
                CommunityReportHintDialog(
                    title = "Bug report sent", text = text,
                    onJoin = {}, onAlreadyMember = {}, onClose = {},
                )
            }
        }
        compose.onNode(hasText(text) and hasScrollAction()).assertExists()
    }

    @Test fun `a sent report shows the hint instead of leaving, and the hint survives recreation`() {
        val restoration = renderForm()
        send()
        compose.onNodeWithText("Bug report sent").assertIsDisplayed()
        compose.onNodeWithText("Follow your report and test the fix early: join the community on Buzz.")
            .assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("community_report_hint").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, consumed)
            assertEquals(0, backs)
        }
    }

    @Test fun `closing hides the hint for this report only and leaves once`() {
        renderForm()
        send()
        compose.onNodeWithTag("community_report_hint_close").performClick()
        compose.onNodeWithTag("community_report_hint").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals(0, hidden)
            assertEquals(emptyList<String>(), opened)
        }
    }

    @Test fun `join opens the link and leaves the form`() {
        renderForm()
        send()
        compose.onNodeWithTag("community_report_hint_join").performClick()
        compose.runOnIdle {
            assertEquals(listOf(COMMUNITY_JOIN_URL), opened)
            assertEquals(1, backs)
            assertEquals(0, hidden)
        }
    }

    @Test fun `join without a browser shows a toast and still leaves the form`() {
        hasBrowser = false
        renderForm()
        send()
        compose.onNodeWithTag("community_report_hint_join").performClick()
        compose.runOnIdle {
            assertEquals("No app can open this link.", ShadowToast.getTextOfLatestToast())
            assertEquals(1, backs)
            assertEquals(0, hidden)
        }
    }

    @Test fun `already a member is stored before the form leaves`() {
        val stored = CompletableDeferred<Unit>()
        renderForm(hideForGood = { hidden++; stored.await() })
        send()
        compose.onNodeWithTag("community_report_hint_member").performClick()
        compose.onNodeWithTag("community_report_hint").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, hidden)
            assertEquals(0, backs)
            stored.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun `a member gets the toast and the way back as before`() {
        offers = false
        renderForm()
        send()
        compose.onNodeWithTag("community_report_hint").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals("Bug report sent", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test fun `a failed send stays on the form without the hint`() {
        renderForm()
        compose.runOnIdle { sendSuccess.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("community_report_hint").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, backs)
            assertEquals(1, consumed)
        }
    }

    @Test fun `the 0_2_5 what's new carries the community line and waits for the version bump`() {
        compose.setContent { MaterialTheme { Release025WhatsNewDialog(onDismiss = {}) } }
        compose.onNodeWithText("New in 0.2.5").assertIsDisplayed()
        // One line of several: the 0.2.5 dialog collects a line per feature.
        compose.onNodeWithText(
            "• Community on Buzz\u00A0– talk to the developers and test new builds first (Settings → Community).",
            substring = true,
        ).assertIsDisplayed()
        assertFalse(WhatsNewItems.RELEASE_025 in WhatsNewItems.registry)
    }
}
