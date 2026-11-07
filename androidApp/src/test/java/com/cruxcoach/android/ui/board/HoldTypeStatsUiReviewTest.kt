package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeStats
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the hold-type card of the statistics (#63): renders it with made-up numbers into
 * `CRUXCOACH_UI_REVIEW_DIR` (light theme with `CRUXCOACH_UI_REVIEW_THEME=light`). Skipped unless that variable is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h1200dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldTypeStatsUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val theme =
        if (System.getenv("CRUXCOACH_UI_REVIEW_THEME") == "light") DarkModeSetting.LIGHT else DarkModeSetting.DARK

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private fun level(problems: Int, sends: Int, hardest: Double?) =
        HoldTypeStats.Level(problems, sends, if (problems == 0) null else sends.toDouble() / problems, hardest)

    private fun row(type: HoldType, yours: Double, board: Double?, level: HoldTypeStats.Level, strength: HoldTypeStats.Strength) =
        HoldTypeRow(type, yours, board, level, strength)

    /** A Kilter climber of about 6c who sends edge problems well and slopers badly. */
    private val kilter = HoldTypeCardStats(
        board = "kilter",
        rows = listOf(
            row(HoldType.JUG, 0.24, 0.31, level(18, 12, 21.0), HoldTypeStats.Strength.AS_USUAL),
            row(HoldType.EDGE, 0.41, 0.33, level(26, 21, 23.0), HoldTypeStats.Strength.STRONG),
            row(HoldType.SLOPER, 0.17, 0.22, level(9, 3, 19.0), HoldTypeStats.Strength.WEAK),
            row(HoldType.PINCH, 0.12, 0.10, level(4, 3, 20.0), HoldTypeStats.Strength.TOO_FEW),
            row(HoldType.POCKET, 0.04, 0.03, level(0, 0, null), HoldTypeStats.Strength.TOO_FEW),
            row(HoldType.VOLUME, 0.02, 0.01, level(0, 0, null), HoldTypeStats.Strength.TOO_FEW),
        ),
        problems = 72,
        countedHolds = 431,
        handHolds = 498,
        boardProblems = 18_240,
        overall = level(72, 47, 23.0),
        datasetVersion = "review",
    )

    private val noBoard = kilter.copy(
        board = "moonboard",
        rows = kilter.rows.map { it.copy(board = null) },
        boardProblems = 0,
        problems = 1,
    )

    private fun shoot(name: String, fontScale: Float = 1f, tap: String? = null, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CruxCoachTheme(theme) { Surface { Box(Modifier.padding(16.dp)) { content() } } }
            }
        }
        compose.waitForIdle()
        tap?.let { compose.onNodeWithText(it).performClick() }
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
    fun german411_strongRowOpen() = shoot("hold-types-de-411", tap = "Leiste") {
        HoldTypesCard(kilter, GradeScale.FRENCH, boardName = "Kilter", color = boardSeriesColors(listOf("kilter", "moonboard")).getValue("kilter"))
    }

    @Test
    @Config(qualifiers = "en-w320dp-h1400dp-xhdpi")
    fun english320_largeFont_weakRowOpen() = shoot("hold-types-en-320-x2", fontScale = 2f, tap = "Sloper") {
        HoldTypesCard(kilter, GradeScale.V_SCALE, boardName = null, color = boardSeriesColors(listOf("kilter", "moonboard")).getValue("kilter"))
    }

    @Test
    @Config(qualifiers = "en-w411dp-h1000dp-xhdpi")
    fun english411_withoutBoardComparison() = shoot("hold-types-en-411-no-board") {
        HoldTypesCard(noBoard, GradeScale.V_SCALE, boardName = "MoonBoard", color = boardSeriesColors(listOf("kilter", "moonboard")).getValue("moonboard"))
    }
}
