package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A catalogue heatmap reads every climb of the board. While it does, the card says that it counts, not
 * that there is no data (RC1 walk 2026-11-16: "No data" for minutes on the phone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h2000dp")
class BoardStatsHeatmapLoadingTest {
    @get:Rule val compose = createComposeRule()

    private val kilter = BoardConstants.HeatmapBoardOption("kilter", 1, 10, "Kilter Original")

    @Test
    fun theHeatmapCardSaysItCountsWhileLoadingAndNoDataOnlyAfter() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val loading = mutableStateOf(true)
        // The loading sign spins forever; move the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BoardStatsSheetContent(
                    stats = BoardLogbookStats(totalSends = 1, totalAttempts = 1),
                    statsInterval = StatsTimeInterval.ALL,
                    gradeScale = GradeScale.FRENCH,
                    heatmapMode = HeatmapMode.GLOBAL,
                    heatmapLoading = loading.value,
                    placements = mapOf(1145 to BoardPlacement(1145L, 1145L, 1L, 8L, 8L)),
                    boardSize = BoardSize(
                        id = 10L, productId = 1L, name = "12x12",
                        edgeLeft = 1, edgeRight = 144, edgeBottom = 1, edgeTop = 156,
                        imageFilename = null,
                    ),
                    heatmapBoardOptions = listOf(kilter),
                    heatmapBoardSelection = kilter,
                    onIntervalSelect = {},
                    onCustomDateRange = { _, _ -> },
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val empty = app.getString(R.string.board_stats_heatmap_empty)
        assertEquals(1, compose.onAllNodesWithTag("board_stats_heatmap_loading").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(empty).fetchSemanticsNodes().size)

        loading.value = false
        compose.mainClock.advanceTimeBy(500)
        assertEquals(0, compose.onAllNodesWithTag("board_stats_heatmap_loading").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText(empty).fetchSemanticsNodes().size)
    }
}
