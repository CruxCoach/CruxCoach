package com.cruxcoach.android.ui.devcontact

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.settings.AppInfoSection
import com.cruxcoach.android.ui.settings.SettingsDestinationRow
import com.cruxcoach.android.ui.settings.SettingsLayout
import com.cruxcoach.android.ui.settings.SettingsPage
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.ui.whatsnew.Release024WhatsNewDialog
import com.cruxcoach.android.ui.whatsnew.Release025WhatsNewDialog
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the community entry points: Settings → Community, its row
 * in the overview, the hint after a sent report and the line in What's new,
 * next to the About and Support pages and the 0.2.4 What's new for comparison.
 * Renders every screen dark and light, English and German, at 320 and 411 dp
 * and font scale 1.0 and 2.0 into `CRUXCOACH_UI_REVIEW_DIR`. Skipped unless
 * that variable is set.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityUiReviewTest(
    private val locale: String,
    private val dark: Boolean,
    private val width: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-w{2}-f{3}")
        fun matrix(): List<Array<Any>> = buildList {
            for (locale in listOf("en", "de")) for (dark in listOf(true, false))
                for (width in listOf(320, 411)) for (scale in listOf(1.0f, 2.0f)) add(arrayOf(locale, dark, width, scale))
        }
    }

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val variant = "$locale-${if (dark) "dark" else "light"}-w$width-f${if (fontScale > 1f) "2" else "1"}"

    /** Sets language, size and font scale before the compose rule starts its activity. */
    @get:Rule(order = 0)
    val device = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                Assume.assumeTrue(dir != null)
                val height = if (width == 320) 640 else 891
                RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-${if (dark) "night" else "notnight"}-xhdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun shoot(name: String, ready: () -> Unit = {}, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
        compose.waitForIdle()
        ready()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            // Dialogs live in windows of their own: dim the screen and draw them on top, as on the phone.
            windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
                canvas.drawColor(android.graphics.Color.argb(0x52, 0, 0, 0))
                val at = IntArray(2).also { w.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
            }
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$prefix$name-$variant.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    @Composable
    private fun Settings(page: SettingsPage?) = SettingsLayout(
        isLoading = false,
        openUpdates = false,
        startPage = page,
        onNavigateBack = {},
        onNavigateToAppShare = {},
    ) { current ->
        when (current) {
            SettingsPage.COMMUNITY -> SettingsSectionCard { CommunityJoinSection(onJoin = {}) }
            SettingsPage.SUPPORT -> SettingsSectionCard {
                DevContactSection(
                    unreadChat = 0, unreadBugs = 1, unreadFeatures = 0, crashReportOptIn = true,
                    onNavigateToChat = {}, onNavigateToBugReports = {}, onNavigateToFeatureRequests = {},
                    onNavigateToCrashReports = {}, onDonateClick = {}, onCrashReportOptInChange = {},
                )
            }
            SettingsPage.ABOUT -> {
                SettingsSectionCard {
                    AppInfoSection(
                        easterAnimationsUnlocked = false, isAnimating = false, isBleConnected = false,
                        onUnlockEasterAnimations = {}, onPlayEasterAnimation = {}, onStopAnimation = {},
                    )
                }
                SettingsDestinationRow(stringResource(R.string.settings_share_title), stringResource(R.string.settings_share_desc), Icons.Outlined.Share) {}
                SettingsDestinationRow(stringResource(R.string.settings_licenses_title), stringResource(R.string.settings_licenses_desc), Icons.Outlined.Gavel) {}
                SettingsDestinationRow(stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_desc), Icons.Outlined.Policy) {}
                SettingsDestinationRow(stringResource(R.string.settings_source_title), stringResource(R.string.settings_source_desc), Icons.Outlined.Code) {}
            }
            else -> Unit
        }
    }

    /** The overview, scrolled down to the "App & help" group with the Community row. */
    @Test
    fun overview() = shoot("overview", ready = {
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("settings_open_ABOUT"))
    }) { Settings(null) }

    @Test
    fun community() = shoot("community") { Settings(SettingsPage.COMMUNITY) }

    /** The bottom of the page, for large fonts on small screens. */
    @Test
    fun communityEnd() = shoot("community-end", ready = {
        val note = RuntimeEnvironment.getApplication().getString(R.string.community_join_note)
        compose.onNodeWithTag("settings_content").performScrollToNode(hasText(note))
    }) { Settings(SettingsPage.COMMUNITY) }

    @Test
    fun reportHintBug() = shoot("hint-bug") {
        CommunityReportHintDialog(
            stringResource(R.string.devcontact_bug_sent), stringResource(R.string.community_report_hint),
            onJoin = {}, onAlreadyMember = {}, onClose = {},
        )
    }

    @Test
    fun reportHintFeature() = shoot("hint-feature") {
        CommunityReportHintDialog(
            stringResource(R.string.devcontact_feature_sent), stringResource(R.string.community_report_hint_feature),
            onJoin = {}, onAlreadyMember = {}, onClose = {},
        )
    }

    @Test
    fun whatsNew025() = shoot("whatsnew-025") { Release025WhatsNewDialog(onDismiss = {}) }

    /** 0.2.4 screens to compare with. */
    @Test
    fun compareAbout() = shoot("cmp-about") { Settings(SettingsPage.ABOUT) }

    @Test
    fun compareSupport() = shoot("cmp-support") { Settings(SettingsPage.SUPPORT) }

    @Test
    fun compareWhatsNew024() = shoot("cmp-whatsnew-024") { Release024WhatsNewDialog(onDismiss = {}) }
}
