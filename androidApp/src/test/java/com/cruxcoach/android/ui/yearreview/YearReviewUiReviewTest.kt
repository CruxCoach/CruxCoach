package com.cruxcoach.android.ui.yearreview

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Design review of the year in review: renders every page with its controls,
 * the list view, the short "so far", loading and error screens, the share sheet,
 * every share image and both entries (the December/January card and the place in
 * the statistics) of a made-up year on three boards into `CRUXCOACH_UI_REVIEW_DIR`,
 * one folder per language, width and font size, in the dark and light theme where
 * the theme shows. Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YearReviewUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    // Computed only when a test renders: without CRUXCOACH_UI_REVIEW_DIR every test is skipped and costs nothing.
    private val input: YearReviewComputer.Input by lazy {
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
                        climbFrames = (1..4 + climb.drop(1).toInt() % 9).joinToString("") { "p${it}r13" }, difficultyAverage = difficulty, isSend = random.nextDouble() < 0.8, boardBrand = board,
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
        YearReviewComputer.Input(entries = entries, workouts = workouts, taggedSends = tagged)
    }

    private val january = LocalDate(2027, 1, 10)
    private val december = LocalDate(2026, 12, 1)
    private val review: YearReview by lazy { YearReviewComputer.compute(input, 2026, january, TimeZone.UTC) }
    private val soFar: YearReview by lazy { YearReviewComputer.compute(input, 2026, december, TimeZone.UTC) }
    private val littleInput by lazy { YearReviewComputer.Input(entries = input.entries.filter { it.climbedAt.startsWith("2026-03") }.take(9)) }
    private val little: YearReview by lazy { YearReviewComputer.compute(littleInput, 2026, december, TimeZone.UTC) }
    private val littleFinal: YearReview by lazy { YearReviewComputer.compute(littleInput, 2026, january, TimeZone.UTC) }
    private val empty: YearReview by lazy { YearReviewComputer.compute(YearReviewComputer.Input(), 2026, december, TimeZone.UTC) }
    private val emptyNewYear: YearReview by lazy { YearReviewComputer.compute(YearReviewComputer.Input(), 2027, january, TimeZone.UTC) }

    private var shown: @Composable () -> Unit by mutableStateOf({})
    private var theme: DarkModeSetting by mutableStateOf(DarkModeSetting.DARK)
    private var view: android.view.View? = null
    private var folder = ""

    /** One composition per test (the rule allows only one); [shoot] swaps what it shows and the theme. */
    private fun start(fontScale: Float) {
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CruxCoachTheme(theme) { Box(Modifier.fillMaxSize()) { shown() } }
            }
        }
        compose.waitForIdle()
    }

    /** Renders [content] into `name.png`; a name starting with "_" only composes, to read what the pages say. */
    private fun shoot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        theme = if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT
        shown = content
        compose.waitForIdle()
        if (name.startsWith("_")) return
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            save(name, bitmap)
            bitmap.recycle()
        }
    }

    private fun save(name: String, bitmap: android.graphics.Bitmap) {
        val out = java.io.File(dir!!, folder).apply { mkdirs() }
        java.io.File(out, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The review's own dark backdrop, as on the phone. */
    @Composable
    private fun Backdrop(content: @Composable () -> Unit) =
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DarkBackground, DarkSurface)))) { content() }

    /** A plain screen in the app's theme, for what sits on the start screen, in the statistics or in a sheet. */
    @Composable
    private fun Screen(content: @Composable () -> Unit) =
        Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(16.dp)) { content() } }

    private fun everything(language: String, width: Int, fontScale: Float) {
        folder = "$language-$width-${if (fontScale > 1f) "large" else "normal"}"
        start(fontScale)
        val context = TrainingContext(null, null, null)

        // The pages with their controls, then the list view: dark in both themes.
        var pages: List<ReviewPage> = emptyList()
        shoot("_probe") { pages = reviewPages(review, GradeScale.FRENCH, context) }
        pages.forEachIndexed { i, page ->
            shoot("page-${(i + 1).toString().padStart(2, '0')}-${page.kind.name.lowercase()}") {
                Backdrop { ReviewPager(reviewPages(review, GradeScale.FRENCH, context), i, onIndex = {}, onClose = {}, onShare = {}, onList = {}, boardColors = reviewBoardColors(review)) }
            }
        }
        shoot("page-so-far-intro") {
            Backdrop { ReviewPager(reviewPages(soFar, GradeScale.FRENCH, context), 0, onIndex = {}, onClose = {}, onShare = {}, onList = {}, boardColors = reviewBoardColors(soFar)) }
        }
        shoot("page-so-far-summary") {
            val p = reviewPages(soFar, GradeScale.FRENCH, context)
            Backdrop { ReviewPager(p, p.lastIndex, onIndex = {}, onClose = {}, onShare = {}, onList = {}, boardColors = reviewBoardColors(soFar)) }
        }
        shoot("list-top") { Backdrop { ReviewList(reviewPages(review, GradeScale.FRENCH, context), reviewTitle(review), onClose = {}, onShare = {}, onPages = {}, boardColors = reviewBoardColors(review)) } }
        shoot("list-rhythm") { Backdrop { ReviewList(reviewPages(review, GradeScale.FRENCH, context), reviewTitle(review), onClose = {}, onShare = {}, onPages = {}, startAt = 5, boardColors = reviewBoardColors(review)) } }
        if (width == 411 && fontScale == 1f) {
            // The review stays dark when the app is light.
            shoot("page-02-volume-app-light", dark = false) {
                Backdrop { ReviewPager(reviewPages(review, GradeScale.FRENCH, context), 1, onIndex = {}, onClose = {}, onShare = {}, onList = {}, boardColors = reviewBoardColors(review)) }
            }
            shoot("list-top-app-light", dark = false) {
                Backdrop { ReviewList(reviewPages(review, GradeScale.FRENCH, context), reviewTitle(review), onClose = {}, onShare = {}, onPages = {}, boardColors = reviewBoardColors(review)) }
            }
        }

        // Below the minimum, nothing at all, loading and a failed load.
        shoot("state-little-so-far") { Backdrop { SoFar(2026, little, onClose = {}) } }
        shoot("state-little-final") { Backdrop { SoFar(2026, littleFinal, onClose = {}) } }
        shoot("state-empty") { Backdrop { SoFar(2026, empty, onClose = {}) } }
        shoot("state-loading") { Backdrop { Loading(2026, onClose = {}) } }
        shoot("state-failed") { Backdrop { LoadFailed(onRetry = {}, onClose = {}) } }

        // What follows the app's theme: the entries and the share sheet.
        for (dark in listOf(true, false)) {
            val t = if (dark) "dark" else "light"
            shoot("entry-card-$t", dark) {
                Screen {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        YearReviewCard(2026, soFar = true, onOpen = {}, onHide = {})
                        YearReviewCard(2026, soFar = false, onOpen = {}, onHide = {})
                    }
                }
            }
            shoot("entry-stats-$t", dark) {
                Screen {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        YearReviewStatsContent(
                            YearReviewEntryViewModel.State(today = january, years = listOf(2026, 2025), selectedYear = 2026, selected = review),
                            onOpen = {}, onSelect = {}, onTestDate = {},
                        )
                        YearReviewStatsContent(
                            YearReviewEntryViewModel.State(today = january, years = emptyList(), selectedYear = 2027, selected = emptyNewYear),
                            onOpen = {}, onSelect = {}, onTestDate = {},
                        )
                    }
                }
            }
            shoot("share-sheet-$t", dark) {
                val page = reviewPages(review, GradeScale.FRENCH, context, SharePrivacy(problemNames = false, exactDates = false)).first { it.kind == ReviewPageKind.HIGHLIGHTS }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ShareSheetContent(
                        YearShareImage.render(page, null, "CruxCoach", reviewBoardColors(review).mapValues { it.value.toArgb() }), page.title,
                        nameAvailable = false, showName = false, showProblems = false, showDates = false,
                        onShowName = {}, onShowProblems = {}, onShowDates = {}, onSend = {},
                    )
                }
            }
        }

        // The share images have a fixed size, so one set per language is enough.
        if (width == 411 && fontScale == 1f) {
            var shared: List<ReviewPage> = emptyList()
            var open: List<ReviewPage> = emptyList()
            shoot("_probe") {
                shared = reviewPages(review, GradeScale.FRENCH, context, SharePrivacy(problemNames = false, exactDates = false))
                open = reviewPages(review, GradeScale.FRENCH, context, SharePrivacy(name = "Alex Example", problemNames = true, exactDates = true))
            }
            val colors = reviewBoardColors(review).mapValues { it.value.toArgb() }
            shared.forEachIndexed { i, page ->
                save("image-${(i + 1).toString().padStart(2, '0')}-${page.kind.name.lowercase()}", YearShareImage.render(page, null, "CruxCoach", colors))
            }
            open.filter { it.kind == ReviewPageKind.HIGHLIGHTS || it.kind == ReviewPageKind.SUMMARY }.forEach { page ->
                save("image-open-${page.kind.name.lowercase()}", YearShareImage.render(page, "Alex Example · 2026", "CruxCoach", colors))
            }
        }
    }

    @Test
    fun english() = everything("en", 411, 1f)

    @Test
    fun englishLargestFont() = everything("en", 411, 2f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun englishNarrow() = everything("en", 320, 1f)

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xhdpi")
    fun englishNarrowLargestFont() = everything("en", 320, 2f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun german() = everything("de", 411, 1f)

    @Test
    @Config(qualifiers = "de-w411dp-h891dp-xhdpi")
    fun germanLargestFont() = everything("de", 411, 2f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun germanNarrow() = everything("de", 320, 1f)

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun germanNarrowLargestFont() = everything("de", 320, 2f)
}
