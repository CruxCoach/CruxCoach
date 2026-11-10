package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeStats
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design review of the hold-type card of the statistics (#63): the card under the angles card as in the sheet, with
 * a strong and a weak type open, with too few problems, without the board comparison, and its help. Renders every
 * state dark and light, English and German, at 320 and 411 dp and font scale 1.0 and 2.0 into
 * `CRUXCOACH_UI_REVIEW_DIR`, with made-up numbers. Skipped unless that variable is set.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldTypeStatsUiReviewTest(
    private val locale: String,
    private val dark: Boolean,
    private val width: Int,
    private val fontScale: Float,
) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-w{2}-f{3}")
        fun matrix(): List<Array<Any>> = buildList {
            for (locale in listOf("en", "de")) for (dark in listOf(true, false))
                for (width in listOf(320, 411)) for (scale in listOf(1.0f, 2.0f)) add(arrayOf(locale, dark, width, scale))
        }
    }

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val prefix: String = System.getenv("CRUXCOACH_UI_REVIEW_PREFIX") ?: ""
    private val variant = "$locale-${if (dark) "dark" else "light"}-w$width-f${if (fontScale > 1f) "2" else "1"}"

    /** Sets language, size and font scale before the compose rule starts its activity. */
    @get:Rule(order = 0)
    val device = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                Assume.assumeTrue(dir != null)
                // Tall enough for the whole card at font scale 2 on 320 dp.
                RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h2000dp-${if (dark) "night" else "notnight"}-xhdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()

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

    /** A first week on the board: three problems, no verdict for any type. */
    private val fewProblems = kilter.copy(
        rows = listOf(
            row(HoldType.JUG, 0.45, 0.31, level(2, 2, 18.0), HoldTypeStats.Strength.TOO_FEW),
            row(HoldType.EDGE, 0.35, 0.33, level(1, 0, null), HoldTypeStats.Strength.TOO_FEW),
            row(HoldType.SLOPER, 0.20, 0.22, level(0, 0, null), HoldTypeStats.Strength.TOO_FEW),
        ),
        problems = 3,
        countedHolds = 19,
        handHolds = 21,
        overall = level(3, 2, 18.0),
    )

    /** The catalogue has no problems in your grades yet: no board marks. */
    private val noBoard = kilter.copy(rows = kilter.rows.map { it.copy(board = null) }, boardProblems = 0)

    private val angles = listOf(AngleDistEntry(25, 14), AngleDistEntry(30, 31), AngleDistEntry(35, 19), AngleDistEntry(40, 8))

    private fun shoot(name: String, ready: () -> Unit = {}, content: @Composable () -> Unit) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
        ready()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            // Dialogs live in windows of their own: dim the screen and draw them on top, as on the phone.
            windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
                canvas.drawColor(android.graphics.Color.argb(0x52, 0, 0, 0))
                val at = IntArray(2).also { w.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
            }
            java.io.File(dir!!).mkdirs()
            java.io.File(dir, "$prefix$name-$variant.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    /** Opens the row of [type] by its name, as a tap on the phone does. */
    private fun open(type: Int) {
        compose.onNode(hasContentDescription(app.getString(type) + " ", substring = true)).performClick()
    }

    @Composable
    private fun Card(stats: HoldTypeCardStats, boardName: String? = "Kilter") {
        HoldTypesCard(
            stats, if (locale == "de") GradeScale.FRENCH else GradeScale.V_SCALE, boardName = boardName,
            color = boardSeriesColors(listOf("kilter", "moonboard")).getValue("kilter"),
        )
    }

    /** As in the sheet: the angles card above, nothing open. */
    @Test
    fun inSheet() = shoot("in-sheet") {
        ChartSection(stringResource(R.string.board_stats_angle_distribution)) { BoardAngleDistChart(entries = angles) }
        Card(kilter)
    }

    @Test
    fun strongOpen() = shoot("strong-open", ready = { open(R.string.hold_type_edge) }) { Card(kilter, boardName = null) }

    @Test
    fun weakOpen() = shoot("weak-open", ready = { open(R.string.hold_type_sloper) }) { Card(kilter) }

    @Test
    fun tooFew() = shoot("too-few", ready = { open(R.string.hold_type_jug) }) { Card(fewProblems) }

    @Test
    fun withoutBoard() = shoot("no-board", ready = { open(R.string.hold_type_edge) }) { Card(noBoard) }

    @Test
    fun help() = shoot("help", ready = {
        val title = app.getString(R.string.board_stats_hold_types_title_board, "Kilter")
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, title)).performClick()
    }) { Card(kilter) }
}
