package com.cruxcoach.android.spraywall

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.navigation.BrowserMainDrawer
import com.cruxcoach.android.ui.yearreview.ReviewPage
import com.cruxcoach.android.ui.yearreview.ReviewPageKind
import com.cruxcoach.android.ui.yearreview.TrainingContext
import com.cruxcoach.android.ui.yearreview.reviewPages
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** With [SprayWallFeature] off, spray walls show nowhere: not in the main menu, not in the year in review. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class SprayWallSwitchTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the main menu has spray walls only while the switch is on`() {
        var on by mutableStateOf(true)
        compose.setContent { MaterialTheme { BrowserMainDrawer(sprayWalls = on) {} } }
        compose.onNodeWithTag("menu_spray_walls").assertExists()

        on = false
        compose.waitForIdle()
        compose.onNodeWithTag("menu_spray_walls").assertDoesNotExist()
        compose.onNodeWithTag("menu_wall_games").assertExists()
    }

    @Test
    fun `the year in review mentions spray walls only while the switch is on`() {
        val review = YearReviewComputer.compute(
            YearReviewComputer.Input(
                entries = (1..10).map { i ->
                    AscentWithClimb(
                        uuid = "e$i", climbUuid = "c$i", angle = 40, isMirror = false, bidCount = 1, quality = null, difficulty = null,
                        comment = null, climbedAt = "2026-03-${10 + i}T18:00:00", climbName = "Problem $i", climbFrames = "",
                        difficultyAverage = 20.0, boardBrand = "kilter",
                    )
                },
            ),
            2026, LocalDate(2027, 1, 10), TimeZone.UTC,
        )
        val training = TrainingContext(null, null, null)
        var on: ReviewPage? = null
        var off: ReviewPage? = null
        compose.setContent {
            on = reviewPages(review, GradeScale.FRENCH, training, sprayWalls = true).first { it.kind == ReviewPageKind.INTRO }
            off = reviewPages(review, GradeScale.FRENCH, training, sprayWalls = false).first { it.kind == ReviewPageKind.INTRO }
        }
        compose.waitForIdle()

        assertNotNull(on!!.note)
        assertTrue(on!!.howCalculated!!.contains("Spray walls"))
        assertNull(off!!.note)
        assertFalse(off!!.howCalculated!!.contains("spray", ignoreCase = true))
    }
}
