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
    fun theTrainingImageKeepsTheBestValueButNothingWorkedOutFromBodyWeight() {
        val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())
        val hang = benchmark("finger.max_hang", edgeMm = 20.0, loadKg = 20.0, durationS = 10.0)
        val hangSets = benchmark("finger.max_hang", edgeMm = 20.0, loadKg = 20.0, durationS = 10.0, sets = 3)
        val weighted = benchmark("pull.weighted_pull_up", loadKg = 20.0, reps = 5)
        val pullUps = benchmark("pull.pull_up", reps = 12)
        val pulldown = benchmark("pull.lat_pulldown", loadKg = 50.0, reps = 8)
        val training = TrainingContext(catalog, AthleteProfile(), 70.0)

        val shown = mutableMapOf<Benchmark, Pair<ReviewRow, ReviewRow>>()
        compose.setContent {
            listOf(hang, hangSets, weighted, pullUps, pulldown).forEach { b ->
                val review = reviewWith(b)
                shown[b] = bestValue(reviewPages(review, GradeScale.FRENCH, training, SharePrivacy.SCREEN)) to
                    bestValue(reviewPages(review, GradeScale.FRENCH, training, SharePrivacy()))
            }
        }
        compose.waitForIdle()

        shown.values.forEach { (screen, image) ->
            assertEquals(screen.value, image.value)
            assertFalse(image.detail!!, image.detail!!.contains("%") || image.detail!!.contains("BW"))
        }
        // A hang keeps its load, time, edge and sets; the 10-second max with the share of body weight stays on the phone.
        for (b in listOf(hang, hangSets)) {
            val (screen, image) = shown.getValue(b)
            assertTrue(screen.detail!!, screen.detail!!.contains("% BW"))
            assertEquals(screen.detail!!.substringBefore(" · 10-s max"), image.detail)
        }
        assertEquals("+20 kg · 10 s · 20 mm", shown.getValue(hang).second.detail)
        assertTrue(shown.getValue(hangSets).second.detail!!.startsWith("3 × 10 s"))
        // Load and reps stay; the estimated max with body weight in it goes.
        shown.getValue(weighted).let { (screen, image) ->
            assertTrue(screen.detail!!, screen.detail!!.contains("1RM"))
            assertEquals(screen.detail!!.substringBefore(" · est. 1RM"), image.detail)
        }
        // Reps alone, and a lifted weight without body weight, are the same on the image.
        assertEquals(shown.getValue(pullUps).first.detail, shown.getValue(pullUps).second.detail)
        assertEquals(shown.getValue(pulldown).first.detail, shown.getValue(pulldown).second.detail)
    }

    private fun benchmark(slug: String, edgeMm: Double? = null, loadKg: Double? = null, reps: Int? = null, durationS: Double? = null, sets: Int? = null) =
        Benchmark(
            id = slug, exerciseSlug = slug, edgeMm = edgeMm, loadKg = loadKg, reps = reps, durationS = durationS, sets = sets, bodyweightKg = 70.0,
            measuredAt = LocalDate(2026, 6, 1).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )

    private fun reviewWith(best: Benchmark) = YearReviewComputer.compute(
        YearReviewComputer.Input(
            entries = (1..10).map { i ->
                AscentWithClimb(
                    uuid = "e$i", climbUuid = "c$i", angle = 40, isMirror = false, bidCount = 1, quality = null, difficulty = null,
                    comment = null, climbedAt = "2026-03-${10 + i}T18:00:00", climbName = "Problem $i", climbFrames = "",
                    difficultyAverage = 20.0, boardBrand = "kilter",
                )
            },
            benchmarks = listOf(best),
        ),
        2026, LocalDate(2027, 1, 10), TimeZone.UTC,
    )

    private fun bestValue(pages: List<ReviewPage>): ReviewRow =
        pages.first { it.kind == ReviewPageKind.TRAINING }.rows.first { it.label == "Newest best value" }
}
