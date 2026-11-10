package com.cruxcoach.android.ui.yearreview

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.DarkSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The review is dark in both themes. In the light theme the status bar kept dark icons above
 * the dark page; while the review shows, the icons are light (and below Android 15 the bars take
 * the page colours), and leaving it brings the theme's bars back. The theme owns the bars and
 * the review only asks for dark ones, so neither the theme recomposing nor a review that is there
 * from the first frame (the activity was recreated) leaves them wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@Suppress("DEPRECATION") // The app's theme colours the bars through these window properties.
class YearReviewSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The light theme's status bar colour, read inside the theme. */
    private var themeBackground = 0

    @Test
    fun `the icons are light while the review shows, and the light theme's come back after`() {
        val (bars, open) = show()
        assertTrue(bars().isAppearanceLightStatusBars)

        open(true)
        assertFalse(bars().isAppearanceLightStatusBars)
        assertFalse(bars().isAppearanceLightNavigationBars)

        open(false)
        assertTrue(bars().isAppearanceLightStatusBars)
    }

    /** Below Android 15 the bars take a colour of their own; from 15 on the dark page shows through them. */
    @Test
    @Config(sdk = [34])
    fun `below Android 15 the bars take the page colours, and the theme's come back after`() {
        var window: android.view.Window? = null
        val (_, open) = show { window = it }
        val themeStatus = window!!.statusBarColor
        val themeNavigation = window!!.navigationBarColor

        open(true)
        assertEquals(DarkBackground.toArgb(), window!!.statusBarColor)
        assertEquals(DarkSurface.toArgb(), window!!.navigationBarColor)

        open(false)
        assertEquals(themeStatus, window!!.statusBarColor)
        assertEquals(themeNavigation, window!!.navigationBarColor)
    }

    /**
     * The stored dark-mode setting arrives after the first frame, and the theme sets the bars again
     * whenever it recomposes. That must not undo the review's bars.
     */
    @Test
    fun `the theme recomposing while the review shows leaves its icons light`() {
        var setting by mutableStateOf(DarkModeSetting.SYSTEM)
        val (bars, open) = show(setting = { setting })
        open(true)
        assertFalse(bars().isAppearanceLightStatusBars)

        setting = DarkModeSetting.LIGHT
        compose.waitForIdle()
        assertFalse(bars().isAppearanceLightStatusBars)
        assertFalse(bars().isAppearanceLightNavigationBars)

        open(false)
        assertTrue(bars().isAppearanceLightStatusBars)
    }

    @Test
    @Config(sdk = [34])
    fun `below Android 15 the theme recomposing leaves the page colours`() {
        var setting by mutableStateOf(DarkModeSetting.SYSTEM)
        var window: android.view.Window? = null
        val (_, open) = show(setting = { setting }) { window = it }
        open(true)

        setting = DarkModeSetting.LIGHT
        compose.waitForIdle()
        assertEquals(DarkBackground.toArgb(), window!!.statusBarColor)
        assertEquals(DarkSurface.toArgb(), window!!.navigationBarColor)
    }

    /** The activity was recreated with the review open: it is there in the first pass, together with the theme. */
    @Test
    fun `a review that is open from the first frame has light icons, and closing brings the theme's back`() {
        val (bars, open) = show(openAtStart = true)
        assertFalse(bars().isAppearanceLightStatusBars)
        assertFalse(bars().isAppearanceLightNavigationBars)

        open(false)
        assertTrue(bars().isAppearanceLightStatusBars)
    }

    @Test
    @Config(sdk = [34])
    fun `below Android 15 a review open from the first frame takes the page colours, and closing gives the bars back`() {
        val window = compose.activity.window
        val ownNavigation = window.navigationBarColor
        val (_, open) = show(openAtStart = true)
        assertEquals(DarkBackground.toArgb(), window.statusBarColor)
        assertEquals(DarkSurface.toArgb(), window.navigationBarColor)

        open(false)
        assertEquals(themeBackground, window.statusBarColor)
        assertEquals(ownNavigation, window.navigationBarColor)
    }

    /** The light theme (or [setting]) with the review closed (or open); the returned function opens or closes it. */
    private fun show(
        openAtStart: Boolean = false,
        setting: () -> DarkModeSetting = { DarkModeSetting.LIGHT },
        onWindow: (android.view.Window) -> Unit = {},
    ): Pair<() -> WindowInsetsControllerCompat, (Boolean) -> Unit> {
        var open by mutableStateOf(openAtStart)
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(setting()) {
                themeBackground = MaterialTheme.colorScheme.background.toArgb()
                if (open) DarkSystemBars()
            }
        }
        compose.waitForIdle()
        val window = (requireNotNull(view).context as Activity).window
        onWindow(window)
        return Pair(
            { WindowCompat.getInsetsController(window, window.decorView) },
            { value -> open = value; compose.waitForIdle() },
        )
    }
}
