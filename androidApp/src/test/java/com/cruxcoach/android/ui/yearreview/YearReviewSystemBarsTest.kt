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
 * The review is dark in both themes. In the light theme the status bar stayed light with dark
 * icons above the dark page; while the review shows, both bars are dark with light icons, and
 * leaving it brings the theme's bars back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@Suppress("DEPRECATION") // The app's theme colours the bars through these window properties.
class YearReviewSystemBarsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the bars are dark while the review shows, and the light theme's come back after`() {
        var open by mutableStateOf(false)
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(DarkModeSetting.LIGHT) { if (open) DarkSystemBars() }
        }
        compose.waitForIdle()
        val window = (requireNotNull(view).context as Activity).window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        val themeStatus = window.statusBarColor
        val themeNavigation = window.navigationBarColor
        assertTrue(bars.isAppearanceLightStatusBars)

        open = true
        compose.waitForIdle()
        assertEquals(DarkBackground.toArgb(), window.statusBarColor)
        assertEquals(DarkSurface.toArgb(), window.navigationBarColor)
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)

        open = false
        compose.waitForIdle()
        assertEquals(themeStatus, window.statusBarColor)
        assertEquals(themeNavigation, window.navigationBarColor)
        assertTrue(bars.isAppearanceLightStatusBars)
    }
}
