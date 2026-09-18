package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cruxcoach.android.ui.onboarding.TourHint
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

    @Test fun `tour remains dismissible and actions scroll into view with large text`() {
        var ended = false
        var connected = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(320.dp).height(400.dp)) {
                    TourHint(R.string.tour_connect_title, R.string.tour_connect_body,
                        R.string.cd_board_connect, { connected = true }, { ended = true },
                        R.string.tour_later)
                } }
            }
        }
        compose.onNodeWithContentDescription("End tour").assertIsDisplayed()
        compose.onNodeWithText("Connect board").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(connected)
        compose.onNodeWithContentDescription("End tour").assertIsDisplayed().performClick()
        assertTrue(ended)
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
