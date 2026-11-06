package com.cruxcoach.android.ui.yearreview

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.DarkSurface
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.yearreview.TaggedSend
import com.cruxcoach.domain.yearreview.YearReview
import com.cruxcoach.domain.yearreview.YearReviewComputer
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * Design review of the year in review: renders every page, the list view and
 * every share image of a made-up year on three boards into
 * `CRUXCOACH_UI_REVIEW_DIR`, in English and German, at normal and the largest
 * font size. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YearReviewUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private val review: YearReview = run {
        val random = Random(64)
        var id = 0
        val entries = mutableListOf<AscentWithClimb>()
        val boards = listOf("kilter" to 40L, "tension" to 50L, "moonboard" to 40L)
        var day = LocalDate(2025, 1, 2)
        while (day <= LocalDate(2026, 12, 20)) {
            if (random.nextDouble() < 0.3) {
                val (board, angle) = boards[listOf(0, 0, 0, 1, 1, 2).random(random)]
                repeat(random.nextInt(4, 10)) { i ->
                    val climb = "c${random.nextInt(0, 400)}"
                    val difficulty = (14 + random.nextInt(0, 8) + (day.year - 2025) * 2).toDouble()
                    entries += AscentWithClimb(
                        uuid = "e${id++}", climbUuid = climb, angle = angle, isMirror = false,
                        bidCount = random.nextInt(1, 6).toLong(), quality = null, difficulty = null, comment = null,
                        climbedAt = "${day}T18:${(10 + i).toString().padStart(2, '0')}:00", climbName = "Problem ${climb.drop(1)} Crimpfest",
                        climbFrames = "", difficultyAverage = difficulty, isSend = random.nextDouble() < 0.8, boardBrand = board,
                    )
                }
            }
            day = day.plus(1, DateTimeUnit.DAY)
        }
        val workouts = (0 until 30).map { i ->
            val d = LocalDate(2026, 1, 5).plus(i * 11, DateTimeUnit.DAY)
            Workout("w$i", startedAt = i * 100_000_000L, endedAt = i * 100_000_000L + 3_000_000L, day = d.toString(), updatedAt = 0)
        }
        val tagged = listOf(listOf("CRIMP"), listOf("CRIMP", "PINCH"), listOf("SLOPER"), listOf("CRIMP"), listOf("JUG", "CRIMP"), listOf("UNDERCLING"))
            .mapIndexed { i, tags -> TaggedSend(LocalDate(2026, 3, 1).plus(i * 9, DateTimeUnit.DAY).toString(), tags) }
        YearReviewComputer.compute(
            YearReviewComputer.Input(entries = entries, workouts = workouts, taggedSends = tagged), 2026, LocalDate(2027, 1, 10), TimeZone.UTC,
        )
    }

    private var shown: @Composable () -> Unit by mutableStateOf({})
    private var view: android.view.View? = null
    private var pages: List<ReviewPage> = emptyList()
    private var shared: List<ReviewPage> = emptyList()

    /** One composition per test (the rule allows only one); [shoot] swaps what it shows. */
    private fun start(fontScale: Float) {
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CruxCoachTheme(DarkModeSetting.DARK) {
                    val context = TrainingContext(null, null, null)
                    pages = reviewPages(review, GradeScale.FRENCH, context)
                    shared = reviewPages(review, GradeScale.FRENCH, context, SharePrivacy(problemNames = false, exactDates = false))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DarkBackground, DarkSurface)))) { shown() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        shown = content
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            save(name, bitmap)
            bitmap.recycle()
        }
    }

    private fun save(name: String, bitmap: android.graphics.Bitmap) {
        java.io.File(dir!!).mkdirs()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pagesAndImages(language: String, fontScale: Float) {
        start(fontScale)
        val suffix = if (fontScale > 1f) "-large" else ""
        pages.forEachIndexed { i, page ->
            shoot("$language-page-${i + 1}-${page.kind.name.lowercase()}$suffix") { PageContent(page, i, pages.size) }
        }
        if (fontScale == 1f) {
            shared.forEach { page ->
                save("$language-share-${page.kind.name.lowercase()}", YearShareImage.render(page, null, "CruxCoach", mapOf("kilter" to 0xFFFF6D00.toInt())))
            }
            shoot("$language-list") { ReviewList(pages, onClose = {}, onShare = {}, onPages = {}) }
        }
    }

    @Test
    fun english() = pagesAndImages("en", 1f)

    @Test
    fun englishLargestFont() = pagesAndImages("en", 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun german() = pagesAndImages("de", 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun germanLargestFont() = pagesAndImages("de", 2f)
}
