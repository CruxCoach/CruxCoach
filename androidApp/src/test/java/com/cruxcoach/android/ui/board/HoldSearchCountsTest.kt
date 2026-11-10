package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hold search counts one hold and one climb in the singular. The filter banner read
 * "1 Holds → 12 Climbs" and "1 Griffe → 12 Boulder", the sheet "1 climbs found".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class HoldSearchCountsTest {
    @get:Rule val compose = createComposeRule()

    private fun banner(holds: Int, climbs: Int, zone: Boolean = false) = compose.setContent {
        MaterialTheme {
            HoldSearchActionBar(
                holdFilterActive = true,
                heatmapActive = false,
                selectedCount = holds,
                zoneActive = zone,
                matchCount = climbs,
                onOpenSheet = {},
                onClearFilter = {},
            )
        }
    }

    private fun found(climbs: Int): String =
        ApplicationProvider.getApplicationContext<Application>().resources
            .getQuantityString(R.plurals.board_holdsearch_climbs_found, climbs, climbs)

    @Test fun `one hold and several climbs`() {
        banner(holds = 1, climbs = 12)
        compose.onNodeWithText("1 hold → 12 climbs").assertIsDisplayed()
    }

    @Test fun `several holds and one climb`() {
        banner(holds = 3, climbs = 1)
        compose.onNodeWithText("3 holds → 1 climb").assertIsDisplayed()
    }

    @Test fun `an area alone counts its climbs`() {
        banner(holds = 0, climbs = 1, zone = true)
        compose.onNodeWithText("Area → 1 climb").assertIsDisplayed()
    }

    @Test fun `holds inside an area count like holds alone`() {
        banner(holds = 2, climbs = 0, zone = true)
        compose.onNodeWithText("2 holds → 0 climbs").assertIsDisplayed()
    }

    @Test fun `the sheet's result line`() {
        assertEquals("1 climb found", found(1))
        assertEquals("0 climbs found", found(0))
        assertEquals("12 climbs found", found(12))
    }

    @Test @Config(qualifiers = "de")
    fun `German counts one Griff and several Griffe`() {
        banner(holds = 1, climbs = 12)
        compose.onNodeWithText("1 Griff → 12 Boulder").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "de")
    fun `German with several holds, an area and the sheet's line`() {
        banner(holds = 0, climbs = 1, zone = true)
        compose.onNodeWithText("Bereich → 1 Boulder").assertIsDisplayed()
        assertEquals("1 Boulder gefunden", found(1))
        assertEquals("12 Boulder gefunden", found(12))
    }
}
