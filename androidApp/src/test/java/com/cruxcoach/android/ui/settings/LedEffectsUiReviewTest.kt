package com.cruxcoach.android.ui.settings

import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.led.LedEffectStatus
import com.cruxcoach.android.led.LedEffectsState
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the LED effects: the card on the board settings page in each
 * of its states below the 0.2.4 "All boards" card, the flashing-light notice, the
 * info texts and the climb menu's quick pick, into `CRUXCOACH_UI_REVIEW_DIR`, one
 * folder per language, width and font size, dark and light. Pages render on a
 * tall screen so the whole card shows; dialogs and the sheet on a real phone
 * height. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LedEffectsUiReviewTest {

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

    /** Shows [content] in both themes; [open] taps what opens a dialog or sheet first. */
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

    private fun notifications(enabled: Boolean) =
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(enabled)

    /** The board settings page as the app lays it out: the 0.2.4 "All boards" card, then the LED effects. */
    @Composable
    private fun BoardPage(state: LedEffectsState) {
        SettingsLayout(
            isLoading = false,
            openUpdates = false,
            startPage = SettingsPage.BOARD,
            onNavigateBack = {},
            onNavigateToAppShare = {},
        ) {
            SettingsSectionCard {
                SettingsGroupHeader(stringResource(R.string.settings_group_all_boards))
                BoardSendModeSection(BoardSendMode.AUTOMATIC, BoardSendMode.EXPLICIT, {}, {})
                HorizontalDivider()
                BleAutoDisconnectSection(60, {})
            }
            SettingsSectionCard {
                LedEffectsSection(
                    state = state, onSelect = {}, onKeepRunningChange = {}, onStrobeNoticeSeen = {}, onConnect = {},
                )
            }
        }
    }

    @Composable
    private fun QuickSheet(state: LedEffectsState) =
        LedEffectQuickSheet(
            state = state, onSelect = {}, onStrobeNoticeSeen = {}, onConnect = {}, onSendAgain = {}, onDismiss = {},
        )

    private val fresh = LedEffectsState()
    private val running = LedEffectsState(effectId = "pulse", status = LedEffectStatus.RUNNING, boardShowsEffects = true)

    private fun pages(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        shoot("page-1-fresh") { BoardPage(fresh) }
        shoot("page-2-running") { BoardPage(running) }
        shoot("page-3-waiting") { BoardPage(running.copy(status = LedEffectStatus.WAITING_FOR_CLIMB)) }
        shoot("page-4-no-board") {
            BoardPage(LedEffectsState(effectId = "rainbow_wave", status = LedEffectStatus.NO_BOARD))
        }
        shoot("page-5-unsupported") {
            BoardPage(LedEffectsState(effectId = "rainbow_wave", status = LedEffectStatus.UNSUPPORTED_BOARD))
        }
        shoot("page-6-paused") { BoardPage(running.copy(status = LedEffectStatus.PAUSED)) }
        shoot("page-7-ended") { BoardPage(running.copy(status = LedEffectStatus.ENDED)) }
        shoot("page-8-strobe-remove-animations") {
            BoardPage(
                running.copy(
                    effectId = "two_phase_strobe", strobeNoticeSeen = true, systemAnimationsRemoved = true,
                ),
            )
        }
        notifications(false)
        shoot("page-9-background-no-notifications") { BoardPage(running.copy(keepRunningInBackground = true)) }
        notifications(true)
        shoot("page-10-background-on") { BoardPage(running.copy(keepRunningInBackground = true)) }
    }

    private fun overlays(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        val title = app.getString(R.string.settings_led_anim_title)
        val tap = { tag: String -> compose.onNodeWithTag(tag).performScrollTo().performClick() }
        val info = { label: String ->
            compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, label))
                .performScrollTo().performClick()
        }
        shoot("dialog-1-strobe", open = { tap("led_effect_two_phase_strobe") }) { BoardPage(running) }
        shoot(
            "dialog-2-strobe-remove-animations",
            open = { tap("led_effect_chaotic_strobe") },
        ) { BoardPage(running.copy(strobeNoticeSeen = true, systemAnimationsRemoved = true)) }
        shoot("dialog-3-info", open = { info(title) }) { BoardPage(running) }
        shoot("sheet-1-running") { QuickSheet(running) }
        shoot("sheet-2-ended") { QuickSheet(running.copy(status = LedEffectStatus.ENDED)) }
        shoot("sheet-3-strobe-dialog", open = { tap("led_effect_two_phase_strobe") }) { QuickSheet(running) }
    }

    @Test
    @Config(qualifiers = "en-w411dp-h2000dp-xhdpi")
    fun pagesEnglish() = pages("en", 411, 1f)

    @Test
    @Config(qualifiers = "en-w411dp-h2600dp-xhdpi")
    fun pagesEnglishLargestFont() = pages("en", 411, 2f)

    @Test
    @Config(qualifiers = "en-w320dp-h2400dp-xhdpi")
    fun pagesEnglishNarrow() = pages("en", 320, 1f)

    @Test
    @Config(qualifiers = "en-w320dp-h3200dp-xhdpi")
    fun pagesEnglishNarrowLargestFont() = pages("en", 320, 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h2000dp-xhdpi")
    fun pagesGerman() = pages("de", 411, 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h2600dp-xhdpi")
    fun pagesGermanLargestFont() = pages("de", 411, 2f)

    @Test
    @Config(qualifiers = "de-w320dp-h2400dp-xhdpi")
    fun pagesGermanNarrow() = pages("de", 320, 1f)

    @Test
    @Config(qualifiers = "de-w320dp-h3200dp-xhdpi")
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
