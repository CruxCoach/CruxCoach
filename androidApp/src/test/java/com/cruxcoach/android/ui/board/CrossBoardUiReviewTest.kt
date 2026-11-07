package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Design review of the stats across boards (#44): renders both views with a
 * three-board logbook and writes them as PNGs into `CRUXCOACH_UI_REVIEW_DIR`
 * (light theme with `CRUXCOACH_UI_REVIEW_THEME=light`). Skipped unless that
 * variable is set, so CI pays nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossBoardUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val theme =
        if (System.getenv("CRUXCOACH_UI_REVIEW_THEME") == "light") DarkModeSetting.LIGHT else DarkModeSetting.DARK

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private var id = 0

    /** [n] problems of one grade on one board in one month, each sent after [tries] tries. */
    private fun problems(
        board: String, month: String, difficulty: Double, n: Int, tries: Long,
    ): List<AscentWithClimb> = (1..n).map {
        AscentWithClimb(
            uuid = "r-${++id}", climbUuid = "c-${id}", angle = 40, isMirror = false,
            bidCount = tries, quality = null, difficulty = difficulty.toLong(), comment = null,
            climbedAt = "$month-${(10 + it).toString().padStart(2, '0')}T18:00:00",
            climbName = "c", climbFrames = "", difficultyAverage = difficulty,
            isSend = true, boardBrand = board,
        )
    }

    private val logbook = buildList {
        addAll(problems("kilter", "2025-10", 14.0, 5, 1))
        addAll(problems("kilter", "2025-11", 16.0, 6, 2))
        addAll(problems("kilter", "2025-12", 16.0, 4, 2))
        addAll(problems("kilter", "2026-01", 18.0, 4, 3))
        addAll(problems("kilter", "2026-02", 18.0, 3, 2))
        addAll(problems("kilter", "2026-03", 20.0, 2, 6))
        addAll(problems("moonboard", "2025-11", 14.0, 3, 3))
        addAll(problems("moonboard", "2026-01", 16.0, 3, 4))
        addAll(problems("moonboard", "2026-02", 16.0, 2, 3))
        addAll(problems("moonboard", "2026-03", 18.0, 2, 5))
        addAll(problems("tension", "2025-12", 14.0, 3, 2))
        addAll(problems("tension", "2026-02", 16.0, 1, 1))
    }

    private val stats = CrossBoardStatsComputer.compute(
        logbook, StatsTimeInterval.ALL, GradeScale.FRENCH,
        clock = Clock.fixed(Instant.parse("2026-03-31T12:00:00Z"), ZoneOffset.UTC),
    )

    // The phone test's case: three boards close together in a month in the middle.
    private val crowded = CrossBoardStatsComputer.compute(
        buildList {
            addAll(problems("kilter", "2026-08", 18.0, 3, 2))
            addAll(problems("tension", "2026-08", 16.0, 3, 2))
            addAll(problems("kilter", "2026-09", 18.0, 1, 3))
            addAll(problems("kilter", "2026-09", 18.0, 1, 4))
            addAll(problems("tension", "2026-09", 18.0, 1, 5))
            addAll(problems("moonboard", "2026-09", 18.0, 2, 2))
            addAll(problems("kilter", "2026-10", 20.0, 3, 3))
            addAll(problems("tension", "2026-10", 20.0, 1, 6))
            addAll(problems("moonboard", "2026-10", 20.0, 1, 6))
            addAll(problems("kilter", "2026-11", 22.0, 1, 10))
        },
        StatsTimeInterval.ALL, GradeScale.FRENCH,
        clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC),
    )

    private fun shoot(name: String, before: () -> Unit = {}, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) { Surface { androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() } } }
        }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$prefix$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun triesPerGrade() = shoot("cross-board-tries") {
        CrossBoardSection(stats, CrossBoardView.TRIES_PER_GRADE, onViewSelect = {})
    }

    @Test
    fun triesPerGradeWithAGradeOpen() = shoot("cross-board-tries-open", before = {
        compose.onNodeWithText(stats.grades[1].grade).performClick()
    }) {
        CrossBoardSection(stats, CrossBoardView.TRIES_PER_GRADE, onViewSelect = {})
    }

    @Test
    fun perMonth() = shoot("cross-board-months") {
        CrossBoardSection(stats, CrossBoardView.PER_MONTH, onViewSelect = {})
    }

    @Test
    fun perMonthCrowded() = shoot("cross-board-months-crowded", before = {
        // September is the second of four months; tap it in the upper chart.
        compose.onNodeWithContentDescription("Sep:", substring = true).performTouchInput {
            val left = 36.dp.toPx()
            click(Offset(left + (width - left) * 1.5f / 4f, 60.dp.toPx()))
        }
    }) {
        CrossBoardSection(crowded, CrossBoardView.PER_MONTH, onViewSelect = {})
    }
}
