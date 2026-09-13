package com.cruxcoach.android.ui.map

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.*
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h720dp")
class MapStatsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `map filters do not hide other brands from overall statistics`() {
        val locations = listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.TENSION).map { brand ->
            BoardLocation(id = brand.wireValue, name = brand.displayName, lat = 0.0, lng = 0.0,
                address = null, city = null, countryCode = "DE", phone = null, email = null,
                url = null, instagram = null, layoutName = if (brand == BoardBrand.MOONBOARD) "2024" else null,
                layoutId = null, sizeLabel = null, productSizeId = null,
                accessType = AccessType.PUBLIC, adjustability = Adjustability.UNKNOWN,
                fixedAngle = null, frameMaker = null, boardBrand = brand)
        }
        compose.setContent {
            CruxCoachTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    StatsScreen(MapState(unfilteredLocations = locations,
                        unfilteredStats = MapStats.from(locations),
                        filteredLocations = locations.take(1), stats = MapStats.from(locations.take(1))))
                }
            }
        }
        compose.onNodeWithText("MoonBoard").assertIsDisplayed()
        compose.onNodeWithText("Tension").assertIsDisplayed()
        compose.onNodeWithTag("map_statistics").performScrollToNode(hasText("MoonBoard · 2024"))
        compose.onNodeWithText("MoonBoard · 2024").assertIsDisplayed()
        compose.onNodeWithTag("map_statistics").performScrollToNode(hasText("Tension · Layout unbekannt"))
        compose.onNodeWithText("Tension · Layout unbekannt").assertIsDisplayed()
    }
}
