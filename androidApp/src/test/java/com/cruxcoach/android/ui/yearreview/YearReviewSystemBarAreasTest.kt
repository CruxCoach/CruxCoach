package com.cruxcoach.android.ui.yearreview

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import org.robolectric.annotation.GraphicsMode

/**
 * Seen on a phone with Android 15 and the light theme (build 1000041): the review was dark, but the
 * strips behind the status bar and the navigation bar stayed light, and their icons had turned
 * white. From Android 15 on the window ignores the bar colours; behind the see-through bars lies
 * what the app draws there, the theme's background, because the screens keep out of those areas.
 * So while the review shows, the theme darkens those two strips as well.
 *
 * The app here is built like the real one: the theme, its background over the whole window, and a
 * page that keeps out of the bars' areas. The test gives the window a status bar and a navigation
 * bar and looks at the pixels behind them. What it cannot show is the platform's part: that a
 * phone really ignores the bar colours from Android 15 on and leaves the bars see-through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("DEPRECATION") // Below Android 15 the bars take their colours from the window.
class YearReviewSystemBarAreasTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private var lightBackground = 0
    private lateinit var view: View
    private val window: Window get() = compose.activity.window

    @Test
    fun `from Android 15 the areas behind both bars are dark while the review shows, and the theme's come back after`() {
        showApp()
        assertEquals(lightBackground, behindStatusBar())
        assertEquals(lightBackground, behindNavigationBar())
        assertTrue(bars().isAppearanceLightStatusBars)

        open(true)
        assertEquals(DarkBackground.toArgb(), behindStatusBar())
        assertEquals(DarkSurface.toArgb(), behindNavigationBar())
        assertFalse(bars().isAppearanceLightStatusBars)
        assertFalse(bars().isAppearanceLightNavigationBars)
        assertEquals("the page between the bars is left alone", Page.toArgb(), pixel(STATUS + 8))

        open(false)
        assertEquals(lightBackground, behindStatusBar())
        assertEquals(lightBackground, behindNavigationBar())
        assertTrue(bars().isAppearanceLightStatusBars)
    }

    /** There the bars are not see-through: the window colours them, and the theme draws nothing behind them. */
    @Test
    @Config(sdk = [34])
    fun `below Android 15 the window colours the bars and nothing is drawn behind them`() {
        showApp()
        open(true)
        assertEquals(DarkBackground.toArgb(), window.statusBarColor)
        assertEquals(DarkSurface.toArgb(), window.navigationBarColor)
        assertEquals(lightBackground, behindStatusBar())
        assertEquals(lightBackground, behindNavigationBar())
    }

    /** The light theme over the whole window, as MainActivity sets it up, with a page that keeps out of the bars. */
    private fun showApp() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(DarkModeSetting.LIGHT) {
                lightBackground = MaterialTheme.colorScheme.background.toArgb()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
                        if (open) {
                            DarkSystemBars()
                            Box(Modifier.fillMaxSize().background(Page))
                        }
                    }
                }
            }
        }
        giveBars()
    }

    private fun open(value: Boolean) {
        open = value
        giveBars()
    }

    /** A status bar and a navigation bar for the window; a test window has none of its own. */
    private fun giveBars() {
        compose.waitForIdle()
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAVIGATION))
                .build()
            ViewCompat.dispatchApplyWindowInsets(window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun bars() = WindowCompat.getInsetsController(window, window.decorView)
    private fun behindStatusBar() = pixel(STATUS / 2)
    private fun behindNavigationBar() = pixel(view.height - NAVIGATION / 2)

    /** The colour the app draws in the middle of the row [y] of its window. */
    private fun pixel(y: Int): Int = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.getPixel(view.width / 2, y)
    }

    private companion object {
        const val STATUS = 48
        const val NAVIGATION = 96
        val Page = Color(0xFF224466)
    }
}
