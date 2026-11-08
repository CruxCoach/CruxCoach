package com.cruxcoach.android.ui.yearreview

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What a shared image of the year in review (#64) leaves out that the screen shows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class YearReviewPrivacyTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun theTrainingImageShowsTheExerciseAndTheAddedLoadButNoBodyWeight() {
        val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())
        val hang = Benchmark(
            id = "b1", exerciseSlug = "finger.max_hang", edgeMm = 20.0, loadKg = 20.0, durationS = 10.0, bodyweightKg = 70.0,
            measuredAt = LocalDate(2026, 6, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        val entries = (1..10).map { i ->
            AscentWithClimb(
                uuid = "e$i", climbUuid = "c$i", angle = 40, isMirror = false, bidCount = 1, quality = null, difficulty = null,
                comment = null, climbedAt = "2026-03-${10 + i}T18:00:00", climbName = "Problem $i", climbFrames = "",
                difficultyAverage = 20.0, boardBrand = "kilter",
            )
        }
        val review = YearReviewComputer.compute(YearReviewComputer.Input(entries = entries, benchmarks = listOf(hang)), 2026, LocalDate(2027, 1, 10), TimeZone.UTC)
        val training = TrainingContext(catalog, AthleteProfile(), 70.0)

        lateinit var screen: ReviewRow
        lateinit var image: ReviewRow
        compose.setContent {
            screen = bestValue(reviewPages(review, GradeScale.FRENCH, training, SharePrivacy.SCREEN))
            image = bestValue(reviewPages(review, GradeScale.FRENCH, training, SharePrivacy()))
        }
        compose.waitForIdle()

        // The phone shows the whole best value, with the share of body weight.
        assertTrue(screen.detail!!, screen.detail!!.contains("%"))
        assertEquals(screen.value, image.value)
        // The image only the exercise and what was added: no total, no share of body weight.
        assertEquals("+20 kg", image.detail)
        assertFalse(image.detail!!.contains("90"))
    }

    private fun bestValue(pages: List<ReviewPage>): ReviewRow =
        pages.first { it.kind == ReviewPageKind.TRAINING }.rows.first { it.label == "Newest best value" }
}
