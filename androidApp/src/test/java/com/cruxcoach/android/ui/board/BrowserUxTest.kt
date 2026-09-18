package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import com.cruxcoach.android.ui.onboarding.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import com.cruxcoach.android.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserUxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `angle and Bluetooth remain directly reachable at 320dp`() {
        var angleOpened = false
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            BoardBrowserHeader(BoardBrowserHeaderContext("Kilter Original", "12x12"), false, 40,
                onAngle = { angleOpened = true }, onOpenMenu = {}, onBoardPicker = {}, onBluetooth = {},
                onFilter = {}, onLogbook = {}, onLists = {}, onSettings = {}, onTour = {})
        } } }
        compose.onNodeWithTag("board_header_angle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("board_ble_button").assertIsDisplayed()
        compose.onNodeWithTag("board_filter_toggle").assertIsDisplayed()
        compose.onNodeWithTag("board_header_overflow").performClick()
        compose.onNodeWithTag("board_tour_replay").assertIsDisplayed()
        assertTrue(angleOpened)
    }

    @Test fun `spotlight passes real touch to the original button and stays skippable at large font`() {
        var ended = false
        var connected = 0
        var backgroundClicks = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(500.dp).padding(top = 24.dp)) {
                    TourHost(remember { TourTargets() }, TourTarget.BLUETOOTH,
                        R.string.tour_spotlight_connect, { ended = true }) {
                        Box(Modifier.fillMaxSize()) {
                            Button(onClick = { backgroundClicks++ }, modifier = Modifier.align(Alignment.Center)
                                .testTag("background_climb")) { Text("Climb") }
                            Button(onClick = { connected++ }, modifier = Modifier.align(Alignment.TopEnd)
                                .tourTarget(TourTarget.BLUETOOTH).testTag("real_connect")) { Text("Bluetooth") }
                        }
                    }
                } }
            }
        }
        compose.onNodeWithTag("tour_spotlight").assertIsDisplayed()
        compose.onNodeWithTag("background_climb").performTouchInput { click(); swipeUp() }
        assertEquals(0, backgroundClicks)
        compose.onNodeWithTag("real_connect").performTouchInput { click() }
        assertEquals(1, connected)
        compose.onNodeWithTag("tour_skip").assertIsDisplayed().performTouchInput { click() }
        assertTrue(ended)
    }

    @Test fun `spotlight follows overflow to the real filter menu item on a narrow header`() {
        var filtered = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(500.dp)) {
                    TourHost(remember { TourTargets() }, TourTarget.FILTER, R.string.tour_spotlight_filter, {}) {
                        Box(Modifier.fillMaxSize()) {
                            BoardBrowserHeader(BoardBrowserHeaderContext("Kilter Original", "12x12"), false, 40,
                                onAngle = {}, onOpenMenu = {}, onBoardPicker = {}, onBluetooth = {},
                                onFilter = { filtered = true }, onLogbook = {}, onLists = {}, onSettings = {}, onTour = {})
                        }
                    }
                } }
            }
        }
        compose.onNodeWithTag("tour_spotlight").assertIsDisplayed()
        compose.onNodeWithTag("board_header_overflow").performTouchInput { click() }
        compose.onNodeWithTag("tour_spotlight").assertDoesNotExist()
        compose.onNodeWithTag("board_settings_button").assertIsNotEnabled()
        compose.onNodeWithTag("board_filter_toggle").performTouchInput { click() }
        assertTrue(filtered)
    }

    @Test fun `filter header leaves controls visible with large text`() {
        val viewModel = io.mockk.mockk<BoardBrowserViewModel>(relaxed = true)
        io.mockk.every { viewModel.state } returns kotlinx.coroutines.flow.MutableStateFlow(BoardBrowserState())
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(500.dp)) {
                    BoardFilterScreen(viewModel, {})
                } }
            }
        }
        compose.onNodeWithText("Filters", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("board_filter_reset").assertIsDisplayed().performClick()
        io.mockk.verify { viewModel.clearAllBrowseFilters() }
        compose.onNodeWithTag("board_filter_show_results").assertIsDisplayed()
    }

    @Test fun `compact status keeps multi selection when reopening details`() {
        val selected = mutableStateOf(setOf(ClimbStatusFilter.NEW))
        compose.setContent { MaterialTheme { BoardStatusFilter(selected.value, compact = true) { selected.value = it } } }
        compose.onNodeWithTag("board_filter_status_new").assertDoesNotExist()
        compose.onNodeWithTag("board_filter_status_details").performClick()
        compose.onNodeWithTag("board_filter_status_new").assertIsSelected()
        compose.onNodeWithTag("board_filter_status_attempted").performClick()
        compose.onNodeWithTag("board_filter_status_details").performClick().performClick()
        compose.onNodeWithTag("board_filter_status_attempted").assertIsSelected()
    }

    @Test fun `angle set retains negative and nonuniform catalogue angles`() {
        assertEquals(listOf(-5, 15, 25, 40), browserAngleOptions(BrowserFilterState(boardBrand = BoardBrand.GRASSHOPPER.wireValue, angleChips = listOf(40, 15, -5, 25))))
        assertEquals(emptyList<Int>(), browserAngleOptions(BrowserFilterState(boardBrand = BoardBrand.TENSION.wireValue)))
        assertEquals((0..70 step 5).toList(), browserAngleOptions(BrowserFilterState()))
    }

    @Test fun `filter count excludes physical angle and sorting but includes hidden restrictions`() {
        assertEquals(0, BrowserFilterState(angle = 15).activeBrowseFilterCount())
        assertEquals(3, BrowserFilterState(benchmarkOnly = true, myClimbsOnly = true, originFilter = OriginFilter.CRUXCOACH).activeBrowseFilterCount())
    }
}
