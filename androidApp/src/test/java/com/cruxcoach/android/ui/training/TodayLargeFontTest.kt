package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.training.today.TodayScreen
import com.cruxcoach.android.ui.training.today.TodayViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Today at the largest font (phone check 2026-11-09, 320 dp and font size 2.0): the "Dein Tag" tiles cut their labels
 * off two in a row, so from font size 1.3 they go one under the other; at the normal size they stay two in a row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TodayLargeFontTest : AthleteScreenTest() {

    private fun renderAt(fontScale: Float) {
        repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) }
        val vm = loaded(TodayViewModel(service))
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                    TodayScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm)
                }
            }
        }
        scrollTo("today_list", hasTestTag("today_log_climbing"))
        scrollTo("today_list", hasTestTag("today_fuel"))
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun `at the largest font the day tiles go one under the other`() {
        renderAt(2f)
        val food = bounds("today_fuel")
        val water = bounds("today_water_add")
        assertTrue("water $water beside food $food", water.top >= food.bottom)
    }

    @Test
    fun `at the normal font the day tiles stay two in a row`() {
        renderAt(1f)
        val food = bounds("today_fuel")
        val water = bounds("today_water_add")
        assertTrue("water $water not beside food $food", water.top < food.bottom && water.left >= food.right)
    }
}
