package com.cruxcoach.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.shinywall.ShinyWallImportPreview
import com.cruxcoach.android.data.shinywall.ShinyWallImportResult
import com.cruxcoach.android.data.shinywall.formatShinyWallImportSummary
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the Shiny Wall account: the imports page with the 0.2.4
 * Kilter card above it and the Shiny Wall card in each of its states, then the
 * sign-in sheet, the import preview, the disconnect question and the info text,
 * into `CRUXCOACH_UI_REVIEW_DIR`, one folder per language, width and font size,
 * dark and light. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShinyWallAccountUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private var shown: @Composable () -> Unit by mutableStateOf({})
    private var theme: DarkModeSetting by mutableStateOf(DarkModeSetting.DARK)
    private var view: android.view.View? = null
    private var folder = ""

    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** One composition per test (the rule allows only one); [shoot] swaps what it shows and the theme. */
    private fun start(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                shown()
            }
        }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(8) {
            Thread.sleep(40)
            compose.waitForIdle()
        }
    }

    /** Shows [content] in both themes; [open] taps what opens a dialog first. */
    private fun shoot(name: String, open: () -> Unit = {}, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            theme = if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT
            shown = content
            settle()
            open()
            settle()
            capture("$name-${if (dark) "dark" else "light"}")
            shown = {}
            compose.waitForIdle()
        }
    }

    /** The screen, and on top of it the dialogs and sheets it opened, as the window manager stacks them. */
    private fun capture(name: String) {
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            root.draw(canvas)
            windows().filter { it !== root && it.width > 0 }.forEach { window ->
                if (window.width >= root.width && window.height >= root.height) {
                    // A modal sheet: a full-screen window with its own scrim.
                    window.draw(canvas)
                } else {
                    canvas.drawColor(android.graphics.Color.argb(150, 0, 0, 0))
                    canvas.save()
                    canvas.translate((root.width - window.width) / 2f, (root.height - window.height) / 2f)
                    window.draw(canvas)
                    canvas.restore()
                }
            }
            val out = File(dir!!, folder).apply { mkdirs() }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    /** The imports page as the app lays it out: Kilter (0.2.4), Shiny Wall, then the file imports. */
    @Composable
    private fun ImportsPage(shiny: ShinyWallAccountState, kilter: KilterAccountState = KilterAccountState()) {
        SettingsLayout(
            isLoading = false,
            openUpdates = false,
            startPage = SettingsPage.IMPORTS,
            onNavigateBack = {},
            onNavigateToAppShare = {},
        ) {
            SettingsSectionCard {
                KilterAccountSection(
                    state = kilter,
                    onShowLogin = {}, onDismissLogin = {}, onEmailChanged = {}, onPasswordChanged = {},
                    onLogin = {}, onImportOneTime = {}, onImportPersistent = {}, onDismissPreview = {},
                    onSyncNow = {}, onPushEnabledChanged = {}, onClimbPublishEnabledChanged = {},
                    onDisconnect = {}, onShowDisconnectConfirm = {}, onDismissDisconnectConfirm = {},
                    onDismissResult = {}, onRetryPublishQueueNow = {},
                )
            }
            SettingsSectionCard { Shiny(shiny) }
            SettingsSectionCard {
                BoardLogbookImportSection(onNavigateToAuroraMigration = {}, onNavigateToMoonBoardCsvImport = {})
            }
        }
    }

    @Composable
    private fun Shiny(state: ShinyWallAccountState) = ShinyWallAccountContent(
        state = state,
        onShowLogin = {}, onDismissLogin = {}, onEmailChanged = {}, onPasswordChanged = {}, onLogin = {},
        onCheck = {}, onImport = {}, onDismissPreview = {}, onShowDisconnectConfirm = {},
        onDismissDisconnectConfirm = {}, onDisconnect = {}, onDismissResult = {},
    )

    // 7 November 2026, 20:13 UTC.
    private val lastImport = 1_794_082_380_000L
    private val connected = ShinyWallAccountState(isConnected = true, displayName = "lena.boulders", lastImportAtMs = lastImport)
    private val kilterConnected = KilterAccountState(isConnected = true, username = "lena_climbs", lastSync = "7. Nov., 19:40")

    private fun summary(r: ShinyWallImportResult) = formatShinyWallImportSummary(app, r)

    private fun pages(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        shoot("page-1-not-connected") { ImportsPage(ShinyWallAccountState()) }
        shoot("page-2-connected-never-imported") { ImportsPage(connected.copy(lastImportAtMs = null)) }
        shoot("page-3-checking") { ImportsPage(connected.copy(isImporting = true)) }
        shoot("page-4-imported") {
            ImportsPage(connected.copy(resultMessage = summary(ShinyWallImportResult(3, 1, 0, 0, 2))))
        }
        shoot("page-5-imported-again") {
            ImportsPage(connected.copy(resultMessage = summary(ShinyWallImportResult(0, 0, 4, 2, 2))))
        }
        shoot("page-6-empty-account") {
            ImportsPage(connected.copy(resultMessage = summary(ShinyWallImportResult(0, 0, 0, 0, 0))))
        }
        shoot("page-7-error-network") {
            ImportsPage(connected.copy(resultMessage = app.getString(R.string.shinywall_import_error_network), resultIsError = true))
        }
        shoot("page-8-session-expired") { ImportsPage(connected.copy(sessionExpired = true)) }
        shoot("page-9-both-connected") { ImportsPage(connected, kilterConnected) }
        shoot("page-10-only-deleted-entries") {
            ImportsPage(connected.copy(resultMessage = summary(ShinyWallImportResult(0, 0, 0, 0, 0, deleted = 1))))
        }
    }

    private fun overlays(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        val login = ShinyWallAccountState(showLoginSheet = true)
        shoot("sheet-1-login-empty") { ImportsPage(login) }
        shoot("sheet-2-login-error") {
            ImportsPage(
                login.copy(
                    loginEmail = "lena@example.org", loginPassword = "wrong-password",
                    loginError = app.getString(R.string.shinywall_auth_invalid_credentials),
                ),
            )
        }
        shoot("sheet-3-login-busy") {
            ImportsPage(login.copy(loginEmail = "lena@example.org", loginPassword = "secret-pass", isLoggingIn = true))
        }
        shoot("dialog-1-preview-new") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(5, 3, 1, 0, 0)))
        }
        shoot("dialog-2-preview-skipped") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(9, 3, 1, 2, 3)))
        }
        shoot("dialog-3-preview-nothing-new") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(4, 0, 0, 4, 0)))
        }
        shoot("dialog-4-preview-empty-account") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(0, 0, 0, 0, 0)))
        }
        shoot("dialog-5-preview-importing") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(5, 3, 1, 0, 0), isImporting = true))
        }
        shoot("dialog-6-preview-one-each") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(3, 1, 1, 1, 1)))
        }
        shoot("dialog-9-preview-deleted") {
            ImportsPage(connected.copy(preview = ShinyWallImportPreview(2, 0, 0, 1, 0, deleted = 1)))
        }
        shoot("sheet-4-sign-in-again") { ImportsPage(connected.copy(sessionExpired = true, showLoginSheet = true)) }
        shoot("dialog-7-disconnect") { ImportsPage(connected.copy(showDisconnectConfirm = true)) }
        val title = app.getString(R.string.shinywall_section_title)
        shoot("dialog-8-info", open = {
            compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, title))
                .performScrollTo().performClick()
        }) { ImportsPage(ShinyWallAccountState()) }
    }

    @Test
    @Config(qualifiers = "en-w411dp-h1600dp-xhdpi")
    fun pagesEnglish() = pages("en", 411, 1f)

    @Test
    @Config(qualifiers = "en-w411dp-h2400dp-xhdpi")
    fun pagesEnglishLargestFont() = pages("en", 411, 2f)

    @Test
    @Config(qualifiers = "en-w320dp-h1900dp-xhdpi")
    fun pagesEnglishNarrow() = pages("en", 320, 1f)

    @Test
    @Config(qualifiers = "en-w320dp-h3000dp-xhdpi")
    fun pagesEnglishNarrowLargestFont() = pages("en", 320, 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h1600dp-xhdpi")
    fun pagesGerman() = pages("de", 411, 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h2400dp-xhdpi")
    fun pagesGermanLargestFont() = pages("de", 411, 2f)

    @Test
    @Config(qualifiers = "de-w320dp-h1900dp-xhdpi")
    fun pagesGermanNarrow() = pages("de", 320, 1f)

    @Test
    @Config(qualifiers = "de-w320dp-h3000dp-xhdpi")
    fun pagesGermanNarrowLargestFont() = pages("de", 320, 2f)

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xhdpi")
    fun overlaysEnglish() = overlays("en", 411, 1f)

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-xhdpi")
    fun overlaysEnglishLargestFont() = overlays("en", 411, 2f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun overlaysEnglishNarrow() = overlays("en", 320, 1f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun overlaysEnglishNarrowLargestFont() = overlays("en", 320, 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun overlaysGerman() = overlays("de", 411, 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun overlaysGermanLargestFont() = overlays("de", 411, 2f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun overlaysGermanNarrow() = overlays("de", 320, 1f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun overlaysGermanNarrowLargestFont() = overlays("de", 320, 2f)
}
