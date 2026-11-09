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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With [SprayWallFeature] off, spray walls show nowhere: not in the year in review, and in the main menu only while
 * something of mine may be on the relays (that part in [SprayWallRepositoryTest]).
 */
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
        val input = YearReviewComputer.Input(
            entries = (1..10).map { i ->
                AscentWithClimb(
                    uuid = "e$i", climbUuid = "c$i", angle = 40, isMirror = false, bidCount = 1, quality = null, difficulty = null,
                    comment = null, climbedAt = "2026-03-${10 + i}T18:00:00", climbName = "Problem $i", climbFrames = "",
                    difficultyAverage = 20.0, boardBrand = "kilter",
                )
            },
        )
        // The finished year and the year so far each have their own text, with and without spray walls.
        val final = YearReviewComputer.compute(input, 2026, LocalDate(2027, 1, 10), TimeZone.UTC)
        val soFar = YearReviewComputer.compute(input, 2026, LocalDate(2026, 6, 1), TimeZone.UTC)
        assertTrue(final.isFinal)
        assertFalse(soFar.isFinal)
        val training = TrainingContext(null, null, null)
        val intros = mutableMapOf<Pair<Boolean, Boolean>, ReviewPage>()
        compose.setContent {
            for (review in listOf(final, soFar)) for (on in listOf(true, false)) {
                intros[review.isFinal to on] = reviewPages(review, GradeScale.FRENCH, training, sprayWalls = on).first { it.kind == ReviewPageKind.INTRO }
            }
        }
        compose.waitForIdle()

        for (isFinal in listOf(true, false)) {
            val on = intros.getValue(isFinal to true)
            val off = intros.getValue(isFinal to false)
            assertNotNull(on.note)
            assertTrue(on.howCalculated!!.contains("Spray walls"))
            assertNull(off.note)
            assertFalse(off.howCalculated!!.contains("spray", ignoreCase = true))
            for (page in listOf(on, off)) assertEquals(!isFinal, page.howCalculated!!.contains("The year is not over yet"))
        }
    }
}
