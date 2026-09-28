package com.cruxcoach.android.ui.onboarding

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourSkipLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `top target leaves a prominent unobstructed exit below at large font`() = checkLayout(false)
    @Test fun `bottom target reserves exit space above the hint at large font`() = checkLayout(true)

    private fun checkLayout(bottom: Boolean) {
        var ended = 0
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                CruxCoachTheme(DarkModeSetting.DARK) {
                    Surface {
                        TourHost(remember { TourTargets() }, TourTarget.BLUETOOTH,
                            R.string.tour_spotlight_connect, { ended++ }) {
                            Box(Modifier.fillMaxSize()) {
                                Button(onClick = {}, modifier = Modifier
                                    .align(if (bottom) Alignment.BottomCenter else Alignment.TopCenter)
                                    .tourTarget(TourTarget.BLUETOOTH).testTag("target")) { Text("Bluetooth") }
                            }
                        }
                    }
                }
            }
        }
        val skip = compose.onNodeWithTag("tour_skip").assertIsDisplayed()
        val skipBounds = skip.fetchSemanticsNode().boundsInRoot
        val targetBounds = compose.onNodeWithTag("target").fetchSemanticsNode().boundsInRoot
        val app = ApplicationProvider.getApplicationContext<Application>()
        val hintBounds = compose.onNodeWithText(app.getString(R.string.tour_spotlight_connect)).fetchSemanticsNode().boundsInRoot
        assertFalse(skipBounds.overlaps(targetBounds))
        assertFalse(skipBounds.overlaps(hintBounds))
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(directory).mkdirs()
                java.io.File(directory, "tour-exit-$bottom.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        skip.performTouchInput { click() }
        assertEquals(1, ended)
    }
}
