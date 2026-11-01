package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp")
class BoardStatsHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `accessible actions preserve period and reach long chart choices`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val selected = mutableStateOf(ActivityView.CALENDAR)
        var periodChanges = 0
        val stats = BoardLogbookStats(
            totalSends = 3,
            totalAttempts = 6,
            gradeOutcomes = listOf(GradeOutcomeEntry("6a", 15, 1, 2, 3)),
            activityMap = mapOf(java.time.LocalDate.now() to 2),
            weeklyVolume = listOf(WeeklyVolumeEntry(java.time.LocalDate.now(), 1, 2, 0, 0)),
        )
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                    Box(Modifier.fillMaxSize()) {
                        BoardStatsSheet(
                            stats = stats,
                            statsInterval = StatsTimeInterval.ALL,
                            gradeScale = GradeScale.V_SCALE,
                            activityView = selected.value,
                            onActivityViewSelect = { selected.value = it },
                            onIntervalSelect = { periodChanges++ },
                            onCustomDateRange = { _, _ -> periodChanges++ },
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        // Exercise accessibility actions in the modal window. Robolectric does
        // not reliably dispatch native touch events into nested dialog windows.
        // Physical-device touch coverage is separate from this fixture.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription(app.getString(
            R.string.action_show_info, app.getString(R.string.board_stats_title),
        )).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("info_dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.action_close)).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(0, periodChanges) }
        compose.onNodeWithText(app.getString(R.string.board_stats_grades_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.board_stats_activity))
            .performScrollTo().assertIsDisplayed().onParent()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText(app.getString(R.string.board_stats_weekly_volume))
            .performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(ActivityView.WEEKLY, selected.value) }
    }
}
