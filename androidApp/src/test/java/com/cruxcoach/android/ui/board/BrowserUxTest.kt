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

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BrowserUxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `angle and Bluetooth remain directly reachable at 320dp`() {
        var angleOpened = false
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            BoardBrowserHeader(BoardBrowserHeaderContext("Kilter Original", "12x12"), false, 40,
                onAngle = { angleOpened = true }, onOpenMenu = {}, onBoardPicker = {}, onBluetooth = {},
                onFilter = {})
        } } }
        compose.onNodeWithTag("board_header_angle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("board_ble_button").assertIsDisplayed()
        compose.onNodeWithTag("board_filter_toggle").assertIsDisplayed()
        compose.onNodeWithTag("board_browser_home").assertIsDisplayed()
        compose.onNodeWithTag("board_browser_board_picker").assertIsDisplayed()
        compose.onNodeWithTag("board_header_overflow").assertDoesNotExist()
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

    @Test fun `filter and Bluetooth remain direct at 320dp and large font`() {
        var filtered = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(500.dp)) {
                    TourHost(remember { TourTargets() }, TourTarget.FILTER, R.string.tour_spotlight_filter, {}) {
                        Box(Modifier.fillMaxSize()) {
                            BoardBrowserHeader(BoardBrowserHeaderContext("Kilter Original", "12x12"), false, 40,
                                onAngle = {}, onOpenMenu = {}, onBoardPicker = {}, onBluetooth = {},
                                onFilter = { filtered = true })
                        }
                    }
                } }
            }
        }
        compose.onNodeWithTag("tour_spotlight").assertIsDisplayed()
        compose.onNodeWithTag("board_ble_button").assertIsDisplayed()
        compose.onNodeWithTag("board_browser_home").assertIsDisplayed()
        val family = compose.onNodeWithText("Kilter", substring = true, useUnmergedTree = true)
        family.assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        family.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // Text can retain a wider paragraph layout than its wrap-content node.
        // Check the painted line, not didOverflowWidth (paragraph width vs node).
        assertEquals(1, layout.lineCount)
        assertFalse(layout.isLineEllipsized(0))
        assertTrue("Family line ${layout.getLineLeft(0)}..${layout.getLineRight(0)} must fit ${layout.size.width}",
            layout.getLineLeft(0) >= 0f && layout.getLineRight(0) <= layout.size.width + 0.5f)
        compose.onNodeWithTag("board_filter_toggle").performTouchInput { click() }
        assertTrue(filtered)
    }

    @Test fun `quicklog spotlight permits both results but blocks the middle action`() {
        var attempts = 0
        var sends = 0
        var lights = 0
        compose.setContent { MaterialTheme {
            Box(Modifier.width(360.dp).height(500.dp)) {
                TourHost(remember { TourTargets() }, TourTarget.QUICK_ATTEMPT,
                    R.string.tour_spotlight_quicklog, {}, secondaryTarget = TourTarget.QUICK_SEND) {
                    Box(Modifier.fillMaxSize()) {
                        Button(onClick = { attempts++ }, modifier = Modifier.align(Alignment.BottomStart)
                            .tourTarget(TourTarget.QUICK_ATTEMPT).testTag("attempt")) { Text("Try") }
                        Button(onClick = { lights++ }, modifier = Modifier.align(Alignment.BottomCenter)
                            .testTag("light")) { Text("Light") }
                        Button(onClick = { sends++ }, modifier = Modifier.align(Alignment.BottomEnd)
                            .tourTarget(TourTarget.QUICK_SEND).testTag("send")) { Text("Top") }
                    }
                }
            }
        } }
        compose.onNodeWithTag("attempt").performTouchInput { click() }
        compose.onNodeWithTag("send").performTouchInput { click() }
        compose.onNodeWithTag("light").performTouchInput { click() }
        assertEquals(1, attempts)
        assertEquals(1, sends)
        assertEquals(0, lights)
    }

    @Test fun `logo menu guides to the real logbook and blocks unrelated destinations`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val tour = BrowserTour(context)
        tour.logged("saved-entry")
        var destination: String? = null
        compose.setContent { MaterialTheme {
            com.cruxcoach.android.ui.navigation.BrowserMainDrawer(tourVisible = true) { destination = it }
        } }
        compose.onNodeWithTag("menu_settings").performTouchInput { click() }
        compose.waitForIdle()
        assertNull(destination)
        compose.onNodeWithTag("menu_logbook").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(com.cruxcoach.android.ui.navigation.Routes.BOARD_LOGBOOK, destination)
        assertEquals(TourStep.ENTRY, tour.step())
        tour.move(TourStep.DONE)
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
