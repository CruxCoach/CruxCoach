package com.cruxcoach.android.ui.training.bodymap

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/** The drawings compose in light and dark theme and describe themselves for screen readers. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BodyMapRenderTest {

    @get:Rule val compose = createComposeRule()

    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    @Test
    fun `body map, picker, grip and thumbnails render in both themes`() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        val pickup = catalog["finger.one_arm_pickup"]!!
        val pullUp = catalog["pull.weighted_pull_up"]!!
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column {
                    BodyMap(ExerciseBodyAreas.of(pullUp), Modifier.width(300.dp).height(240.dp))
                    BodyMapPicker(selected = BodyArea.FINGERS, onSelect = {})
                    GripPictogram(GripType.HALF_CRIMP, 20.0, Modifier.size(110.dp, 76.dp))
                    ExerciseThumb(pickup)
                    ExerciseThumb(pullUp)
                }
            }
        }
        fun check() {
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithContentDescription("Body map", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithContentDescription("Lats", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithContentDescription("Half crimp · 20 mm").fetchSemanticsNodes().isNotEmpty())
        }
        check()
        dark = true
        check()
    }
}
