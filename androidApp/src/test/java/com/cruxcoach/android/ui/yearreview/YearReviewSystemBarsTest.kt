package com.cruxcoach.android.ui.yearreview

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
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
 * the page colours), and leaving it brings the theme's bars back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@Suppress("DEPRECATION") // The app's theme colours the bars through these window properties.
class YearReviewSystemBarsTest {
    @get:Rule val compose = createComposeRule()

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

    /** The light theme with the review closed; the returned function opens or closes it. */
    private fun show(onWindow: (android.view.Window) -> Unit = {}): Pair<() -> WindowInsetsControllerCompat, (Boolean) -> Unit> {
        var open by mutableStateOf(false)
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(DarkModeSetting.LIGHT) { if (open) DarkSystemBars() }
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
