package com.cruxcoach.android.ui.board.games

import android.app.Application
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotField
import com.cruxcoach.domain.board.games.KnotLimb
import com.cruxcoach.domain.board.games.KnotSpacing
import com.cruxcoach.domain.board.games.WallRows
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Design review of the wall games: every game and state on a Shiny Wall,
 * rendered into `CRUXCOACH_UI_REVIEW_DIR`. Skipped unless that variable is set.
 *
 * Each screen is drawn in every variant: dark and light, English and German,
 * font scale 1.0 and 2.0, 320 dp and 411 dp wide, at a phone's height
 * (`<screen>/<variant>.png`); `-end` screens are scrolled to the wall.
 * `pair-*.png` puts two variants of the same size side by side.
 * `CRUXCOACH_UI_REVIEW_ONLY` limits the run to variants whose name contains it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h2000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallGamesUiReviewTest {

    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    private val only: String? = System.getenv("CRUXCOACH_UI_REVIEW_ONLY")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private data class Variant(val dark: Boolean, val locale: String, val fontScale: Float, val width: Int) {
        val name = "${if (dark) "dark" else "light"}-$locale-fs$fontScale-w$width"
        /** A phone of this width: 411 × 891 dp, 320 × 694 dp. */
        val height: Int get() = (width * 2.17f).roundToInt()
    }

    private val variants = buildList {
        for (fontScale in listOf(1f, 2f)) for (width in listOf(411, 320)) {
            for (dark in listOf(true, false)) for (locale in listOf("de", "en")) add(Variant(dark, locale, fontScale, width))
        }
    }.filter { only == null || only in it.name }

    private val pairs = listOf(1f, 2f).flatMap { fontScale ->
        listOf(411, 320).map { width ->
            val swap = (fontScale == 2f) != (width == 320)
            "fs$fontScale-w$width" to listOf(
                Variant(dark = true, locale = if (swap) "en" else "de", fontScale, width),
                Variant(dark = false, locale = if (swap) "de" else "en", fontScale, width),
            )
        }
    }

    // ------------------------------------------------------------------ data

    private val board: WallGameBoard by lazy { loadShinyWall(ShinyWallVariant.ST1) }

    /** Holds of a row band, bottom to top, as the lava or a climb would light them. */
    private fun holdsBetween(from: Double, to: Double, role: Int, step: Int = 3): List<BoardHold> {
        val size = board.boardSize!!
        val bottom = size.edgeBottom + from * (size.edgeTop - size.edgeBottom)
        val top = size.edgeBottom + to * (size.edgeTop - size.edgeBottom)
        return board.placements.values.filter { it.y >= bottom && it.y <= top }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .filterIndexed { i, _ -> i % step == 0 }
            .map { BoardHold(it.placementId.toInt(), role) }
    }

    private val climb by lazy {
        GameClimb(
            uuid = "c1", name = "Algo investido", angle = 40,
            holds = holdsBetween(0.0, 1.0, 13, step = 14), offBoard = 0,
        )
    }

    private val idle by lazy { WallGamesState(loading = false, board = board) }

    private val actions = object : WallGamesActions {
        override fun selectGame(game: WallGame) = Unit
        override fun setLevel(level: Int) = Unit
        override fun setTraverseDifficulty(difficulty: Double) = Unit
        override fun setSoundOn(on: Boolean) = Unit
        override fun setThiefDensity(density: Int) = Unit
        override fun newThiefClimb() = Unit
        override fun setStealCount(count: Int) = Unit
        override fun stealHolds() = Unit
        override fun start() = Unit
        override fun stop() = Unit
        override fun reset() = Unit
    }

    // ----------------------------------------------------------- rendering

    @Composable
    private fun Localized(v: Variant, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val density = LocalDensity.current
        val config = remember(v) {
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(v.locale))
                fontScale = v.fontScale
                screenWidthDp = v.width
                screenHeightDp = v.height
            }
        }
        val context = remember(v) { base.createConfigurationContext(config) }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalResources provides context.resources,
            LocalDensity provides Density(density.density, v.fontScale),
        ) { content() }
    }

    /** Draws [content] in every variant into `<dir>/<name>/`; [before] runs after the first frame (a scroll). */
    private fun shoot(name: String, phoneHeight: Boolean = true, before: () -> Unit = {}, content: @Composable () -> Unit) {
        var variant by mutableStateOf(variants.first())
        var view: android.view.View? = null
        var bounds = Rect.Zero
        Locale.setDefault(Locale.forLanguageTag(variant.locale))
        compose.setContent {
            view = LocalView.current
            val v = variant
            key(v) {
                Localized(v) {
                    CruxCoachTheme(if (v.dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                        Box(Modifier.fillMaxSize()) {
                            val size = Modifier.width(v.width.dp).let { if (phoneHeight) it.height(v.height.dp) else it }
                            Surface(size.onGloballyPositioned { bounds = it.boundsInRoot() }) { content() }
                        }
                    }
                }
            }
        }
        val out = File(dir!!, name).apply { mkdirs() }
        variants.forEach { v ->
            Locale.setDefault(Locale.forLanguageTag(v.locale))
            variant = v
            compose.waitForIdle()
            runCatching { before() }
            // The wall photo decodes in the background.
            Thread.sleep(IMAGE_WAIT_MS)
            compose.waitForIdle()
            compose.runOnIdle {
                val box = bounds
                val bitmap = Bitmap.createBitmap(
                    box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                canvas.translate(-box.left, -box.top)
                requireNotNull(view).draw(canvas)
                File(out, "${v.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        Locale.setDefault(Locale.ENGLISH)
        pairs.forEach { (size, members) -> pairSheets(out, size, members) }
    }

    private fun pairSheets(out: File, size: String, members: List<Variant>) {
        val shots = members.mapNotNull { v ->
            File(out, "${v.name}.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        }
        if (shots.size < 2) return
        val gap = 24
        val sheet = Bitmap.createBitmap(shots.sumOf { it.width } + gap * (shots.size - 1), shots.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(android.graphics.Color.rgb(128, 128, 128))
        var left = 0f
        shots.forEach { shot ->
            canvas.drawBitmap(shot, left, 0f, null)
            left += shot.width + gap
        }
        File(out, "pair-$size.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        shots.forEach { it.recycle() }
    }

    @Composable
    private fun Screen(state: WallGamesState, connected: Boolean = true) =
        WallGamesContent(state, connected, actions, onNavigateBack = {}, onConnect = {}, onChooseClimb = {})

    private val toWall: () -> Unit = { compose.onNodeWithTag("wall_games_board").performScrollTo() }

    // --------------------------------------------------------------- screens

    @Test
    fun lava() = shoot("lava") { Screen(idle, connected = false) }

    @Test
    fun lavaEnd() = shoot("lava-end", before = toWall) { Screen(idle, connected = false) }

    @Test
    fun lavaRunning() = shoot("lava-running") {
        Screen(idle.copy(run = GameRun(GamePhase.RUNNING, elapsedMs = 12_300, step = 4, steps = 11, lit = holdsBetween(0.0, 0.3, 15))))
    }

    @Test
    fun countdown() = shoot("countdown") { Screen(idle.copy(run = GameRun(GamePhase.COUNTDOWN, countdown = 3))) }

    @Test
    fun lavaFinished() = shoot("lava-finished") {
        Screen(idle.copy(run = GameRun(GamePhase.FINISHED, elapsedMs = 48_700, step = 11, steps = 11, timelineDone = true)))
    }

    @Test
    fun enduranceNoClimb() = shoot("endurance-no-climb") { Screen(idle.copy(game = WallGame.ENDURANCE)) }

    @Test
    fun enduranceClimb() = shoot("endurance-climb") {
        Screen(idle.copy(game = WallGame.ENDURANCE, climb = climb.copy(offBoard = 2)))
    }

    @Test
    fun enduranceProblem() = shoot("endurance-problem") {
        // As the view model leaves it: no climb, the reason why.
        Screen(idle.copy(game = WallGame.ENDURANCE, climb = null, climbProblem = ClimbProblem.OTHER_BOARD))
    }

    @Test
    fun enduranceRunning() = shoot("endurance-running") {
        Screen(
            idle.copy(
                game = WallGame.ENDURANCE, climb = climb,
                run = GameRun(GamePhase.RUNNING, elapsedMs = 95_000, step = 7, steps = 7, lit = climb.holds, timelineDone = true),
            ),
        )
    }

    @Test
    fun traverse() = shoot("traverse") { Screen(idle.copy(game = WallGame.TRAVERSE, climb = climb)) }

    @Test
    fun thief() = shoot("thief") {
        Screen(idle.copy(game = WallGame.THIEF, thiefRoute = holdsBetween(0.05, 0.95, 13, step = 9), thiefLive = true))
    }

    @Test
    fun thiefEnd() = shoot("thief-end", before = toWall) {
        Screen(idle.copy(game = WallGame.THIEF, thiefRoute = holdsBetween(0.05, 0.95, 13, step = 9), thiefLive = true))
    }

    @Test
    fun sendProblems() = shoot("send-failed") { Screen(idle.copy(sendProblem = WallSendProblem.FAILED)) }

    @Test
    fun session() = shoot("session") { Screen(idle.copy(sendProblem = WallSendProblem.SESSION)) }

    @Test
    fun thiefTooMany() = shoot("thief-too-many") {
        Screen(idle.copy(game = WallGame.THIEF, thiefRoute = holdsBetween(0.05, 0.95, 13, step = 9), thiefLive = true,
            sendProblem = WallSendProblem.TOO_MANY_HOLDS))
    }

    @Test
    fun noHolds() = shoot("no-holds") {
        Screen(WallGamesState(loading = false, unavailable = WallGameBoardLoad.NoHolds))
    }

    @Test
    fun unsupported() = shoot("unsupported") {
        Screen(WallGamesState(loading = false, unavailable = WallGameBoardLoad.Unsupported))
    }

    @Test
    fun loading() = shoot("loading") { Screen(WallGamesState()) }

    @Test
    fun climbPicker() = shoot("climb-picker", phoneHeight = false) {
        ClimbPickerContent(
            listOf(
                RecentClimb("c1", "Algo investido", 40), RecentClimb("c2", "La vía del tió", 40),
                RecentClimb("c3", "Free Palestine", 35), RecentClimb("c4", "Peito3", 40),
            ),
            onPick = {},
            onBrowse = {},
        )
    }

    @Test
    fun climbPickerEmpty() = shoot("climb-picker-empty", phoneHeight = false) { ClimbPickerContent(emptyList(), onPick = {}, onBrowse = {}) }

    // ------------------------------------------------------------------ Knot

    private val kilter: WallGameBoard by lazy { loadKilter(sizeId = 10).copy(angle = 40) }

    /** Knot set up on the Kilter 12x12 for [climbers], as the view model leaves it. */
    private fun knotIdle(climbers: Int, safetySeen: Boolean = true, byTap: Boolean = false): WallGamesState {
        val colours = Knot.colours(BoardBrand.KILTER)!!
        return WallGamesState(
            loading = false,
            board = kilter,
            game = WallGame.KNOT,
            knot = KnotState(
                climbers = climbers,
                names = listOf("Anna", "Benedikt", "").take(Knot.MAX_CLIMBERS),
                byTap = byTap,
                colours = colours,
                maxClimbers = Knot.maxClimbers(kilter.rows, 0.0254),
                layout = Knot.layout(kilter.rows, climbers, colours.size, 0.0254),
                spacings = KnotSpacing.entries.toSet(),
                safetySeen = safetySeen,
            ),
        )
    }

    private fun knotRun(climbers: Int, byTap: Boolean = false, run: (KnotField) -> KnotRun): WallGamesState {
        val idle = knotIdle(climbers, byTap = byTap)
        val field = idle.knot.layout!!.field(Knot.colourOrder(idle.knot.colours!!, 3))
        return idle.copy(knot = idle.knot.copy(run = run(field)))
    }

    @Test
    fun knotFirstVisit() = shoot("knot-first") { Screen(knotIdle(1, safetySeen = false)) }

    @Test
    fun knotTwo() = shoot("knot-two") { Screen(knotIdle(2)) }

    @Test
    fun knotTwoEnd() = shoot("knot-two-end", before = toWall) { Screen(knotIdle(2)) }

    @Test
    fun knotThreeByTap() = shoot("knot-three-by-tap", before = toByTap) { Screen(knotIdle(3, byTap = true)) }

    private val toByTap: () -> Unit = { compose.onNodeWithTag("knot_by_tap").performScrollTo() }

    @Test
    fun knotWideGreyedOut() = shoot("knot-spacing-greyed", before = toByTap) {
        val state = knotIdle(3)
        Screen(state.copy(knot = state.knot.copy(spacing = KnotSpacing.MEDIUM, spacings = setOf(KnotSpacing.CLOSE, KnotSpacing.MEDIUM))))
    }

    @Test
    fun knotShinyWall() = shoot("knot-shiny") { Screen(idle.copy(game = WallGame.KNOT)) }

    @Test
    fun knotPositions() = shoot("knot-positions") {
        Screen(knotRun(3) { KnotRun(KnotPhase.POSITIONS, field = it, climbers = List(3) { KnotClimber() }) })
    }

    @Test
    fun knotCountdown() = shoot("knot-countdown") {
        Screen(knotRun(2) { KnotRun(KnotPhase.COUNTDOWN, field = it, countdown = 4, climbers = List(2) { KnotClimber() }, callMs = 6_000) })
    }

    @Test
    fun knotRunning() = shoot("knot-running") {
        Screen(
            knotRun(3) {
                KnotRun(
                    KnotPhase.RUNNING, field = it,
                    // Benedikt came off during this call: his row offers the undo.
                    climbers = listOf(KnotClimber(held = 6), KnotClimber(out = true, held = 4, outAtCall = 17), KnotClimber(held = 5)),
                    call = KnotCall(17, climber = 2, limb = KnotLimb.RIGHT_FOOT, colour = KnotColour.MAGENTA),
                    callMs = 6_000, callLeftMs = 3_900,
                )
            },
        )
    }

    @Test
    fun knotRunningByTap() = shoot("knot-running-by-tap") {
        Screen(
            knotRun(2, byTap = true) {
                KnotRun(
                    KnotPhase.RUNNING, field = it, climbers = listOf(KnotClimber(held = 3), KnotClimber(held = 2)),
                    call = KnotCall(6, climber = 1, limb = KnotLimb.LEFT_HAND, colour = KnotColour.YELLOW),
                )
            },
        )
    }

    @Test
    fun knotRunningSolo() = shoot("knot-running-solo") {
        Screen(
            knotRun(1) {
                KnotRun(
                    KnotPhase.RUNNING, field = it, climbers = listOf(KnotClimber(held = 11)),
                    call = KnotCall(12, climber = 0, limb = KnotLimb.LEFT_HAND, colour = KnotColour.WHITE),
                    callMs = 4_000, callLeftMs = 1_200,
                )
            },
        )
    }

    @Test
    fun knotWinner() = shoot("knot-winner") {
        Screen(
            knotRun(2) {
                KnotRun(
                    KnotPhase.FINISHED, field = it, climbers = listOf(KnotClimber(held = 14), KnotClimber(out = true, held = 13)),
                    call = KnotCall(28, climber = 1, limb = KnotLimb.LEFT_FOOT, colour = KnotColour.YELLOW), winner = 0,
                )
            },
        )
    }

    @Test
    fun knotSoloResult() = shoot("knot-solo-result") {
        Screen(knotRun(1) { KnotRun(KnotPhase.FINISHED, field = it, climbers = listOf(KnotClimber(out = true, held = 23))) })
    }

    @Test
    fun knotStopped() = shoot("knot-stopped") {
        Screen(knotRun(3) { KnotRun(KnotPhase.FINISHED, field = it, climbers = List(3) { i -> KnotClimber(held = 3 + i) }) })
    }

    // -------------------------------------------------------------- loading

    private fun loadShinyWall(variant: ShinyWallVariant): WallGameBoard {
        val file = listOf(
            File("src/main/assets/board_geometry/shinywall.sqlite3"),
            File("androidApp/src/main/assets/board_geometry/shinywall.sqlite3"),
        ).first { it.exists() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val size = db.rawQuery(
                "SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                arrayOf(variant.productSizeId.toString()),
            ).use { c ->
                c.moveToFirst()
                BoardSize(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5),
                    c.getLong(6), null, BoardBrand.SHINYWALL)
            }
            val placements = mutableMapOf<Int, BoardPlacement>()
            db.rawQuery(
                "SELECT placement_id, hole_id, set_id, x, y FROM placements WHERE placement_id BETWEEN ? AND ?",
                arrayOf(variant.placementId(0).toString(), variant.placementId(variant.holdCount - 1).toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    placements[c.getInt(0)] = BoardPlacement(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                }
            }
            return WallGameBoard(
                brand = BoardBrand.SHINYWALL,
                layoutId = variant.layoutId,
                rows = WallRows.of(WallGameHolds.fromPlacements(placements.values) { true }),
                placements = placements,
                boardSize = size,
                shinyVariant = variant,
            )
        }
    }

    /** The Kilter Original holds with an LED on product size [sizeId], from the bundled geometry. */
    private fun loadKilter(sizeId: Int): WallGameBoard {
        val file = listOf(
            File("src/main/assets/board_geometry/kilter.sqlite3"),
            File("androidApp/src/main/assets/board_geometry/kilter.sqlite3"),
        ).first { it.exists() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val size = db.rawQuery(
                "SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                arrayOf(sizeId.toString()),
            ).use { c ->
                c.moveToFirst()
                BoardSize(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5),
                    c.getLong(6), null, BoardBrand.KILTER)
            }
            val placements = mutableMapOf<Int, BoardPlacement>()
            val leds = mutableMapOf<Int, Int>()
            db.rawQuery(
                "SELECT p.placement_id, p.hole_id, p.set_id, p.x, p.y, l.position FROM placements p JOIN leds l " +
                    "ON l.hole_id = p.hole_id AND l.board_brand = p.board_brand AND l.product_size_id = ?",
                arrayOf(sizeId.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    placements[c.getInt(0)] = BoardPlacement(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4))
                    leds[c.getInt(0)] = c.getInt(5)
                }
            }
            return WallGameBoard(
                brand = BoardBrand.KILTER,
                layoutId = 1,
                rows = WallRows.of(WallGameHolds.fromPlacements(placements.values) { true }),
                placements = placements,
                boardSize = size,
                ledMap = leds,
            )
        }
    }

    private companion object {
        const val IMAGE_WAIT_MS = 300L
    }
}
