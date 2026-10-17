package com.cruxcoach.android.ui.map

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h891dp")
@OptIn(ExperimentalMaterial3Api::class)
class MapScreenLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val boards = listOf(
        mapBoard(
            "k", 48.1400, 11.5800, name = "Boulderwelt München", url = "https://example.com",
            sizeLabel = "12x12", productSizeId = 10, adjustability = Adjustability.ADJUSTABLE,
        ),
        mapBoard(
            "m", 48.1400, 11.5800, name = "Boulderwelt München", brand = BoardBrand.MOONBOARD, layoutId = 5,
            access = AccessType.PUBLIC, hasLed = true, fixedAngle = 40,
        ),
        mapBoard("t", 48.1300, 11.7000, name = "Einstein", brand = BoardBrand.TENSION),
    )
    private val info = mapOf(
        venueKey(48.14, 11.58) to MapVenueInfo(
            hours = VenueHours.parse(List(5) { "10:00-23:00" } + List(2) { "09:00-22:00" }),
        )
    )
    private val venues = groupIntoVenues(boards, info)
    private val state = MapState(
        unfilteredLocations = boards,
        unfilteredVenues = venues,
        filteredLocations = boards,
        filteredVenues = venues,
        isLoading = false,
        boardInfo = mapOf("k" to MapBoardInfo(minAngle = 0, maxAngle = 70, angleStep = 5)),
    )

    private fun show(
        selected: MapVenue? = null,
        mapState: MapState = state,
        onSelect: (MapVenue) -> Unit = {},
        onToggleBrand: (BoardBrand) -> Unit = {},
        onBrowse: (BoardBrand, Int, Int?) -> Unit = { _, _, _ -> },
    ) {
        compose.setContent {
            CruxCoachTheme {
                Layout(selected, mapState, onSelect, onToggleBrand, onBrowse)
            }
        }
    }

    @Composable
    private fun Layout(
        selected: MapVenue?,
        mapState: MapState,
        onSelect: (MapVenue) -> Unit,
        onToggleBrand: (BoardBrand) -> Unit,
        onBrowse: (BoardBrand, Int, Int?) -> Unit,
    ) {
        MapScreenLayout(
            state = mapState,
            inView = VenuesInView(mapState.filteredVenues.size, mapState.filteredVenues),
            selectedVenue = selected,
            search = MapSearchUi(),
            scaffoldState = rememberBottomSheetScaffoldState(),
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onQueryChange = {},
            onSearchResult = {},
            onOpenStats = {},
            onOpenFilters = {},
            onSelectAllBrands = {},
            onToggleBrand = onToggleBrand,
            onSelectVenue = onSelect,
            onCloseVenue = {},
            onBrowseClimbs = onBrowse,
            onResetFilters = {},
            onOverlayHeight = {},
            onPeekHeight = { _, _ -> },
        ) { Box(Modifier.fillMaxSize().background(Color.LightGray)) }
    }

    @Test
    fun `the sheet lists the places in view and a tap selects one`() {
        var picked: MapVenue? = null
        show(onSelect = { picked = it })
        compose.onNodeWithText("2 Orte mit Boards im Ausschnitt").assertIsDisplayed()
        compose.onNodeWithText("Boulderwelt München").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("Boulderwelt München", picked?.name)
    }

    @Test
    fun `brand menu works as legend and filter`() {
        var toggled: BoardBrand? = null
        show(onToggleBrand = { toggled = it })
        compose.onNodeWithTag("map_brand_chip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("map_brand_menu").assertIsDisplayed()
        compose.onNodeWithTag("map_brand_tension").performClick()
        assertEquals(BoardBrand.TENSION, toggled)
    }

    @Test
    fun `a selected place shows its boards, angles and published hours`() {
        var browsed: Pair<BoardBrand, Int>? = null
        show(selected = venues.first { it.boards.size == 2 }, onBrowse = { b, l, _ -> browsed = b to l })
        compose.onNodeWithTag("map_venue_name").assertIsDisplayed()
        compose.onNodeWithText("Route").assertIsDisplayed()
        compose.onNodeWithText("Website").assertIsDisplayed()
        compose.onNodeWithText("12x12, with Kickboard · verstellbar 0–70° in 5°-Schritten").assertExists()
        compose.onNodeWithText("Kommerziell · LED · 40°").assertExists()
        compose.onNodeWithText("Mo–Fr").assertExists()
        compose.onNodeWithText("10:00–23:00").assertExists()
        // Kilter and MoonBoard both have a catalogue: one button per board.
        compose.onAllNodesWithText("Climbs für dieses Board ansehen").assertCountEquals(2)
    }

    @Test
    fun `filters that hide everything offer a reset`() {
        show(mapState = state.copy(filteredLocations = emptyList(), filteredVenues = emptyList()))
        compose.onNodeWithText("Keine Boards passen zu deinen Filtern").assertIsDisplayed()
        compose.onNodeWithText("Zurücksetzen").assertIsDisplayed()
    }
}
