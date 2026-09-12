package com.cruxcoach.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountScreensTest {
    @get:Rule val compose = createComposeRule()
    private var reviewView: View? = null
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun text(id: Int) = app.getString(id)

    @Test fun `optional profile fields preserve edits and local save never publishes`() {
        val state = mutableStateOf(NostrProfileEditState(isLoading = false))
        var saved: NostrProfileEditState? = null
        var published = 0
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Scaffold(bottomBar = { ProfileSaveBar(state.value) { saved = state.value } }) { padding ->
                        NostrProfileContent(state.value, actions(state, { published++ }), Modifier.padding(padding))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.nostr_profile_banner_change)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.nostr_profile_lightning)).assertDoesNotExist()
        compose.onNodeWithTag("profile_save_local").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nostr_profile_display_name)).performTextInput("Mara")
        compose.onNodeWithText(text(R.string.ux_profile_links)).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(text(R.string.nostr_profile_website_label)).performScrollTo().performTextInput("https://example.org")
        compose.onNodeWithText(text(R.string.ux_profile_links)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.nostr_profile_website_label)).assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.nostr_profile_website_label)).assertDoesNotExist()
        compose.onNodeWithTag("profile_save_local").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("Mara", saved?.displayName)
            assertEquals("https://example.org", saved?.website)
            assertEquals(0, published)
        }
        compose.onNodeWithText(text(R.string.ux_profile_links)).performScrollTo().performClick()
        compose.onNodeWithText("https://example.org").performScrollTo().assertIsDisplayed()
    }

    @Test fun `public profile still requires confirmation and respects a busy save`() {
        val busy = mutableStateOf(false)
        var published = 0
        compose.setContent {
            CruxCoachTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    ProfilePublicationSection(busy.value, false) { published++ }
                }
            }
        }
        compose.onNodeWithTag("profile_publish").performTouchInput { click() }
        compose.onNodeWithText(text(R.string.nostr_profile_publish_warning_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, published) }
        compose.onNodeWithText(app.getString(android.R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, published) }
        compose.onNodeWithTag("profile_publish").performClick()
        compose.onNodeWithText(text(R.string.nostr_profile_publish_confirm)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, published); busy.value = true }
        compose.onNodeWithTag("profile_publish").assertIsNotEnabled()
    }

    @Test fun `recovery help cannot copy a key or acknowledge a backup`() {
        var copies = 0
        var acknowledgements = 0
        compose.setContent {
            CruxCoachTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Column(Modifier.fillMaxSize()) {
                        AccountRecoverySection(false, { copies++ }, { acknowledgements++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, text(R.string.account_recovery_title)))
            .performTouchInput { click() }
        compose.onNodeWithText(text(R.string.action_close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, copies); assertEquals(0, acknowledgements) }
        compose.onNodeWithText(text(R.string.backup_key_warning_acknowledged)).performClick()
        compose.onNodeWithText(text(R.string.backup_key_warning_ack_cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements) }
        compose.onNodeWithText(text(R.string.backup_key_warning_acknowledged)).performClick()
        compose.onNodeWithText(text(R.string.backup_key_warning_ack_confirm)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, acknowledgements); assertEquals(0, copies) }
        compose.onNodeWithTag("account_copy_secret").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun `Amber shows the active public identity and only the matching switch action`() {
        var localSwitches = 0
        var imports = 0
        var copies = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    AccountManagementContent(
                        KeyManagementState(isLoading = false, signerMode = SignerMode.AMBER,
                            npubDisplay = "PUBLIC-FIXTURE", amberPubkeyDisplay = "PUBLIC-FIXTURE"),
                        onCopyNsec = { error("Amber must not offer local key export") },
                        onImport = { imports++ }, onSetupAmber = {},
                        onDisconnectAmber = { localSwitches++ }, onCopyNpub = { copies++ },
                        onAcknowledgeBackup = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("account_copy_secret").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.key_label_local_key_inactive)).assertDoesNotExist()
        compose.onNodeWithTag("account_switch").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, localSwitches); assertEquals(0, imports) }
        compose.onNodeWithText(text(R.string.account_copy_id)).performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun `optional Amber help never connects and import remains reachable at large font`() {
        var connections = 0
        var imports = 0
        compose.setContent {
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    AccountManagementContent(KeyManagementState(isLoading = false, keyBackedUp = true),
                        {}, { imports++ }, { connections++ }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("account_connect_amber").assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, text(R.string.account_amber_title)))
            .performScrollTo().performTouchInput { click() }
        compose.onNodeWithText(text(R.string.action_close)).performClick()
        compose.runOnIdle { assertEquals(0, connections); assertEquals(0, imports) }
        compose.onNodeWithText(text(R.string.account_amber_title)).performClick()
        compose.onNodeWithTag("account_connect_amber").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("account_switch").performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, connections); assertEquals(1, imports) }
    }

    @Test fun `profile starts with personal fields and visible save instead of empty cover`() {
        val state = mutableStateOf(NostrProfileEditState(isLoading = false))
        compose.setContent {
            reviewView = LocalView.current
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                Scaffold(bottomBar = { ProfileSaveBar(state.value) {} }) { padding ->
                    NostrProfileContent(state.value, actions(state), Modifier.padding(padding))
                }
            }
        }
        compose.onNodeWithText(text(R.string.nostr_profile_display_name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.nostr_profile_about)).assertIsDisplayed()
        compose.onNodeWithTag("profile_save_local").assertIsDisplayed()
        reviewImage("profile-normal")
    }

    @Test fun `recovery action remains visible in unbacked local account`() {
        compose.setContent {
            reviewView = LocalView.current
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AccountManagementContent(KeyManagementState(isLoading = false), {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("account_copy_secret").assertIsDisplayed()
        reviewImage("account-normal-fixture")
    }

    private fun actions(state: MutableState<NostrProfileEditState>, onPublish: () -> Unit = {}) = ProfileEditorActions(
        onDisplayName = { state.value = state.value.copy(displayName = it) },
        onAbout = { state.value = state.value.copy(about = it) },
        onLightning = { state.value = state.value.copy(lightningAddress = it) },
        onNip05 = { state.value = state.value.copy(nip05 = it) },
        onWebsite = { state.value = state.value.copy(website = it) },
        onImportKilter = {}, onEditPicture = {}, onRemovePicture = {}, onEditBanner = {},
        onRemoveBanner = {}, onPublish = onPublish, onAutoNote = {},
    )

    // Optional review artifacts render these production components with synthetic data only.
    private fun reviewImage(name: String) {
        val directory = System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let(::File) ?: return
        directory.mkdirs()
        compose.runOnIdle {
            val view = requireNotNull(reviewView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
